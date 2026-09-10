package com.nxr.platform.commerce;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Customer-aware grading prices and deterministic weight-based return shipping. */
@Service
public class CommercePolicyService {

    public static final Set<String> SUPPORTED_CURRENCIES = Set.of(
        "USD", "CNY", "EUR", "GBP", "HKD", "JPY", "CAD", "AUD", "SGD"
    );
    public static final Set<String> SEGMENTS = Set.of("consumer", "business", "all");
    public static final Set<String> ORIGINS = Set.of("customer_submission", "owned_inventory");
    private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("9999999999.99");

    private final JdbcClient jdbcClient;

    public CommercePolicyService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Stable hook for order creation. Returned amounts are the values to freeze on grading_order. */
    public QuoteResult quoteForOrder(long customerId, String country, String currency, int cardCount) {
        return quoteForOrder(customerId, country, currency, cardCount, null);
    }

    /** Optional shipping option code preserves the customer's existing global-option selection on fallback. */
    public QuoteResult quoteForOrder(
        long customerId, String countryValue, String currencyValue, int cardCount, String requestedShippingOptionCode
    ) {
        if (customerId <= 0) throw badRequest("Customer is required");
        if (cardCount < 1 || cardCount > 10_000) throw badRequest("Card count is outside the supported quote range");
        String country = normalizeDestination(countryValue);
        String currency = normalizeCurrency(currencyValue);
        String accountType = jdbcClient.sql("SELECT account_type_code FROM customer_account WHERE id = :customerId AND is_active = 1")
            .param("customerId", customerId).query(String.class).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer account not found"));
        String segment = "merchant".equalsIgnoreCase(accountType) ? "business" : "consumer";

        PriceMatch price = selectPrice(customerId, segment, currency, cardCount);
        ShippingMatch shipping = selectShipping(country, currency, cardCount, requestedShippingOptionCode);
        RoutingAssignment routing = routingForCustomer(customerId);
        int scale = currencyScale(currency);
        BigDecimal serviceFee = price.unitPrice().multiply(BigDecimal.valueOf(cardCount)).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal shippingFee = shipping.amount().setScale(scale, RoundingMode.HALF_UP);
        BigDecimal total = serviceFee.add(shippingFee).setScale(scale, RoundingMode.HALF_UP);
        ensureOrderAmount(serviceFee, "Grading fee");
        ensureOrderAmount(shippingFee, "Return shipping fee");
        ensureOrderAmount(total, "Quoted total");
        return new QuoteResult(
            customerId, segment, country, currency, cardCount, price.policyId(), shipping.policyId(),
            price.sourceCode(), shipping.sourceCode(), price.unitPrice(), serviceFee, shippingFee, total,
            shipping.perCardWeightGrams(), shipping.packagingWeightGrams(), shipping.totalWeightGrams(),
            shipping.chargeableWeightGrams(),
            shipping.shippingOptionCode(), shipping.displayName(), routing
        );
    }

    /** Quotes one physical batch once, then allocates its shipping total across child orders exactly. */
    public BatchQuoteResult quoteBatch(
        long customerId, String country, String currency, List<BatchPartRequest> parts,
        String requestedShippingOptionCode
    ) {
        if (parts == null || parts.isEmpty() || parts.size() > 500) throw badRequest("Batch parts are required");
        HashSet<String> references = new HashSet<>();
        int totalCards = 0;
        List<BatchPartRequest> normalized = new ArrayList<>();
        for (BatchPartRequest part : parts) {
            if (part == null) throw badRequest("Batch part is required");
            String reference = requireText(part.reference(), "Batch part reference", 64);
            if (!references.add(reference)) throw badRequest("Batch part references must be unique");
            int count = boundedPositive(part.cardCount(), "Batch part card count", 10_000);
            try { totalCards = Math.addExact(totalCards, count); }
            catch (ArithmeticException exception) { throw badRequest("Batch card count is too large"); }
            if (totalCards > 10_000) throw badRequest("Batch card count is outside the supported quote range");
            normalized.add(new BatchPartRequest(reference, count));
        }
        QuoteResult aggregate = quoteForOrder(customerId, country, currency, totalCards, requestedShippingOptionCode);
        int scale = currencyScale(aggregate.currencyCode());
        long shippingMinor = aggregate.returnShippingFee().movePointRight(scale).longValueExact();
        long[] allocatedMinor = new long[normalized.size()];
        long[] remainders = new long[normalized.size()];
        long allocated = 0;
        for (int i = 0; i < normalized.size(); i++) {
            long numerator = Math.multiplyExact(shippingMinor, normalized.get(i).cardCount());
            allocatedMinor[i] = numerator / totalCards;
            remainders[i] = numerator % totalCards;
            allocated += allocatedMinor[i];
        }
        long unitsLeft = shippingMinor - allocated;
        List<Integer> allocationOrder = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) allocationOrder.add(i);
        allocationOrder.sort(Comparator.<Integer>comparingLong(i -> remainders[i]).reversed()
            .thenComparing(i -> normalized.get(i).reference()));
        for (int i = 0; i < unitsLeft; i++) allocatedMinor[allocationOrder.get(i)]++;

        List<AllocatedBatchQuote> allocations = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            BatchPartRequest part = normalized.get(i);
            BigDecimal serviceFee = aggregate.unitPrice().multiply(BigDecimal.valueOf(part.cardCount()))
                .setScale(scale, RoundingMode.HALF_UP);
            BigDecimal shippingFee = BigDecimal.valueOf(allocatedMinor[i], scale);
            allocations.add(new AllocatedBatchQuote(part.reference(), part.cardCount(), serviceFee, shippingFee,
                serviceFee.add(shippingFee).setScale(scale, RoundingMode.HALF_UP)));
        }
        return new BatchQuoteResult(aggregate, List.copyOf(allocations));
    }

    private PriceMatch selectPrice(long customerId, String segment, String currency, int cardCount) {
        return jdbcClient.sql(
                """
                SELECT id, unit_price, customer_id, customer_segment_code
                FROM commerce_price_policy
                WHERE currency_code = :currency AND is_active = 1
                  AND minimum_quantity <= :cardCount
                  AND (maximum_quantity IS NULL OR maximum_quantity >= :cardCount)
                  AND (customer_id = :customerId OR
                       (customer_id IS NULL AND (customer_segment_code = :segment OR customer_segment_code = 'all')))
                ORDER BY CASE WHEN customer_id = :customerId THEN 3
                              WHEN customer_segment_code = :segment THEN 2 ELSE 1 END DESC,
                         priority_no DESC, minimum_quantity DESC, id DESC
                LIMIT 1
                """
            )
            .param("currency", currency).param("cardCount", cardCount)
            .param("customerId", customerId).param("segment", segment)
            .query((rs, rowNum) -> {
                Long exactCustomer = rs.getObject("customer_id", Long.class);
                String matchedSegment = rs.getString("customer_segment_code");
                String source = exactCustomer != null ? "customer_policy"
                    : "all".equals(matchedSegment) ? "all_customer_policy" : matchedSegment + "_policy";
                return new PriceMatch(rs.getLong("id"), rs.getBigDecimal("unit_price"), source);
            }).optional()
            .orElseGet(() -> jdbcClient.sql(
                    """
                    SELECT unit_price FROM grading_service_price
                    WHERE price_code = 'basic_grading' AND currency_code = :currency AND is_active = 1
                    LIMIT 1
                    """
                ).param("currency", currency).query(BigDecimal.class).optional()
                .map(value -> new PriceMatch(null, value, "global_price"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Grading price is not configured for this currency")));
    }

    private ShippingMatch selectShipping(String country, String currency, int cardCount, String requestedOptionCode) {
        String requested = clean(requestedOptionCode, 33).toLowerCase(Locale.ROOT);
        if (requested.length() > 32) throw badRequest("Shipping option code is invalid");
        if (!requested.isBlank() && !requested.startsWith("weight_")) {
            return globalShipping(country, currency, requested);
        }
        Long requestedPolicyId = requested.isBlank() ? null : parseWeightPolicyId(requested);
        String policyIdClause = requestedPolicyId == null ? "" : " AND id = :policyId\n";
        JdbcClient.StatementSpec statement = jdbcClient.sql(
                """
                SELECT id, display_name, per_card_weight_grams, packaging_weight_grams,
                       first_weight_grams, first_weight_price, additional_weight_grams,
                       additional_weight_price, discount_quantity_threshold, discount_percent,
                       free_shipping_quantity_threshold
                FROM commerce_shipping_policy
                WHERE currency_code = :currency AND is_active = 1
                  AND (destination_country = :country OR destination_country = '*')
                """ + policyIdClause + """
                ORDER BY CASE WHEN destination_country = :country THEN 1 ELSE 0 END DESC,
                         priority_no DESC, id DESC
                LIMIT 1
                """
            ).param("currency", currency).param("country", country);
        if (requestedPolicyId != null) statement = statement.param("policyId", requestedPolicyId);
        return statement.query((rs, rowNum) -> calculateShipping(
                rs.getLong("id"), rs.getString("display_name"), cardCount,
                rs.getInt("per_card_weight_grams"), rs.getInt("packaging_weight_grams"),
                rs.getInt("first_weight_grams"), rs.getBigDecimal("first_weight_price"),
                rs.getInt("additional_weight_grams"), rs.getBigDecimal("additional_weight_price"),
                rs.getObject("discount_quantity_threshold", Integer.class), rs.getBigDecimal("discount_percent"),
                rs.getObject("free_shipping_quantity_threshold", Integer.class)
            )).optional().orElseGet(() -> {
                if (requestedPolicyId != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected weight shipping policy is no longer available");
                }
                return globalShipping(country, currency, null);
            });
    }

    private ShippingMatch calculateShipping(
        long policyId, String displayName, int cardCount, int perCardWeight, int packagingWeight,
        int firstWeight, BigDecimal firstPrice, int additionalWeight, BigDecimal additionalPrice,
        Integer discountThreshold, BigDecimal discountPercent, Integer freeThreshold
    ) {
        long actualWeight = (long) perCardWeight * cardCount + packagingWeight;
        if (actualWeight > Integer.MAX_VALUE) throw badRequest("Quoted shipment weight is too large");
        int chargeableWeight = Math.max(firstWeight, (int) actualWeight);
        int excess = Math.max(0, chargeableWeight - firstWeight);
        long additionalUnits = excess == 0 ? 0 : ((long) excess + additionalWeight - 1) / additionalWeight;
        BigDecimal amount = firstPrice.add(additionalPrice.multiply(BigDecimal.valueOf(additionalUnits)));
        if (discountThreshold != null && cardCount >= discountThreshold && discountPercent.signum() > 0) {
            amount = amount.multiply(BigDecimal.valueOf(100).subtract(discountPercent))
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        }
        if (freeThreshold != null && cardCount >= freeThreshold) amount = BigDecimal.ZERO;
        return new ShippingMatch(policyId, amount, "weight_policy", perCardWeight, packagingWeight,
            (int) actualWeight, chargeableWeight, "weight_" + policyId, displayName);
    }

    private ShippingMatch globalShipping(String country, String currency, String requestedOptionCode) {
        List<GlobalShippingOption> options = jdbcClient.sql(
                """
                SELECT option_code, display_name, country_scope, price_amount, sort_order
                FROM return_shipping_option
                WHERE currency_code = :currency AND is_active = 1
                ORDER BY sort_order, id
                """
            ).param("currency", currency)
            .query((rs, rowNum) -> new GlobalShippingOption(
                rs.getString("option_code"), rs.getString("display_name"), rs.getString("country_scope"),
                rs.getBigDecimal("price_amount"), rs.getInt("sort_order")
            )).list();
        String requested = clean(requestedOptionCode, 32).toLowerCase(Locale.ROOT);
        return options.stream()
            .filter(option -> requested.isBlank() || option.optionCode().equalsIgnoreCase(requested))
            .filter(option -> countryMatches(option.countryScope(), country))
            .findFirst()
            .map(option -> new ShippingMatch(null, option.price(), "global_shipping", null, null, null, null,
                option.optionCode(), option.displayName()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Return shipping is not configured for this destination and currency"));
    }

    public RoutingAssignment routingForCustomer(long customerId) {
        return jdbcClient.sql(
                """
                SELECT r.business_line_id, bl.display_name AS line_name, r.work_center_id,
                       wc.display_name AS center_name, r.order_origin_code
                FROM commerce_customer_routing r
                JOIN commerce_business_line bl ON bl.id = r.business_line_id AND bl.is_active = 1
                JOIN commerce_work_center wc ON wc.id = r.work_center_id AND wc.is_active = 1
                WHERE r.customer_id = :customerId
                  AND r.order_origin_code = 'customer_submission'
                  AND bl.order_origin_code = 'customer_submission'
                """
            ).param("customerId", customerId).query((rs, rowNum) -> new RoutingAssignment(
                rs.getLong("business_line_id"), rs.getString("line_name"), rs.getLong("work_center_id"),
                rs.getString("center_name"), rs.getString("order_origin_code")
            )).optional().orElseGet(() -> defaultRouting("customer_submission"));
    }

    public RoutingAssignment defaultRouting(String originValue) {
        String origin = normalizeOrigin(originValue);
        BusinessLine line = listBusinessLines().stream()
            .filter(item -> item.active() && item.defaultLine() && item.orderOriginCode().equals(origin)).findFirst().orElse(null);
        WorkCenter center = listWorkCenters().stream()
            .filter(item -> item.active() && item.defaultCenter()).findFirst().orElse(null);
        return new RoutingAssignment(line == null ? null : line.id(), line == null ? null : line.displayName(),
            center == null ? null : center.id(), center == null ? null : center.displayName(), origin);
    }

    public CommerceCatalog catalog() {
        return new CommerceCatalog(listPricePolicies(), listShippingPolicies(), listBusinessLines(), listWorkCenters(),
            listCustomerRoutings());
    }

    public List<PricePolicy> listPricePolicies() {
        return jdbcClient.sql("SELECT * FROM commerce_price_policy ORDER BY currency_code, priority_no DESC, minimum_quantity, id")
            .query((rs, n) -> new PricePolicy(rs.getLong("id"), rs.getString("policy_code"), rs.getString("display_name"),
                rs.getString("customer_segment_code"), rs.getObject("customer_id", Long.class), rs.getString("currency_code"),
                rs.getInt("minimum_quantity"), rs.getObject("maximum_quantity", Integer.class), rs.getBigDecimal("unit_price"),
                rs.getInt("priority_no"), rs.getBoolean("is_active"))).list();
    }

    public List<ShippingPolicy> listShippingPolicies() {
        return jdbcClient.sql("SELECT * FROM commerce_shipping_policy ORDER BY currency_code, destination_country, priority_no DESC, id")
            .query((rs, n) -> new ShippingPolicy(rs.getLong("id"), rs.getString("policy_code"), rs.getString("display_name"),
                rs.getString("destination_country"), rs.getString("currency_code"), rs.getInt("per_card_weight_grams"),
                rs.getInt("packaging_weight_grams"), rs.getInt("first_weight_grams"), rs.getBigDecimal("first_weight_price"),
                rs.getInt("additional_weight_grams"), rs.getBigDecimal("additional_weight_price"),
                rs.getObject("discount_quantity_threshold", Integer.class), rs.getBigDecimal("discount_percent"),
                rs.getObject("free_shipping_quantity_threshold", Integer.class), rs.getInt("priority_no"), rs.getBoolean("is_active"))).list();
    }

    public List<BusinessLine> listBusinessLines() {
        return jdbcClient.sql("SELECT id,line_code,display_name,order_origin_code,is_default,is_active FROM commerce_business_line ORDER BY display_name,id")
            .query((rs, n) -> new BusinessLine(rs.getLong("id"), rs.getString("line_code"), rs.getString("display_name"),
                rs.getString("order_origin_code"), rs.getBoolean("is_default"), rs.getBoolean("is_active"))).list();
    }

    public List<WorkCenter> listWorkCenters() {
        return jdbcClient.sql("SELECT id,center_code,display_name,is_default,is_active FROM commerce_work_center ORDER BY display_name,id")
            .query((rs, n) -> new WorkCenter(rs.getLong("id"), rs.getString("center_code"), rs.getString("display_name"),
                rs.getBoolean("is_default"), rs.getBoolean("is_active"))).list();
    }

    public List<CustomerRouting> listCustomerRoutings() {
        return jdbcClient.sql(
                """
                SELECT r.customer_id, c.email, r.business_line_id, r.work_center_id, r.order_origin_code
                FROM commerce_customer_routing r JOIN customer_account c ON c.id = r.customer_id
                ORDER BY c.email
                """
            ).query((rs, n) -> new CustomerRouting(rs.getLong("customer_id"), rs.getString("email"),
                rs.getLong("business_line_id"), rs.getLong("work_center_id"), rs.getString("order_origin_code"))).list();
    }

    @Transactional
    public PricePolicy savePricePolicy(PricePolicyRequest request) {
        if (request == null) throw badRequest("Price policy is required");
        String code = normalizeCode(request.policyCode(), "Policy code", 64).toLowerCase(Locale.ROOT);
        String name = requireText(request.displayName(), "Display name", 128);
        String segment = normalizeSegment(request.customerSegmentCode());
        String currency = normalizeCurrency(request.currencyCode());
        int min = boundedPositive(request.minimumQuantity(), "Minimum quantity", 10_000);
        Integer max = positiveOrNull(request.maximumQuantity(), "Maximum quantity", 10_000);
        if (max != null && max < min) throw badRequest("Maximum quantity must not be below minimum quantity");
        BigDecimal price = money(request.unitPrice(), currency, false, "Unit price");
        Long customerId = request.customerId();
        if (customerId != null && customerId <= 0) throw badRequest("Customer id is invalid");
        if (customerId != null) {
            int merchant = jdbcClient.sql("SELECT COUNT(*) FROM customer_account WHERE id=:id AND is_active=1 AND account_type_code='merchant'")
                .param("id", customerId).query(Integer.class).single();
            if (merchant != 1) throw badRequest("Customer-specific pricing requires an active business customer");
            segment = "business";
        }
        int priority = boundedPriority(request.priorityNo());
        boolean active = request.active() == null || request.active();
        if (request.id() == null) {
            jdbcClient.sql("""
                INSERT INTO commerce_price_policy
                  (policy_code,display_name,customer_segment_code,customer_id,currency_code,minimum_quantity,maximum_quantity,unit_price,priority_no,is_active)
                VALUES (:code,:name,:segment,:customerId,:currency,:min,:max,:price,:priority,:active)
                """).param("code", code).param("name", name).param("segment", segment).param("customerId", customerId)
                .param("currency", currency).param("min", min).param("max", max).param("price", price)
                .param("priority", priority).param("active", active ? 1 : 0).update();
        } else {
            requirePositiveId(request.id(), "Price policy id");
            int changed = jdbcClient.sql("""
                UPDATE commerce_price_policy SET policy_code=:code,display_name=:name,customer_segment_code=:segment,
                  customer_id=:customerId,currency_code=:currency,minimum_quantity=:min,maximum_quantity=:max,
                  unit_price=:price,priority_no=:priority,is_active=:active WHERE id=:id
                """).param("code", code).param("name", name).param("segment", segment).param("customerId", customerId)
                .param("currency", currency).param("min", min).param("max", max).param("price", price)
                .param("priority", priority).param("active", active ? 1 : 0).param("id", request.id()).update();
            if (changed != 1) throw notFound("Price policy not found");
        }
        return listPricePolicies().stream().filter(item -> item.policyCode().equals(code)).findFirst().orElseThrow();
    }

    @Transactional
    public ShippingPolicy saveShippingPolicy(ShippingPolicyRequest request) {
        if (request == null) throw badRequest("Shipping policy is required");
        String code = normalizeCode(request.policyCode(), "Policy code", 64).toLowerCase(Locale.ROOT);
        String name = requireText(request.displayName(), "Display name", 128);
        String country = "*".equals(clean(request.destinationCountry(), 129)) ? "*"
            : normalizeDestination(request.destinationCountry());
        String currency = normalizeCurrency(request.currencyCode());
        int perCard = boundedPositive(request.perCardWeightGrams(), "Per-card weight", 100_000);
        int packaging = boundedNonNegative(request.packagingWeightGrams(), "Packaging weight", 1_000_000);
        int firstWeight = boundedPositive(request.firstWeightGrams(), "First weight", 1_000_000);
        int additionalWeight = boundedPositive(request.additionalWeightGrams(), "Additional weight", 1_000_000);
        BigDecimal firstPrice = money(request.firstWeightPrice(), currency, true, "First-weight price");
        BigDecimal additionalPrice = money(request.additionalWeightPrice(), currency, true, "Additional-weight price");
        Integer discountThreshold = positiveOrNull(request.discountQuantityThreshold(), "Discount threshold", 10_000);
        BigDecimal discount = request.discountPercent() == null ? BigDecimal.ZERO : request.discountPercent();
        if (discount.signum() < 0 || discount.compareTo(BigDecimal.valueOf(100)) > 0 || discount.scale() > 2)
            throw badRequest("Discount percent must be between 0 and 100 with at most two decimals");
        Integer freeThreshold = positiveOrNull(request.freeShippingQuantityThreshold(), "Free-shipping threshold", 10_000);
        int priority = boundedPriority(request.priorityNo());
        boolean active = request.active() == null || request.active();
        if (request.id() == null) {
            jdbcClient.sql("""
                INSERT INTO commerce_shipping_policy
                  (policy_code,display_name,destination_country,currency_code,per_card_weight_grams,packaging_weight_grams,
                   first_weight_grams,first_weight_price,additional_weight_grams,additional_weight_price,
                   discount_quantity_threshold,discount_percent,free_shipping_quantity_threshold,priority_no,is_active)
                VALUES (:code,:name,:country,:currency,:perCard,:packaging,:firstWeight,:firstPrice,:additionalWeight,
                        :additionalPrice,:discountThreshold,:discount,:freeThreshold,:priority,:active)
                """).param("code", code).param("name", name).param("country", country).param("currency", currency)
                .param("perCard", perCard).param("packaging", packaging).param("firstWeight", firstWeight)
                .param("firstPrice", firstPrice).param("additionalWeight", additionalWeight).param("additionalPrice", additionalPrice)
                .param("discountThreshold", discountThreshold).param("discount", discount).param("freeThreshold", freeThreshold)
                .param("priority", priority).param("active", active ? 1 : 0).update();
        } else {
            requirePositiveId(request.id(), "Shipping policy id");
            int changed = jdbcClient.sql("""
                UPDATE commerce_shipping_policy SET policy_code=:code,display_name=:name,destination_country=:country,
                  currency_code=:currency,per_card_weight_grams=:perCard,packaging_weight_grams=:packaging,
                  first_weight_grams=:firstWeight,first_weight_price=:firstPrice,additional_weight_grams=:additionalWeight,
                  additional_weight_price=:additionalPrice,discount_quantity_threshold=:discountThreshold,
                  discount_percent=:discount,free_shipping_quantity_threshold=:freeThreshold,priority_no=:priority,is_active=:active
                WHERE id=:id
                """).param("code", code).param("name", name).param("country", country).param("currency", currency)
                .param("perCard", perCard).param("packaging", packaging).param("firstWeight", firstWeight)
                .param("firstPrice", firstPrice).param("additionalWeight", additionalWeight).param("additionalPrice", additionalPrice)
                .param("discountThreshold", discountThreshold).param("discount", discount).param("freeThreshold", freeThreshold)
                .param("priority", priority).param("active", active ? 1 : 0).param("id", request.id()).update();
            if (changed != 1) throw notFound("Shipping policy not found");
        }
        return listShippingPolicies().stream().filter(item -> item.policyCode().equals(code)).findFirst().orElseThrow();
    }

    @Transactional
    public BusinessLine saveBusinessLine(BusinessLineRequest request) {
        if (request == null) throw badRequest("Business line is required");
        String code = normalizeCode(request.lineCode(), "Business-line code", 48).toLowerCase(Locale.ROOT);
        String name = requireText(request.displayName(), "Display name", 128);
        String origin = normalizeOrigin(request.orderOriginCode());
        boolean defaultLine = Boolean.TRUE.equals(request.defaultLine());
        boolean active = request.active() == null || request.active();
        if (defaultLine) jdbcClient.sql("UPDATE commerce_business_line SET is_default=0 WHERE order_origin_code=:origin")
            .param("origin", origin).update();
        if (request.id() == null) {
            jdbcClient.sql("INSERT INTO commerce_business_line(line_code,display_name,order_origin_code,is_default,is_active) VALUES(:code,:name,:origin,:fallback,:active)")
                .param("code", code).param("name", name).param("origin", origin).param("fallback", defaultLine ? 1 : 0)
                .param("active", active ? 1 : 0).update();
        } else {
            int changed = jdbcClient.sql("UPDATE commerce_business_line SET line_code=:code,display_name=:name,order_origin_code=:origin,is_default=:fallback,is_active=:active WHERE id=:id")
                .param("code", code).param("name", name).param("origin", origin).param("fallback", defaultLine ? 1 : 0)
                .param("active", active ? 1 : 0).param("id", request.id()).update();
            if (changed != 1) throw notFound("Business line not found");
        }
        return listBusinessLines().stream().filter(item -> item.lineCode().equals(code)).findFirst().orElseThrow();
    }

    @Transactional
    public WorkCenter saveWorkCenter(WorkCenterRequest request) {
        if (request == null) throw badRequest("Work center is required");
        String code = normalizeCode(request.centerCode(), "Work-center code", 48).toLowerCase(Locale.ROOT);
        String name = requireText(request.displayName(), "Display name", 128);
        boolean defaultCenter = Boolean.TRUE.equals(request.defaultCenter());
        boolean active = request.active() == null || request.active();
        if (defaultCenter) jdbcClient.sql("UPDATE commerce_work_center SET is_default=0").update();
        if (request.id() == null) {
            jdbcClient.sql("INSERT INTO commerce_work_center(center_code,display_name,is_default,is_active) VALUES(:code,:name,:fallback,:active)")
                .param("code", code).param("name", name).param("fallback", defaultCenter ? 1 : 0).param("active", active ? 1 : 0).update();
        } else {
            int changed = jdbcClient.sql("UPDATE commerce_work_center SET center_code=:code,display_name=:name,is_default=:fallback,is_active=:active WHERE id=:id")
                .param("code", code).param("name", name).param("fallback", defaultCenter ? 1 : 0)
                .param("active", active ? 1 : 0).param("id", request.id()).update();
            if (changed != 1) throw notFound("Work center not found");
        }
        return listWorkCenters().stream().filter(item -> item.centerCode().equals(code)).findFirst().orElseThrow();
    }

    @Transactional
    public CustomerRouting saveCustomerRouting(CustomerRoutingRequest request, Long updatedBy) {
        if (request == null || request.customerId() == null || request.customerId() <= 0) throw badRequest("Customer is required");
        requireActiveLine(request.businessLineId());
        requireActiveCenter(request.workCenterId());
        String origin = normalizeOrigin(request.orderOriginCode());
        if (!"customer_submission".equals(origin)) {
            throw badRequest("Customer order routing must use a customer-submission business line");
        }
        int matchingLine = jdbcClient.sql("""
            SELECT COUNT(*) FROM commerce_business_line
            WHERE id=:lineId AND is_active=1 AND order_origin_code='customer_submission'
            """).param("lineId", request.businessLineId()).query(Integer.class).single();
        if (matchingLine != 1) {
            throw badRequest("Customer order routing must use a customer-submission business line");
        }
        jdbcClient.sql("""
            INSERT INTO commerce_customer_routing(customer_id,business_line_id,work_center_id,order_origin_code,updated_by_user_id)
            VALUES(:customerId,:lineId,:centerId,:origin,:updatedBy)
            ON DUPLICATE KEY UPDATE business_line_id=VALUES(business_line_id),work_center_id=VALUES(work_center_id),
              order_origin_code=VALUES(order_origin_code),updated_by_user_id=VALUES(updated_by_user_id),updated_at=CURRENT_TIMESTAMP
            """).param("customerId", request.customerId()).param("lineId", request.businessLineId())
            .param("centerId", request.workCenterId()).param("origin", origin).param("updatedBy", updatedBy).update();
        return listCustomerRoutings().stream().filter(item -> item.customerId() == request.customerId()).findFirst().orElseThrow();
    }

    void requireActiveLine(Long id) {
        if (id == null || id <= 0 || jdbcClient.sql("SELECT COUNT(*) FROM commerce_business_line WHERE id=:id AND is_active=1")
            .param("id", id).query(Integer.class).single() != 1) throw badRequest("Active business line is required");
    }

    void requireActiveCenter(Long id) {
        if (id == null || id <= 0 || jdbcClient.sql("SELECT COUNT(*) FROM commerce_work_center WHERE id=:id AND is_active=1")
            .param("id", id).query(Integer.class).single() != 1) throw badRequest("Active work center is required");
    }

    private static boolean countryMatches(String scope, String country) {
        if (scope == null || scope.isBlank() || "*".equals(scope.trim())) return true;
        return List.of(scope.toUpperCase(Locale.ROOT).split(",")).stream().map(String::trim).anyMatch(country::equals);
    }

    private static int currencyScale(String currency) { return "JPY".equals(currency) ? 0 : 2; }
    private static String normalizeDestination(String value) {
        String destination = clean(value, 129).toUpperCase(Locale.ROOT);
        if (destination.isBlank() || destination.length() > 128 || !destination.matches("[A-Z0-9][A-Z0-9 ._'-]{0,127}"))
            throw badRequest("Destination country is invalid");
        return destination;
    }
    private static long parseWeightPolicyId(String optionCode) {
        try {
            long id = Long.parseLong(optionCode.substring("weight_".length()));
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (RuntimeException exception) {
            throw badRequest("Weight shipping option code is invalid");
        }
    }
    private static String normalizeCurrency(String value) {
        String currency = clean(value, 4).toUpperCase(Locale.ROOT);
        if (!SUPPORTED_CURRENCIES.contains(currency)) throw badRequest("Unsupported currency");
        return currency;
    }
    private static String normalizeSegment(String value) {
        String segment = clean(value, 17).toLowerCase(Locale.ROOT);
        if (!SEGMENTS.contains(segment)) throw badRequest("Unsupported customer segment");
        return segment;
    }
    private static String normalizeOrigin(String value) {
        String origin = clean(value, 33).toLowerCase(Locale.ROOT);
        if (!ORIGINS.contains(origin)) throw badRequest("Unsupported order origin");
        return origin;
    }
    private static String normalizeCode(String value, String label, int max) {
        String code = clean(value, max + 1).toUpperCase(Locale.ROOT);
        if (code.isBlank() || !code.matches("[A-Z0-9*][A-Z0-9_-]{0," + (max - 1) + "}")) throw badRequest(label + " is invalid");
        return code;
    }
    private static String requireText(String value, String label, int max) {
        String text = clean(value, max + 1);
        if (text.isBlank() || text.length() > max) throw badRequest(label + " is required");
        return text;
    }
    private static String clean(String value, int max) {
        if (value == null) return "";
        String text = value.trim().replaceAll("[\\r\\n\\t]+", " ").replaceAll(" +", " ");
        return text.substring(0, Math.min(text.length(), max));
    }
    private static int boundedPositive(Integer value, String label, int max) {
        if (value == null || value < 1 || value > max) throw badRequest(label + " must be between 1 and " + max);
        return value;
    }
    private static int boundedNonNegative(Integer value, String label, int max) {
        if (value == null || value < 0 || value > max) throw badRequest(label + " must be between 0 and " + max);
        return value;
    }
    private static Integer positiveOrNull(Integer value, String label, int max) {
        return value == null ? null : boundedPositive(value, label, max);
    }
    private static int boundedPriority(Integer value) {
        int priority = value == null ? 0 : value;
        if (priority < -1000 || priority > 1000) throw badRequest("Priority is outside the supported range");
        return priority;
    }
    private static BigDecimal money(BigDecimal value, String currency, boolean allowZero, String label) {
        if (value == null || (allowZero ? value.signum() < 0 : value.signum() <= 0) || value.compareTo(new BigDecimal("100000000")) > 0)
            throw badRequest(label + " is invalid");
        try { return value.setScale(currencyScale(currency), RoundingMode.UNNECESSARY).setScale(2); }
        catch (ArithmeticException error) { throw badRequest(label + " has invalid precision"); }
    }
    private static void ensureOrderAmount(BigDecimal value, String label) {
        if (value.signum() < 0 || value.compareTo(MAX_ORDER_AMOUNT) > 0) throw badRequest(label + " exceeds the supported amount");
    }
    private static void requirePositiveId(Long id, String label) { if (id == null || id <= 0) throw badRequest(label + " is invalid"); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    private record PriceMatch(Long policyId, BigDecimal unitPrice, String sourceCode) {}
    private record ShippingMatch(Long policyId, BigDecimal amount, String sourceCode, Integer perCardWeightGrams,
                                 Integer packagingWeightGrams, Integer totalWeightGrams, Integer chargeableWeightGrams,
                                 String shippingOptionCode, String displayName) {}
    private record GlobalShippingOption(String optionCode, String displayName, String countryScope, BigDecimal price, int sortOrder) {}

    public record RoutingAssignment(Long businessLineId, String businessLineName, Long workCenterId,
                                    String workCenterName, String orderOriginCode) {}
    public record QuoteResult(long customerId, String customerSegmentCode, String destinationCountry, String currencyCode,
                              int cardCount, Long pricePolicyId, Long shippingPolicyId, String pricingSourceCode,
                              String shippingSourceCode, BigDecimal unitPrice, BigDecimal serviceFee,
                              BigDecimal returnShippingFee, BigDecimal totalAmount, Integer perCardWeightGrams,
                              Integer packagingWeightGrams, Integer totalWeightGrams, Integer chargeableWeightGrams,
                              String shippingOptionCode, String shippingDisplayName, RoutingAssignment routing) {}
    public record PricePolicy(long id, String policyCode, String displayName, String customerSegmentCode,
                              Long customerId, String currencyCode, int minimumQuantity, Integer maximumQuantity,
                              BigDecimal unitPrice, int priorityNo, boolean active) {}
    public record ShippingPolicy(long id, String policyCode, String displayName, String destinationCountry,
                                 String currencyCode, int perCardWeightGrams, int packagingWeightGrams,
                                 int firstWeightGrams, BigDecimal firstWeightPrice, int additionalWeightGrams,
                                 BigDecimal additionalWeightPrice, Integer discountQuantityThreshold,
                                 BigDecimal discountPercent, Integer freeShippingQuantityThreshold,
                                 int priorityNo, boolean active) {}
    public record BusinessLine(long id, String lineCode, String displayName, String orderOriginCode,
                               boolean defaultLine, boolean active) {}
    public record WorkCenter(long id, String centerCode, String displayName, boolean defaultCenter, boolean active) {}
    public record CustomerRouting(long customerId, String customerEmail, long businessLineId, long workCenterId,
                                  String orderOriginCode) {}
    public record CommerceCatalog(List<PricePolicy> pricePolicies, List<ShippingPolicy> shippingPolicies,
                                  List<BusinessLine> businessLines, List<WorkCenter> workCenters,
                                  List<CustomerRouting> customerRoutings) {}
    public record PricePolicyRequest(Long id, String policyCode, String displayName, String customerSegmentCode,
                                     Long customerId, String currencyCode, Integer minimumQuantity,
                                     Integer maximumQuantity, BigDecimal unitPrice, Integer priorityNo, Boolean active) {}
    public record ShippingPolicyRequest(Long id, String policyCode, String displayName, String destinationCountry,
                                        String currencyCode, Integer perCardWeightGrams, Integer packagingWeightGrams,
                                        Integer firstWeightGrams, BigDecimal firstWeightPrice, Integer additionalWeightGrams,
                                        BigDecimal additionalWeightPrice, Integer discountQuantityThreshold,
                                        BigDecimal discountPercent, Integer freeShippingQuantityThreshold,
                                        Integer priorityNo, Boolean active) {}
    public record BusinessLineRequest(Long id, String lineCode, String displayName, String orderOriginCode,
                                      Boolean defaultLine, Boolean active) {}
    public record WorkCenterRequest(Long id, String centerCode, String displayName, Boolean defaultCenter, Boolean active) {}
    public record CustomerRoutingRequest(Long customerId, Long businessLineId, Long workCenterId, String orderOriginCode) {}
    public record BatchPartRequest(String reference, Integer cardCount) {}
    public record AllocatedBatchQuote(String reference, int cardCount, BigDecimal serviceFee,
                                      BigDecimal returnShippingFee, BigDecimal totalAmount) {}
    public record BatchQuoteResult(QuoteResult aggregate, List<AllocatedBatchQuote> allocations) {}
}
