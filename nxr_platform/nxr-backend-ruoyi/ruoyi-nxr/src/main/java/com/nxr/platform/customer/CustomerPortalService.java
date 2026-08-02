package com.nxr.platform.customer;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Customer card collection and end-to-end grading order workflow. */
@Service
public class CustomerPortalService {

    private static final Set<String> OWNERSHIP_VISIBILITIES = Set.of("public", "anonymous", "private");
    private static final Set<String> PAYMENT_PROVIDERS = Set.of("manual_transfer", "bank_transfer", "wechat_transfer", "alipay_transfer", "stripe");
    private static final Set<String> SHIPMENT_DIRECTIONS = Set.of("inbound", "outbound");
    private static final Map<String, BigDecimal> SERVICE_PRICES = Map.of(
        "standard", new BigDecimal("20.00"),
        "express", new BigDecimal("35.00"),
        "premium", new BigDecimal("50.00")
    );
    private static final Map<String, Set<String>> ALLOWED_STATUS_TRANSITIONS = Map.ofEntries(
        Map.entry("awaiting_payment", Set.of("payment_review", "cancelled")),
        Map.entry("payment_review", Set.of("awaiting_inbound", "awaiting_payment", "cancelled")),
        Map.entry("awaiting_inbound", Set.of("inbound_shipped", "received", "cancelled")),
        Map.entry("inbound_shipped", Set.of("received", "cancelled")),
        Map.entry("received", Set.of("grading", "review", "cancelled")),
        Map.entry("grading", Set.of("review", "cancelled")),
        Map.entry("review", Set.of("completed", "cancelled")),
        Map.entry("completed", Set.of("return_shipped")),
        Map.entry("return_shipped", Set.of("delivered")),
        Map.entry("delivered", Set.of())
    );

    private final JdbcClient jdbcClient;
    private final SimpleJdbcInsert ownershipInsert;
    private final SimpleJdbcInsert orderInsert;
    private final SimpleJdbcInsert orderItemInsert;
    private final SimpleJdbcInsert paymentInsert;
    private final SimpleJdbcInsert shipmentInsert;

    public CustomerPortalService(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.ownershipInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("certificate_ownership")
            .usingColumns("cert_id", "active_cert_id", "customer_id", "ownership_status_code", "visibility_code", "note")
            .usingGeneratedKeyColumns("id");
        this.orderInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("grading_order")
            .usingColumns(
                "order_no", "customer_id", "status_code", "service_level_code", "total_card_count",
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
            .usingColumns("order_id", "direction_code", "payment_type_code", "provider_code", "status_code", "amount", "currency_code")
            .usingGeneratedKeyColumns("id");
        this.shipmentInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("order_shipment")
            .usingColumns("order_id", "direction_code", "carrier_name", "tracking_number", "status_code", "shipped_by_user_id", "note")
            .usingGeneratedKeyColumns("id");
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
                       s.card_name, s.brand_name, s.year_label, s.set_name, s.card_number,
                       gs.final_grade_value, gs.final_grade_label,
                       front_media.public_url AS front_image_url
                FROM certificate_ownership o
                JOIN published_certificate pc ON UPPER(pc.cert_id) = UPPER(o.cert_id)
                JOIN grading_submission s ON s.id = pc.submission_id
                JOIN grading_score gs ON gs.submission_id = s.id
                LEFT JOIN submission_media front_media ON front_media.id = pc.published_front_media_id
                WHERE o.customer_id = :customerId
                  AND o.ownership_status_code = 'active'
                ORDER BY o.bound_at DESC, o.id DESC
                """
            )
            .param("customerId", customerId)
            .query((rs, rowNum) -> new CustomerCardResponse(
                rs.getString("cert_id"),
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
        List<OrderItemRequest> requestedItems = request.items() == null ? List.of() : request.items();
        if (requestedItems.isEmpty() || requestedItems.size() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An order must include between 1 and 30 cards");
        }
        String serviceLevel = normalizeServiceLevel(request.serviceLevel());
        String contactName = requireText(request.contactName(), "Contact name", 128);
        String contactPhone = requireText(request.contactPhone(), "Contact phone", 64);
        String addressLine1 = requireText(request.returnAddressLine1(), "Return address", 255);
        String city = requireText(request.returnCity(), "Return city", 128);
        String postalCode = requireText(request.returnPostalCode(), "Return postal code", 64);
        String country = requireText(request.returnCountry(), "Return country", 128);
        BigDecimal serviceFee = SERVICE_PRICES.get(serviceLevel).multiply(BigDecimal.valueOf(requestedItems.size()));
        BigDecimal returnShippingFee = new BigDecimal("12.00");
        BigDecimal totalAmount = serviceFee.add(returnShippingFee).setScale(2, RoundingMode.HALF_UP);
        String orderNo = generateOrderNumber();

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_no", orderNo);
        values.put("customer_id", customerId);
        values.put("status_code", "awaiting_payment");
        values.put("service_level_code", serviceLevel);
        values.put("total_card_count", requestedItems.size());
        values.put("service_fee", serviceFee);
        values.put("return_shipping_fee", returnShippingFee);
        values.put("total_amount", totalAmount);
        values.put("currency_code", "USD");
        values.put("contact_name", contactName);
        values.put("contact_phone", contactPhone);
        values.put("return_address_line1", addressLine1);
        values.put("return_address_line2", blankToNull(clean(request.returnAddressLine2(), 255)));
        values.put("return_city", city);
        values.put("return_region", blankToNull(clean(request.returnRegion(), 128)));
        values.put("return_postal_code", postalCode);
        values.put("return_country", country);
        values.put("customer_note", blankToNull(clean(request.customerNote(), 2000)));
        long orderId;
        try {
            orderId = orderInsert.executeAndReturnKey(values).longValue();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Please submit the order again", exception);
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
            orderItemInsert.execute(itemValues);
        }

        paymentInsert.execute(Map.of(
            "order_id", orderId,
            "direction_code", "receivable",
            "payment_type_code", "grading_fee",
            "provider_code", "manual_transfer",
            "status_code", "pending",
            "amount", totalAmount,
            "currency_code", "USD"
        ));
        addTimelineEvent(orderId, "order_created", "Order created", "Your grading order is ready for payment.", "awaiting_payment", true, "customer", customerId, null);
        addTimelineEvent(orderId, "payment_pending", "Awaiting payment", "Submit a transfer reference after payment so our team can confirm it.", "awaiting_payment", true, "system", null, null);
        return requireCustomerOrder(customerId, orderNo);
    }

    public OrderListResponse listCustomerOrders(long customerId, int page, int pageSize) {
        return listOrders(page, pageSize, null, null, customerId);
    }

    public OrderDetailResponse requireCustomerOrder(long customerId, String orderNo) {
        return loadOrderDetailByOrderNo(orderNo)
            .filter(order -> order.customer().id() == customerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    @Transactional
    public OrderDetailResponse submitPaymentProof(long customerId, String orderNo, SubmitPaymentProofRequest request) {
        OrderDetailResponse order = requireCustomerOrder(customerId, orderNo);
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

    @Transactional
    public OrderDetailResponse addInboundShipment(long customerId, String orderNo, CreateShipmentRequest request) {
        OrderDetailResponse order = requireCustomerOrder(customerId, orderNo);
        if (!Set.of("awaiting_inbound", "inbound_shipped").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Inbound tracking can be added after payment has been confirmed");
        }
        String carrier = requireText(request.carrierName(), "Carrier", 128);
        String tracking = requireText(request.trackingNumber(), "Tracking number", 255);
        Map<String, Object> shipmentValues = new LinkedHashMap<>();
        shipmentValues.put("order_id", order.id());
        shipmentValues.put("direction_code", "inbound");
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
        return listOrders(page, pageSize, status, query, null);
    }

    public OrderDetailResponse requireAdminOrder(long orderId) {
        return loadOrderDetailById(orderId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    @Transactional
    public OrderDetailResponse confirmPayment(long orderId, long paymentId, long adminUserId, ConfirmPaymentRequest request) {
        OrderDetailResponse order = requireAdminOrder(orderId);
        PaymentRecord payment = requireOrderPayment(order.id(), paymentId);
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
        updateOrderStatus(order.id(), "awaiting_inbound", "Payment confirmed", "Payment has been confirmed. Please send your cards to NXR and add inbound tracking.", true, "admin", null, adminUserId, false);
        return requireAdminOrder(orderId);
    }

    @Transactional
    public OrderDetailResponse rejectPayment(long orderId, long paymentId, long adminUserId, RejectPaymentRequest request) {
        OrderDetailResponse order = requireAdminOrder(orderId);
        PaymentRecord payment = requireOrderPayment(order.id(), paymentId);
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
        updateOrderStatus(order.id(), "awaiting_payment", "Payment needs attention", note, true, "admin", null, adminUserId, false);
        return requireAdminOrder(orderId);
    }

    @Transactional
    public OrderDetailResponse updateOrderStatusByAdmin(long orderId, long adminUserId, UpdateOrderStatusRequest request) {
        OrderDetailResponse order = requireAdminOrder(orderId);
        String targetStatus = normalizeStatus(request.statusCode());
        String detail = blankToNull(clean(request.detail(), 1000));
        updateOrderStatus(order.id(), targetStatus, statusTitle(targetStatus), detail, true, "admin", null, adminUserId, true);
        return requireAdminOrder(orderId);
    }

    @Transactional
    public OrderDetailResponse createAdminShipment(long orderId, long adminUserId, CreateShipmentRequest request) {
        OrderDetailResponse order = requireAdminOrder(orderId);
        String direction = normalizeShipmentDirection(request.direction());
        String carrier = requireText(request.carrierName(), "Carrier", 128);
        String tracking = requireText(request.trackingNumber(), "Tracking number", 255);
        String targetStatus;
        String title;
        String detail;
        if (direction.equals("inbound")) {
            if (!Set.of("awaiting_inbound", "inbound_shipped").contains(order.statusCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Inbound cards can only be received after payment confirmation");
            }
            targetStatus = "received";
            title = "Cards received";
            detail = "NXR received the inbound shipment from " + carrier + ".";
        } else {
            if (!order.statusCode().equals("completed")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Return shipment can be created after grading is completed");
            }
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
        }
        updateOrderStatus(order.id(), targetStatus, title, detail, true, "admin", null, adminUserId, true);
        addTimelineEvent(order.id(), "shipment_created", direction.equals("inbound") ? "Inbound shipment received" : "Shipment recorded", "Shipment #" + shipmentId + " was recorded.", targetStatus, false, "admin", null, adminUserId);
        return requireAdminOrder(orderId);
    }

    @Transactional
    public OrderDetailResponse markShipmentDelivered(long orderId, long shipmentId, long adminUserId) {
        OrderDetailResponse order = requireAdminOrder(orderId);
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
        if (shipment.directionCode().equals("outbound")) {
            updateOrderStatus(order.id(), "delivered", "Return shipment delivered", "Carrier delivery was confirmed.", true, "admin", null, adminUserId, true);
        } else {
            addTimelineEvent(order.id(), "inbound_delivery_confirmed", "Inbound delivery confirmed", "Carrier delivery was confirmed.", "received", true, "admin", null, adminUserId);
        }
        return requireAdminOrder(orderId);
    }

    @Transactional
    public OrderDetailResponse linkOrderItemSubmission(long orderId, long itemId, long submissionId, long adminUserId) {
        OrderDetailResponse order = requireAdminOrder(orderId);
        Integer existing = jdbcClient.sql("SELECT COUNT(*) FROM grading_submission WHERE id = :submissionId")
            .param("submissionId", submissionId)
            .query(Integer.class)
            .single();
        if (existing == null || existing == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading submission not found");
        }
        int updated = jdbcClient.sql(
                """
                UPDATE grading_order_item
                SET grading_submission_id = :submissionId, status_code = 'grading', updated_at = CURRENT_TIMESTAMP
                WHERE id = :itemId AND order_id = :orderId
                """
            )
            .param("submissionId", submissionId)
            .param("itemId", itemId)
            .param("orderId", order.id())
            .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order item not found");
        }
        if (order.statusCode().equals("received")) {
            updateOrderStatus(order.id(), "grading", "Grading in progress", "A grading work record has been linked to an order item.", true, "admin", null, adminUserId, true);
        } else {
            addTimelineEvent(order.id(), "grading_submission_linked", "Grading work linked", "A grading work record has been linked to an order item.", "grading", true, "admin", null, adminUserId);
        }
        return requireAdminOrder(orderId);
    }

    @Transactional
    public PaymentCallbackResponse receivePaymentCallback(String provider, PaymentCallbackRequest request) {
        String normalizedProvider = normalizePaymentProvider(provider);
        String eventId = requireText(request.providerEventId(), "Provider event id", 255);
        String transactionId = requireText(request.providerTransactionId(), "Provider transaction id", 255);
        PaymentRecord payment = jdbcClient.sql(
                """
                SELECT id, order_id, direction_code, payment_type_code, provider_code, method_label, status_code,
                       amount, currency_code, payer_reference, proof_reference, provider_transaction_id,
                       confirmed_by_user_id, submitted_at, confirmed_at, callback_received_at, note, created_at
                FROM payment_record
                WHERE provider_code = :provider AND provider_transaction_id = :transactionId
                """
            )
            .param("provider", normalizedProvider)
            .param("transactionId", transactionId)
            .query((rs, rowNum) -> mapPayment(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment transaction not found"));
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
        if (Set.of("confirmed", "paid", "succeeded").contains(callbackStatus)) {
            jdbcClient.sql(
                    """
                    UPDATE payment_record
                    SET status_code = 'confirmed', callback_received_at = CURRENT_TIMESTAMP,
                        callback_payload = :payload, confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :paymentId
                    """
                )
                .param("payload", clean(request.rawPayload(), 20000))
                .param("paymentId", payment.id())
                .update();
            OrderDetailResponse order = requireAdminOrder(payment.orderId());
            if (Set.of("awaiting_payment", "payment_review").contains(order.statusCode())) {
                updateOrderStatus(order.id(), "awaiting_inbound", "Payment confirmed", "A verified payment callback was received.", true, "payment_callback", null, null, false);
            }
        } else {
            jdbcClient.sql(
                    """
                    UPDATE payment_record
                    SET status_code = :status, callback_received_at = CURRENT_TIMESTAMP,
                        callback_payload = :payload, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :paymentId
                    """
                )
                .param("status", callbackStatus.isBlank() ? "failed" : callbackStatus)
                .param("payload", clean(request.rawPayload(), 20000))
                .param("paymentId", payment.id())
                .update();
        }
        return new PaymentCallbackResponse(false, payment.orderId(), "Callback recorded");
    }

    private OrderListResponse listOrders(int page, int pageSize, String status, String query, Long customerId) {
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
        String whereClause = """
            WHERE (:status IS NULL OR o.status_code = :status)
              AND (:query IS NULL OR UPPER(o.order_no) LIKE :query OR UPPER(c.email) LIKE :query OR UPPER(c.display_name) LIKE :query)
            """ + customerCondition;
        Integer total = jdbcClient.sql(
                "SELECT COUNT(*) FROM grading_order o JOIN customer_account c ON c.id = o.customer_id " + whereClause
            )
            .params(params)
            .query(Integer.class)
            .single();
        List<OrderListItem> items = jdbcClient.sql(
                """
                SELECT o.id, o.order_no, o.status_code, o.service_level_code, o.total_card_count,
                       o.total_amount, o.currency_code, o.created_at, o.updated_at,
                       c.id AS customer_id, c.email AS customer_email, c.display_name AS customer_display_name
                FROM grading_order o
                JOIN customer_account c ON c.id = o.customer_id
                """ + whereClause + " ORDER BY o.created_at DESC, o.id DESC LIMIT :limit OFFSET :offset"
            )
            .params(params)
            .query((rs, rowNum) -> new OrderListItem(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("status_code"),
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
                SELECT o.id, o.order_no, o.status_code, o.service_level_code, o.total_card_count,
                       o.service_fee, o.return_shipping_fee, o.total_amount, o.currency_code,
                       o.contact_name, o.contact_phone, o.return_address_line1, o.return_address_line2,
                       o.return_city, o.return_region, o.return_postal_code, o.return_country,
                       o.customer_note, o.internal_note, o.created_at, o.updated_at,
                       c.id AS customer_id, c.email AS customer_email, c.display_name AS customer_display_name
                FROM grading_order o
                JOIN customer_account c ON c.id = o.customer_id
                """ + " WHERE " + predicate
            )
            .params(params)
            .query((rs, rowNum) -> new OrderDetailResponse(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("status_code"),
                rs.getString("service_level_code"),
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
            base.id(), base.orderNo(), base.statusCode(), base.serviceLevelCode(), base.totalCardCount(),
            base.serviceFee(), base.returnShippingFee(), base.totalAmount(), base.currencyCode(),
            base.contactName(), base.contactPhone(), base.returnAddressLine1(), base.returnAddressLine2(),
            base.returnCity(), base.returnRegion(), base.returnPostalCode(), base.returnCountry(),
            base.customerNote(), base.internalNote(), base.customer(), base.createdAt(), base.updatedAt(),
            listOrderItems(base.id()), listPayments(base.id()), listShipments(base.id()), listTimeline(base.id())
        );
    }

    private List<OrderItemResponse> listOrderItems(long orderId) {
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
                SELECT id, order_id, direction_code, payment_type_code, provider_code, method_label, status_code,
                       amount, currency_code, payer_reference, proof_reference, provider_transaction_id,
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
                SELECT id, order_id, direction_code, carrier_name, tracking_number, status_code,
                       shipped_by_user_id, shipped_at, delivered_at, note
                FROM order_shipment
                WHERE order_id = :orderId
                ORDER BY shipped_at ASC, id ASC
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new ShipmentRecord(
                rs.getLong("id"), rs.getLong("order_id"), rs.getString("direction_code"), rs.getString("carrier_name"),
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
                SELECT id, order_id, direction_code, payment_type_code, provider_code, method_label, status_code,
                       amount, currency_code, payer_reference, proof_reference, provider_transaction_id,
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
                SELECT id, order_id, direction_code, payment_type_code, provider_code, method_label, status_code,
                       amount, currency_code, payer_reference, proof_reference, provider_transaction_id,
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
                SELECT id, order_id, direction_code, carrier_name, tracking_number, status_code,
                       shipped_by_user_id, shipped_at, delivered_at, note
                FROM order_shipment WHERE id = :shipmentId AND order_id = :orderId
                """
            )
            .param("shipmentId", shipmentId)
            .param("orderId", orderId)
            .query((rs, rowNum) -> new ShipmentRecord(
                rs.getLong("id"), rs.getLong("order_id"), rs.getString("direction_code"), rs.getString("carrier_name"),
                rs.getString("tracking_number"), rs.getString("status_code"), rs.getObject("shipped_by_user_id", Long.class),
                rs.getObject("shipped_at", LocalDateTime.class), rs.getObject("delivered_at", LocalDateTime.class), rs.getString("note")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment record not found"));
    }

    private static PaymentRecord mapPayment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentRecord(
            rs.getLong("id"), rs.getLong("order_id"), rs.getString("direction_code"), rs.getString("payment_type_code"),
            rs.getString("provider_code"), rs.getString("method_label"), rs.getString("status_code"), rs.getBigDecimal("amount"),
            rs.getString("currency_code"), rs.getString("payer_reference"), rs.getString("proof_reference"),
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

    private static String normalizeServiceLevel(String value) {
        String serviceLevel = clean(value, 32).toLowerCase(Locale.ROOT);
        return SERVICE_PRICES.containsKey(serviceLevel) ? serviceLevel : "standard";
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
            case "awaiting_payment" -> "Awaiting payment";
            case "payment_review" -> "Payment under review";
            case "awaiting_inbound" -> "Awaiting inbound shipment";
            case "inbound_shipped" -> "Cards shipped to NXR";
            case "received" -> "Cards received";
            case "grading" -> "Grading in progress";
            case "review" -> "Final review";
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
        String contactName,
        String contactPhone,
        String returnAddressLine1,
        String returnAddressLine2,
        String returnCity,
        String returnRegion,
        String returnPostalCode,
        String returnCountry,
        String customerNote,
        List<OrderItemRequest> items
    ) {
    }

    public record OrderItemRequest(
        String cardName,
        String brandName,
        String setName,
        String cardNumber,
        String languageCode,
        BigDecimal declaredValue,
        String itemNote
    ) {
    }

    public record SubmitPaymentProofRequest(String provider, String payerReference, String proofReference) {
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
        String providerTransactionId,
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
        String serviceLevelCode,
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
        String setName,
        String cardNumber,
        String languageCode,
        BigDecimal declaredValue,
        String itemNote,
        String statusCode,
        Long gradingSubmissionId,
        String gradingCertId,
        String gradingStatusCode
    ) {
    }

    public record PaymentRecord(
        long id,
        long orderId,
        String directionCode,
        String paymentTypeCode,
        String providerCode,
        String methodLabel,
        String statusCode,
        BigDecimal amount,
        String currencyCode,
        String payerReference,
        String proofReference,
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
}
