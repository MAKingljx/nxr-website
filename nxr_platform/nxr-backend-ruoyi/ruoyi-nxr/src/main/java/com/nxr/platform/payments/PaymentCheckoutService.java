package com.nxr.platform.payments;

import com.nxr.platform.admission.OrderAdmissionService;
import com.nxr.platform.customer.CustomerPortalService;
import com.nxr.platform.customer.CustomerPortalService.OrderDetailResponse;
import com.nxr.platform.customer.CustomerPortalService.PaymentCallbackRequest;
import com.nxr.platform.payments.PaymentModels.CaptureResult;
import com.nxr.platform.payments.PaymentModels.CheckoutContext;
import com.nxr.platform.payments.PaymentModels.CheckoutRequest;
import com.nxr.platform.payments.PaymentModels.CheckoutResponse;
import com.nxr.platform.payments.PaymentModels.ProviderCheckout;
import com.nxr.platform.payments.PaymentModels.ProviderContext;
import com.nxr.platform.payments.PaymentModels.VerifiedPayment;
import com.nxr.platform.payments.PaymentModels.WebhookRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates short database reservations around network calls; no provider HTTP runs while an order row is locked. */
@Service
class PaymentCheckoutService {

    private static final Set<String> PAYABLE_ORDER_STATES = Set.of("awaiting_payment", "payment_review");
    private static final Set<String> REUSABLE_ATTEMPT_STATES = Set.of("created", "approved", "pending", "payer_action_required");
    private static final Set<String> CAPTURABLE_ATTEMPT_STATES = Set.of("created", "approved", "pending", "payer_action_required", "capture_unknown");

    private final JdbcClient jdbcClient;
    private final PaymentConfigurationService configurations;
    private final PaymentAdapterRegistry adapters;
    private final CustomerPortalService customerPortalService;
    private final TransactionTemplate transactions;
    private OrderAdmissionService orderAdmissionService;

    PaymentCheckoutService(
        JdbcClient jdbcClient,
        PaymentConfigurationService configurations,
        PaymentAdapterRegistry adapters,
        CustomerPortalService customerPortalService,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.configurations = configurations;
        this.adapters = adapters;
        this.customerPortalService = customerPortalService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Autowired(required = false)
    void setOrderAdmissionService(OrderAdmissionService orderAdmissionService) {
        this.orderAdmissionService = orderAdmissionService;
    }

    CheckoutResponse createCheckout(long customerId, String rawOrderNo, CheckoutRequest request) {
        if (request == null) {
            throw PaymentValidationException.badRequest("Checkout request is required");
        }
        String provider = PaymentProviderSpec.require(request.provider()).code();
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        OrderDetailResponse order = customerPortalService.requireCustomerOrder(customerId, rawOrderNo);
        requirePayableSnapshot(order);
        // Reject admission or existing-payment conflicts before consulting channel
        // availability. The reservation below repeats this check under the same
        // order/payment locks that protect attempt creation.
        transactions.executeWithoutResult(ignored -> {
            LockedOrderPayment locked = lockPayableOrderPayment(order.id(), customerId);
            requireAdmissionPaymentAllowed(locked.orderId(), customerId);
        });
        ProviderContext providerContext = configurations.requireEnabled(provider, order.currencyCode());

        Reservation reservation = transactions.execute(status -> reserveCheckout(
            customerId, order, provider, idempotencyKey, providerContext
        ));
        if (reservation == null) {
            throw PaymentValidationException.conflict("Checkout reservation failed");
        }
        if (reservation.existingResponse() != null) {
            return reservation.existingResponse();
        }

        ProviderCheckout created;
        try {
            created = adapters.require(provider).createCheckout(reservation.context());
        } catch (RuntimeException exception) {
            markAttemptUnknown(reservation.attempt().id(), "creation_unknown", null);
            throw exception;
        }
        Boolean finalized;
        try {
            finalized = transactions.execute(status -> finalizeCreatedCheckout(reservation.attempt(), created));
        } catch (RuntimeException exception) {
            markAttemptUnknown(reservation.attempt().id(), "creation_unknown", created);
            throw exception;
        }
        if (!Boolean.TRUE.equals(finalized)) {
            throw PaymentValidationException.conflict("Order changed while checkout was being created; payment requires reconciliation");
        }
        return new CheckoutResponse(
            provider, created.paymentUrl(), created.qrPayload(), normalizeProviderStatus(created.status()), created.providerOrderId()
        );
    }

    CheckoutResponse capturePayPal(long customerId, String rawOrderNo, String rawProviderOrderId) {
        String providerOrderId = normalizeRemoteId(rawProviderOrderId);
        OrderDetailResponse order = customerPortalService.requireCustomerOrder(customerId, rawOrderNo);
        Attempt snapshot = findByProviderOrderId(PaymentProviderSpec.PAYPAL.code(), providerOrderId)
            .filter(value -> value.customerId() == customerId && value.orderId() == order.id())
            .orElseThrow(() -> PaymentValidationException.badRequest("PayPal checkout does not belong to this order"));
        if ("paid".equals(snapshot.status())) {
            return snapshot.toResponse();
        }
        ProviderContext provider = configurations.requireEnabled(PaymentProviderSpec.PAYPAL.code(), snapshot.currency());
        Attempt reserved = transactions.execute(status -> reserveCapture(snapshot.id(), customerId, order.id()));
        if (reserved == null) {
            throw PaymentValidationException.conflict("PayPal capture reservation failed");
        }
        if ("paid".equals(reserved.status())) {
            return reserved.toResponse();
        }
        CheckoutContext context = new CheckoutContext(
            reserved.orderNo(), reserved.paymentRecordId(), reserved.amount(), reserved.currency(), reserved.idempotencyKey(), provider
        );
        CaptureResult captured;
        try {
            captured = adapters.require(PaymentProviderSpec.PAYPAL.code()).capture(context, providerOrderId);
        } catch (RuntimeException exception) {
            markAttemptUnknown(reserved.id(), "capture_unknown", null);
            throw exception;
        }
        settleVerifiedPayment(PaymentProviderSpec.PAYPAL.code(), captured.payment());
        return findByProviderOrderId(PaymentProviderSpec.PAYPAL.code(), providerOrderId).orElseThrow().toResponse();
    }

    String processWebhook(String providerCode, WebhookRequest request) {
        PaymentProviderSpec spec = PaymentProviderSpec.require(providerCode);
        ProviderContext context = configurations.requireForWebhook(spec.code());
        VerifiedPayment payment = adapters.require(spec.code()).verifyWebhook(context, request);
        settleVerifiedPayment(spec.code(), payment);
        return spec == PaymentProviderSpec.ALIPAY ? "success" : "accepted";
    }

    private Reservation reserveCheckout(
        long customerId, OrderDetailResponse orderSnapshot, String provider,
        String idempotencyKey, ProviderContext providerContext
    ) {
        LockedOrderPayment locked = lockPayableOrderPayment(orderSnapshot.id(), customerId);
        requireAdmissionPaymentAllowed(locked.orderId(), customerId);
        if (!locked.currency().equalsIgnoreCase(orderSnapshot.currencyCode())) {
            throw PaymentValidationException.conflict("Order currency changed while checkout was being reserved");
        }
        Attempt idempotent = findByIdempotency(customerId, idempotencyKey).orElse(null);
        if (idempotent != null) {
            requireSameCheckout(idempotent, locked, provider);
            if (REUSABLE_ATTEMPT_STATES.contains(idempotent.status()) && hasRemoteOrder(idempotent)) {
                return new Reservation(idempotent, null, idempotent.toResponse());
            }
            throw PaymentValidationException.conflict("The identical checkout is still pending reconciliation");
        }
        Attempt active = findActiveByOrder(locked.orderId()).orElse(null);
        if (active != null) {
            if (!active.provider().equals(provider)) {
                throw PaymentValidationException.conflict(
                    "This order already has an active checkout with " + active.provider() + "; reconcile it before switching providers"
                );
            }
            if (REUSABLE_ATTEMPT_STATES.contains(active.status()) && hasRemoteOrder(active)) {
                return new Reservation(active, null, active.toResponse());
            }
            throw PaymentValidationException.conflict("The active checkout is pending reconciliation");
        }
        try {
            jdbcClient.sql(
                    """
                    INSERT INTO payment_attempt
                        (payment_record_id, order_id, active_order_id, customer_id, provider_code, idempotency_key,
                         merchant_order_no, expected_amount, expected_currency, status_code)
                    VALUES
                        (:paymentId, :orderId, :orderId, :customerId, :provider, :idempotencyKey,
                         :orderNo, :amount, :currency, 'creating')
                    """
                )
                .param("paymentId", locked.paymentRecordId()).param("orderId", locked.orderId()).param("customerId", customerId)
                .param("provider", provider).param("idempotencyKey", idempotencyKey).param("orderNo", locked.orderNo())
                .param("amount", locked.amount()).param("currency", locked.currency())
                .update();
        } catch (DataIntegrityViolationException exception) {
            throw PaymentValidationException.conflict("Another checkout was reserved for this order");
        }
        Attempt attempt = findByIdempotency(customerId, idempotencyKey)
            .orElseThrow(() -> PaymentValidationException.conflict("Checkout reservation was not persisted"));
        CheckoutContext context = new CheckoutContext(
            locked.orderNo(), locked.paymentRecordId(), attempt.amount(), attempt.currency(), idempotencyKey, providerContext
        );
        return new Reservation(attempt, context, null);
    }

    private boolean finalizeCreatedCheckout(Attempt reservation, ProviderCheckout created) {
        String orderState = lockOrderState(reservation.orderId(), reservation.customerId());
        String finalStatus = PAYABLE_ORDER_STATES.contains(orderState)
            ? normalizeProviderStatus(created.status()) : "requires_review";
        int updated = jdbcClient.sql(
                """
                UPDATE payment_attempt
                SET provider_order_id = :providerOrderId, payment_url = :paymentUrl,
                    qr_payload = :qrPayload, status_code = :status,
                    active_order_id = CASE WHEN :status = 'failed' THEN NULL ELSE active_order_id END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId AND status_code = 'creating'
                """
            )
            .param("providerOrderId", created.providerOrderId()).param("paymentUrl", created.paymentUrl())
            .param("qrPayload", created.qrPayload()).param("status", finalStatus).param("attemptId", reservation.id()).update();
        if (updated != 1) {
            throw PaymentValidationException.conflict("Checkout reservation changed before provider response was saved");
        }
        if (!PAYABLE_ORDER_STATES.contains(orderState)) {
            return false;
        }
        jdbcClient.sql(
                """
                UPDATE payment_record
                SET provider_code = :provider, payment_url = :paymentUrl, qr_payload = :qrPayload,
                    status_code = CASE WHEN status_code IN ('rejected', 'failed') THEN 'pending' ELSE status_code END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :paymentId
                """
            )
            .param("provider", reservation.provider()).param("paymentUrl", created.paymentUrl())
            .param("qrPayload", created.qrPayload()).param("paymentId", reservation.paymentRecordId()).update();
        return true;
    }

    private Attempt reserveCapture(long attemptId, long customerId, long orderId) {
        lockPayableOrder(orderId, customerId);
        requireAdmissionPaymentAllowed(orderId, customerId);
        Attempt current = findById(attemptId, true)
            .orElseThrow(() -> PaymentValidationException.badRequest("PayPal checkout is missing"));
        if ("paid".equals(current.status())) {
            return current;
        }
        if (!CAPTURABLE_ATTEMPT_STATES.contains(current.status())) {
            throw PaymentValidationException.conflict("PayPal checkout is not ready to capture");
        }
        int updated = jdbcClient.sql(
                "UPDATE payment_attempt SET status_code = 'capturing', updated_at = CURRENT_TIMESTAMP WHERE id = :id AND status_code = :status"
            )
            .param("id", current.id()).param("status", current.status()).update();
        if (updated != 1) {
            throw PaymentValidationException.conflict("PayPal capture is already in progress");
        }
        return findById(attemptId, false).orElseThrow();
    }

    private void markAttemptUnknown(long attemptId, String unknownStatus, ProviderCheckout created) {
        try {
            transactions.executeWithoutResult(ignored -> {
                if (created == null) {
                    jdbcClient.sql(
                            "UPDATE payment_attempt SET status_code = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND status_code IN ('creating','capturing')"
                        )
                        .param("status", unknownStatus).param("id", attemptId).update();
                } else {
                    jdbcClient.sql(
                            """
                            UPDATE payment_attempt SET status_code = :status, provider_order_id = :providerOrderId,
                                payment_url = :paymentUrl, qr_payload = :qrPayload, updated_at = CURRENT_TIMESTAMP
                            WHERE id = :id AND status_code = 'creating'
                            """
                        )
                        .param("status", unknownStatus).param("providerOrderId", created.providerOrderId())
                        .param("paymentUrl", created.paymentUrl()).param("qrPayload", created.qrPayload()).param("id", attemptId).update();
                }
            });
        } catch (RuntimeException ignored) {
            // Keep the provider-facing exception. Persisted active-order uniqueness still blocks unsafe duplicate checkout.
        }
    }

    private void settleVerifiedPayment(String provider, VerifiedPayment verified) {
        // Discover the owner before opening the settlement transaction. Otherwise
        // this ordinary lookup creates a MySQL RR snapshot that nested
        // CustomerPortalService reads could keep using after waiting for the order lock.
        Attempt candidate = findByProviderOrderId(provider, verified.providerOrderId())
            .or(() -> findByProviderTransactionId(provider, verified.providerOrderId(), false))
            .orElseThrow(() -> PaymentValidationException.badRequest("Verified provider payment is not linked to an NXR checkout"));
        transactions.executeWithoutResult(ignored -> settleVerifiedPaymentLocked(provider, verified, candidate));
    }

    private void settleVerifiedPaymentLocked(String provider, VerifiedPayment verified, Attempt candidate) {
        String status = normalizeProviderStatus(verified.status());
        // Lock order first, then re-read attempt/payment using a current locking
        // read. This is the same order used by wallet and manual settlement.
        lockOrderState(candidate.orderId(), candidate.customerId());
        Attempt attempt = findById(candidate.id(), true)
            .filter(value -> value.provider().equals(provider) && matchesProviderReference(value, verified.providerOrderId()))
            .orElseThrow(() -> PaymentValidationException.badRequest("Verified provider payment is no longer linked to an NXR checkout"));
        validateVerifiedPayment(provider, attempt, verified, status);
        if (Set.of("refunded", "reversed").contains(attempt.status())
            && !Set.of("refunded", "reversed").contains(status)) {
            recordIgnoredTerminalEvent(provider, attempt, verified);
            return;
        }
        if (Set.of("refunded", "reversed").contains(status)) {
            recordFinanceException(provider, attempt, verified, status);
            return;
        }
        customerPortalService.receivePaymentCallback(provider, new PaymentCallbackRequest(
            verified.eventId(), attempt.paymentNo(), verified.transactionId(), attempt.amount(), attempt.currency(), status,
            verified.rawPayload()
        ));
        jdbcClient.sql(
                """
                UPDATE payment_attempt
                SET provider_transaction_id = :transactionId,
                    status_code = CASE WHEN status_code = 'paid' THEN 'paid' ELSE :status END,
                    active_order_id = CASE WHEN status_code = 'paid' OR :status IN ('paid', 'failed') THEN NULL ELSE active_order_id END,
                    verified_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :attemptId
                """
            )
            .param("transactionId", verified.transactionId()).param("status", status).param("attemptId", attempt.id()).update();
    }

    private void recordFinanceException(String provider, Attempt attempt, VerifiedPayment verified, String status) {
        try {
            jdbcClient.sql(
                    """
                    INSERT INTO payment_callback_event (provider_code, provider_event_id, payment_id, payload)
                    VALUES (:provider, :eventId, :paymentId, :payload)
                    """
                )
                .param("provider", provider).param("eventId", verified.eventId()).param("paymentId", attempt.paymentRecordId())
                .param("payload", verified.rawPayload()).update();
        } catch (DataIntegrityViolationException duplicate) {
            return;
        }
        jdbcClient.sql(
                """
                INSERT INTO payment_finance_exception
                    (order_id, payment_record_id, payment_attempt_id, provider_code, provider_event_id,
                     provider_transaction_id, exception_type_code, amount, currency_code)
                VALUES
                    (:orderId, :paymentId, :attemptId, :provider, :eventId,
                     :transactionId, :exceptionType, :amount, :currency)
                """
            )
            .param("orderId", attempt.orderId()).param("paymentId", attempt.paymentRecordId()).param("attemptId", attempt.id())
            .param("provider", provider).param("eventId", verified.eventId()).param("transactionId", verified.transactionId())
            .param("exceptionType", status).param("amount", normalizedMoney(verified.amount())).param("currency", attempt.currency()).update();
        jdbcClient.sql(
                """
                UPDATE payment_record
                SET status_code = :status, callback_received_at = CURRENT_TIMESTAMP,
                    callback_payload = :payload, updated_at = CURRENT_TIMESTAMP
                WHERE id = :paymentId
                """
            )
            .param("status", status)
            .param("payload", verified.rawPayload()).param("paymentId", attempt.paymentRecordId()).update();
        jdbcClient.sql(
                "UPDATE payment_attempt SET status_code = :status, active_order_id = NULL, verified_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = :id"
            )
            .param("status", status).param("id", attempt.id()).update();
        jdbcClient.sql("UPDATE grading_order SET status_code = 'payment_exception', updated_at = CURRENT_TIMESTAMP WHERE id = :orderId")
            .param("orderId", attempt.orderId()).update();
        jdbcClient.sql(
                """
                INSERT INTO order_timeline_event
                    (order_id, event_code, title, detail, status_code, visible_to_customer, actor_type_code)
                VALUES
                    (:orderId, 'payment_exception', 'Payment needs review',
                     'The payment provider reported a refund or reversal. Fulfillment is paused for financial review.',
                     'payment_exception', 1, 'payment_callback')
                """
            )
            .param("orderId", attempt.orderId()).update();
    }

    private void recordIgnoredTerminalEvent(String provider, Attempt attempt, VerifiedPayment verified) {
        try {
            jdbcClient.sql(
                    """
                    INSERT INTO payment_callback_event (provider_code, provider_event_id, payment_id, payload)
                    VALUES (:provider, :eventId, :paymentId, :payload)
                    """
                )
                .param("provider", provider).param("eventId", verified.eventId()).param("paymentId", attempt.paymentRecordId())
                .param("payload", verified.rawPayload()).update();
        } catch (DataIntegrityViolationException duplicate) {
            // Idempotent replay: terminal refund/reversal state remains unchanged.
        }
    }

    private static void validateVerifiedPayment(String provider, Attempt attempt, VerifiedPayment verified, String status) {
        validateVerifiedAmount(attempt.amount(), verified.amount(), status);
        if (!attempt.currency().equalsIgnoreCase(normalizeCurrency(verified.currency()))) {
            throw PaymentValidationException.badRequest("Verified payment currency does not match the order");
        }
        if (provider.equals(PaymentProviderSpec.PAYPAL.code())) {
            if (!verified.merchantOrderNo().isBlank() && !attempt.orderNo().equals(verified.merchantOrderNo())) {
                throw PaymentValidationException.badRequest("Verified PayPal order reference does not match");
            }
        } else if (!attempt.providerOrderId().equals(verified.merchantOrderNo())) {
            throw PaymentValidationException.badRequest("Verified merchant order reference does not match");
        }
    }

    static void validateVerifiedAmount(BigDecimal expected, BigDecimal actual, String status) {
        BigDecimal normalizedExpected = normalizedMoney(expected);
        BigDecimal normalizedActual = normalizedMoney(actual);
        if (Set.of("refunded", "reversed").contains(status)) {
            if (normalizedActual.compareTo(normalizedExpected) > 0) {
                throw PaymentValidationException.badRequest("Refund or reversal amount exceeds the original payment");
            }
        } else if (normalizedExpected.compareTo(normalizedActual) != 0) {
            throw PaymentValidationException.badRequest("Verified payment amount does not match the order");
        }
    }

    private java.util.Optional<Attempt> findByIdempotency(long customerId, String idempotencyKey) {
        return queryAttempt(" WHERE pa.customer_id = :customerId AND pa.idempotency_key = :idempotencyKey", false)
            .param("customerId", customerId).param("idempotencyKey", idempotencyKey)
            .query((rs, rowNum) -> mapAttempt(rs)).optional();
    }

    private java.util.Optional<Attempt> findByProviderOrderId(String provider, String providerOrderId) {
        return findByProviderOrderId(provider, providerOrderId, false);
    }

    private java.util.Optional<Attempt> findByProviderOrderId(String provider, String providerOrderId, boolean forUpdate) {
        return queryAttempt(" WHERE pa.provider_code = :provider AND pa.provider_order_id = :providerOrderId", forUpdate)
            .param("provider", provider).param("providerOrderId", providerOrderId)
            .query((rs, rowNum) -> mapAttempt(rs)).optional();
    }

    private java.util.Optional<Attempt> findByProviderTransactionId(String provider, String transactionId, boolean forUpdate) {
        return queryAttempt(" WHERE pa.provider_code = :provider AND pa.provider_transaction_id = :transactionId", forUpdate)
            .param("provider", provider).param("transactionId", transactionId)
            .query((rs, rowNum) -> mapAttempt(rs)).optional();
    }

    private java.util.Optional<Attempt> findActiveByOrder(long orderId) {
        return queryAttempt(" WHERE pa.active_order_id = :orderId", false)
            .param("orderId", orderId).query((rs, rowNum) -> mapAttempt(rs)).optional();
    }

    private java.util.Optional<Attempt> findById(long attemptId, boolean forUpdate) {
        return queryAttempt(" WHERE pa.id = :attemptId", forUpdate).param("attemptId", attemptId)
            .query((rs, rowNum) -> mapAttempt(rs)).optional();
    }

    private JdbcClient.StatementSpec queryAttempt(String where, boolean forUpdate) {
        return jdbcClient.sql(ATTEMPT_SELECT + where + (forUpdate ? " FOR UPDATE" : ""));
    }

    private void lockPayableOrder(long orderId, long customerId) {
        if (!PAYABLE_ORDER_STATES.contains(lockOrderState(orderId, customerId))) {
            throw PaymentValidationException.conflict("Order is no longer awaiting payment");
        }
    }

    private void requireAdmissionPaymentAllowed(long orderId, long customerId) {
        if (orderAdmissionService != null) {
            orderAdmissionService.requirePaymentAllowed(orderId, customerId);
        }
    }

    private LockedOrderPayment lockPayableOrderPayment(long orderId, long customerId) {
        LockedOrder lockedOrder = jdbcClient.sql(
                """
                SELECT id, order_no, status_code, total_amount, currency_code
                FROM grading_order
                WHERE id = :orderId AND customer_id = :customerId
                FOR UPDATE
                """
            )
            .param("orderId", orderId)
            .param("customerId", customerId)
            .query((rs, rowNum) -> new LockedOrder(
                rs.getLong("id"), rs.getString("order_no"), rs.getString("status_code"),
                rs.getBigDecimal("total_amount"), rs.getString("currency_code")
            ))
            .optional()
            .orElseThrow(() -> PaymentValidationException.conflict("Order is unavailable"));
        if (!PAYABLE_ORDER_STATES.contains(lockedOrder.status())) {
            throw PaymentValidationException.conflict("Order is no longer awaiting payment");
        }
        LockedPayment lockedPayment = jdbcClient.sql(
                """
                SELECT id, status_code, amount, currency_code
                FROM payment_record
                WHERE order_id = :orderId
                  AND direction_code = 'receivable'
                  AND payment_type_code = 'grading_fee'
                ORDER BY id
                LIMIT 1
                FOR UPDATE
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new LockedPayment(
                rs.getLong("id"), rs.getString("status_code"), rs.getBigDecimal("amount"), rs.getString("currency_code")
            ))
            .optional()
            .orElseThrow(() -> PaymentValidationException.conflict("Order payment record is missing"));
        BigDecimal orderAmount = normalizedMoney(lockedOrder.amount());
        BigDecimal paymentAmount = normalizedMoney(lockedPayment.amount());
        String orderCurrency = normalizeCurrency(lockedOrder.currency());
        String paymentCurrency = normalizeCurrency(lockedPayment.currency());
        if (paymentAmount.compareTo(orderAmount) != 0 || !paymentCurrency.equals(orderCurrency)) {
            throw PaymentValidationException.conflict("Order payment amount is inconsistent");
        }
        if (!Set.of("pending", "rejected", "failed").contains(lockedPayment.status())) {
            throw PaymentValidationException.conflict("The order already has payment activity that requires financial review");
        }
        return new LockedOrderPayment(
            lockedOrder.id(), lockedOrder.orderNo(), lockedPayment.id(), orderAmount, orderCurrency
        );
    }

    private String lockOrderState(long orderId, long customerId) {
        return jdbcClient.sql("SELECT status_code FROM grading_order WHERE id = :orderId AND customer_id = :customerId FOR UPDATE")
            .param("orderId", orderId).param("customerId", customerId).query(String.class).optional()
            .orElseThrow(() -> PaymentValidationException.conflict("Order is unavailable"));
    }

    private static Attempt mapAttempt(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Attempt(
            rs.getLong("id"), rs.getLong("payment_record_id"), rs.getLong("order_id"), rs.getLong("customer_id"),
            rs.getString("provider_code"), rs.getString("idempotency_key"), rs.getString("merchant_order_no"),
            rs.getString("payment_no"), rs.getString("provider_order_id"), rs.getString("provider_transaction_id"),
            rs.getBigDecimal("expected_amount"), rs.getString("expected_currency"), rs.getString("status_code"),
            rs.getString("payment_url"), rs.getString("qr_payload"), rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private static void requirePayableSnapshot(OrderDetailResponse order) {
        if (!PAYABLE_ORDER_STATES.contains(order.statusCode())) {
            throw PaymentValidationException.conflict("Order is not awaiting payment");
        }
    }

    private static void requireSameCheckout(Attempt attempt, LockedOrderPayment locked, String provider) {
        if (attempt.orderId() != locked.orderId() || !attempt.provider().equals(provider)
            || attempt.paymentRecordId() != locked.paymentRecordId()
            || attempt.amount().compareTo(locked.amount()) != 0
            || !attempt.currency().equalsIgnoreCase(locked.currency())) {
            throw PaymentValidationException.conflict("Checkout idempotency key was already used for different payment details");
        }
    }

    private static boolean matchesProviderReference(Attempt attempt, String providerReference) {
        return providerReference != null && (
            providerReference.equals(attempt.providerOrderId()) || providerReference.equals(attempt.providerTransactionId())
        );
    }

    private static boolean hasRemoteOrder(Attempt attempt) {
        return attempt.providerOrderId() != null && !attempt.providerOrderId().isBlank();
    }

    private static String normalizeIdempotencyKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[A-Za-z0-9._:-]{8,100}")) {
            throw PaymentValidationException.badRequest("Idempotency key must contain 8-100 safe characters");
        }
        return value;
    }

    private static String normalizeRemoteId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[A-Za-z0-9_-]{6,80}")) {
            throw PaymentValidationException.badRequest("Provider order ID is invalid");
        }
        return value;
    }

    private static BigDecimal normalizedMoney(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw PaymentValidationException.badRequest("Payment amount must be positive");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw PaymentValidationException.badRequest("Payment amount must have at most two decimal places");
        }
    }

    private static String normalizeCurrency(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z]{3}")) {
            throw PaymentValidationException.badRequest("Payment currency is invalid");
        }
        return value;
    }

    private static String normalizeProviderStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "completed", "confirmed", "succeeded", "success", "paid" -> "paid";
            case "failed", "denied", "closed" -> "failed";
            case "refunded" -> "refunded";
            case "reversed" -> "reversed";
            case "created", "approved", "pending", "payer_action_required" -> value;
            default -> "pending";
        };
    }

    private static final String ATTEMPT_SELECT = """
        SELECT pa.id, pa.payment_record_id, pa.order_id, pa.customer_id, pa.provider_code,
               pa.idempotency_key, pa.merchant_order_no, pr.payment_no,
               pa.provider_order_id, pa.provider_transaction_id,
               pa.expected_amount, pa.expected_currency, pa.status_code,
               pa.payment_url, pa.qr_payload, pa.created_at
        FROM payment_attempt pa
        JOIN payment_record pr ON pr.id = pa.payment_record_id
        """;

    private record Reservation(Attempt attempt, CheckoutContext context, CheckoutResponse existingResponse) {
    }

    private record LockedOrder(long id, String orderNo, String status, BigDecimal amount, String currency) {
    }

    private record LockedPayment(long id, String status, BigDecimal amount, String currency) {
    }

    private record LockedOrderPayment(long orderId, String orderNo, long paymentRecordId, BigDecimal amount, String currency) {
    }

    private record Attempt(
        long id, long paymentRecordId, long orderId, long customerId, String provider, String idempotencyKey,
        String orderNo, String paymentNo, String providerOrderId, String providerTransactionId,
        BigDecimal amount, String currency, String status, String paymentUrl, String qrPayload, LocalDateTime createdAt
    ) {
        CheckoutResponse toResponse() {
            return new CheckoutResponse(provider, paymentUrl, qrPayload, status, providerOrderId);
        }
    }
}
