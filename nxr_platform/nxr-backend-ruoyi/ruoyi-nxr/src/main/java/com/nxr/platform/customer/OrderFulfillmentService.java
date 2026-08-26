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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operational services around a grading order: address book, return-shipping
 * prices, warehouse intake, work tasks, customer support and billing changes.
 */
@Service
public class OrderFulfillmentService {

    private static final Set<String> EXCEPTION_TYPES = Set.of("shortage", "overage", "damaged", "missing_barcode", "other");
    private static final Set<String> WORK_TASK_TYPES = Set.of("preprocess", "vision", "manual_review", "encapsulation", "quality_check");
    private static final Set<String> WORK_TASK_STATUSES = Set.of("pending", "in_progress", "completed", "failed");
    private static final Set<String> TICKET_CATEGORIES = Set.of("shipping_change", "score_dispute", "inquiry");
    private static final Set<String> TICKET_STATUSES = Set.of("open", "assigned", "waiting_customer", "resolved", "closed");
    private static final Set<String> TRACKING_EVENTS = Set.of("label_created", "picked_up", "in_transit", "customs", "exception", "out_for_delivery", "delivered");

    private final JdbcClient jdbcClient;
    private final SimpleJdbcInsert addressInsert;
    private final SimpleJdbcInsert intakeReceiptInsert;
    private final SimpleJdbcInsert exceptionInsert;
    private final SimpleJdbcInsert workTaskInsert;
    private final SimpleJdbcInsert trackingInsert;
    private final SimpleJdbcInsert ticketInsert;
    private final SimpleJdbcInsert ticketMessageInsert;
    private final SimpleJdbcInsert shippingChangeInsert;
    private final SimpleJdbcInsert paymentInsert;

    public OrderFulfillmentService(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.addressInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("customer_address")
            .usingColumns(
                "customer_id", "label", "contact_name", "contact_phone", "address_line1", "address_line2",
                "city", "region", "postal_code", "country", "is_default"
            )
            .usingGeneratedKeyColumns("id");
        this.intakeReceiptInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("order_intake_receipt")
            .usingColumns("order_id", "package_no", "expected_count", "received_count", "condition_note", "received_by_user_id")
            .usingGeneratedKeyColumns("id");
        this.exceptionInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("order_exception")
            .usingColumns(
                "order_id", "exception_type_code", "status_code", "title", "detail", "visible_to_customer", "created_by_user_id"
            )
            .usingGeneratedKeyColumns("id");
        this.workTaskInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("order_work_task")
            .usingColumns("order_id", "order_item_id", "task_type_code", "status_code", "attempt_count", "assigned_user_id")
            .usingGeneratedKeyColumns("id");
        this.trackingInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("shipment_tracking_event")
            .usingColumns(
                "shipment_id", "event_code", "event_title", "location_label", "event_detail", "event_time", "created_by_user_id"
            )
            .usingGeneratedKeyColumns("id");
        this.ticketInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("support_ticket")
            .usingColumns("ticket_no", "order_id", "customer_id", "category_code", "status_code", "subject")
            .usingGeneratedKeyColumns("id");
        this.ticketMessageInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("support_ticket_message")
            .usingColumns(
                "ticket_id", "actor_type_code", "actor_customer_id", "actor_admin_user_id", "message_text", "attachment_reference"
            )
            .usingGeneratedKeyColumns("id");
        this.shippingChangeInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("shipping_change_request")
            .usingColumns(
                "order_id", "ticket_id", "old_option_code", "old_option_name", "old_price_amount",
                "new_option_code", "new_option_name", "new_price_amount", "currency_code",
                "difference_amount", "status_code", "reason"
            )
            .usingGeneratedKeyColumns("id");
        this.paymentInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("payment_record")
            .usingColumns(
                "order_id", "direction_code", "payment_type_code", "payment_no", "provider_code", "status_code",
                "amount", "currency_code", "related_payment_id", "note"
            )
            .usingGeneratedKeyColumns("id");
    }

    public List<CustomerAddress> listAddresses(long customerId) {
        return jdbcClient.sql(
                """
                SELECT id, customer_id, label, contact_name, contact_phone, address_line1, address_line2,
                       city, region, postal_code, country, is_default, created_at, updated_at
                FROM customer_address
                WHERE customer_id = :customerId
                ORDER BY is_default DESC, updated_at DESC, id DESC
                """
            )
            .param("customerId", customerId)
            .query((rs, rowNum) -> mapAddress(rs))
            .list();
    }

    public CustomerAddress requireAddress(long customerId, long addressId) {
        return jdbcClient.sql(
                """
                SELECT id, customer_id, label, contact_name, contact_phone, address_line1, address_line2,
                       city, region, postal_code, country, is_default, created_at, updated_at
                FROM customer_address
                WHERE id = :addressId AND customer_id = :customerId
                """
            )
            .param("addressId", addressId)
            .param("customerId", customerId)
            .query((rs, rowNum) -> mapAddress(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Return address not found"));
    }

    @Transactional
    public CustomerAddress saveAddress(long customerId, Long addressId, AddressRequest request) {
        AddressValues values = normalizeAddress(request);
        if (values.isDefault()) {
            jdbcClient.sql("UPDATE customer_address SET is_default = 0 WHERE customer_id = :customerId")
                .param("customerId", customerId)
                .update();
        }
        long resolvedId;
        if (addressId == null) {
            Map<String, Object> insertValues = new LinkedHashMap<>();
            insertValues.put("customer_id", customerId);
            putAddressValues(insertValues, values);
            resolvedId = addressInsert.executeAndReturnKey(insertValues).longValue();
        } else {
            requireAddress(customerId, addressId);
            int updated = jdbcClient.sql(
                    """
                    UPDATE customer_address
                    SET label = :label, contact_name = :contactName, contact_phone = :contactPhone,
                        address_line1 = :addressLine1, address_line2 = :addressLine2, city = :city,
                        region = :region, postal_code = :postalCode, country = :country,
                        is_default = :isDefault, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :addressId AND customer_id = :customerId
                    """
                )
                .param("label", values.label())
                .param("contactName", values.contactName())
                .param("contactPhone", values.contactPhone())
                .param("addressLine1", values.addressLine1())
                .param("addressLine2", values.addressLine2())
                .param("city", values.city())
                .param("region", values.region())
                .param("postalCode", values.postalCode())
                .param("country", values.country())
                .param("isDefault", values.isDefault() ? 1 : 0)
                .param("addressId", addressId)
                .param("customerId", customerId)
                .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return address not found");
            }
            resolvedId = addressId;
        }
        ensureDefaultAddress(customerId, resolvedId);
        return requireAddress(customerId, resolvedId);
    }

    @Transactional
    public void deleteAddress(long customerId, long addressId) {
        CustomerAddress address = requireAddress(customerId, addressId);
        jdbcClient.sql("DELETE FROM customer_address WHERE id = :addressId AND customer_id = :customerId")
            .param("addressId", addressId)
            .param("customerId", customerId)
            .update();
        if (address.defaultAddress()) {
            jdbcClient.sql(
                    """
                    UPDATE customer_address SET is_default = 1
                    WHERE id = (
                        SELECT id FROM (
                            SELECT id FROM customer_address WHERE customer_id = :customerId
                            ORDER BY updated_at DESC, id DESC LIMIT 1
                        ) candidate
                    )
                    """
                )
                .param("customerId", customerId)
                .update();
        }
    }

    public List<ShippingOption> listShippingOptions(String country, boolean includeInactive) {
        String normalizedCountry = clean(country, 128).toUpperCase(Locale.ROOT);
        return jdbcClient.sql(
                """
                SELECT id, option_code, display_name, description, country_scope, currency_code,
                       price_amount, sort_order, is_active, created_at, updated_at
                FROM return_shipping_option
                WHERE (:includeInactive = 1 OR is_active = 1)
                ORDER BY sort_order ASC, id ASC
                """
            )
            .param("includeInactive", includeInactive ? 1 : 0)
            .query((rs, rowNum) -> mapShippingOption(rs))
            .list()
            .stream()
            .filter(option -> includeInactive || appliesToCountry(option.countryScope(), normalizedCountry))
            .toList();
    }

    public ShippingOption requireShippingOption(String optionCode, String country) {
        String code = normalizeCode(optionCode, "Return shipping option");
        String normalizedCountry = clean(country, 128).toUpperCase(Locale.ROOT);
        ShippingOption option = jdbcClient.sql(
                """
                SELECT id, option_code, display_name, description, country_scope, currency_code,
                       price_amount, sort_order, is_active, created_at, updated_at
                FROM return_shipping_option WHERE option_code = :optionCode
                """
            )
            .param("optionCode", code)
            .query((rs, rowNum) -> mapShippingOption(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Return shipping option not found"));
        if (!option.active() || !appliesToCountry(option.countryScope(), normalizedCountry)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Return shipping option is not available for this address");
        }
        return option;
    }

    public ServicePrice activeServicePrice() {
        return jdbcClient.sql(
                """
                SELECT price_code, display_name, unit_price, currency_code, version_no
                FROM grading_service_price
                WHERE price_code = 'basic_grading' AND is_active = 1
                LIMIT 1
                """
            )
            .query((rs, rowNum) -> new ServicePrice(
                rs.getString("price_code"), rs.getString("display_name"), rs.getBigDecimal("unit_price"),
                rs.getString("currency_code"), rs.getInt("version_no")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Grading price is not configured"));
    }

    @Transactional
    public ServicePrice saveServicePrice(ServicePriceRequest request) {
        String displayName = requireText(request.displayName(), "Service name", 128);
        BigDecimal unitPrice = normalizeMoney(request.unitPrice(), "Service unit price");
        String currencyCode = normalizeCurrency(request.currencyCode());
        int updated = jdbcClient.sql(
                """
                UPDATE grading_service_price
                SET display_name = :displayName, unit_price = :unitPrice, currency_code = :currencyCode,
                    is_active = 1, version_no = version_no + 1, updated_at = CURRENT_TIMESTAMP
                WHERE price_code = 'basic_grading'
                """
            )
            .param("displayName", displayName)
            .param("unitPrice", unitPrice)
            .param("currencyCode", currencyCode)
            .update();
        if (updated == 0) {
            jdbcClient.sql(
                    """
                    INSERT INTO grading_service_price
                        (price_code, display_name, unit_price, currency_code, is_active, version_no)
                    VALUES ('basic_grading', :displayName, :unitPrice, :currencyCode, 1, 1)
                    """
                )
                .param("displayName", displayName)
                .param("unitPrice", unitPrice)
                .param("currencyCode", currencyCode)
                .update();
        }
        return activeServicePrice();
    }

    @Transactional
    public ShippingOption saveShippingOption(Long optionId, ShippingOptionRequest request) {
        String code = normalizeCode(request.optionCode(), "Option code");
        String name = requireText(request.displayName(), "Option name", 128);
        String description = blankToNull(clean(request.description(), 512));
        String countryScope = normalizeCountryScope(request.countryScope());
        String currency = normalizeCurrency(request.currencyCode());
        BigDecimal price = normalizeMoney(request.priceAmount(), "Shipping price");
        int sortOrder = request.sortOrder() == null ? 0 : Math.max(-1000, Math.min(1000, request.sortOrder()));
        boolean active = request.active() == null || request.active();
        if (optionId == null) {
            jdbcClient.sql(
                    """
                    INSERT INTO return_shipping_option
                        (option_code, display_name, description, country_scope, currency_code, price_amount, sort_order, is_active)
                    VALUES (:code, :name, :description, :countryScope, :currency, :price, :sortOrder, :active)
                    """
                )
                .param("code", code)
                .param("name", name)
                .param("description", description)
                .param("countryScope", countryScope)
                .param("currency", currency)
                .param("price", price)
                .param("sortOrder", sortOrder)
                .param("active", active ? 1 : 0)
                .update();
        } else {
            int updated = jdbcClient.sql(
                    """
                    UPDATE return_shipping_option
                    SET option_code = :code, display_name = :name, description = :description,
                        country_scope = :countryScope, currency_code = :currency, price_amount = :price,
                        sort_order = :sortOrder, is_active = :active, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :optionId
                    """
                )
                .param("code", code)
                .param("name", name)
                .param("description", description)
                .param("countryScope", countryScope)
                .param("currency", currency)
                .param("price", price)
                .param("sortOrder", sortOrder)
                .param("active", active ? 1 : 0)
                .param("optionId", optionId)
                .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return shipping option not found");
            }
        }
        return jdbcClient.sql(
                """
                SELECT id, option_code, display_name, description, country_scope, currency_code,
                       price_amount, sort_order, is_active, created_at, updated_at
                FROM return_shipping_option WHERE option_code = :code
                """
            )
            .param("code", code)
            .query((rs, rowNum) -> mapShippingOption(rs))
            .single();
    }

    @Transactional
    public void ensureIntakeCodes(long orderId) {
        OrderRow order = requireOrder(orderId);
        if (order.intakeCode() != null && !order.intakeCode().isBlank()) {
            return;
        }
        String intakeCode = "IN-" + order.orderNo() + "-" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
        String packingSlipCode = "PACK-" + order.orderNo();
        jdbcClient.sql(
                """
                UPDATE grading_order
                SET intake_code = :intakeCode, packing_slip_code = :packingSlipCode, updated_at = CURRENT_TIMESTAMP
                WHERE id = :orderId AND intake_code IS NULL
                """
            )
            .param("intakeCode", intakeCode)
            .param("packingSlipCode", packingSlipCode)
            .param("orderId", orderId)
            .update();
        addTimeline(orderId, "packing_slip_ready", "Packing slip ready", "Print the packing slip and place it inside the shipment.", "awaiting_inbound", true, "system", null, null);
    }

    public PackingSlip requirePackingSlip(long customerId, String orderNo) {
        OrderRow order = requireCustomerOrder(customerId, orderNo);
        if (order.intakeCode() == null || order.intakeCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Packing slip is available after payment confirmation");
        }
        List<LanguageQuantity> groups = jdbcClient.sql(
                """
                SELECT COALESCE(NULLIF(language_code, ''), 'UNSPECIFIED') AS language_code, COUNT(*) AS quantity
                FROM grading_order_item WHERE order_id = :orderId
                GROUP BY COALESCE(NULLIF(language_code, ''), 'UNSPECIFIED')
                ORDER BY language_code
                """
            )
            .param("orderId", order.id())
            .query((rs, rowNum) -> new LanguageQuantity(rs.getString("language_code"), rs.getInt("quantity")))
            .list();
        return new PackingSlip(
            order.orderNo(), order.intakeCode(), order.packingSlipCode(), "nxr://intake/" + order.intakeCode(),
            order.totalCardCount(), groups,
            List.of(
                "Place each card in a sleeve and semi-rigid holder.",
                "Protect cards with bubble wrap and use a rigid shipping box.",
                "Put this packing slip inside the package and use tracked shipping."
            )
        );
    }

    public IntakeLookup lookupIntake(String intakeCode) {
        String code = requireText(intakeCode, "Intake code", 64).toUpperCase(Locale.ROOT);
        OrderRow order = jdbcClient.sql(
                """
                SELECT id, order_no, customer_id, status_code, total_card_count, currency_code,
                       return_country, return_shipping_option_code, return_shipping_option_name,
                       return_shipping_fee, intake_code, packing_slip_code, shipping_label_created_at
                FROM grading_order WHERE UPPER(intake_code) = :intakeCode
                """
            )
            .param("intakeCode", code)
            .query((rs, rowNum) -> mapOrderRow(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Intake order not found"));
        return new IntakeLookup(order.id(), order.orderNo(), order.statusCode(), order.totalCardCount(), order.intakeCode());
    }

    @Transactional
    public AdminOperationsResponse receiveOrder(long orderId, long adminUserId, ReceiveOrderRequest request) {
        OrderRow order = requireOrder(orderId);
        if (!Set.of("awaiting_inbound", "inbound_shipped", "intake_exception").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is not ready for warehouse intake");
        }
        String suppliedCode = requireText(request.intakeCode(), "Intake code", 64).toUpperCase(Locale.ROOT);
        if (order.intakeCode() == null || !order.intakeCode().equalsIgnoreCase(suppliedCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Intake code does not match this order");
        }
        int receivedCount = request.receivedCount() == null ? -1 : request.receivedCount();
        if (receivedCount < 0 || receivedCount > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Received card count is invalid");
        }
        Map<String, Object> receiptValues = new LinkedHashMap<>();
        receiptValues.put("order_id", order.id());
        receiptValues.put("package_no", blankToNull(clean(request.packageNo(), 128)));
        receiptValues.put("expected_count", order.totalCardCount());
        receiptValues.put("received_count", receivedCount);
        receiptValues.put("condition_note", blankToNull(clean(request.conditionNote(), 1000)));
        receiptValues.put("received_by_user_id", adminUserId);
        intakeReceiptInsert.execute(receiptValues);

        boolean hasException = receivedCount != order.totalCardCount();
        if (receivedCount < order.totalCardCount()) {
            createExceptionInternal(order.id(), adminUserId, "shortage", "Card count shortage", "Expected " + order.totalCardCount() + " but received " + receivedCount + ".", true);
        } else if (receivedCount > order.totalCardCount()) {
            createExceptionInternal(order.id(), adminUserId, "overage", "Extra cards received", "Expected " + order.totalCardCount() + " but received " + receivedCount + ".", true);
        }
        List<String> exceptionTypes = request.exceptionTypes() == null ? List.of() : request.exceptionTypes();
        for (String rawType : exceptionTypes) {
            String type = normalizeExceptionType(rawType);
            if ((type.equals("shortage") || type.equals("overage")) && receivedCount != order.totalCardCount()) {
                continue;
            }
            hasException = true;
            createExceptionInternal(order.id(), adminUserId, type, exceptionTitle(type), blankToNull(clean(request.conditionNote(), 1000)), true);
        }

        if (hasException) {
            updateOrderStatus(order.id(), "intake_exception");
            jdbcClient.sql("UPDATE grading_order_item SET status_code = 'intake_exception', updated_at = CURRENT_TIMESTAMP WHERE order_id = :orderId")
                .param("orderId", order.id())
                .update();
            addTimeline(order.id(), "intake_exception", "Warehouse intake needs review", "The receiving team recorded an intake difference.", "intake_exception", true, "admin", null, adminUserId);
        } else {
            completeReceipt(order.id(), adminUserId);
        }
        return loadAdminOperations(order.id());
    }

    @Transactional
    public AdminOperationsResponse createException(long orderId, long adminUserId, OrderExceptionRequest request) {
        OrderRow order = requireOrder(orderId);
        String type = normalizeExceptionType(request.exceptionTypeCode());
        createExceptionInternal(
            order.id(), adminUserId, type,
            blankToNull(clean(request.title(), 255)) == null ? exceptionTitle(type) : clean(request.title(), 255),
            blankToNull(clean(request.detail(), 4000)), request.visibleToCustomer() == null || request.visibleToCustomer()
        );
        updateOrderStatus(order.id(), "intake_exception");
        addTimeline(order.id(), "order_exception", "Order exception recorded", request.visibleToCustomer() == Boolean.FALSE ? "Our team is reviewing the order." : clean(request.detail(), 1000), "intake_exception", true, "admin", null, adminUserId);
        return loadAdminOperations(order.id());
    }

    @Transactional
    public AdminOperationsResponse resolveException(long orderId, long exceptionId, long adminUserId, ResolveExceptionRequest request) {
        requireOrder(orderId);
        String note = requireText(request.resolutionNote(), "Resolution note", 4000);
        int updated = jdbcClient.sql(
                """
                UPDATE order_exception
                SET status_code = 'resolved', resolution_note = :note, resolved_by_user_id = :adminUserId,
                    resolved_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :exceptionId AND order_id = :orderId AND status_code = 'open'
                """
            )
            .param("note", note)
            .param("adminUserId", adminUserId)
            .param("exceptionId", exceptionId)
            .param("orderId", orderId)
            .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order exception is not open");
        }
        int openCount = jdbcClient.sql("SELECT COUNT(*) FROM order_exception WHERE order_id = :orderId AND status_code = 'open'")
            .param("orderId", orderId)
            .query(Integer.class)
            .single();
        if (openCount == 0) {
            completeReceipt(orderId, adminUserId);
        } else {
            addTimeline(orderId, "exception_resolved", "One order exception was resolved", note, "intake_exception", true, "admin", null, adminUserId);
        }
        return loadAdminOperations(orderId);
    }

    @Transactional
    public AdminOperationsResponse updateWorkTask(long orderId, long taskId, long adminUserId, WorkTaskUpdateRequest request) {
        OrderRow order = requireOrder(orderId);
        WorkTaskRecord task = requireWorkTask(order.id(), taskId);
        String status = normalizeWorkTaskStatus(request.statusCode());
        if (task.statusCode().equals("completed") && !status.equals("completed")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Completed work tasks cannot be reopened");
        }
        if (status.equals("completed") && request.resultSummary() != null && request.resultSummary().length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task result is too long");
        }
        int nextAttempt = task.attemptCount() + (status.equals("in_progress") && !task.statusCode().equals("in_progress") ? 1 : 0);
        jdbcClient.sql(
                """
                UPDATE order_work_task
                SET status_code = :status, attempt_count = :attemptCount,
                    result_summary = :resultSummary, failure_reason = :failureReason,
                    assigned_user_id = :adminUserId,
                    started_at = CASE WHEN :status = 'in_progress' AND started_at IS NULL THEN CURRENT_TIMESTAMP ELSE started_at END,
                    completed_at = CASE WHEN :status = 'completed' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :taskId AND order_id = :orderId
                """
            )
            .param("status", status)
            .param("attemptCount", nextAttempt)
            .param("resultSummary", blankToNull(clean(request.resultSummary(), 4000)))
            .param("failureReason", status.equals("failed") ? requireText(request.failureReason(), "Failure reason", 4000) : null)
            .param("adminUserId", adminUserId)
            .param("taskId", taskId)
            .param("orderId", order.id())
            .update();
        addTimeline(
            order.id(), "work_task_" + status, workTaskTitle(task.taskTypeCode()) + " " + status.replace('_', ' '),
            status.equals("failed") ? clean(request.failureReason(), 1000) : clean(request.resultSummary(), 1000),
            order.statusCode(), true, "admin", null, adminUserId
        );
        return loadAdminOperations(order.id());
    }

    @Transactional
    public AdminOperationsResponse createWorkTask(long orderId, long adminUserId, WorkTaskRequest request) {
        OrderRow order = requireOrder(orderId);
        String type = normalizeWorkTaskType(request.taskTypeCode());
        Long itemId = request.orderItemId();
        if (itemId != null) {
            int count = jdbcClient.sql("SELECT COUNT(*) FROM grading_order_item WHERE id = :itemId AND order_id = :orderId")
                .param("itemId", itemId)
                .param("orderId", order.id())
                .query(Integer.class)
                .single();
            if (count == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order item not found");
            }
        }
        workTaskInsert.execute(taskValues(order.id(), itemId, type, adminUserId));
        return loadAdminOperations(order.id());
    }

    @Transactional
    public AdminOperationsResponse qualityCheck(long orderId, long adminUserId, QualityCheckRequest request) {
        OrderRow order = requireOrder(orderId);
        if (!Set.of("review", "quality_check", "quality_hold").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Quality check is available after grading review");
        }
        int unlinkedItems = jdbcClient.sql("SELECT COUNT(*) FROM grading_order_item WHERE order_id = :orderId AND grading_submission_id IS NULL")
            .param("orderId", order.id())
            .query(Integer.class)
            .single();
        if (unlinkedItems > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Every card must be linked to a grading submission before final quality check");
        }
        int unfinishedTasks = jdbcClient.sql(
                """
                SELECT COUNT(*) FROM order_work_task
                WHERE order_id = :orderId AND task_type_code IN ('preprocess', 'vision', 'manual_review', 'encapsulation')
                  AND status_code <> 'completed'
                """
            )
            .param("orderId", order.id())
            .query(Integer.class)
            .single();
        if (unfinishedTasks > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "All grading and encapsulation tasks must be completed before final quality check");
        }
        boolean passed = request.passed() != null && request.passed();
        Map<String, Object> task = taskValues(order.id(), null, "quality_check", adminUserId);
        task.put("status_code", passed ? "completed" : "failed");
        long taskId = workTaskInsert.executeAndReturnKey(task).longValue();
        jdbcClient.sql(
                """
                UPDATE order_work_task
                SET attempt_count = 1, result_summary = :result, failure_reason = :failure,
                    started_at = CURRENT_TIMESTAMP, completed_at = :completedAt, updated_at = CURRENT_TIMESTAMP
                WHERE id = :taskId
                """
            )
            .param("result", blankToNull(clean(request.note(), 4000)))
            .param("failure", passed ? null : requireText(request.note(), "Quality issue", 4000))
            .param("completedAt", passed ? LocalDateTime.now() : null)
            .param("taskId", taskId)
            .update();
        String targetStatus = passed ? "completed" : "quality_hold";
        updateOrderStatus(order.id(), targetStatus);
        jdbcClient.sql("UPDATE grading_order_item SET status_code = :status, updated_at = CURRENT_TIMESTAMP WHERE order_id = :orderId")
            .param("status", passed ? "quality_passed" : "quality_hold")
            .param("orderId", order.id())
            .update();
        addTimeline(order.id(), passed ? "quality_passed" : "quality_failed", passed ? "Final quality check passed" : "Quality check needs rework", clean(request.note(), 1000), targetStatus, true, "admin", null, adminUserId);
        return loadAdminOperations(order.id());
    }

    @Transactional
    public AdminOperationsResponse addTrackingEvent(long orderId, long shipmentId, long adminUserId, TrackingEventRequest request) {
        OrderRow order = requireOrder(orderId);
        ShipmentRow shipment = requireShipment(order.id(), shipmentId);
        String eventCode = clean(request.eventCode(), 32).toLowerCase(Locale.ROOT);
        if (!TRACKING_EVENTS.contains(eventCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported tracking event");
        }
        String title = blankToNull(clean(request.eventTitle(), 255));
        if (title == null) {
            title = trackingTitle(eventCode);
        }
        LocalDateTime eventTime = request.eventTime() == null ? LocalDateTime.now() : request.eventTime();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("shipment_id", shipment.id());
        values.put("event_code", eventCode);
        values.put("event_title", title);
        values.put("location_label", blankToNull(clean(request.locationLabel(), 255)));
        values.put("event_detail", blankToNull(clean(request.eventDetail(), 4000)));
        values.put("event_time", eventTime);
        values.put("created_by_user_id", adminUserId);
        trackingInsert.execute(values);
        jdbcClient.sql(
                """
                UPDATE order_shipment
                SET status_code = :status,
                    delivered_at = CASE WHEN :status = 'delivered' THEN :eventTime ELSE delivered_at END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :shipmentId
                """
            )
            .param("status", eventCode)
            .param("eventTime", eventTime)
            .param("shipmentId", shipment.id())
            .update();
        if (eventCode.equals("delivered") && shipment.directionCode().equals("outbound")) {
            updateOrderStatus(order.id(), "delivered");
        }
        addTimeline(order.id(), "shipment_" + eventCode, title, clean(request.eventDetail(), 1000), eventCode.equals("delivered") ? "delivered" : order.statusCode(), true, "admin", null, adminUserId);
        return loadAdminOperations(order.id());
    }

    @Transactional
    public TicketRecord createTicket(long customerId, String orderNo, TicketRequest request) {
        OrderRow order = requireCustomerOrder(customerId, orderNo);
        String category = normalizeTicketCategory(request.categoryCode());
        String subject = requireText(request.subject(), "Ticket subject", 255);
        String message = requireText(request.message(), "Ticket message", 4000);
        long ticketId = ticketInsert.executeAndReturnKey(Map.of(
            "ticket_no", generateReference("TKT"),
            "order_id", order.id(),
            "customer_id", customerId,
            "category_code", category,
            "status_code", "open",
            "subject", subject
        )).longValue();
        insertTicketMessage(ticketId, "customer", customerId, null, message, request.attachmentReference());
        addTimeline(order.id(), "ticket_created", "Support ticket created", subject, order.statusCode(), true, "customer", customerId, null);
        return requireTicket(ticketId, customerId, false);
    }

    public List<TicketRecord> listCustomerTickets(long customerId, String orderNo) {
        OrderRow order = requireCustomerOrder(customerId, orderNo);
        return listTickets(order.id(), customerId, false);
    }

    @Transactional
    public TicketRecord addCustomerTicketMessage(long customerId, long ticketId, TicketMessageRequest request) {
        TicketRecord ticket = requireTicket(ticketId, customerId, false);
        if (ticket.statusCode().equals("closed")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Closed tickets cannot receive new messages");
        }
        insertTicketMessage(ticket.id(), "customer", customerId, null, requireText(request.message(), "Ticket message", 4000), request.attachmentReference());
        jdbcClient.sql("UPDATE support_ticket SET status_code = 'open', updated_at = CURRENT_TIMESTAMP WHERE id = :ticketId")
            .param("ticketId", ticket.id())
            .update();
        return requireTicket(ticket.id(), customerId, false);
    }

    @Transactional
    public TicketRecord updateTicketByAdmin(long orderId, long ticketId, long adminUserId, AdminTicketRequest request) {
        requireOrder(orderId);
        TicketRecord ticket = requireTicket(ticketId, null, true);
        if (ticket.orderId() != orderId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Support ticket not found");
        }
        String status = normalizeTicketStatus(request.statusCode());
        String message = blankToNull(clean(request.message(), 4000));
        if (message != null) {
            insertTicketMessage(ticket.id(), "admin", null, adminUserId, message, request.attachmentReference());
        }
        jdbcClient.sql(
                """
                UPDATE support_ticket
                SET status_code = :status, assigned_user_id = :adminUserId,
                    closed_at = CASE WHEN :status IN ('resolved', 'closed') THEN CURRENT_TIMESTAMP ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :ticketId
                """
            )
            .param("status", status)
            .param("adminUserId", adminUserId)
            .param("ticketId", ticket.id())
            .update();
        addTimeline(orderId, "ticket_" + status, "Support ticket " + status.replace('_', ' '), message, requireOrder(orderId).statusCode(), true, "admin", null, adminUserId);
        return requireTicket(ticket.id(), null, true);
    }

    @Transactional
    public ShippingChangeRecord requestShippingChange(long customerId, String orderNo, ShippingChangeRequest request) {
        OrderRow order = requireCustomerOrder(customerId, orderNo);
        assertShippingChangeOpen(order);
        assertNoOpenShippingChange(order);
        EffectiveShippingOption current = effectiveShippingOption(order.id());
        ShippingOption next = requireShippingOption(request.newOptionCode(), order.returnCountry());
        if (!next.currencyCode().equalsIgnoreCase(current.currencyCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shipping option currency must match the order");
        }
        if (next.optionCode().equals(current.optionCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a different return shipping option");
        }
        String reason = requireText(request.reason(), "Change reason", 2000);
        TicketRecord ticket = createTicket(customerId, orderNo, new TicketRequest(
            "shipping_change", "Return shipping option change", reason, request.attachmentReference()
        ));
        BigDecimal difference = next.priceAmount().subtract(current.priceAmount()).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_id", order.id());
        values.put("ticket_id", ticket.id());
        values.put("old_option_code", current.optionCode());
        values.put("old_option_name", current.displayName());
        values.put("old_price_amount", current.priceAmount());
        values.put("new_option_code", next.optionCode());
        values.put("new_option_name", next.displayName());
        values.put("new_price_amount", next.priceAmount());
        values.put("currency_code", next.currencyCode());
        values.put("difference_amount", difference);
        values.put("status_code", "requested");
        values.put("reason", reason);
        long requestId = shippingChangeInsert.executeAndReturnKey(values).longValue();
        addTimeline(order.id(), "shipping_change_requested", "Return shipping change requested", current.displayName() + " → " + next.displayName(), order.statusCode(), true, "customer", customerId, null);
        return requireShippingChange(order.id(), requestId);
    }

    @Transactional
    public ShippingChangeRecord reviewShippingChange(long orderId, long requestId, long adminUserId, ReviewShippingChangeRequest request) {
        OrderRow order = requireOrder(orderId);
        assertShippingChangeOpen(order);
        ShippingChangeRecord change = requireShippingChange(order.id(), requestId);
        if (!change.statusCode().equals("requested")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipping change request has already been reviewed");
        }
        boolean approved = request.approved() != null && request.approved();
        String note = requireText(request.note(), "Review note", 2000);
        Long paymentId = null;
        String status;
        if (!approved) {
            status = "rejected";
        } else if (change.differenceAmount().signum() == 0) {
            status = "settled";
        } else {
            boolean surcharge = change.differenceAmount().signum() > 0;
            Map<String, Object> paymentValues = new LinkedHashMap<>();
            paymentValues.put("order_id", order.id());
            paymentValues.put("direction_code", surcharge ? "receivable" : "payable");
            paymentValues.put("payment_type_code", surcharge ? "shipping_adjustment" : "shipping_refund");
            paymentValues.put("payment_no", generateReference(surcharge ? "ADJ" : "REF"));
            paymentValues.put("provider_code", "manual_transfer");
            paymentValues.put("status_code", surcharge ? "pending" : "refund_pending");
            paymentValues.put("amount", change.differenceAmount().abs());
            paymentValues.put("currency_code", change.currencyCode());
            paymentValues.put("related_payment_id", null);
            paymentValues.put("note", note);
            paymentId = paymentInsert.executeAndReturnKey(paymentValues).longValue();
            status = "awaiting_settlement";
        }
        jdbcClient.sql(
                """
                UPDATE shipping_change_request
                SET status_code = :status, payment_id = :paymentId, reviewed_by_user_id = :adminUserId,
                    reviewed_at = CURRENT_TIMESTAMP, settled_at = CASE WHEN :status = 'settled' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :requestId AND order_id = :orderId
                """
            )
            .param("status", status)
            .param("paymentId", paymentId)
            .param("adminUserId", adminUserId)
            .param("requestId", requestId)
            .param("orderId", order.id())
            .update();
        if (change.ticketId() != null) {
            insertTicketMessage(change.ticketId(), "admin", null, adminUserId, note, null);
        }
        addTimeline(order.id(), approved ? "shipping_change_approved" : "shipping_change_rejected", approved ? "Return shipping change approved" : "Return shipping change rejected", note, order.statusCode(), true, "admin", null, adminUserId);
        return requireShippingChange(order.id(), requestId);
    }

    @Transactional
    public ShippingChangeRecord settleShippingChange(long orderId, long requestId, long adminUserId, SettleShippingChangeRequest request) {
        OrderRow order = requireOrder(orderId);
        ShippingChangeRecord change = requireShippingChange(order.id(), requestId);
        if (!change.statusCode().equals("awaiting_settlement") || change.paymentId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipping change is not awaiting settlement");
        }
        boolean surcharge = change.differenceAmount().signum() > 0;
        String nextPaymentStatus = surcharge ? "confirmed" : "refunded";
        jdbcClient.sql(
                """
                UPDATE payment_record
                SET status_code = :status, provider_transaction_id = :transactionId,
                    confirmed_by_user_id = :adminUserId, confirmed_at = CURRENT_TIMESTAMP,
                    note = :note, updated_at = CURRENT_TIMESTAMP
                WHERE id = :paymentId
                """
            )
            .param("status", nextPaymentStatus)
            .param("transactionId", blankToNull(clean(request.providerTransactionId(), 255)))
            .param("adminUserId", adminUserId)
            .param("note", requireText(request.note(), "Settlement note", 2000))
            .param("paymentId", change.paymentId())
            .update();
        jdbcClient.sql(
                """
                UPDATE shipping_change_request
                SET status_code = 'settled', settled_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :requestId AND order_id = :orderId
                """
            )
            .param("requestId", requestId)
            .param("orderId", order.id())
            .update();
        addTimeline(order.id(), surcharge ? "shipping_surcharge_paid" : "shipping_refund_recorded", surcharge ? "Shipping surcharge paid" : "Shipping refund recorded", clean(request.note(), 1000), order.statusCode(), true, "admin", null, adminUserId);
        return requireShippingChange(order.id(), requestId);
    }

    public EffectiveShippingOption effectiveShippingOption(long orderId) {
        OrderRow order = requireOrder(orderId);
        return jdbcClient.sql(
                """
                SELECT new_option_code, new_option_name, new_price_amount, currency_code
                FROM shipping_change_request
                WHERE order_id = :orderId AND status_code = 'settled'
                ORDER BY settled_at DESC, id DESC LIMIT 1
                """
            )
            .param("orderId", order.id())
            .query((rs, rowNum) -> new EffectiveShippingOption(
                rs.getString("new_option_code"), rs.getString("new_option_name"),
                rs.getBigDecimal("new_price_amount"), rs.getString("currency_code")
            ))
            .optional()
            .orElseGet(() -> new EffectiveShippingOption(
                blankToNull(order.shippingOptionCode()) == null ? "economy_line" : order.shippingOptionCode(),
                blankToNull(order.shippingOptionName()) == null ? "Economy Line" : order.shippingOptionName(),
                order.returnShippingFee(), order.currencyCode()
            ));
    }

    public EffectiveShippingOption assertOutboundReady(long orderId) {
        OrderRow order = requireOrder(orderId);
        if (!order.statusCode().equals("completed")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Return shipment can be created after final quality check");
        }
        int pendingChanges = jdbcClient.sql(
                """
                SELECT COUNT(*) FROM shipping_change_request
                WHERE order_id = :orderId AND status_code IN ('requested', 'awaiting_settlement')
                """
            )
            .param("orderId", order.id())
            .query(Integer.class)
            .single();
        if (pendingChanges > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Settle the return shipping change before creating the return label");
        }
        return effectiveShippingOption(order.id());
    }

    @Transactional
    public void markShippingLabelCreated(long orderId) {
        jdbcClient.sql(
                "UPDATE grading_order SET shipping_label_created_at = COALESCE(shipping_label_created_at, CURRENT_TIMESTAMP) WHERE id = :orderId"
            )
            .param("orderId", orderId)
            .update();
    }

    public CustomerOperationsResponse loadCustomerOperations(long customerId, String orderNo) {
        OrderRow order = requireCustomerOrder(customerId, orderNo);
        PackingSlip packingSlip = order.intakeCode() == null ? null : requirePackingSlip(customerId, orderNo);
        return new CustomerOperationsResponse(
            packingSlip,
            loadExceptions(order.id(), true),
            loadTrackingEvents(order.id()),
            listTickets(order.id(), customerId, false),
            listShippingChanges(order.id()),
            effectiveShippingOption(order.id())
        );
    }

    public AdminOperationsResponse loadAdminOperations(long orderId) {
        OrderRow order = requireOrder(orderId);
        return new AdminOperationsResponse(
            order.id(), order.orderNo(), order.intakeCode(), order.totalCardCount(),
            loadReceipts(order.id()), loadExceptions(order.id(), false), loadWorkTasks(order.id()),
            loadTrackingEvents(order.id()), listTickets(order.id(), null, true), listShippingChanges(order.id()),
            effectiveShippingOption(order.id())
        );
    }

    public void requireMerchant(long customerId) {
        String accountType = jdbcClient.sql("SELECT account_type_code FROM customer_account WHERE id = :customerId")
            .param("customerId", customerId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer account not found"));
        if (!"merchant".equals(accountType)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant batch ordering is not enabled for this account");
        }
    }

    @Transactional
    public long createMerchantImportJob(long customerId, String sourceName, int totalRows) {
        requireMerchant(customerId);
        jdbcClient.sql(
                """
                INSERT INTO merchant_import_job (customer_id, source_name, total_rows, status_code)
                VALUES (:customerId, :sourceName, :totalRows, 'processing')
                """
            )
            .param("customerId", customerId)
            .param("sourceName", blankToNull(clean(sourceName, 255)))
            .param("totalRows", totalRows)
            .update();
        return jdbcClient.sql("SELECT id FROM merchant_import_job WHERE customer_id = :customerId ORDER BY id DESC LIMIT 1")
            .param("customerId", customerId)
            .query(Long.class)
            .single();
    }

    @Transactional
    public void recordMerchantImportRow(long jobId, int rowNo, Long orderId, String errorMessage) {
        jdbcClient.sql(
                """
                INSERT INTO merchant_import_row (import_job_id, row_no, status_code, order_id, error_message)
                VALUES (:jobId, :rowNo, :status, :orderId, :error)
                """
            )
            .param("jobId", jobId)
            .param("rowNo", rowNo)
            .param("status", errorMessage == null ? "accepted" : "rejected")
            .param("orderId", orderId)
            .param("error", blankToNull(clean(errorMessage, 1000)))
            .update();
    }

    @Transactional
    public MerchantImportResult finishMerchantImportJob(long jobId) {
        int accepted = jdbcClient.sql("SELECT COUNT(*) FROM merchant_import_row WHERE import_job_id = :jobId AND status_code = 'accepted'")
            .param("jobId", jobId).query(Integer.class).single();
        int rejected = jdbcClient.sql("SELECT COUNT(*) FROM merchant_import_row WHERE import_job_id = :jobId AND status_code = 'rejected'")
            .param("jobId", jobId).query(Integer.class).single();
        jdbcClient.sql(
                """
                UPDATE merchant_import_job
                SET accepted_rows = :accepted, rejected_rows = :rejected, status_code = 'completed', completed_at = CURRENT_TIMESTAMP
                WHERE id = :jobId
                """
            )
            .param("accepted", accepted)
            .param("rejected", rejected)
            .param("jobId", jobId)
            .update();
        List<MerchantImportRow> rows = jdbcClient.sql(
                """
                SELECT row_no, status_code, order_id, error_message
                FROM merchant_import_row WHERE import_job_id = :jobId ORDER BY row_no
                """
            )
            .param("jobId", jobId)
            .query((rs, rowNum) -> new MerchantImportRow(
                rs.getInt("row_no"), rs.getString("status_code"), rs.getObject("order_id", Long.class), rs.getString("error_message")
            ))
            .list();
        return new MerchantImportResult(jobId, accepted, rejected, rows);
    }

    private void ensureDefaultAddress(long customerId, long candidateId) {
        int defaultCount = jdbcClient.sql("SELECT COUNT(*) FROM customer_address WHERE customer_id = :customerId AND is_default = 1")
            .param("customerId", customerId)
            .query(Integer.class)
            .single();
        if (defaultCount == 0) {
            jdbcClient.sql("UPDATE customer_address SET is_default = 1 WHERE id = :addressId AND customer_id = :customerId")
                .param("addressId", candidateId)
                .param("customerId", customerId)
                .update();
        }
    }

    private void completeReceipt(long orderId, long adminUserId) {
        updateOrderStatus(orderId, "received");
        jdbcClient.sql("UPDATE grading_order_item SET status_code = 'received', updated_at = CURRENT_TIMESTAMP WHERE order_id = :orderId")
            .param("orderId", orderId)
            .update();
        ensureInitialWorkTasks(orderId);
        addTimeline(orderId, "warehouse_received", "Cards received and counted", "Warehouse intake is complete.", "received", true, "admin", null, adminUserId);
    }

    private void ensureInitialWorkTasks(long orderId) {
        List<Long> itemIds = jdbcClient.sql("SELECT id FROM grading_order_item WHERE order_id = :orderId ORDER BY item_no")
            .param("orderId", orderId)
            .query(Long.class)
            .list();
        for (Long itemId : itemIds) {
            for (String type : List.of("preprocess", "vision")) {
                int existing = jdbcClient.sql(
                        "SELECT COUNT(*) FROM order_work_task WHERE order_id = :orderId AND order_item_id = :itemId AND task_type_code = :type"
                    )
                    .param("orderId", orderId)
                    .param("itemId", itemId)
                    .param("type", type)
                    .query(Integer.class)
                    .single();
                if (existing == 0) {
                    workTaskInsert.execute(taskValues(orderId, itemId, type, null));
                }
            }
        }
    }

    public void ensureReviewAndEncapsulationTasks(long orderId, long itemId) {
        for (String type : List.of("manual_review", "encapsulation")) {
            int existing = jdbcClient.sql(
                    "SELECT COUNT(*) FROM order_work_task WHERE order_id = :orderId AND order_item_id = :itemId AND task_type_code = :type"
                )
                .param("orderId", orderId)
                .param("itemId", itemId)
                .param("type", type)
                .query(Integer.class)
                .single();
            if (existing == 0) {
                workTaskInsert.execute(taskValues(orderId, itemId, type, null));
            }
        }
    }

    private void createExceptionInternal(long orderId, long adminUserId, String type, String title, String detail, boolean visible) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_id", orderId);
        values.put("exception_type_code", type);
        values.put("status_code", "open");
        values.put("title", requireText(title, "Exception title", 255));
        values.put("detail", blankToNull(clean(detail, 4000)));
        values.put("visible_to_customer", visible ? 1 : 0);
        values.put("created_by_user_id", adminUserId);
        exceptionInsert.execute(values);
    }

    private List<IntakeReceipt> loadReceipts(long orderId) {
        return jdbcClient.sql(
                """
                SELECT id, package_no, expected_count, received_count, condition_note, received_by_user_id, received_at
                FROM order_intake_receipt WHERE order_id = :orderId ORDER BY received_at DESC, id DESC
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new IntakeReceipt(
                rs.getLong("id"), rs.getString("package_no"), rs.getInt("expected_count"), rs.getInt("received_count"),
                rs.getString("condition_note"), rs.getLong("received_by_user_id"), rs.getObject("received_at", LocalDateTime.class)
            ))
            .list();
    }

    private List<ExceptionRecord> loadExceptions(long orderId, boolean customerOnly) {
        String visibility = customerOnly ? " AND visible_to_customer = 1" : "";
        return jdbcClient.sql(
                """
                SELECT id, exception_type_code, status_code, title, detail, resolution_note,
                       visible_to_customer, created_at, resolved_at
                FROM order_exception WHERE order_id = :orderId
                """ + visibility + " ORDER BY created_at DESC, id DESC"
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new ExceptionRecord(
                rs.getLong("id"), rs.getString("exception_type_code"), rs.getString("status_code"),
                rs.getString("title"), rs.getString("detail"), rs.getString("resolution_note"),
                rs.getBoolean("visible_to_customer"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("resolved_at", LocalDateTime.class)
            ))
            .list();
    }

    private List<WorkTaskRecord> loadWorkTasks(long orderId) {
        return jdbcClient.sql(
                """
                SELECT id, order_item_id, task_type_code, status_code, attempt_count, result_summary,
                       failure_reason, assigned_user_id, started_at, completed_at, created_at, updated_at
                FROM order_work_task WHERE order_id = :orderId ORDER BY id ASC
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapWorkTask(rs))
            .list();
    }

    private WorkTaskRecord requireWorkTask(long orderId, long taskId) {
        return jdbcClient.sql(
                """
                SELECT id, order_item_id, task_type_code, status_code, attempt_count, result_summary,
                       failure_reason, assigned_user_id, started_at, completed_at, created_at, updated_at
                FROM order_work_task WHERE id = :taskId AND order_id = :orderId
                """
            )
            .param("taskId", taskId)
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapWorkTask(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Work task not found"));
    }

    private List<TrackingEvent> loadTrackingEvents(long orderId) {
        return jdbcClient.sql(
                """
                SELECT e.id, e.shipment_id, s.direction_code, e.event_code, e.event_title,
                       e.location_label, e.event_detail, e.event_time
                FROM shipment_tracking_event e
                JOIN order_shipment s ON s.id = e.shipment_id
                WHERE s.order_id = :orderId
                ORDER BY e.event_time ASC, e.id ASC
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new TrackingEvent(
                rs.getLong("id"), rs.getLong("shipment_id"), rs.getString("direction_code"),
                rs.getString("event_code"), rs.getString("event_title"), rs.getString("location_label"),
                rs.getString("event_detail"), rs.getObject("event_time", LocalDateTime.class)
            ))
            .list();
    }

    private List<TicketRecord> listTickets(long orderId, Long customerId, boolean admin) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", orderId);
        String customerFilter = "";
        if (!admin) {
            customerFilter = " AND customer_id = :customerId";
            params.put("customerId", customerId);
        }
        List<Long> ticketIds = jdbcClient.sql(
                "SELECT id FROM support_ticket WHERE order_id = :orderId" + customerFilter + " ORDER BY created_at DESC, id DESC"
            )
            .params(params)
            .query(Long.class)
            .list();
        return ticketIds.stream().map(id -> requireTicket(id, customerId, admin)).toList();
    }

    private TicketRecord requireTicket(long ticketId, Long customerId, boolean admin) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ticketId", ticketId);
        String customerFilter = "";
        if (!admin) {
            customerFilter = " AND customer_id = :customerId";
            params.put("customerId", customerId);
        }
        TicketRecord base = jdbcClient.sql(
                """
                SELECT id, ticket_no, order_id, customer_id, category_code, status_code, subject,
                       assigned_user_id, created_at, updated_at, closed_at
                FROM support_ticket WHERE id = :ticketId
                """ + customerFilter
            )
            .params(params)
            .query((rs, rowNum) -> new TicketRecord(
                rs.getLong("id"), rs.getString("ticket_no"), rs.getLong("order_id"), rs.getLong("customer_id"),
                rs.getString("category_code"), rs.getString("status_code"), rs.getString("subject"),
                rs.getObject("assigned_user_id", Long.class), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class), rs.getObject("closed_at", LocalDateTime.class), List.of()
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Support ticket not found"));
        List<TicketMessage> messages = jdbcClient.sql(
                """
                SELECT id, actor_type_code, actor_customer_id, actor_admin_user_id,
                       message_text, attachment_reference, created_at
                FROM support_ticket_message WHERE ticket_id = :ticketId ORDER BY created_at ASC, id ASC
                """
            )
            .param("ticketId", ticketId)
            .query((rs, rowNum) -> new TicketMessage(
                rs.getLong("id"), rs.getString("actor_type_code"), rs.getObject("actor_customer_id", Long.class),
                rs.getObject("actor_admin_user_id", Long.class), rs.getString("message_text"),
                rs.getString("attachment_reference"), rs.getObject("created_at", LocalDateTime.class)
            ))
            .list();
        return new TicketRecord(
            base.id(), base.ticketNo(), base.orderId(), base.customerId(), base.categoryCode(), base.statusCode(),
            base.subject(), base.assignedUserId(), base.createdAt(), base.updatedAt(), base.closedAt(), messages
        );
    }

    private void insertTicketMessage(long ticketId, String actorType, Long customerId, Long adminUserId, String message, String attachment) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ticket_id", ticketId);
        values.put("actor_type_code", actorType);
        values.put("actor_customer_id", customerId);
        values.put("actor_admin_user_id", adminUserId);
        values.put("message_text", message);
        values.put("attachment_reference", blankToNull(clean(attachment, 512)));
        ticketMessageInsert.execute(values);
    }

    private List<ShippingChangeRecord> listShippingChanges(long orderId) {
        return jdbcClient.sql(
                """
                SELECT id, ticket_id, old_option_code, old_option_name, old_price_amount,
                       new_option_code, new_option_name, new_price_amount, currency_code,
                       difference_amount, status_code, reason, payment_id, reviewed_by_user_id,
                       reviewed_at, settled_at, created_at
                FROM shipping_change_request WHERE order_id = :orderId ORDER BY created_at DESC, id DESC
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapShippingChange(rs, orderId))
            .list();
    }

    private ShippingChangeRecord requireShippingChange(long orderId, long requestId) {
        return jdbcClient.sql(
                """
                SELECT id, ticket_id, old_option_code, old_option_name, old_price_amount,
                       new_option_code, new_option_name, new_price_amount, currency_code,
                       difference_amount, status_code, reason, payment_id, reviewed_by_user_id,
                       reviewed_at, settled_at, created_at
                FROM shipping_change_request WHERE id = :requestId AND order_id = :orderId
                """
            )
            .param("requestId", requestId)
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapShippingChange(rs, orderId))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipping change request not found"));
    }

    private void assertShippingChangeOpen(OrderRow order) {
        if (order.shippingLabelCreatedAt() != null || Set.of("return_shipped", "delivered", "cancelled").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Return shipping can no longer be changed after the label is created");
        }
    }

    private void assertNoOpenShippingChange(OrderRow order) {
        int pending = jdbcClient.sql(
                "SELECT COUNT(*) FROM shipping_change_request WHERE order_id = :orderId AND status_code IN ('requested', 'awaiting_settlement')"
            )
            .param("orderId", order.id())
            .query(Integer.class)
            .single();
        if (pending > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Another return shipping change is still open");
        }
    }

    private OrderRow requireCustomerOrder(long customerId, String orderNo) {
        String normalizedOrderNo = requireText(orderNo, "Order number", 40).toUpperCase(Locale.ROOT);
        return jdbcClient.sql(orderRowSelect() + " WHERE order_no = :orderNo AND customer_id = :customerId")
            .param("orderNo", normalizedOrderNo)
            .param("customerId", customerId)
            .query((rs, rowNum) -> mapOrderRow(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    private OrderRow requireOrder(long orderId) {
        return jdbcClient.sql(orderRowSelect() + " WHERE id = :orderId")
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapOrderRow(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    private ShipmentRow requireShipment(long orderId, long shipmentId) {
        return jdbcClient.sql(
                "SELECT id, order_id, direction_code, status_code FROM order_shipment WHERE id = :shipmentId AND order_id = :orderId"
            )
            .param("shipmentId", shipmentId)
            .param("orderId", orderId)
            .query((rs, rowNum) -> new ShipmentRow(
                rs.getLong("id"), rs.getLong("order_id"), rs.getString("direction_code"), rs.getString("status_code")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment record not found"));
    }

    private void updateOrderStatus(long orderId, String status) {
        jdbcClient.sql("UPDATE grading_order SET status_code = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :orderId")
            .param("status", status)
            .param("orderId", orderId)
            .update();
    }

    private void addTimeline(
        long orderId,
        String eventCode,
        String title,
        String detail,
        String statusCode,
        boolean visible,
        String actorType,
        Long customerId,
        Long adminUserId
    ) {
        jdbcClient.sql(
                """
                INSERT INTO order_timeline_event
                    (order_id, event_code, title, detail, status_code, visible_to_customer,
                     actor_type_code, actor_customer_id, actor_admin_user_id)
                VALUES (:orderId, :eventCode, :title, :detail, :statusCode, :visible,
                        :actorType, :customerId, :adminUserId)
                """
            )
            .param("orderId", orderId)
            .param("eventCode", eventCode)
            .param("title", title)
            .param("detail", blankToNull(clean(detail, 4000)))
            .param("statusCode", statusCode)
            .param("visible", visible ? 1 : 0)
            .param("actorType", actorType)
            .param("customerId", customerId)
            .param("adminUserId", adminUserId)
            .update();
    }

    private static String orderRowSelect() {
        return """
            SELECT id, order_no, customer_id, status_code, total_card_count, currency_code,
                   return_country, return_shipping_option_code, return_shipping_option_name,
                   return_shipping_fee, intake_code, packing_slip_code, shipping_label_created_at
            FROM grading_order
            """;
    }

    private static OrderRow mapOrderRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRow(
            rs.getLong("id"), rs.getString("order_no"), rs.getLong("customer_id"), rs.getString("status_code"),
            rs.getInt("total_card_count"), rs.getString("currency_code"), rs.getString("return_country"),
            rs.getString("return_shipping_option_code"), rs.getString("return_shipping_option_name"),
            rs.getBigDecimal("return_shipping_fee"), rs.getString("intake_code"), rs.getString("packing_slip_code"),
            rs.getObject("shipping_label_created_at", LocalDateTime.class)
        );
    }

    private static CustomerAddress mapAddress(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CustomerAddress(
            rs.getLong("id"), rs.getLong("customer_id"), rs.getString("label"), rs.getString("contact_name"),
            rs.getString("contact_phone"), rs.getString("address_line1"), rs.getString("address_line2"),
            rs.getString("city"), rs.getString("region"), rs.getString("postal_code"), rs.getString("country"),
            rs.getBoolean("is_default"), rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private static ShippingOption mapShippingOption(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ShippingOption(
            rs.getLong("id"), rs.getString("option_code"), rs.getString("display_name"), rs.getString("description"),
            rs.getString("country_scope"), rs.getString("currency_code"), rs.getBigDecimal("price_amount"),
            rs.getInt("sort_order"), rs.getBoolean("is_active"), rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private static WorkTaskRecord mapWorkTask(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkTaskRecord(
            rs.getLong("id"), rs.getObject("order_item_id", Long.class), rs.getString("task_type_code"),
            rs.getString("status_code"), rs.getInt("attempt_count"), rs.getString("result_summary"),
            rs.getString("failure_reason"), rs.getObject("assigned_user_id", Long.class),
            rs.getObject("started_at", LocalDateTime.class), rs.getObject("completed_at", LocalDateTime.class),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private static ShippingChangeRecord mapShippingChange(java.sql.ResultSet rs, long orderId) throws java.sql.SQLException {
        return new ShippingChangeRecord(
            rs.getLong("id"), orderId, rs.getObject("ticket_id", Long.class), rs.getString("old_option_code"),
            rs.getString("old_option_name"), rs.getBigDecimal("old_price_amount"), rs.getString("new_option_code"),
            rs.getString("new_option_name"), rs.getBigDecimal("new_price_amount"), rs.getString("currency_code"),
            rs.getBigDecimal("difference_amount"), rs.getString("status_code"), rs.getString("reason"),
            rs.getObject("payment_id", Long.class), rs.getObject("reviewed_by_user_id", Long.class),
            rs.getObject("reviewed_at", LocalDateTime.class), rs.getObject("settled_at", LocalDateTime.class),
            rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private static AddressValues normalizeAddress(AddressRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Return address is required");
        }
        return new AddressValues(
            blankToDefault(clean(request.label(), 64), "Return address"),
            requireText(request.contactName(), "Contact name", 128),
            requireText(request.contactPhone(), "Contact phone", 64),
            requireText(request.addressLine1(), "Address line 1", 255),
            blankToNull(clean(request.addressLine2(), 255)),
            requireText(request.city(), "City", 128),
            blankToNull(clean(request.region(), 128)),
            requireText(request.postalCode(), "Postal code", 64),
            requireText(request.country(), "Country", 128),
            request.defaultAddress() != null && request.defaultAddress()
        );
    }

    private static void putAddressValues(Map<String, Object> values, AddressValues address) {
        values.put("label", address.label());
        values.put("contact_name", address.contactName());
        values.put("contact_phone", address.contactPhone());
        values.put("address_line1", address.addressLine1());
        values.put("address_line2", address.addressLine2());
        values.put("city", address.city());
        values.put("region", address.region());
        values.put("postal_code", address.postalCode());
        values.put("country", address.country());
        values.put("is_default", address.isDefault() ? 1 : 0);
    }

    private static Map<String, Object> taskValues(long orderId, Long itemId, String type, Long adminUserId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_id", orderId);
        values.put("order_item_id", itemId);
        values.put("task_type_code", type);
        values.put("status_code", "pending");
        values.put("attempt_count", 0);
        values.put("assigned_user_id", adminUserId);
        return values;
    }

    private static boolean appliesToCountry(String scope, String normalizedCountry) {
        if (scope == null || scope.isBlank() || scope.trim().equals("*")) {
            return true;
        }
        if (normalizedCountry.isBlank()) {
            return false;
        }
        return List.of(scope.toUpperCase(Locale.ROOT).split(","))
            .stream().map(String::trim).anyMatch(normalizedCountry::equals);
    }

    private static String normalizeCountryScope(String value) {
        String scope = clean(value, 1000).toUpperCase(Locale.ROOT);
        if (scope.isBlank() || scope.equals("*")) {
            return "*";
        }
        return String.join(",", List.of(scope.split(",")).stream().map(String::trim).filter(item -> !item.isBlank()).distinct().toList());
    }

    private static String normalizeExceptionType(String value) {
        String type = clean(value, 32).toLowerCase(Locale.ROOT);
        if (!EXCEPTION_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported intake exception type");
        }
        return type;
    }

    private static String normalizeWorkTaskType(String value) {
        String type = clean(value, 32).toLowerCase(Locale.ROOT);
        if (!WORK_TASK_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported work task type");
        }
        return type;
    }

    private static String normalizeWorkTaskStatus(String value) {
        String status = clean(value, 32).toLowerCase(Locale.ROOT);
        if (!WORK_TASK_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported work task status");
        }
        return status;
    }

    private static String normalizeTicketCategory(String value) {
        String category = clean(value, 32).toLowerCase(Locale.ROOT);
        if (!TICKET_CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported support ticket category");
        }
        return category;
    }

    private static String normalizeTicketStatus(String value) {
        String status = clean(value, 32).toLowerCase(Locale.ROOT);
        if (!TICKET_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported support ticket status");
        }
        return status;
    }

    private static String normalizeCode(String value, String label) {
        String code = requireText(value, label, 32).toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!code.matches("[a-z0-9_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " contains unsupported characters");
        }
        return code;
    }

    private static String normalizeCurrency(String value) {
        String currency = requireText(value, "Currency", 8).toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Currency must be a three-letter code");
        }
        return currency;
    }

    private static BigDecimal normalizeMoney(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("1000000")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must be between 0 and 1000000");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String exceptionTitle(String type) {
        return switch (type) {
            case "shortage" -> "Card count shortage";
            case "overage" -> "Extra cards received";
            case "damaged" -> "Package or card damage";
            case "missing_barcode" -> "Packing slip or barcode missing";
            default -> "Warehouse intake exception";
        };
    }

    private static String workTaskTitle(String type) {
        return switch (type) {
            case "preprocess" -> "Card preprocessing";
            case "vision" -> "Machine vision check";
            case "manual_review" -> "Manual grading review";
            case "encapsulation" -> "Encapsulation";
            case "quality_check" -> "Final quality check";
            default -> "Work task";
        };
    }

    private static String trackingTitle(String code) {
        return switch (code) {
            case "label_created" -> "Shipping label created";
            case "picked_up" -> "Shipment picked up";
            case "in_transit" -> "Shipment in transit";
            case "customs" -> "Shipment at customs";
            case "exception" -> "Shipment exception";
            case "out_for_delivery" -> "Out for delivery";
            case "delivered" -> "Shipment delivered";
            default -> "Shipment updated";
        };
    }

    private static String generateReference(String prefix) {
        return prefix + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss")) + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
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
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.substring(0, Math.min(normalized.length(), maxLength));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record AddressRequest(
        String label,
        String contactName,
        String contactPhone,
        String addressLine1,
        String addressLine2,
        String city,
        String region,
        String postalCode,
        String country,
        Boolean defaultAddress
    ) {
    }

    public record CustomerAddress(
        long id,
        long customerId,
        String label,
        String contactName,
        String contactPhone,
        String addressLine1,
        String addressLine2,
        String city,
        String region,
        String postalCode,
        String country,
        boolean defaultAddress,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }

    public record ShippingOptionRequest(
        String optionCode,
        String displayName,
        String description,
        String countryScope,
        String currencyCode,
        BigDecimal priceAmount,
        Integer sortOrder,
        Boolean active
    ) {
    }

    public record ShippingOption(
        long id,
        String optionCode,
        String displayName,
        String description,
        String countryScope,
        String currencyCode,
        BigDecimal priceAmount,
        int sortOrder,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }

    public record ServicePrice(String priceCode, String displayName, BigDecimal unitPrice, String currencyCode, int versionNo) {
    }

    public record ServicePriceRequest(String displayName, BigDecimal unitPrice, String currencyCode) {
    }

    public record PackingSlip(
        String orderNo,
        String intakeCode,
        String packingSlipCode,
        String qrPayload,
        int totalCardCount,
        List<LanguageQuantity> languageGroups,
        List<String> packingInstructions
    ) {
    }

    public record LanguageQuantity(String languageCode, int quantity) {
    }

    public record IntakeLookup(long orderId, String orderNo, String statusCode, int expectedCardCount, String intakeCode) {
    }

    public record ReceiveOrderRequest(
        String intakeCode,
        String packageNo,
        Integer receivedCount,
        String conditionNote,
        List<String> exceptionTypes
    ) {
    }

    public record OrderExceptionRequest(String exceptionTypeCode, String title, String detail, Boolean visibleToCustomer) {
    }

    public record ResolveExceptionRequest(String resolutionNote) {
    }

    public record WorkTaskRequest(Long orderItemId, String taskTypeCode) {
    }

    public record WorkTaskUpdateRequest(String statusCode, String resultSummary, String failureReason) {
    }

    public record QualityCheckRequest(Boolean passed, String note) {
    }

    public record TrackingEventRequest(
        String eventCode,
        String eventTitle,
        String locationLabel,
        String eventDetail,
        LocalDateTime eventTime
    ) {
    }

    public record TicketRequest(String categoryCode, String subject, String message, String attachmentReference) {
    }

    public record TicketMessageRequest(String message, String attachmentReference) {
    }

    public record AdminTicketRequest(String statusCode, String message, String attachmentReference) {
    }

    public record ShippingChangeRequest(String newOptionCode, String reason, String attachmentReference) {
    }

    public record ReviewShippingChangeRequest(Boolean approved, String note) {
    }

    public record SettleShippingChangeRequest(String providerTransactionId, String note) {
    }

    public record IntakeReceipt(
        long id,
        String packageNo,
        int expectedCount,
        int receivedCount,
        String conditionNote,
        long receivedByUserId,
        LocalDateTime receivedAt
    ) {
    }

    public record ExceptionRecord(
        long id,
        String exceptionTypeCode,
        String statusCode,
        String title,
        String detail,
        String resolutionNote,
        boolean visibleToCustomer,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
    ) {
    }

    public record WorkTaskRecord(
        long id,
        Long orderItemId,
        String taskTypeCode,
        String statusCode,
        int attemptCount,
        String resultSummary,
        String failureReason,
        Long assignedUserId,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
    }

    public record TrackingEvent(
        long id,
        long shipmentId,
        String directionCode,
        String eventCode,
        String eventTitle,
        String locationLabel,
        String eventDetail,
        LocalDateTime eventTime
    ) {
    }

    public record TicketMessage(
        long id,
        String actorTypeCode,
        Long actorCustomerId,
        Long actorAdminUserId,
        String message,
        String attachmentReference,
        LocalDateTime createdAt
    ) {
    }

    public record TicketRecord(
        long id,
        String ticketNo,
        long orderId,
        long customerId,
        String categoryCode,
        String statusCode,
        String subject,
        Long assignedUserId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime closedAt,
        List<TicketMessage> messages
    ) {
    }

    public record ShippingChangeRecord(
        long id,
        long orderId,
        Long ticketId,
        String oldOptionCode,
        String oldOptionName,
        BigDecimal oldPriceAmount,
        String newOptionCode,
        String newOptionName,
        BigDecimal newPriceAmount,
        String currencyCode,
        BigDecimal differenceAmount,
        String statusCode,
        String reason,
        Long paymentId,
        Long reviewedByUserId,
        LocalDateTime reviewedAt,
        LocalDateTime settledAt,
        LocalDateTime createdAt
    ) {
    }

    public record EffectiveShippingOption(String optionCode, String displayName, BigDecimal priceAmount, String currencyCode) {
    }

    public record CustomerOperationsResponse(
        PackingSlip packingSlip,
        List<ExceptionRecord> exceptions,
        List<TrackingEvent> trackingEvents,
        List<TicketRecord> tickets,
        List<ShippingChangeRecord> shippingChanges,
        EffectiveShippingOption effectiveShippingOption
    ) {
    }

    public record AdminOperationsResponse(
        long orderId,
        String orderNo,
        String intakeCode,
        int expectedCardCount,
        List<IntakeReceipt> receipts,
        List<ExceptionRecord> exceptions,
        List<WorkTaskRecord> workTasks,
        List<TrackingEvent> trackingEvents,
        List<TicketRecord> tickets,
        List<ShippingChangeRecord> shippingChanges,
        EffectiveShippingOption effectiveShippingOption
    ) {
    }

    public record MerchantImportRow(int rowNo, String statusCode, Long orderId, String errorMessage) {
    }

    public record MerchantImportResult(long jobId, int acceptedRows, int rejectedRows, List<MerchantImportRow> rows) {
    }

    private record AddressValues(
        String label,
        String contactName,
        String contactPhone,
        String addressLine1,
        String addressLine2,
        String city,
        String region,
        String postalCode,
        String country,
        boolean isDefault
    ) {
    }

    private record OrderRow(
        long id,
        String orderNo,
        long customerId,
        String statusCode,
        int totalCardCount,
        String currencyCode,
        String returnCountry,
        String shippingOptionCode,
        String shippingOptionName,
        BigDecimal returnShippingFee,
        String intakeCode,
        String packingSlipCode,
        LocalDateTime shippingLabelCreatedAt
    ) {
    }

    private record ShipmentRow(long id, long orderId, String directionCode, String statusCode) {
    }
}
