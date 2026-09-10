package com.nxr.platform.customer;

import com.nxr.platform.admission.OrderAdmissionService;
import com.nxr.platform.commerce.CommercePolicyService;
import com.nxr.platform.commerce.OrderAccessScopeService;
import com.nxr.platform.shared.ProductTypePolicy;
import com.nxr.platform.notifications.NotificationOutboxService;
import com.ruoyi.common.utils.SecurityUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Customer card collection and end-to-end grading order workflow. */
@Service
public class CustomerPortalService {

    private static final Set<String> OWNERSHIP_VISIBILITIES = Set.of("public", "anonymous", "private");
    private static final Set<String> PAYMENT_PROVIDERS = Set.of(
        "manual_transfer", "bank_transfer", "wechat_transfer", "wechat_pay_native",
        "alipay_transfer", "alipay", "stripe", "paypal"
    );
    private static final Set<String> SHIPMENT_DIRECTIONS = Set.of("inbound", "outbound");
    private static final Map<String, Set<String>> ALLOWED_STATUS_TRANSITIONS = Map.ofEntries(
        Map.entry("admission_review", Set.of("cancelled")),
        Map.entry("terms_confirmation", Set.of("cancelled")),
        Map.entry("payment_expired", Set.of("cancelled")),
        Map.entry("awaiting_payment", Set.of("payment_review", "cancelled")),
        Map.entry("payment_exception", Set.of()),
        Map.entry("payment_review", Set.of("awaiting_inbound", "awaiting_payment", "cancelled")),
        Map.entry("awaiting_inbound", Set.of("inbound_shipped", "received", "intake_exception", "cancelled")),
        Map.entry("inbound_shipped", Set.of("received", "intake_exception", "cancelled")),
        Map.entry("intake_exception", Set.of("received", "cancelled")),
        Map.entry("received", Set.of("grading", "review", "intake_exception", "cancelled")),
        Map.entry("grading", Set.of("review", "cancelled")),
        Map.entry("review", Set.of("quality_check", "quality_hold", "completed", "cancelled")),
        Map.entry("quality_check", Set.of("completed", "quality_hold", "cancelled")),
        Map.entry("quality_hold", Set.of("quality_check", "review", "cancelled")),
        Map.entry("completed", Set.of("return_shipped")),
        Map.entry("return_shipped", Set.of("delivered")),
        Map.entry("delivered", Set.of()),
        Map.entry("cancelled", Set.of())
    );

    private final JdbcClient jdbcClient;
    private final SimpleJdbcInsert ownershipInsert;
    private final SimpleJdbcInsert orderInsert;
    private final SimpleJdbcInsert orderItemInsert;
    private final SimpleJdbcInsert paymentInsert;
    private final SimpleJdbcInsert shipmentInsert;
    private final OrderFulfillmentService orderFulfillmentService;
    private final MerchantWalletService merchantWalletService;
    private final NotificationOutboxService notificationOutboxService;
    private OrderAdmissionService orderAdmissionService;
    private CustomerOrderPhotoService customerOrderPhotoService;
    private CommercePolicyService commercePolicyService;
    private OrderAccessScopeService orderAccessScopeService;

    public CustomerPortalService(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this(jdbcClient, jdbcTemplate, null, null, null);
    }

    public CustomerPortalService(
        JdbcClient jdbcClient,
        JdbcTemplate jdbcTemplate,
        OrderFulfillmentService orderFulfillmentService
    ) {
        this(jdbcClient, jdbcTemplate, orderFulfillmentService, null, null);
    }

    @Autowired
    public CustomerPortalService(
        JdbcClient jdbcClient,
        JdbcTemplate jdbcTemplate,
        OrderFulfillmentService orderFulfillmentService,
        MerchantWalletService merchantWalletService,
        NotificationOutboxService notificationOutboxService
    ) {
        this.jdbcClient = jdbcClient;
        this.orderFulfillmentService = orderFulfillmentService;
        this.merchantWalletService = merchantWalletService;
        this.notificationOutboxService = notificationOutboxService;
        this.ownershipInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("certificate_ownership")
            .usingColumns("cert_id", "active_cert_id", "customer_id", "ownership_status_code", "visibility_code", "note")
            .usingGeneratedKeyColumns("id");
        this.orderInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("grading_order")
            .usingColumns(
                "order_no", "customer_id", "status_code", "service_level_code", "total_card_count",
                "return_shipping_option_code", "return_shipping_option_name",
                "service_fee", "return_shipping_fee", "total_amount", "currency_code", "contact_name",
                "contact_phone", "return_address_line1", "return_address_line2", "return_city", "return_region",
                "return_postal_code", "return_country", "customer_note"
            )
            .usingGeneratedKeyColumns("id");
        this.orderItemInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("grading_order_item")
            .usingColumns(
                "order_id", "item_no", "card_name", "brand_name", "set_name", "card_number",
                "language_code", "declared_value", "item_note", "status_code"
            );
        this.paymentInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("payment_record")
            .usingColumns(
                "order_id", "direction_code", "payment_type_code", "payment_no", "provider_code", "status_code",
                "amount", "currency_code", "payment_url", "qr_payload"
            )
            .usingGeneratedKeyColumns("id");
        this.shipmentInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("order_shipment")
            .usingColumns(
                "order_id", "direction_code", "shipping_option_code", "shipping_option_name",
                "carrier_name", "tracking_number", "status_code", "shipped_by_user_id", "note"
            )
            .usingGeneratedKeyColumns("id");
    }

    @Autowired(required = false)
    public void setOrderAdmissionService(OrderAdmissionService orderAdmissionService) {
        this.orderAdmissionService = orderAdmissionService;
    }

    @Autowired(required = false)
    public void setCustomerOrderPhotoService(CustomerOrderPhotoService customerOrderPhotoService) {
        this.customerOrderPhotoService = customerOrderPhotoService;
    }

    @Autowired(required = false)
    public void setCommercePolicyService(CommercePolicyService commercePolicyService) {
        this.commercePolicyService = commercePolicyService;
    }

    @Autowired(required = false)
    public void setOrderAccessScopeService(OrderAccessScopeService orderAccessScopeService) {
        this.orderAccessScopeService = orderAccessScopeService;
    }

    public CardCommunityResponse loadCardCommunity(String certificateId) {
        String certId = normalizeCertificateId(certificateId);
        return new CardCommunityResponse(
            findActiveOwnership(certId).map(this::toOwnershipSummary).orElse(null),
            listOwnershipEvents(certId)
        );
    }

    public List<CustomerCardResponse> listCustomerCards(long customerId) {
        return jdbcClient.sql(
                """
                SELECT o.cert_id, o.visibility_code, o.note, o.bound_at,
                       COALESCE(NULLIF(s.product_type_code, ''), 'graded_card') AS product_type_code,
                       s.vintage_classification_code,
                       s.merch_description,
                       s.card_name, s.brand_name, s.year_label, s.set_name, s.card_number,
                       gs.final_grade_value, gs.final_grade_label,
                       front_media.public_url AS front_image_url
                FROM certificate_ownership o
                JOIN published_certificate pc ON UPPER(pc.cert_id) = UPPER(o.cert_id)
                JOIN grading_submission s ON s.id = pc.submission_id
                LEFT JOIN grading_score gs ON gs.submission_id = s.id
                LEFT JOIN submission_media front_media ON front_media.id = pc.published_front_media_id
                WHERE o.customer_id = :customerId
                  AND o.ownership_status_code = 'active'
                ORDER BY o.bound_at DESC, o.id DESC
                """
            )
            .param("customerId", customerId)
            .query((rs, rowNum) -> new CustomerCardResponse(
                rs.getString("cert_id"),
                ProductTypePolicy.normalizeStored(rs.getString("product_type_code")),
                rs.getString("vintage_classification_code"),
                rs.getString("merch_description"),
                rs.getString("card_name"),
                rs.getString("brand_name"),
                rs.getString("year_label"),
                rs.getString("set_name"),
                rs.getString("card_number"),
                rs.getBigDecimal("final_grade_value"),
                rs.getString("final_grade_label"),
                rs.getString("front_image_url"),
                rs.getString("visibility_code"),
                rs.getString("note"),
                rs.getObject("bound_at", LocalDateTime.class)
            ))
            .list();
    }

    @Transactional
    public CardCommunityResponse claimCard(long customerId, String certificateId, ClaimCardRequest request) {
        String certId = requirePublishedCertificate(certificateId);
        if (findActiveOwnership(certId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This certificate is already bound to a collector account");
        }

        String visibility = normalizeVisibility(request.visibility());
        String note = clean(request.note(), 1000);
        try {
            ownershipInsert.execute(Map.of(
                "cert_id", certId,
                "active_cert_id", certId,
                "customer_id", customerId,
                "ownership_status_code", "active",
                "visibility_code", visibility,
                "note", note
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This certificate is already bound to a collector account", exception);
        }
        addOwnershipEvent(certId, null, customerId, "bound", visibility, note);
        return loadCardCommunity(certId);
    }

    @Transactional
    public CardCommunityResponse transferCard(
        long customerId,
        String certificateId,
        TransferCardRequest request,
        CustomerAuthService customerAuthService
    ) {
        String certId = requirePublishedCertificate(certificateId);
        OwnershipRecord ownership = findActiveOwnership(certId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This certificate is not bound to a collector account"));
        if (ownership.customerId() != customerId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the current collector can transfer this certificate");
        }

        CustomerAuthService.CustomerAccount recipient = customerAuthService.findCustomerByEmail(request.recipientEmail())
            .filter(CustomerAuthService.CustomerAccount::active)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient customer account was not found"));
        if (recipient.id() == customerId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The recipient already owns this certificate");
        }

        String visibility = normalizeVisibility(request.visibility());
        String note = clean(request.message(), 1000);
        jdbcClient.sql(
                """
                UPDATE certificate_ownership
                SET active_cert_id = NULL, ownership_status_code = 'transferred', released_at = CURRENT_TIMESTAMP
                WHERE id = :ownershipId AND ownership_status_code = 'active'
                """
            )
            .param("ownershipId", ownership.id())
            .update();
        try {
            ownershipInsert.execute(Map.of(
                "cert_id", certId,
                "active_cert_id", certId,
                "customer_id", recipient.id(),
                "ownership_status_code", "active",
                "visibility_code", visibility,
                "note", note
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This certificate ownership changed before the transfer completed", exception);
        }
        addOwnershipEvent(certId, customerId, recipient.id(), "transferred", visibility, note);
        return loadCardCommunity(certId);
    }

    @Transactional
    public OrderDetailResponse createOrder(long customerId, CreateOrderRequest request) {
        return createOrderInternal(customerId, request, null, null);
    }

    /** Internal merchant-batch path. The quote and allocation are created server-side by CommercePolicyService. */
    @Transactional
    public OrderDetailResponse createOrderWithBatchQuote(
        long customerId,
        CreateOrderRequest request,
        CommercePolicyService.QuoteResult aggregateQuote,
        CommercePolicyService.AllocatedBatchQuote allocation
    ) {
        if (aggregateQuote == null || allocation == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A server-issued batch quote allocation is required");
        }
        return createOrderInternal(customerId, request, aggregateQuote, allocation);
    }

    private OrderDetailResponse createOrderInternal(
        long customerId,
        CreateOrderRequest request,
        CommercePolicyService.QuoteResult aggregateQuote,
        CommercePolicyService.AllocatedBatchQuote allocation
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grading order details are required");
        }
        OrderFulfillmentService fulfillment = requireFulfillmentService();
        int maxCards = orderAdmissionService == null ? 30 : orderAdmissionService.maxCardsPerOrder();
        List<OrderItemRequest> requestedItems = resolveOrderItems(request, maxCards);
        if (requestedItems.isEmpty() || requestedItems.size() > maxCards) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An order must include between 1 and " + maxCards + " cards");
        }
        validateOrderPhotos(customerId, requestedItems);
        String serviceLevel = "basic_grading";

        OrderFulfillmentService.CustomerAddress savedAddress = request.returnAddressId() == null
            ? null
            : fulfillment.requireAddress(customerId, request.returnAddressId());
        String contactName = savedAddress == null
            ? requireText(request.contactName(), "Contact name", 128) : savedAddress.contactName();
        String contactPhone = savedAddress == null
            ? requireText(request.contactPhone(), "Contact phone", 64) : savedAddress.contactPhone();
        String addressLine1 = savedAddress == null
            ? requireText(request.returnAddressLine1(), "Return address", 255) : savedAddress.addressLine1();
        String addressLine2 = savedAddress == null
            ? blankToNull(clean(request.returnAddressLine2(), 255)) : savedAddress.addressLine2();
        String city = savedAddress == null
            ? requireText(request.returnCity(), "Return city", 128) : savedAddress.city();
        String region = savedAddress == null
            ? blankToNull(clean(request.returnRegion(), 128)) : savedAddress.region();
        String postalCode = savedAddress == null
            ? requireText(request.returnPostalCode(), "Return postal code", 64) : savedAddress.postalCode();
        String country = savedAddress == null
            ? requireText(request.returnCountry(), "Return country", 128) : savedAddress.country();

        if (savedAddress == null && Boolean.TRUE.equals(request.saveReturnAddress())) {
            fulfillment.saveAddress(customerId, null, new OrderFulfillmentService.AddressRequest(
                "Return address", contactName, contactPhone, addressLine1, addressLine2,
                city, region, postalCode, country, fulfillment.listAddresses(customerId).isEmpty()
            ));
        }

        String requestedCurrency = request.currencyCode() == null || request.currencyCode().isBlank()
            ? "USD" : request.currencyCode();
        CommercePolicyService.QuoteResult commerceQuote = aggregateQuote != null ? aggregateQuote
            : commercePolicyService == null ? null : commercePolicyService.quoteForOrder(
                customerId, country, requestedCurrency, requestedItems.size(), request.returnShippingOptionCode()
            );
        OrderFulfillmentService.ShippingOption shippingOption = commerceQuote == null
            ? selectShippingOption(fulfillment, request.returnShippingOptionCode(), country) : null;
        OrderFulfillmentService.ServicePrice servicePrice = commerceQuote == null
            ? fulfillment.activeServicePrice(requestedCurrency) : null;
        if (commerceQuote == null && !servicePrice.currencyCode().equalsIgnoreCase(shippingOption.currencyCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Grading and return shipping currencies do not match");
        }
        if (allocation != null) {
            validateBatchAllocation(customerId, requestedItems.size(), country, requestedCurrency,
                request.returnShippingOptionCode(), commerceQuote, allocation);
        }
        BigDecimal serviceFee = allocation != null ? allocation.serviceFee() : commerceQuote == null
            ? servicePrice.unitPrice().multiply(BigDecimal.valueOf(requestedItems.size())) : commerceQuote.serviceFee();
        BigDecimal returnShippingFee = allocation != null ? allocation.returnShippingFee()
            : commerceQuote == null ? shippingOption.priceAmount() : commerceQuote.returnShippingFee();
        BigDecimal totalAmount = allocation != null ? allocation.totalAmount() : commerceQuote == null
            ? serviceFee.add(returnShippingFee).setScale(2, RoundingMode.HALF_UP) : commerceQuote.totalAmount();
        String quoteCurrency = commerceQuote == null ? servicePrice.currencyCode() : commerceQuote.currencyCode();
        String shippingOptionCode = commerceQuote == null ? shippingOption.optionCode() : commerceQuote.shippingOptionCode();
        String shippingOptionName = commerceQuote == null ? shippingOption.displayName() : commerceQuote.shippingDisplayName();
        validatePresentedQuote(request, totalAmount, quoteCurrency);
        String orderNo = generateOrderNumber();

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_no", orderNo);
        values.put("customer_id", customerId);
        values.put("status_code", orderAdmissionService == null ? "awaiting_payment" : "admission_review");
        values.put("service_level_code", serviceLevel);
        values.put("return_shipping_option_code", shippingOptionCode);
        values.put("return_shipping_option_name", shippingOptionName);
        values.put("total_card_count", requestedItems.size());
        values.put("service_fee", serviceFee);
        values.put("return_shipping_fee", returnShippingFee);
        values.put("total_amount", totalAmount);
        values.put("currency_code", quoteCurrency);
        values.put("contact_name", contactName);
        values.put("contact_phone", contactPhone);
        values.put("return_address_line1", addressLine1);
        values.put("return_address_line2", addressLine2);
        values.put("return_city", city);
        values.put("return_region", region);
        values.put("return_postal_code", postalCode);
        values.put("return_country", country);
        values.put("customer_note", blankToNull(clean(request.customerNote(), 2000)));
        long orderId;
        try {
            orderId = orderInsert.executeAndReturnKey(values).longValue();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Please submit the order again", exception);
        }
        if (commerceQuote != null) {
            CommercePolicyService.RoutingAssignment routing = commerceQuote.routing();
            jdbcClient.sql(
                    """
                    UPDATE grading_order
                    SET order_origin_code = :origin, business_line_id = :lineId, work_center_id = :centerId,
                        commerce_price_policy_id = :pricePolicyId, commerce_shipping_policy_id = :shippingPolicyId,
                        quoted_unit_price = :unitPrice, quoted_card_weight_grams = :cardWeight,
                        quoted_packaging_weight_grams = :packagingWeight,
                        quoted_chargeable_weight_grams = :chargeableWeight,
                        pricing_source_code = :pricingSource, shipping_source_code = :shippingSource
                    WHERE id = :orderId
                    """
                )
                .param("origin", routing.orderOriginCode()).param("lineId", routing.businessLineId())
                .param("centerId", routing.workCenterId()).param("pricePolicyId", commerceQuote.pricePolicyId())
                .param("shippingPolicyId", commerceQuote.shippingPolicyId()).param("unitPrice", commerceQuote.unitPrice())
                .param("cardWeight", commerceQuote.perCardWeightGrams()).param("packagingWeight", commerceQuote.packagingWeightGrams())
                .param("chargeableWeight", commerceQuote.chargeableWeightGrams()).param("pricingSource", commerceQuote.pricingSourceCode())
                .param("shippingSource", commerceQuote.shippingSourceCode()).param("orderId", orderId).update();
        }

        int itemNo = 1;
        for (OrderItemRequest item : requestedItems) {
            Map<String, Object> itemValues = new LinkedHashMap<>();
            itemValues.put("order_id", orderId);
            itemValues.put("item_no", itemNo++);
            itemValues.put("card_name", requireText(item.cardName(), "Card name", 255));
            itemValues.put("brand_name", blankToNull(clean(item.brandName(), 128)));
            itemValues.put("set_name", blankToNull(clean(item.setName(), 255)));
            itemValues.put("card_number", blankToNull(clean(item.cardNumber(), 128)));
            itemValues.put("language_code", blankToNull(clean(item.languageCode(), 32)));
            itemValues.put("declared_value", normalizeDeclaredValue(item.declaredValue()));
            itemValues.put("item_note", blankToNull(clean(item.itemNote(), 1000)));
            itemValues.put("status_code", "awaiting_inbound");
            if (orderAdmissionService == null) {
                orderItemInsert.execute(itemValues);
            } else {
                jdbcClient.sql(
                        """
                        INSERT INTO grading_order_item
                            (order_id, item_no, card_name, brand_name, year_label, rarity, product_type, category,
                             set_name, card_number, language_code, declared_value, item_note,
                             front_photo_id, back_photo_id, status_code)
                        VALUES
                            (:orderId, :itemNo, :cardName, :brandName, :year, :rarity, :productType, :category,
                             :setName, :cardNumber, :languageCode, :declaredValue, :itemNote,
                             :frontPhotoId, :backPhotoId, 'awaiting_inbound')
                        """
                    )
                    .param("orderId", orderId).param("itemNo", itemNo - 1)
                    .param("cardName", itemValues.get("card_name")).param("brandName", itemValues.get("brand_name"))
                    .param("year", blankToNull(clean(item.year(), 32))).param("rarity", blankToNull(clean(item.rarity(), 128)))
                    .param("productType", blankToNull(clean(item.productType(), 64)))
                    .param("category", blankToNull(clean(item.category(), 128)))
                    .param("setName", itemValues.get("set_name")).param("cardNumber", itemValues.get("card_number"))
                    .param("languageCode", itemValues.get("language_code")).param("declaredValue", itemValues.get("declared_value"))
                    .param("itemNote", itemValues.get("item_note")).param("frontPhotoId", item.frontPhotoId())
                    .param("backPhotoId", item.backPhotoId()).update();
            }
        }
        attachOrderPhotos(customerId, orderId, requestedItems);

        String paymentNo = "PAY-" + orderNo;
        paymentInsert.execute(Map.of(
            "order_id", orderId,
            "direction_code", "receivable",
            "payment_type_code", "grading_fee",
            "payment_no", paymentNo,
            "provider_code", "manual_transfer",
            "status_code", "pending",
            "amount", totalAmount,
            "currency_code", quoteCurrency,
            "payment_url", "/account/orders/" + orderNo + "#payment",
            "qr_payload", "nxr://payment/" + paymentNo
        ));
        if (orderAdmissionService == null) {
            addTimelineEvent(orderId, "order_created", "Order created", "Your grading order is ready for payment.", "awaiting_payment", true, "customer", customerId, null);
            addTimelineEvent(orderId, "payment_pending", "Awaiting payment", "Submit a transfer reference after payment so our team can confirm it.", "awaiting_payment", true, "system", null, null);
            enqueueOrderNotification(customerId, orderNo, "created", "Your grading order has been created and is ready for payment.");
        } else {
            addTimelineEvent(orderId, "application_submitted", "Application submitted",
                "NXR will review the item list and quoted amount before payment opens.", "admission_review", true, "customer", customerId, null);
            orderAdmissionService.initialize(orderId, customerId, orderNo);
        }
        return requireCustomerOrder(customerId, orderNo);
    }

    public OrderListResponse listCustomerOrders(long customerId, int page, int pageSize) {
        return listOrders(page, pageSize, null, null, customerId, null);
    }

    public OrderDetailResponse requireCustomerOrder(long customerId, String orderNo) {
        return loadOrderDetailByOrderNo(orderNo)
            .filter(order -> order.customer().id() == customerId)
            .map(this::sanitizeCustomerOrder)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    private OrderDetailResponse sanitizeCustomerOrder(OrderDetailResponse order) {
        return new OrderDetailResponse(
            order.id(), order.orderNo(), order.statusCode(), order.admissionStatus(), order.serviceLevelCode(),
            order.returnShippingOptionCode(), order.returnShippingOptionName(), order.totalCardCount(),
            order.serviceFee(), order.returnShippingFee(), order.totalAmount(), order.currencyCode(),
            order.contactName(), order.contactPhone(), order.returnAddressLine1(), order.returnAddressLine2(),
            order.returnCity(), order.returnRegion(), order.returnPostalCode(), order.returnCountry(),
            order.customerNote(), null, order.intakeCode(), order.packingSlipCode(), order.shippingLabelCreatedAt(),
            order.customer(), order.createdAt(), order.updatedAt(), order.items(), order.payments(), order.shipments(), order.timeline()
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse submitPaymentProof(long customerId, String orderNo, SubmitPaymentProofRequest request) {
        long orderId = lockCustomerOrderForPayment(customerId, orderNo);
        requireAdmissionPaymentAllowed(orderId, customerId);
        OrderDetailResponse order = requireCustomerOrder(customerId, orderNo);
        assertNoActiveGatewayPayment(order.id());
        if (!Set.of("awaiting_payment", "payment_review").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is not waiting for payment confirmation");
        }
        PaymentRecord payment = findReceivablePayment(order.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment record not found"));
        String payerReference = requireText(request.payerReference(), "Payer reference", 255);
        String provider = normalizePaymentProvider(request.provider());
        String proofReference = blankToNull(clean(request.proofReference(), 512));
        jdbcClient.sql(
                """
                UPDATE payment_record
                SET provider_code = :provider, payer_reference = :payerReference, proof_reference = :proofReference,
                    status_code = 'proof_submitted', submitted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :paymentId
                """
            )
            .param("provider", provider)
            .param("payerReference", payerReference)
            .param("proofReference", proofReference)
            .param("paymentId", payment.id())
            .update();
        updateOrderStatus(order.id(), "payment_review", "Payment proof submitted", "Our staff will verify the payment before accepting shipment.", true, "customer", customerId, null, true);
        return requireCustomerOrder(customerId, orderNo);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentSessionResponse createPaymentSession(long customerId, String orderNo, PaymentSessionRequest request) {
        long orderId = lockCustomerOrderForPayment(customerId, orderNo);
        requireAdmissionPaymentAllowed(orderId, customerId);
        OrderDetailResponse order = requireCustomerOrder(customerId, orderNo);
        if (!Set.of("awaiting_payment", "payment_review").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is not waiting for payment");
        }
        String provider = normalizePaymentProvider(request == null ? null : request.provider());
        PaymentRecord payment = findReceivablePayment(order.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment record not found"));
        if (!Set.of("pending", "rejected", "failed").contains(payment.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The order already has payment activity that requires financial review");
        }
        if (payment.amount().compareTo(order.totalAmount()) != 0
            || !payment.currencyCode().equalsIgnoreCase(order.currencyCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order payment amount is inconsistent");
        }
        String paymentNo = payment.paymentNo() == null || payment.paymentNo().isBlank()
            ? "PAY-" + order.orderNo() : payment.paymentNo();
        String paymentUrl = "/account/orders/" + order.orderNo() + "?provider=" + provider + "#payment";
        String qrPayload = "nxr://payment/" + provider + "/" + paymentNo;
        jdbcClient.sql(
                """
                UPDATE payment_record
                SET payment_no = :paymentNo, provider_code = :provider, payment_url = :paymentUrl,
                    qr_payload = :qrPayload,
                    status_code = CASE WHEN status_code = 'rejected' THEN 'pending' ELSE status_code END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :paymentId
                """
            )
            .param("paymentNo", paymentNo)
            .param("provider", provider)
            .param("paymentUrl", paymentUrl)
            .param("qrPayload", qrPayload)
            .param("paymentId", payment.id())
            .update();
        return new PaymentSessionResponse(payment.id(), paymentNo, provider, paymentUrl, qrPayload, payment.amount(), payment.currencyCode());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse payOrderFromWallet(long customerId, String orderNo, WalletPaymentRequest request) {
        MerchantWalletService wallet = requireMerchantWalletService();
        long orderId = lockCustomerOrderForPayment(customerId, orderNo);
        requireAdmissionPaymentAllowed(orderId, customerId);
        wallet.debitOrder(customerId, orderId, request == null ? null : request.idempotencyKey());
        String currentStatus = currentLockedOrderStatus(orderId);
        if (Set.of("awaiting_payment", "payment_review").contains(currentStatus)) {
            updateOrderStatus(orderId, "awaiting_inbound", "Payment confirmed",
                "Your prepaid wallet payment was confirmed. Please send your cards to NXR.",
                true, "customer", customerId, null, false);
            requireFulfillmentService().ensureIntakeCodes(orderId);
        } else if (!"awaiting_inbound".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order payment state changed while wallet payment was being processed");
        }
        return requireCustomerOrder(customerId, orderNo);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse cancelCustomerOrder(long customerId, String orderNo, CancelOrderRequest request) {
        lockCustomerOrderForPayment(customerId, orderNo);
        OrderDetailResponse order = requireCustomerOrder(customerId, orderNo);
        if ("cancelled".equals(order.statusCode())) {
            return order;
        }
        String reason = requireText(request == null ? null : request.reason(), "Cancellation reason", 1000);
        assertNoActiveGatewayPayment(order.id());
        MerchantWalletService wallet = merchantWalletService;
        boolean walletPaid = wallet != null && wallet.hasPaidWalletOrder(order.id());
        if (walletPaid) {
            if (!"awaiting_inbound".equals(order.statusCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A wallet-paid order can only be cancelled before cards are shipped or received");
            }
            wallet.refundOrder(order.id(), "customer", customerId, null, reason);
        } else {
            if (!Set.of("admission_review", "terms_confirmation", "payment_expired", "awaiting_payment", "payment_review")
                .contains(order.statusCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order can no longer be cancelled online");
            }
            requireNoRecordedFundsForCancellation(order.id());
        }
        updateOrderStatus(order.id(), "cancelled", "Order cancelled", reason, true, "customer", customerId, null, true);
        return requireCustomerOrder(customerId, orderNo);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse addInboundShipment(long customerId, String orderNo, CreateShipmentRequest request) {
        lockCustomerOrderForPayment(customerId, orderNo);
        OrderDetailResponse order = requireCustomerOrder(customerId, orderNo);
        assertIndividualShipmentAllowed(order.id());
        if (!Set.of("awaiting_inbound", "inbound_shipped").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Inbound tracking can be added after payment has been confirmed");
        }
        String carrier = requireText(request.carrierName(), "Carrier", 128);
        String tracking = requireText(request.trackingNumber(), "Tracking number", 255);
        Map<String, Object> shipmentValues = new LinkedHashMap<>();
        shipmentValues.put("order_id", order.id());
        shipmentValues.put("direction_code", "inbound");
        shipmentValues.put("shipping_option_code", null);
        shipmentValues.put("shipping_option_name", null);
        shipmentValues.put("carrier_name", carrier);
        shipmentValues.put("tracking_number", tracking);
        shipmentValues.put("status_code", "shipped");
        shipmentValues.put("shipped_by_user_id", null);
        shipmentValues.put("note", blankToNull(clean(request.note(), 1000)));
        shipmentInsert.execute(shipmentValues);
        updateOrderStatus(order.id(), "inbound_shipped", "Cards shipped to NXR", carrier + " tracking: " + tracking, true, "customer", customerId, null, true);
        return requireCustomerOrder(customerId, orderNo);
    }

    public OrderListResponse listAdminOrders(int page, int pageSize, String status, String query) {
        OrderAccessScopeService.AccessScope scope = orderAccessScopeService == null
            ? new OrderAccessScopeService.AccessScope(true, List.of(), List.of())
            : orderAccessScopeService.scopeForUser(SecurityUtils.getUserId());
        return listAdminOrders(page, pageSize, status, query, scope);
    }

    OrderListResponse listAdminOrders(
        int page, int pageSize, String status, String query, OrderAccessScopeService.AccessScope scope
    ) {
        return listOrders(page, pageSize, status, query, null, scope);
    }

    public OrderDetailResponse requireAdminOrder(long orderId) {
        return loadOrderDetailById(orderId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse confirmPayment(long orderId, long paymentId, long adminUserId, ConfirmPaymentRequest request) {
        lockOrderForPayment(orderId);
        assertNoActiveGatewayPayment(orderId);
        OrderDetailResponse order = requireAdminOrder(orderId);
        PaymentRecord payment = requireOrderPayment(order.id(), paymentId);
        if (!"receivable".equals(payment.directionCode()) || !"grading_fee".equals(payment.paymentTypeCode())
            || !Set.of("awaiting_payment", "payment_review", "payment_expired").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an order's initial grading payment can be confirmed here");
        }
        if (!Set.of("pending", "proof_submitted").contains(payment.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment cannot be confirmed in its current state");
        }
        String transactionId = blankToNull(clean(request.providerTransactionId(), 255));
        String note = blankToNull(clean(request.note(), 1000));
        jdbcClient.sql(
                """
                UPDATE payment_record
                SET status_code = 'confirmed', provider_transaction_id = :transactionId,
                    note = :note, confirmed_by_user_id = :adminUserId,
                    confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :paymentId
                """
            )
            .param("transactionId", transactionId)
            .param("note", note)
            .param("adminUserId", adminUserId)
            .param("paymentId", paymentId)
            .update();
        completeConfirmedPaymentOrder(order.id(), "admin", null, adminUserId);
        return requireAdminOrder(orderId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse rejectPayment(long orderId, long paymentId, long adminUserId, RejectPaymentRequest request) {
        lockOrderForPayment(orderId);
        assertNoActiveGatewayPayment(orderId);
        OrderDetailResponse order = requireAdminOrder(orderId);
        PaymentRecord payment = requireOrderPayment(order.id(), paymentId);
        if (!"receivable".equals(payment.directionCode()) || !"grading_fee".equals(payment.paymentTypeCode())
            || !Set.of("awaiting_payment", "payment_review", "payment_expired").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an order's initial grading payment can be rejected here");
        }
        if (!Set.of("pending", "proof_submitted").contains(payment.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment cannot be rejected in its current state");
        }
        String note = requireText(request.note(), "Rejection note", 1000);
        jdbcClient.sql(
                """
                UPDATE payment_record
                SET status_code = 'rejected', note = :note, confirmed_by_user_id = :adminUserId,
                    confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :paymentId
                """
            )
            .param("note", note)
            .param("adminUserId", adminUserId)
            .param("paymentId", paymentId)
            .update();
        String targetStatus = "payment_expired".equals(order.statusCode()) ? "payment_expired" : "awaiting_payment";
        updateOrderStatus(order.id(), targetStatus, "Payment needs attention", note, true, "admin", null, adminUserId, false);
        return requireAdminOrder(orderId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse updateOrderStatusByAdmin(long orderId, long adminUserId, UpdateOrderStatusRequest request) {
        lockOrderForPayment(orderId);
        OrderDetailResponse order = requireAdminOrder(orderId);
        String targetStatus = normalizeStatus(request.statusCode());
        requireFulfillmentService().assertManualStatusTransitionAllowed(orderId, targetStatus);
        if (order.admissionStatus() != null
            && Set.of("awaiting_payment", "payment_review", "awaiting_inbound").contains(targetStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Admission, payment proof and payment confirmation must use their dedicated workflows");
        }
        if (!"cancelled".equals(targetStatus) && orderAdmissionService != null) {
            orderAdmissionService.requireGenericStatusChangeAllowed(order.id());
        }
        String detail = blankToNull(clean(request.detail(), 1000));
        if ("cancelled".equals(targetStatus)) {
            assertNoActiveGatewayPayment(order.id());
        }
        if ("cancelled".equals(targetStatus) && merchantWalletService != null && merchantWalletService.hasPaidWalletOrder(order.id())) {
            if (!"awaiting_inbound".equals(order.statusCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A wallet-paid order can only be refunded before inbound shipment");
            }
            merchantWalletService.refundOrder(order.id(), "admin", null, adminUserId, detail);
        } else if ("cancelled".equals(targetStatus)) {
            requireNoRecordedFundsForCancellation(order.id());
        }
        updateOrderStatus(order.id(), targetStatus, statusTitle(targetStatus), detail, true, "admin", null, adminUserId, true);
        return requireAdminOrder(orderId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse createAdminShipment(long orderId, long adminUserId, CreateShipmentRequest request) {
        lockOrderForPayment(orderId);
        OrderDetailResponse order = requireAdminOrder(orderId);
        assertIndividualShipmentAllowed(order.id());
        String direction = normalizeShipmentDirection(request.direction());
        String carrier = requireText(request.carrierName(), "Carrier", 128);
        String tracking = requireText(request.trackingNumber(), "Tracking number", 255);
        String targetStatus;
        String title;
        String detail;
        OrderFulfillmentService.EffectiveShippingOption outboundOption = null;
        if (direction.equals("inbound")) {
            if (!Set.of("awaiting_inbound", "inbound_shipped").contains(order.statusCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Inbound cards can only be received after payment confirmation");
            }
            targetStatus = "received";
            title = "Cards received";
            detail = "NXR received the inbound shipment from " + carrier + ".";
        } else {
            outboundOption = requireFulfillmentService().assertOutboundReady(order.id());
            targetStatus = "return_shipped";
            title = "Return shipment sent";
            detail = carrier + " tracking: " + tracking;
        }

        String shipmentNote = blankToNull(clean(request.note(), 1000));
        Long matchingInboundShipmentId = direction.equals("inbound")
            ? jdbcClient.sql(
                    """
                    SELECT id FROM order_shipment
                    WHERE order_id = :orderId AND direction_code = 'inbound' AND tracking_number = :tracking
                    ORDER BY id DESC LIMIT 1
                    """
                )
                .param("orderId", order.id())
                .param("tracking", tracking)
                .query(Long.class)
                .optional()
                .orElse(null)
            : null;
        long shipmentId;
        if (matchingInboundShipmentId != null) {
            shipmentId = matchingInboundShipmentId;
            jdbcClient.sql(
                    """
                    UPDATE order_shipment
                    SET carrier_name = :carrier, status_code = 'received', delivered_at = CURRENT_TIMESTAMP,
                        shipped_by_user_id = :adminUserId, note = COALESCE(:note, note), updated_at = CURRENT_TIMESTAMP
                    WHERE id = :shipmentId
                    """
                )
                .param("carrier", carrier)
                .param("adminUserId", adminUserId)
                .param("note", shipmentNote)
                .param("shipmentId", shipmentId)
                .update();
        } else {
            Map<String, Object> shipmentValues = new LinkedHashMap<>();
            shipmentValues.put("order_id", order.id());
            shipmentValues.put("direction_code", direction);
            shipmentValues.put("shipping_option_code", outboundOption == null ? null : outboundOption.optionCode());
            shipmentValues.put("shipping_option_name", outboundOption == null ? null : outboundOption.displayName());
            shipmentValues.put("carrier_name", carrier);
            shipmentValues.put("tracking_number", tracking);
            shipmentValues.put("status_code", direction.equals("inbound") ? "received" : "shipped");
            shipmentValues.put("shipped_by_user_id", adminUserId);
            shipmentValues.put("note", shipmentNote);
            shipmentId = shipmentInsert.executeAndReturnKey(shipmentValues).longValue();
        }
        if (direction.equals("inbound")) {
            jdbcClient.sql("UPDATE grading_order_item SET status_code = 'received', updated_at = CURRENT_TIMESTAMP WHERE order_id = :orderId")
                .param("orderId", order.id())
                .update();
        } else {
            jdbcClient.sql("UPDATE grading_order_item SET status_code = 'return_shipped', updated_at = CURRENT_TIMESTAMP WHERE order_id = :orderId")
                .param("orderId", order.id())
                .update();
            requireFulfillmentService().markShippingLabelCreated(order.id());
        }
        updateOrderStatus(order.id(), targetStatus, title, detail, true, "admin", null, adminUserId, true);
        addTimelineEvent(order.id(), "shipment_created", direction.equals("inbound") ? "Inbound shipment received" : "Shipment recorded", "Shipment #" + shipmentId + " was recorded.", targetStatus, false, "admin", null, adminUserId);
        return requireAdminOrder(orderId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse markShipmentDelivered(long orderId, long shipmentId, long adminUserId) {
        lockOrderForPayment(orderId);
        OrderDetailResponse order = requireAdminOrder(orderId);
        assertIndividualShipmentAllowed(order.id());
        ShipmentRecord shipment = requireOrderShipment(order.id(), shipmentId);
        if (shipment.deliveredAt() != null) {
            return order;
        }
        jdbcClient.sql(
                """
                UPDATE order_shipment
                SET status_code = 'delivered', delivered_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :shipmentId
                """
            )
            .param("shipmentId", shipmentId)
            .update();
        if (shipment.directionCode().equals("outbound") && "return_shipped".equals(order.statusCode())) {
            jdbcClient.sql("UPDATE grading_order_item SET status_code = 'delivered', updated_at = CURRENT_TIMESTAMP WHERE order_id = :orderId")
                .param("orderId", order.id())
                .update();
            updateOrderStatus(order.id(), "delivered", "Return shipment delivered", "Carrier delivery was confirmed.", true, "admin", null, adminUserId, true);
        } else {
            String eventCode = shipment.directionCode().equals("outbound") ? "outbound_delivery_confirmed" : "inbound_delivery_confirmed";
            addTimelineEvent(order.id(), eventCode, "Delivery confirmed", "Carrier delivery was confirmed.", order.statusCode(), true, "admin", null, adminUserId);
        }
        return requireAdminOrder(orderId);
    }

    private void assertIndividualShipmentAllowed(long orderId) {
        String batchNo = jdbcClient.sql(
                """
                SELECT b.batch_no
                FROM merchant_order_batch_item bi
                JOIN merchant_order_batch b ON b.id = bi.batch_id
                WHERE bi.order_id = :orderId AND b.status_code <> 'cancelled'
                LIMIT 1
                """
            )
            .param("orderId", orderId)
            .query(String.class)
            .optional()
            .orElse(null);
        if (batchNo != null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Order belongs to merchant batch " + batchNo + "; use the batch's shared shipment workflow"
            );
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse linkOrderItemSubmission(long orderId, long itemId, long submissionId, long adminUserId) {
        return linkOrderItemSubmission(orderId, itemId, submissionId, adminUserId, false);
    }

    /**
     * Trusted service seam for a submission created for the locked order item in the caller's transaction.
     * The new row must belong to this staff user and already carry the order's exact customer-submission route.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetailResponse linkNewOrderItemSubmission(
        long orderId, long itemId, long submissionId, long adminUserId
    ) {
        return linkOrderItemSubmission(orderId, itemId, submissionId, adminUserId, true);
    }

    private OrderDetailResponse linkOrderItemSubmission(
        long orderId, long itemId, long submissionId, long adminUserId, boolean newlyCreatedForOrder
    ) {
        lockOrderForPayment(orderId);
        OrderDetailResponse order = requireAdminOrder(orderId);
        if (orderAccessScopeService != null && newlyCreatedForOrder) {
            orderAccessScopeService.requireAccessibleOrder(adminUserId, orderId);
        } else if (orderAccessScopeService != null) {
            orderAccessScopeService.requireAccessibleSubmission(adminUserId, submissionId);
        }
        if ("payment_exception".equals(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order work is paused while payment needs attention");
        }
        if (!Set.of("received", "grading", "review", "quality_check", "quality_hold").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A grading submission can only be linked after intake");
        }
        OrderItemLinkRow itemLink = jdbcClient.sql(
                "SELECT grading_submission_id FROM grading_order_item WHERE id = :itemId AND order_id = :orderId FOR UPDATE"
            )
            .param("itemId", itemId)
            .param("orderId", orderId)
            .query((rs, rowNum) -> new OrderItemLinkRow(rs.getObject("grading_submission_id", Long.class)))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order item not found"));
        Long currentSubmissionId = itemLink.submissionId();
        if (currentSubmissionId != null && currentSubmissionId != submissionId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order item is already linked to another grading submission");
        }

        if (commercePolicyService == null) {
            jdbcClient.sql("SELECT id FROM grading_submission WHERE id = :submissionId FOR UPDATE")
                .param("submissionId", submissionId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading submission not found"));
        } else {
            OrderRoutingRow orderRouting = jdbcClient.sql(
                "SELECT business_line_id, work_center_id FROM grading_order WHERE id = :orderId"
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new OrderRoutingRow(
                rs.getObject("business_line_id", Long.class), rs.getObject("work_center_id", Long.class)
            ))
            .single();
            if (orderRouting.businessLineId() == null || orderRouting.workCenterId() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order routing must be assigned before linking grading work");
            }
            SubmissionRoutingRow submission = jdbcClient.sql(
                "SELECT order_origin_code, business_line_id, work_center_id, entry_by_user_id FROM grading_submission WHERE id = :submissionId FOR UPDATE"
            )
            .param("submissionId", submissionId)
            .query((rs, rowNum) -> new SubmissionRoutingRow(
                rs.getString("order_origin_code"), rs.getObject("business_line_id", Long.class),
                rs.getObject("work_center_id", Long.class), rs.getObject("entry_by_user_id", Long.class)
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading submission not found"));
            if (newlyCreatedForOrder && (!"customer_submission".equals(submission.orderOriginCode())
                || submission.entryByUserId() == null || submission.entryByUserId() != adminUserId
                || !orderRouting.businessLineId().equals(submission.businessLineId())
                || !orderRouting.workCenterId().equals(submission.workCenterId()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "New grading submission must be created by this staff user for the order's exact route");
            }
            if ((submission.businessLineId() != null && !submission.businessLineId().equals(orderRouting.businessLineId()))
            || (submission.workCenterId() != null && !submission.workCenterId().equals(orderRouting.workCenterId()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Grading submission routing does not match this order's business line and work center");
            }
            String origin = blankToNull(clean(submission.orderOriginCode(), 32));
            if ("owned_inventory".equals(origin)
            && (submission.businessLineId() == null || submission.workCenterId() == null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Owned-inventory grading work must retain its assigned business line and work center");
            }
            jdbcClient.sql(
                """
                UPDATE grading_submission
                SET order_origin_code = COALESCE(NULLIF(order_origin_code, ''), 'customer_submission'),
                    business_line_id = COALESCE(business_line_id, :lineId),
                    work_center_id = COALESCE(work_center_id, :centerId)
                WHERE id = :submissionId
                """
            )
            .param("lineId", orderRouting.businessLineId())
            .param("centerId", orderRouting.workCenterId())
            .param("submissionId", submissionId)
            .update();
        }
        boolean linkedElsewhere = !jdbcClient.sql(
                "SELECT id FROM grading_order_item WHERE grading_submission_id = :submissionId AND id <> :itemId FOR UPDATE"
            )
            .param("submissionId", submissionId)
            .param("itemId", itemId)
            .query(Long.class)
            .list()
            .isEmpty();
        if (linkedElsewhere) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Grading submission is already linked to another order item");
        }
        int updated;
        try {
            updated = jdbcClient.sql(
                """
                UPDATE grading_order_item
                SET grading_submission_id = :submissionId, status_code = 'grading', updated_at = CURRENT_TIMESTAMP
                WHERE id = :itemId AND order_id = :orderId
                  AND (grading_submission_id IS NULL OR grading_submission_id = :submissionId)
                """
                )
                .param("submissionId", submissionId)
                .param("itemId", itemId)
                .param("orderId", order.id())
                .update();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Grading submission is already linked to another order item", exception);
        }
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order item changed before the grading work was linked");
        }
        if (order.statusCode().equals("received")) {
            updateOrderStatus(order.id(), "grading", "Grading in progress", "A grading work record has been linked to an order item.", true, "admin", null, adminUserId, true);
        } else {
            addTimelineEvent(order.id(), "grading_submission_linked", "Grading work linked", "A grading work record has been linked to an order item.", "grading", true, "admin", null, adminUserId);
        }
        requireFulfillmentService().ensureReviewAndEncapsulationTasks(order.id(), itemId);
        return requireAdminOrder(orderId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentCallbackResponse receivePaymentCallback(String provider, PaymentCallbackRequest request) {
        String normalizedProvider = normalizePaymentProvider(provider);
        String eventId = requireText(request.providerEventId(), "Provider event id", 255);
        String transactionId = requireText(request.providerTransactionId(), "Provider transaction id", 255);
        String paymentNo = clean(request.paymentNo(), 48).toUpperCase(Locale.ROOT);
        long orderId = jdbcClient.sql(
                """
                SELECT order_id FROM payment_record
                WHERE provider_code = :provider
                  AND ((:paymentNo <> '' AND UPPER(payment_no) = :paymentNo)
                       OR (:paymentNo = '' AND provider_transaction_id = :transactionId))
                """
            )
            .param("provider", normalizedProvider)
            .param("paymentNo", paymentNo)
            .param("transactionId", transactionId)
            .query(Long.class)
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment transaction not found"));

        // Keep the same lock order as wallet/manual settlement: order first,
        // then the payment row. Re-read after acquiring both locks so a stale
        // pending snapshot can never overwrite a concurrent confirmation.
        lockOrderForPayment(orderId);
        PaymentRecord payment = jdbcClient.sql(
                """
                SELECT id, order_id, direction_code, payment_type_code, payment_no, provider_code, method_label, status_code,
                       amount, currency_code, payer_reference, proof_reference, payment_url, qr_payload, provider_transaction_id,
                       confirmed_by_user_id, submitted_at, confirmed_at, callback_received_at, note, created_at
                FROM payment_record
                WHERE order_id = :orderId AND provider_code = :provider
                  AND ((:paymentNo <> '' AND UPPER(payment_no) = :paymentNo)
                       OR (:paymentNo = '' AND provider_transaction_id = :transactionId))
                FOR UPDATE
                """
            )
            .param("orderId", orderId)
            .param("provider", normalizedProvider)
            .param("paymentNo", paymentNo)
            .param("transactionId", transactionId)
            .query((rs, rowNum) -> mapPayment(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment transaction not found"));
        BigDecimal callbackAmount;
        try {
            callbackAmount = request.amount() == null ? null : request.amount().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment callback amount has unsupported precision");
        }
        if (callbackAmount == null || payment.amount().compareTo(callbackAmount) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment callback amount does not match");
        }
        if (!payment.currencyCode().equalsIgnoreCase(clean(request.currencyCode(), 8))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment callback currency does not match");
        }
        try {
            jdbcClient.sql(
                    """
                    INSERT INTO payment_callback_event (provider_code, provider_event_id, payment_id, payload)
                    VALUES (:provider, :eventId, :paymentId, :payload)
                    """
                )
                .param("provider", normalizedProvider)
                .param("eventId", eventId)
                .param("paymentId", payment.id())
                .param("payload", clean(request.rawPayload(), 20000))
                .update();
        } catch (DataIntegrityViolationException exception) {
            return new PaymentCallbackResponse(true, payment.orderId(), "Callback was already processed");
        }

        String callbackStatus = clean(request.status(), 32).toLowerCase(Locale.ROOT);
        boolean successful = Set.of("confirmed", "paid", "succeeded").contains(callbackStatus);
        if ("confirmed".equals(payment.statusCode())) {
            if (!successful) {
                return new PaymentCallbackResponse(false, payment.orderId(), "Late non-success callback ignored for confirmed payment");
            }
            if (!transactionId.equals(payment.providerTransactionId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment was already confirmed with another provider transaction");
            }
            return new PaymentCallbackResponse(false, payment.orderId(), "Payment was already confirmed");
        }
        if (successful) {
            jdbcClient.sql(
                    """
                    UPDATE payment_record
                    SET status_code = 'confirmed', callback_received_at = CURRENT_TIMESTAMP,
                        callback_payload = :payload, provider_transaction_id = :transactionId,
                        confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :paymentId AND status_code <> 'confirmed'
                    """
                )
                .param("payload", clean(request.rawPayload(), 20000))
                .param("transactionId", transactionId)
                .param("paymentId", payment.id())
                .update();
            completeConfirmedPaymentOrder(payment.orderId(), "payment_callback", null, null);
        } else {
            jdbcClient.sql(
                    """
                    UPDATE payment_record
                    SET status_code = :status, callback_received_at = CURRENT_TIMESTAMP,
                        callback_payload = :payload, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :paymentId AND status_code <> 'confirmed'
                    """
                )
                .param("status", callbackStatus.isBlank() ? "failed" : callbackStatus)
                .param("payload", clean(request.rawPayload(), 20000))
                .param("paymentId", payment.id())
                .update();
        }
        return new PaymentCallbackResponse(false, payment.orderId(), "Callback recorded");
    }

    private OrderListResponse listOrders(
        int page, int pageSize, String status, String query, Long customerId,
        OrderAccessScopeService.AccessScope accessScope
    ) {
        int resolvedPage = Math.max(1, page);
        int resolvedPageSize = Math.min(Math.max(1, pageSize), 100);
        int offset = (resolvedPage - 1) * resolvedPageSize;
        String normalizedStatus = blankToNull(clean(status, 32));
        String normalizedQuery = blankToNull(clean(query, 128));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", normalizedStatus);
        params.put("query", normalizedQuery == null ? null : "%" + normalizedQuery.toUpperCase(Locale.ROOT) + "%");
        params.put("limit", resolvedPageSize);
        params.put("offset", offset);
        String customerCondition = "";
        if (customerId != null) {
            customerCondition = " AND o.customer_id = :customerId";
            params.put("customerId", customerId);
        }
        String scopeCondition = "";
        if (customerId == null && accessScope != null) {
            scopeCondition = """
                 AND (:scopeAll = 1 OR (
                   o.business_line_id IN (:scopeLineIds)
                   AND o.work_center_id IN (:scopeCenterIds)
                   AND EXISTS (SELECT 1 FROM commerce_business_line bl WHERE bl.id = o.business_line_id AND bl.is_active = 1)
                   AND EXISTS (SELECT 1 FROM commerce_work_center wc WHERE wc.id = o.work_center_id AND wc.is_active = 1)
                 ))
                """;
            params.put("scopeAll", accessScope.unrestricted() ? 1 : 0);
            params.put("scopeLineIds", accessScope.safeBusinessLineIds());
            params.put("scopeCenterIds", accessScope.safeWorkCenterIds());
        }
        String whereClause = """
            WHERE (:status IS NULL OR o.status_code = :status)
              AND (:query IS NULL OR UPPER(o.order_no) LIKE :query OR UPPER(c.email) LIKE :query OR UPPER(c.display_name) LIKE :query)
            """ + customerCondition + scopeCondition;
        Integer total = jdbcClient.sql(
                "SELECT COUNT(*) FROM grading_order o JOIN customer_account c ON c.id = o.customer_id " + whereClause
            )
            .params(params)
            .query(Integer.class)
            .single();
        List<OrderListItem> items = jdbcClient.sql(
                """
                SELECT o.id, o.order_no, o.status_code, %s AS admission_status_code,
                       o.service_level_code,
                       o.return_shipping_option_code, o.return_shipping_option_name, o.total_card_count,
                       o.total_amount, o.currency_code, o.created_at, o.updated_at,
                       c.id AS customer_id, c.email AS customer_email, c.display_name AS customer_display_name
                FROM grading_order o
                JOIN customer_account c ON c.id = o.customer_id
                """.formatted(orderAdmissionService == null ? "NULL" : "o.admission_status_code")
                + whereClause + " ORDER BY o.created_at DESC, o.id DESC LIMIT :limit OFFSET :offset"
            )
            .params(params)
            .query((rs, rowNum) -> new OrderListItem(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("status_code"),
                rs.getString("admission_status_code"),
                rs.getString("service_level_code"),
                rs.getInt("total_card_count"),
                rs.getBigDecimal("total_amount"),
                rs.getString("currency_code"),
                new CustomerReference(rs.getLong("customer_id"), rs.getString("customer_email"), rs.getString("customer_display_name")),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
            ))
            .list();
        return new OrderListResponse(items, resolvedPage, resolvedPageSize, total == null ? 0 : total);
    }

    private Optional<OrderDetailResponse> loadOrderDetailByOrderNo(String orderNo) {
        String normalizedOrderNo = clean(orderNo, 40).toUpperCase(Locale.ROOT);
        return loadOrderDetail("o.order_no = :orderNo", Map.of("orderNo", normalizedOrderNo));
    }

    private Optional<OrderDetailResponse> loadOrderDetailById(long orderId) {
        return loadOrderDetail("o.id = :orderId", Map.of("orderId", orderId));
    }

    private Optional<OrderDetailResponse> loadOrderDetail(String predicate, Map<String, Object> params) {
        return jdbcClient.sql(
                """
                SELECT o.id, o.order_no, o.status_code, %s AS admission_status_code,
                       o.service_level_code, o.total_card_count,
                       o.return_shipping_option_code, o.return_shipping_option_name,
                       o.service_fee, o.return_shipping_fee, o.total_amount, o.currency_code,
                       o.contact_name, o.contact_phone, o.return_address_line1, o.return_address_line2,
                       o.return_city, o.return_region, o.return_postal_code, o.return_country,
                       o.customer_note, o.internal_note, o.intake_code, o.packing_slip_code,
                       o.shipping_label_created_at, o.created_at, o.updated_at,
                       c.id AS customer_id, c.email AS customer_email, c.display_name AS customer_display_name
                FROM grading_order o
                JOIN customer_account c ON c.id = o.customer_id
                """.formatted(orderAdmissionService == null ? "NULL" : "o.admission_status_code")
                + " WHERE " + predicate
            )
            .params(params)
            .query((rs, rowNum) -> new OrderDetailResponse(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("status_code"),
                rs.getString("admission_status_code"),
                rs.getString("service_level_code"),
                rs.getString("return_shipping_option_code"),
                rs.getString("return_shipping_option_name"),
                rs.getInt("total_card_count"),
                rs.getBigDecimal("service_fee"),
                rs.getBigDecimal("return_shipping_fee"),
                rs.getBigDecimal("total_amount"),
                rs.getString("currency_code"),
                rs.getString("contact_name"),
                rs.getString("contact_phone"),
                rs.getString("return_address_line1"),
                rs.getString("return_address_line2"),
                rs.getString("return_city"),
                rs.getString("return_region"),
                rs.getString("return_postal_code"),
                rs.getString("return_country"),
                rs.getString("customer_note"),
                rs.getString("internal_note"),
                rs.getString("intake_code"),
                rs.getString("packing_slip_code"),
                rs.getObject("shipping_label_created_at", LocalDateTime.class),
                new CustomerReference(rs.getLong("customer_id"), rs.getString("customer_email"), rs.getString("customer_display_name")),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                List.of(), List.of(), List.of(), List.of()
            ))
            .optional()
            .map(this::withOrderRelations);
    }

    private OrderDetailResponse withOrderRelations(OrderDetailResponse base) {
        return new OrderDetailResponse(
            base.id(), base.orderNo(), base.statusCode(), base.admissionStatus(), base.serviceLevelCode(),
            base.returnShippingOptionCode(), base.returnShippingOptionName(), base.totalCardCount(),
            base.serviceFee(), base.returnShippingFee(), base.totalAmount(), base.currencyCode(),
            base.contactName(), base.contactPhone(), base.returnAddressLine1(), base.returnAddressLine2(),
            base.returnCity(), base.returnRegion(), base.returnPostalCode(), base.returnCountry(),
            base.customerNote(), base.internalNote(), base.intakeCode(), base.packingSlipCode(), base.shippingLabelCreatedAt(),
            base.customer(), base.createdAt(), base.updatedAt(),
            listOrderItems(base.id()), listPayments(base.id()), listShipments(base.id()), listTimeline(base.id())
        );
    }

    private List<OrderItemResponse> listOrderItems(long orderId) {
        if (orderAdmissionService != null) {
            return jdbcClient.sql(
                    """
                    SELECT i.id, i.item_no, i.card_name, i.brand_name, i.year_label, i.rarity, i.product_type,
                           i.category, i.set_name, i.card_number, i.language_code, i.declared_value, i.item_note,
                           i.front_photo_id, i.back_photo_id, i.status_code, i.grading_submission_id,
                           s.cert_id AS grading_cert_id, s.status_code AS grading_status_code
                    FROM grading_order_item i
                    LEFT JOIN grading_submission s ON s.id = i.grading_submission_id
                    WHERE i.order_id = :orderId
                    ORDER BY i.item_no ASC
                    """
                )
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderItemResponse(
                    rs.getLong("id"), rs.getInt("item_no"), rs.getString("card_name"), rs.getString("brand_name"),
                    rs.getString("year_label"), rs.getString("rarity"), rs.getString("product_type"), rs.getString("category"),
                    rs.getString("set_name"), rs.getString("card_number"), rs.getString("language_code"),
                    rs.getBigDecimal("declared_value"), rs.getString("item_note"),
                    rs.getObject("front_photo_id", Long.class), rs.getObject("back_photo_id", Long.class),
                    rs.getString("status_code"), rs.getObject("grading_submission_id", Long.class),
                    rs.getString("grading_cert_id"), rs.getString("grading_status_code")
                ))
                .list();
        }
        return jdbcClient.sql(
                """
                SELECT i.id, i.item_no, i.card_name, i.brand_name, i.set_name, i.card_number, i.language_code,
                       i.declared_value, i.item_note, i.status_code, i.grading_submission_id,
                       s.cert_id AS grading_cert_id, s.status_code AS grading_status_code
                FROM grading_order_item i
                LEFT JOIN grading_submission s ON s.id = i.grading_submission_id
                WHERE i.order_id = :orderId
                ORDER BY i.item_no ASC
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new OrderItemResponse(
                rs.getLong("id"), rs.getInt("item_no"), rs.getString("card_name"), rs.getString("brand_name"),
                rs.getString("set_name"), rs.getString("card_number"), rs.getString("language_code"),
                rs.getBigDecimal("declared_value"), rs.getString("item_note"), rs.getString("status_code"),
                rs.getObject("grading_submission_id", Long.class), rs.getString("grading_cert_id"), rs.getString("grading_status_code")
            ))
            .list();
    }

    private List<PaymentRecord> listPayments(long orderId) {
        return jdbcClient.sql(
                """
                SELECT id, order_id, direction_code, payment_type_code, payment_no, provider_code, method_label, status_code,
                       amount, currency_code, payer_reference, proof_reference, payment_url, qr_payload, provider_transaction_id,
                       confirmed_by_user_id, submitted_at, confirmed_at, callback_received_at, note, created_at
                FROM payment_record
                WHERE order_id = :orderId
                ORDER BY created_at ASC, id ASC
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapPayment(rs))
            .list();
    }

    private List<ShipmentRecord> listShipments(long orderId) {
        return jdbcClient.sql(
                """
                SELECT id, order_id, direction_code, shipping_option_code, shipping_option_name,
                       carrier_name, tracking_number, status_code,
                       shipped_by_user_id, shipped_at, delivered_at, note
                FROM order_shipment
                WHERE order_id = :orderId
                ORDER BY shipped_at ASC, id ASC
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new ShipmentRecord(
                rs.getLong("id"), rs.getLong("order_id"), rs.getString("direction_code"),
                rs.getString("shipping_option_code"), rs.getString("shipping_option_name"), rs.getString("carrier_name"),
                rs.getString("tracking_number"), rs.getString("status_code"), rs.getObject("shipped_by_user_id", Long.class),
                rs.getObject("shipped_at", LocalDateTime.class), rs.getObject("delivered_at", LocalDateTime.class), rs.getString("note")
            ))
            .list();
    }

    private List<OrderTimelineEvent> listTimeline(long orderId) {
        return jdbcClient.sql(
                """
                SELECT id, event_code, title, detail, status_code, visible_to_customer, actor_type_code, created_at
                FROM order_timeline_event
                WHERE order_id = :orderId AND visible_to_customer = 1
                ORDER BY created_at ASC, id ASC
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new OrderTimelineEvent(
                rs.getLong("id"), rs.getString("event_code"), rs.getString("title"), rs.getString("detail"),
                rs.getString("status_code"), rs.getBoolean("visible_to_customer"), rs.getString("actor_type_code"),
                rs.getObject("created_at", LocalDateTime.class)
            ))
            .list();
    }

    private Optional<PaymentRecord> findReceivablePayment(long orderId) {
        return jdbcClient.sql(
                """
                SELECT id, order_id, direction_code, payment_type_code, payment_no, provider_code, method_label, status_code,
                       amount, currency_code, payer_reference, proof_reference, payment_url, qr_payload, provider_transaction_id,
                       confirmed_by_user_id, submitted_at, confirmed_at, callback_received_at, note, created_at
                FROM payment_record
                WHERE order_id = :orderId AND direction_code = 'receivable'
                ORDER BY id ASC LIMIT 1
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapPayment(rs))
            .optional();
    }

    private PaymentRecord requireOrderPayment(long orderId, long paymentId) {
        return jdbcClient.sql(
                """
                SELECT id, order_id, direction_code, payment_type_code, payment_no, provider_code, method_label, status_code,
                       amount, currency_code, payer_reference, proof_reference, payment_url, qr_payload, provider_transaction_id,
                       confirmed_by_user_id, submitted_at, confirmed_at, callback_received_at, note, created_at
                FROM payment_record WHERE id = :paymentId AND order_id = :orderId
                """
            )
            .param("paymentId", paymentId)
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapPayment(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment record not found"));
    }

    private ShipmentRecord requireOrderShipment(long orderId, long shipmentId) {
        return jdbcClient.sql(
                """
                SELECT id, order_id, direction_code, shipping_option_code, shipping_option_name,
                       carrier_name, tracking_number, status_code,
                       shipped_by_user_id, shipped_at, delivered_at, note
                FROM order_shipment WHERE id = :shipmentId AND order_id = :orderId
                """
            )
            .param("shipmentId", shipmentId)
            .param("orderId", orderId)
            .query((rs, rowNum) -> new ShipmentRecord(
                rs.getLong("id"), rs.getLong("order_id"), rs.getString("direction_code"),
                rs.getString("shipping_option_code"), rs.getString("shipping_option_name"), rs.getString("carrier_name"),
                rs.getString("tracking_number"), rs.getString("status_code"), rs.getObject("shipped_by_user_id", Long.class),
                rs.getObject("shipped_at", LocalDateTime.class), rs.getObject("delivered_at", LocalDateTime.class), rs.getString("note")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment record not found"));
    }

    private static PaymentRecord mapPayment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentRecord(
            rs.getLong("id"), rs.getLong("order_id"), rs.getString("direction_code"), rs.getString("payment_type_code"),
            rs.getString("payment_no"), rs.getString("provider_code"), rs.getString("method_label"), rs.getString("status_code"), rs.getBigDecimal("amount"),
            rs.getString("currency_code"), rs.getString("payer_reference"), rs.getString("proof_reference"), rs.getString("payment_url"), rs.getString("qr_payload"),
            rs.getString("provider_transaction_id"), rs.getObject("confirmed_by_user_id", Long.class),
            rs.getObject("submitted_at", LocalDateTime.class), rs.getObject("confirmed_at", LocalDateTime.class),
            rs.getObject("callback_received_at", LocalDateTime.class), rs.getString("note"), rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private Optional<OwnershipRecord> findActiveOwnership(String certId) {
        return jdbcClient.sql(
                """
                SELECT o.id, o.cert_id, o.customer_id, o.visibility_code, o.note, o.bound_at,
                       c.display_name, c.email
                FROM certificate_ownership o
                JOIN customer_account c ON c.id = o.customer_id
                WHERE o.active_cert_id = :certId AND o.ownership_status_code = 'active'
                LIMIT 1
                """
            )
            .param("certId", certId)
            .query((rs, rowNum) -> new OwnershipRecord(
                rs.getLong("id"), rs.getString("cert_id"), rs.getLong("customer_id"), rs.getString("visibility_code"),
                rs.getString("note"), rs.getObject("bound_at", LocalDateTime.class), rs.getString("display_name"), rs.getString("email")
            ))
            .optional();
    }

    private OwnershipSummary toOwnershipSummary(OwnershipRecord record) {
        return new OwnershipSummary(
            record.certId(), record.customerId(), collectorLabel(record.displayName(), record.visibilityCode()),
            record.visibilityCode(), record.note(), record.boundAt()
        );
    }

    private List<OwnershipEvent> listOwnershipEvents(String certId) {
        return jdbcClient.sql(
                """
                SELECT e.id, e.event_type_code, e.visibility_code, e.message, e.created_at,
                       from_customer.display_name AS from_display_name,
                       to_customer.display_name AS to_display_name
                FROM certificate_ownership_event e
                LEFT JOIN customer_account from_customer ON from_customer.id = e.from_customer_id
                LEFT JOIN customer_account to_customer ON to_customer.id = e.to_customer_id
                WHERE e.cert_id = :certId
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT 30
                """
            )
            .param("certId", certId)
            .query((rs, rowNum) -> new OwnershipEvent(
                rs.getLong("id"), rs.getString("event_type_code"), rs.getString("visibility_code"),
                collectorLabel(rs.getString("from_display_name"), rs.getString("visibility_code")),
                collectorLabel(rs.getString("to_display_name"), rs.getString("visibility_code")),
                rs.getString("message"), rs.getObject("created_at", LocalDateTime.class)
            ))
            .list();
    }

    private void addOwnershipEvent(
        String certId,
        Long fromCustomerId,
        Long toCustomerId,
        String eventType,
        String visibility,
        String message
    ) {
        jdbcClient.sql(
                """
                INSERT INTO certificate_ownership_event
                    (cert_id, from_customer_id, to_customer_id, event_type_code, visibility_code, message)
                VALUES (:certId, :fromCustomerId, :toCustomerId, :eventType, :visibility, :message)
                """
            )
            .param("certId", certId)
            .param("fromCustomerId", fromCustomerId)
            .param("toCustomerId", toCustomerId)
            .param("eventType", eventType)
            .param("visibility", visibility)
            .param("message", message)
            .update();
    }

    private void updateOrderStatus(
        long orderId,
        String targetStatus,
        String title,
        String detail,
        boolean visibleToCustomer,
        String actorType,
        Long customerId,
        Long adminUserId,
        boolean enforceTransition
    ) {
        OrderDetailResponse order = requireAdminOrder(orderId);
        if (order.statusCode().equals(targetStatus)) {
            addTimelineEvent(orderId, "status_note", title, detail, targetStatus, visibleToCustomer, actorType, customerId, adminUserId);
            return;
        }
        if (enforceTransition && !ALLOWED_STATUS_TRANSITIONS.getOrDefault(order.statusCode(), Set.of()).contains(targetStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The order cannot move from " + order.statusCode() + " to " + targetStatus);
        }
        jdbcClient.sql(
                "UPDATE grading_order SET status_code = :targetStatus, updated_at = CURRENT_TIMESTAMP WHERE id = :orderId"
            )
            .param("targetStatus", targetStatus)
            .param("orderId", orderId)
            .update();
        addTimelineEvent(orderId, "status_changed", title, detail, targetStatus, visibleToCustomer, actorType, customerId, adminUserId);
        if (visibleToCustomer) {
            String notificationStatus = notificationStatus(targetStatus);
            if (notificationStatus != null) {
                enqueueOrderNotification(order.customer().id(), order.orderNo(), notificationStatus,
                    detail == null || detail.isBlank() ? title : detail);
            }
        }
    }

    private void enqueueOrderNotification(long customerId, String orderNo, String statusCode, String message) {
        if (notificationOutboxService != null) {
            notificationOutboxService.enqueueOrderStatus(customerId, orderNo, statusCode, message);
        }
    }

    private static String notificationStatus(String orderStatus) {
        return switch (orderStatus) {
            case "awaiting_inbound" -> "paid";
            case "received" -> "received";
            case "grading" -> "grading";
            case "review" -> "review";
            case "return_shipped" -> "return_shipped";
            case "delivered" -> "delivered";
            default -> null;
        };
    }

    private void addTimelineEvent(
        long orderId,
        String eventCode,
        String title,
        String detail,
        String statusCode,
        boolean visibleToCustomer,
        String actorType,
        Long customerId,
        Long adminUserId
    ) {
        jdbcClient.sql(
                """
                INSERT INTO order_timeline_event
                    (order_id, event_code, title, detail, status_code, visible_to_customer, actor_type_code, actor_customer_id, actor_admin_user_id)
                VALUES (:orderId, :eventCode, :title, :detail, :statusCode, :visibleToCustomer, :actorType, :customerId, :adminUserId)
                """
            )
            .param("orderId", orderId)
            .param("eventCode", eventCode)
            .param("title", title)
            .param("detail", detail)
            .param("statusCode", statusCode)
            .param("visibleToCustomer", visibleToCustomer ? 1 : 0)
            .param("actorType", actorType)
            .param("customerId", customerId)
            .param("adminUserId", adminUserId)
            .update();
    }

    private String requirePublishedCertificate(String value) {
        String certId = normalizeCertificateId(value);
        Integer count = jdbcClient.sql("SELECT COUNT(*) FROM published_certificate WHERE UPPER(cert_id) = :certId")
            .param("certId", certId)
            .query(Integer.class)
            .single();
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found");
        }
        return certId;
    }

    private String generateOrderNumber() {
        String prefix = "NXR" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        for (int attempt = 0; attempt < 10; attempt += 1) {
            String candidate = prefix + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
            Integer count = jdbcClient.sql("SELECT COUNT(*) FROM grading_order WHERE order_no = :orderNo")
                .param("orderNo", candidate)
                .query(Integer.class)
                .single();
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to allocate an order number");
    }

    private static String normalizeCertificateId(String value) {
        String certId = clean(value, 32).toUpperCase(Locale.ROOT);
        if (certId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Certificate id is required");
        }
        return certId;
    }

    private static String normalizeVisibility(String value) {
        String visibility = clean(value, 32).toLowerCase(Locale.ROOT);
        return OWNERSHIP_VISIBILITIES.contains(visibility) ? visibility : "public";
    }

    private static List<OrderItemRequest> resolveOrderItems(CreateOrderRequest request, int maxCards) {
        List<LanguageGroupRequest> groups = request.languageGroups() == null ? List.of() : request.languageGroups();
        if (!groups.isEmpty()) {
            List<OrderItemRequest> items = new ArrayList<>();
            for (LanguageGroupRequest group : groups) {
                String languageCode = requireText(group.languageCode(), "Card language", 32).toUpperCase(Locale.ROOT);
                int quantity = group.quantity() == null ? 0 : group.quantity();
                if (quantity < 1 || quantity > maxCards) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Each language quantity must be between 1 and " + maxCards);
                }
                for (int index = 0; index < quantity; index += 1) {
                    if (items.size() >= maxCards) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "An order cannot include more than " + maxCards + " cards");
                    }
                    items.add(new OrderItemRequest(
                        languageCode + " card " + (index + 1), null, null, null, languageCode, null, null
                    ));
                }
            }
            return items;
        }
        return request.items() == null ? List.of() : request.items();
    }

    private static OrderFulfillmentService.ShippingOption selectShippingOption(
        OrderFulfillmentService fulfillment,
        String requestedCode,
        String country
    ) {
        if (requestedCode != null && !requestedCode.isBlank()) {
            return fulfillment.requireShippingOption(requestedCode, country);
        }
        return fulfillment.listShippingOptions(country, false).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No return shipping option is available for this address"));
    }

    private OrderFulfillmentService requireFulfillmentService() {
        if (orderFulfillmentService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order fulfillment service is unavailable");
        }
        return orderFulfillmentService;
    }

    private MerchantWalletService requireMerchantWalletService() {
        if (merchantWalletService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Merchant wallet service is unavailable");
        }
        return merchantWalletService;
    }

    private void requireAdmissionPaymentAllowed(long orderId, long customerId) {
        if (orderAdmissionService != null) {
            orderAdmissionService.requirePaymentAllowed(orderId, customerId);
        }
    }

    private void completeConfirmedPaymentOrder(long orderId, String actorType, Long customerId, Long adminUserId) {
        String currentStatus = currentLockedOrderStatus(orderId);
        boolean validOperationalState = Set.of("awaiting_payment", "payment_review").contains(currentStatus);
        if (!validOperationalState
            || (orderAdmissionService != null && orderAdmissionService.verifiedPaymentRequiresReview(orderId))) {
            jdbcClient.sql("UPDATE grading_order SET status_code = 'payment_exception', updated_at = CURRENT_TIMESTAMP WHERE id = :orderId")
                .param("orderId", orderId).update();
            addTimelineEvent(orderId, "late_payment_received", "Payment received after admission window",
                "The verified funds were recorded, but fulfillment is paused for financial review.", "payment_exception",
                true, actorType, customerId, adminUserId);
            return;
        }
        updateOrderStatus(orderId, "awaiting_inbound", "Payment confirmed",
            "Payment has been confirmed. Please send your cards to NXR and add inbound tracking.",
            true, actorType, customerId, adminUserId, false);
        requireFulfillmentService().ensureIntakeCodes(orderId);
    }

    private void validateOrderPhotos(long customerId, List<OrderItemRequest> items) {
        if (customerOrderPhotoService == null) return;
        for (OrderItemRequest item : items) {
            customerOrderPhotoService.requireOwnedPhoto(customerId, item.frontPhotoId());
            customerOrderPhotoService.requireOwnedPhoto(customerId, item.backPhotoId());
        }
    }

    private void attachOrderPhotos(long customerId, long orderId, List<OrderItemRequest> items) {
        if (customerOrderPhotoService == null) return;
        List<Long> ids = items.stream()
            .flatMap(item -> java.util.stream.Stream.of(item.frontPhotoId(), item.backPhotoId()))
            .filter(java.util.Objects::nonNull)
            .toList();
        customerOrderPhotoService.attachToOrder(customerId, orderId, ids);
    }

    private static void validateBatchAllocation(
        long customerId,
        int cardCount,
        String country,
        String requestedCurrency,
        String requestedShippingOptionCode,
        CommercePolicyService.QuoteResult aggregate,
        CommercePolicyService.AllocatedBatchQuote allocation
    ) {
        if (aggregate.customerId() != customerId || allocation.cardCount() != cardCount
            || !clean(country, 128).equalsIgnoreCase(aggregate.destinationCountry())
            || !clean(requestedCurrency, 8).equalsIgnoreCase(aggregate.currencyCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The batch allocation does not match this order");
        }
        if (requestedShippingOptionCode != null && !requestedShippingOptionCode.isBlank()
            && !clean(requestedShippingOptionCode, 64).equalsIgnoreCase(aggregate.shippingOptionCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The batch shipping option changed");
        }
        BigDecimal serviceFee = requireBatchAmount(allocation.serviceFee(), false, "Batch grading fee");
        BigDecimal shippingFee = requireBatchAmount(allocation.returnShippingFee(), true, "Batch return shipping fee");
        BigDecimal total = requireBatchAmount(allocation.totalAmount(), false, "Batch total");
        BigDecimal expectedServiceFee = aggregate.unitPrice().multiply(BigDecimal.valueOf(cardCount));
        if (serviceFee.compareTo(expectedServiceFee) != 0
            || serviceFee.add(shippingFee).compareTo(total) != 0
            || shippingFee.compareTo(aggregate.returnShippingFee()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The batch allocation amounts are inconsistent");
        }
    }

    private static BigDecimal requireBatchAmount(BigDecimal value, boolean allowZero, String label) {
        if (value == null || value.signum() < 0 || (!allowZero && value.signum() == 0) || value.scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is invalid");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static void validatePresentedQuote(CreateOrderRequest request, BigDecimal calculatedTotal, String calculatedCurrency) {
        if (request.quotedTotalAmount() == null && (request.quotedCurrencyCode() == null || request.quotedCurrencyCode().isBlank())) return;
        if (request.quotedTotalAmount() == null || request.quotedCurrencyCode() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both quoted total and currency are required");
        }
        BigDecimal presented;
        try {
            presented = request.quotedTotalAmount().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quoted total has unsupported precision");
        }
        if (presented.compareTo(calculatedTotal) != 0
            || !clean(request.quotedCurrencyCode(), 8).equalsIgnoreCase(calculatedCurrency)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The quote changed; refresh the order quote before submitting");
        }
    }

    private void lockOrderForPayment(long orderId) {
        jdbcClient.sql("SELECT id FROM grading_order WHERE id = :orderId FOR UPDATE")
            .param("orderId", orderId)
            .query(Long.class)
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    private long lockCustomerOrderForPayment(long customerId, String orderNo) {
        return jdbcClient.sql(
                "SELECT id FROM grading_order WHERE customer_id = :customerId AND order_no = :orderNo FOR UPDATE"
            )
            .param("customerId", customerId)
            .param("orderNo", requireText(orderNo, "Order number", 48).toUpperCase(Locale.ROOT))
            .query(Long.class)
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    private String currentLockedOrderStatus(long orderId) {
        return jdbcClient.sql("SELECT status_code FROM grading_order WHERE id = :orderId FOR UPDATE")
            .param("orderId", orderId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    private void assertNoActiveGatewayPayment(long orderId) {
        boolean active = !jdbcClient.sql(
                """
                SELECT id FROM payment_attempt
                WHERE order_id = :orderId
                  AND status_code IN ('creating', 'created', 'approved', 'pending', 'payer_action_required', 'creation_unknown', 'capture_unknown', 'capturing')
                FOR UPDATE
                """
            )
            .param("orderId", orderId)
            .query(Long.class)
            .list()
            .isEmpty();
        if (active) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An online payment attempt is still active for this order");
        }
    }

    private void requireNoRecordedFundsForCancellation(long orderId) {
        boolean fundsRecorded = jdbcClient.sql(
                """
                SELECT status_code FROM payment_record
                WHERE order_id = :orderId AND direction_code = 'receivable' AND payment_type_code = 'grading_fee'
                FOR UPDATE
                """
            )
            .param("orderId", orderId)
            .query(String.class)
            .list()
            .stream()
            .anyMatch(status -> Set.of("proof_submitted", "confirmed", "refunded", "reversed").contains(status));
        if (fundsRecorded) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This order has recorded payment activity and requires a dedicated financial review before cancellation");
        }
    }

    private static String normalizePaymentProvider(String value) {
        String provider = clean(value, 32).toLowerCase(Locale.ROOT);
        if (!PAYMENT_PROVIDERS.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment provider");
        }
        return provider;
    }

    private static String normalizeShipmentDirection(String value) {
        String direction = clean(value, 16).toLowerCase(Locale.ROOT);
        if (!SHIPMENT_DIRECTIONS.contains(direction)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shipment direction must be inbound or outbound");
        }
        return direction;
    }

    private static String normalizeStatus(String value) {
        String status = clean(value, 32).toLowerCase(Locale.ROOT);
        if (!ALLOWED_STATUS_TRANSITIONS.containsKey(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported order status");
        }
        return status;
    }

    private static BigDecimal normalizeDeclaredValue(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0 || value.compareTo(new BigDecimal("1000000")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Declared value must be between 0 and 1000000");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String requireText(String value, String label, int maxLength) {
        String text = clean(value, maxLength);
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        }
        return text;
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String text = value.trim().replaceAll("\\s+", " ");
        return text.substring(0, Math.min(text.length(), maxLength));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String collectorLabel(String displayName, String visibility) {
        if ("private".equals(visibility)) {
            return "Not publicly shown";
        }
        if ("anonymous".equals(visibility)) {
            return "Private collector";
        }
        return displayName == null || displayName.isBlank() ? "Collector" : displayName;
    }

    private static String statusTitle(String status) {
        return switch (status) {
            case "admission_review" -> "Application under review";
            case "terms_confirmation" -> "Confirm quote and terms";
            case "payment_expired" -> "Payment deadline expired";
            case "awaiting_payment" -> "Awaiting payment";
            case "payment_review" -> "Payment under review";
            case "awaiting_inbound" -> "Awaiting inbound shipment";
            case "inbound_shipped" -> "Cards shipped to NXR";
            case "received" -> "Cards received";
            case "intake_exception" -> "Warehouse intake needs review";
            case "grading" -> "Grading in progress";
            case "review" -> "Final review";
            case "quality_check" -> "Final quality check";
            case "quality_hold" -> "Quality rework required";
            case "completed" -> "Grading completed";
            case "return_shipped" -> "Return shipment sent";
            case "delivered" -> "Order delivered";
            case "cancelled" -> "Order cancelled";
            default -> "Order updated";
        };
    }

    public record ClaimCardRequest(String visibility, String note) {
    }

    public record TransferCardRequest(String recipientEmail, String visibility, String message) {
    }

    public record CardCommunityResponse(
        OwnershipSummary ownership,
        List<OwnershipEvent> timeline
    ) {
    }

    public record OwnershipSummary(
        String certId,
        long customerId,
        String ownerLabel,
        String visibilityCode,
        String note,
        LocalDateTime boundAt
    ) {
    }

    public record OwnershipEvent(
        long id,
        String eventTypeCode,
        String visibilityCode,
        String fromLabel,
        String toLabel,
        String message,
        LocalDateTime createdAt
    ) {
    }

    public record CustomerCardResponse(
        String certId,
        String productType,
        String vintageClassification,
        String merchDescription,
        String cardName,
        String brandName,
        String yearLabel,
        String setName,
        String cardNumber,
        BigDecimal finalGradeValue,
        String finalGradeLabel,
        String frontImageUrl,
        String visibilityCode,
        String note,
        LocalDateTime boundAt
    ) {
    }

    public record CreateOrderRequest(
        String serviceLevel,
        Long returnAddressId,
        Boolean saveReturnAddress,
        String returnShippingOptionCode,
        String contactName,
        String contactPhone,
        String returnAddressLine1,
        String returnAddressLine2,
        String returnCity,
        String returnRegion,
        String returnPostalCode,
        String returnCountry,
        String customerNote,
        List<LanguageGroupRequest> languageGroups,
        List<OrderItemRequest> items,
        String currencyCode,
        BigDecimal quotedTotalAmount,
        String quotedCurrencyCode
    ) {
        public CreateOrderRequest(
            String serviceLevel, Long returnAddressId, Boolean saveReturnAddress, String returnShippingOptionCode,
            String contactName, String contactPhone, String returnAddressLine1, String returnAddressLine2,
            String returnCity, String returnRegion, String returnPostalCode, String returnCountry, String customerNote,
            List<LanguageGroupRequest> languageGroups, List<OrderItemRequest> items
        ) {
            this(serviceLevel, returnAddressId, saveReturnAddress, returnShippingOptionCode, contactName, contactPhone,
                returnAddressLine1, returnAddressLine2, returnCity, returnRegion, returnPostalCode, returnCountry,
                customerNote, languageGroups, items, "USD", null, null);
        }

        public CreateOrderRequest(
            String serviceLevel, Long returnAddressId, Boolean saveReturnAddress, String returnShippingOptionCode,
            String contactName, String contactPhone, String returnAddressLine1, String returnAddressLine2,
            String returnCity, String returnRegion, String returnPostalCode, String returnCountry, String customerNote,
            List<LanguageGroupRequest> languageGroups, List<OrderItemRequest> items, String currencyCode
        ) {
            this(serviceLevel, returnAddressId, saveReturnAddress, returnShippingOptionCode, contactName, contactPhone,
                returnAddressLine1, returnAddressLine2, returnCity, returnRegion, returnPostalCode, returnCountry,
                customerNote, languageGroups, items, currencyCode, null, null);
        }
    }

    public record LanguageGroupRequest(String languageCode, Integer quantity) {
    }

    public record OrderItemRequest(
        String cardName,
        String brandName,
        String year,
        String rarity,
        String productType,
        String category,
        String setName,
        String cardNumber,
        String languageCode,
        BigDecimal declaredValue,
        String itemNote,
        Long frontPhotoId,
        Long backPhotoId
    ) {
        public OrderItemRequest(
            String cardName, String brandName, String setName, String cardNumber,
            String languageCode, BigDecimal declaredValue, String itemNote
        ) {
            this(cardName, brandName, null, null, null, null, setName, cardNumber,
                languageCode, declaredValue, itemNote, null, null);
        }
    }

    public record SubmitPaymentProofRequest(String provider, String payerReference, String proofReference) {
    }

    public record PaymentSessionRequest(String provider) {
    }

    public record WalletPaymentRequest(String idempotencyKey) {
    }

    public record CancelOrderRequest(String reason) {
    }

    public record PaymentSessionResponse(
        long paymentId,
        String paymentNo,
        String provider,
        String paymentUrl,
        String qrPayload,
        BigDecimal amount,
        String currencyCode
    ) {
    }

    public record ConfirmPaymentRequest(String providerTransactionId, String note) {
    }

    public record RejectPaymentRequest(String note) {
    }

    public record UpdateOrderStatusRequest(String statusCode, String detail) {
    }

    public record CreateShipmentRequest(String direction, String carrierName, String trackingNumber, String note) {
    }

    public record PaymentCallbackRequest(
        String providerEventId,
        String paymentNo,
        String providerTransactionId,
        BigDecimal amount,
        String currencyCode,
        String status,
        String rawPayload
    ) {
    }

    public record PaymentCallbackResponse(boolean duplicate, long orderId, String message) {
    }

    public record OrderListResponse(List<OrderListItem> items, int page, int pageSize, int total) {
    }

    public record OrderListItem(
        long id,
        String orderNo,
        String statusCode,
        String admissionStatus,
        String serviceLevelCode,
        int totalCardCount,
        BigDecimal totalAmount,
        String currencyCode,
        CustomerReference customer,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }

    public record OrderDetailResponse(
        long id,
        String orderNo,
        String statusCode,
        String admissionStatus,
        String serviceLevelCode,
        String returnShippingOptionCode,
        String returnShippingOptionName,
        int totalCardCount,
        BigDecimal serviceFee,
        BigDecimal returnShippingFee,
        BigDecimal totalAmount,
        String currencyCode,
        String contactName,
        String contactPhone,
        String returnAddressLine1,
        String returnAddressLine2,
        String returnCity,
        String returnRegion,
        String returnPostalCode,
        String returnCountry,
        String customerNote,
        String internalNote,
        String intakeCode,
        String packingSlipCode,
        LocalDateTime shippingLabelCreatedAt,
        CustomerReference customer,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<OrderItemResponse> items,
        List<PaymentRecord> payments,
        List<ShipmentRecord> shipments,
        List<OrderTimelineEvent> timeline
    ) {
    }

    public record CustomerReference(long id, String email, String displayName) {
    }

    public record OrderItemResponse(
        long id,
        int itemNo,
        String cardName,
        String brandName,
        String year,
        String rarity,
        String productType,
        String category,
        String setName,
        String cardNumber,
        String languageCode,
        BigDecimal declaredValue,
        String itemNote,
        Long frontPhotoId,
        Long backPhotoId,
        String statusCode,
        Long gradingSubmissionId,
        String gradingCertId,
        String gradingStatusCode
    ) {
        public OrderItemResponse(
            long id, int itemNo, String cardName, String brandName, String setName, String cardNumber,
            String languageCode, BigDecimal declaredValue, String itemNote, String statusCode,
            Long gradingSubmissionId, String gradingCertId, String gradingStatusCode
        ) {
            this(id, itemNo, cardName, brandName, null, null, null, null, setName, cardNumber, languageCode,
                declaredValue, itemNote, null, null, statusCode, gradingSubmissionId, gradingCertId, gradingStatusCode);
        }
    }

    public record PaymentRecord(
        long id,
        long orderId,
        String directionCode,
        String paymentTypeCode,
        String paymentNo,
        String providerCode,
        String methodLabel,
        String statusCode,
        BigDecimal amount,
        String currencyCode,
        String payerReference,
        String proofReference,
        String paymentUrl,
        String qrPayload,
        String providerTransactionId,
        Long confirmedByUserId,
        LocalDateTime submittedAt,
        LocalDateTime confirmedAt,
        LocalDateTime callbackReceivedAt,
        String note,
        LocalDateTime createdAt
    ) {
    }

    public record ShipmentRecord(
        long id,
        long orderId,
        String directionCode,
        String shippingOptionCode,
        String shippingOptionName,
        String carrierName,
        String trackingNumber,
        String statusCode,
        Long shippedByUserId,
        LocalDateTime shippedAt,
        LocalDateTime deliveredAt,
        String note
    ) {
    }

    public record OrderTimelineEvent(
        long id,
        String eventCode,
        String title,
        String detail,
        String statusCode,
        boolean visibleToCustomer,
        String actorTypeCode,
        LocalDateTime createdAt
    ) {
    }

    private record OwnershipRecord(
        long id,
        String certId,
        long customerId,
        String visibilityCode,
        String note,
        LocalDateTime boundAt,
        String displayName,
        String email
    ) {
    }

    private record OrderItemLinkRow(Long submissionId) {
    }

    private record OrderRoutingRow(Long businessLineId, Long workCenterId) {
    }

    private record SubmissionRoutingRow(
        String orderOriginCode, Long businessLineId, Long workCenterId, Long entryByUserId
    ) {
    }
}
