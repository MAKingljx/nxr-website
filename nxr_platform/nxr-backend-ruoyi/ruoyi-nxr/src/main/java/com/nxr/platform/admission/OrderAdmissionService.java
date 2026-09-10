package com.nxr.platform.admission;

import com.nxr.platform.notifications.NotificationOutboxService;
import com.nxr.platform.customer.CustomerOrderPhotoService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Admission, quote acceptance and payment-deadline policy for new grading orders. */
@Service
public class OrderAdmissionService {

    private static final Set<String> DECISIONS = Set.of("approve", "reject", "request_information");
    private final JdbcClient jdbcClient;
    private final NotificationOutboxService notifications;
    private final Clock clock;
    private CustomerOrderPhotoService photoService;

    @Autowired
    public OrderAdmissionService(JdbcClient jdbcClient, NotificationOutboxService notifications) {
        this(jdbcClient, notifications, Clock.systemDefaultZone());
    }

    OrderAdmissionService(JdbcClient jdbcClient, NotificationOutboxService notifications, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setCustomerOrderPhotoService(CustomerOrderPhotoService photoService) {
        this.photoService = photoService;
    }

    public int maxCardsPerOrder() {
        return config().maxCardsPerOrder();
    }

    public PublicConfig publicConfig() {
        Config value = config();
        return new PublicConfig(value.paymentDeadlineHours(), value.maxCardsPerOrder(), value.termsVersion(),
            value.termsText(), value.turnaroundText());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void initialize(long orderId, long customerId, String orderNo) {
        int updated = jdbcClient.sql(
                """
                UPDATE grading_order
                SET admission_status_code = 'pending_review', admission_submitted_at = CURRENT_TIMESTAMP,
                    payment_deadline_status_code = 'not_started', updated_at = CURRENT_TIMESTAMP
                WHERE id = :orderId AND customer_id = :customerId AND admission_status_code IS NULL
                """
            )
            .param("orderId", orderId)
            .param("customerId", customerId)
            .update();
        if (updated != 1) {
            throw conflict("Order admission was already initialized or the order changed");
        }
        insertEvent(orderId, "submitted", "Order submitted for admission review", "NXR will review the item list and quote before payment opens.",
            "customer", customerId, null);
        enqueue(customerId, orderNo, "created", "Your grading order application has been received for review.");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdmissionResponse requireCustomerAdmission(long customerId, String rawOrderNo) {
        AdmissionRow row = lockByOrderNo(customerId, rawOrderNo);
        expireIfDue(row);
        return response(reload(row.orderId()));
    }

    public AdmissionResponse requireAdminAdmission(long orderId) {
        return response(requireById(orderId, false));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdmissionResponse resubmit(long customerId, String rawOrderNo, ResubmitRequest request) {
        AdmissionRow row = lockByOrderNo(customerId, rawOrderNo);
        if (!"needs_information".equals(row.admissionStatus())) {
            throw conflict("Only an order awaiting more information can be resubmitted");
        }
        String note = clean(request == null ? null : request.note(), 2000);
        List<Long> supplementalPhotoIds = request == null || request.supplementalPhotoIds() == null
            ? List.of() : request.supplementalPhotoIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (supplementalPhotoIds.size() > 40) {
            throw badRequest("At most 40 supplemental photos can be submitted at once");
        }
        if (note.isBlank() && supplementalPhotoIds.isEmpty()) {
            throw badRequest("A note or supplemental photo is required");
        }
        if (photoService != null) {
            supplementalPhotoIds.forEach(photoId -> photoService.requireOwnedPhoto(customerId, photoId));
            photoService.attachToOrder(customerId, row.orderId(), supplementalPhotoIds);
        }
        for (Long photoId : supplementalPhotoIds) {
            jdbcClient.sql(
                    "INSERT INTO order_admission_supplemental_photo (order_id, photo_id, submitted_by_customer_id) VALUES (:orderId, :photoId, :customerId)"
                )
                .param("orderId", row.orderId()).param("photoId", photoId).param("customerId", customerId).update();
        }
        int updated = jdbcClient.sql(
                """
                UPDATE grading_order
                SET admission_status_code = 'pending_review', status_code = 'admission_review',
                    admission_submitted_at = CURRENT_TIMESTAMP, admission_decided_at = NULL,
                    admission_decision_note = NULL, admission_reviewed_by_user_id = NULL,
                    admission_revision = admission_revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :orderId AND admission_status_code = 'needs_information'
                """
            )
            .param("orderId", row.orderId())
            .update();
        if (updated != 1) {
            throw conflict("Admission changed; refresh the order before resubmitting");
        }
        insertEvent(row.orderId(), "resubmitted", "Additional information submitted", note,
            "customer", customerId, null);
        enqueue(customerId, row.orderNo(), "created", "Your updated grading order application has been resubmitted for review.");
        return response(reload(row.orderId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdmissionResponse acceptTerms(long customerId, String rawOrderNo, AcceptTermsRequest request) {
        AdmissionRow row = lockByOrderNo(customerId, rawOrderNo);
        if (!"approved".equals(row.admissionStatus())) {
            throw conflict("This order has not been approved for payment");
        }
        if (!quoteSnapshotMatchesOrder(row)) {
            throw conflict("The order quote changed after approval; NXR must review it again");
        }
        if (isExpired(row)) {
            throw conflict("The payment deadline has expired; NXR must review the order again");
        }
        String termsVersion = requireText(request == null ? null : request.termsVersion(), "Terms version", 64);
        BigDecimal acceptedAmount = money(request == null ? null : request.acceptedQuotedAmount(), "Accepted quoted amount");
        String acceptedCurrency = currency(request == null ? null : request.acceptedCurrency());
        if (!termsVersion.equals(row.approvedTermsVersion())
            || acceptedAmount.compareTo(row.approvedQuoteAmount()) != 0
            || !acceptedCurrency.equals(row.approvedQuoteCurrency())) {
            throw conflict("The accepted terms or quote do not match the approved order snapshot");
        }
        if (row.termsAcceptedAt() != null && Set.of("awaiting_payment", "payment_review").contains(row.orderStatus())) {
            return response(row);
        }
        if (!"terms_confirmation".equals(row.orderStatus()) || hasPaymentActivity(row.orderId())) {
            throw conflict("Terms cannot be confirmed after payment processing has started or the order state has changed");
        }
        jdbcClient.sql(
                """
                INSERT INTO order_terms_acceptance
                    (order_id, customer_id, terms_version, accepted_quote_amount, accepted_quote_currency)
                VALUES (:orderId, :customerId, :termsVersion, :amount, :currency)
                ON DUPLICATE KEY UPDATE accepted_at = accepted_at
                """
            )
            .param("orderId", row.orderId())
            .param("customerId", customerId)
            .param("termsVersion", termsVersion)
            .param("amount", acceptedAmount)
            .param("currency", acceptedCurrency)
            .update();
        int updated = jdbcClient.sql(
                """
                UPDATE grading_order
                SET accepted_terms_version = :termsVersion, terms_accepted_at = CURRENT_TIMESTAMP,
                    status_code = 'awaiting_payment', admission_revision = admission_revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :orderId AND admission_status_code = 'approved'
                  AND status_code = 'terms_confirmation' AND admission_revision = :expectedRevision
                  AND payment_deadline_status_code = 'active' AND payment_due_at >= :now
                """
            )
            .param("termsVersion", termsVersion)
            .param("orderId", row.orderId())
            .param("expectedRevision", row.revision())
            .param("now", now())
            .update();
        if (updated != 1) {
            throw conflict("Admission changed; refresh the order before confirming terms");
        }
        insertEvent(row.orderId(), "terms_accepted", "Quote and terms confirmed",
            "The customer confirmed the approved item list, quoted amount and terms version.", "customer", customerId, null);
        return response(reload(row.orderId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdmissionResponse decide(long orderId, long adminUserId, DecisionRequest request) {
        AdmissionRow row = requireById(orderId, true);
        String decision = clean(request == null ? null : request.decision(), 32).toLowerCase(Locale.ROOT);
        if (!DECISIONS.contains(decision)) {
            throw badRequest("Decision must be approve, reject or request_information");
        }
        if (request == null || request.expectedRevision() == null || request.expectedRevision() != row.revision()) {
            throw conflict("Admission changed; refresh the order before recording a decision");
        }
        boolean initialDecision = "pending_review".equals(row.admissionStatus())
            && "admission_review".equals(row.orderStatus());
        boolean deadlineRenewal = "approve".equals(decision) && "approved".equals(row.admissionStatus())
            && "payment_expired".equals(row.orderStatus());
        if (!initialDecision && !deadlineRenewal) {
            throw conflict("This admission decision cannot be changed in its current state");
        }
        if (hasPaymentActivity(orderId)) {
            throw conflict("Admission cannot be changed after payment processing has started");
        }
        String note = requireText(request == null ? null : request.note(), "Decision note", 2000);
        if ("approve".equals(decision)) {
            Config config = configForUpdate();
            LocalDateTime dueAt = now().plusHours(config.paymentDeadlineHours());
            int updated = jdbcClient.sql(
                    """
                    UPDATE grading_order
                    SET admission_status_code = 'approved', status_code = 'terms_confirmation',
                        admission_decided_at = CURRENT_TIMESTAMP, admission_decision_note = :note,
                        admission_reviewed_by_user_id = :adminUserId,
                        payment_due_at = :dueAt, payment_deadline_status_code = 'active',
                        approved_terms_version = :termsVersion, approved_terms_text = :termsText,
                        approved_turnaround_text = :turnaroundText,
                        approved_quote_amount = total_amount, approved_quote_currency = currency_code,
                        accepted_terms_version = NULL, terms_accepted_at = NULL,
                        admission_revision = admission_revision + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :orderId AND admission_revision = :expectedRevision
                    """
                )
                .param("note", note)
                .param("adminUserId", adminUserId)
                .param("dueAt", dueAt)
                .param("termsVersion", config.termsVersion())
                .param("termsText", config.termsText())
                .param("turnaroundText", config.turnaroundText())
                .param("orderId", orderId)
                .param("expectedRevision", row.revision())
                .update();
            if (updated != 1) {
                throw conflict("Admission changed; refresh the order before recording a decision");
            }
            insertEvent(orderId, "approved", "Order approved", note, "admin", null, adminUserId);
            enqueue(row.customerId(), row.orderNo(), "admission_approved",
                "Your order was approved. Confirm the quoted amount and terms before the payment deadline.");
        } else {
            String status = "reject".equals(decision) ? "rejected" : "needs_information";
            String event = "reject".equals(decision) ? "rejected" : "information_requested";
            String title = "reject".equals(decision) ? "Order application rejected" : "Additional information requested";
            int updated = jdbcClient.sql(
                    """
                    UPDATE grading_order
                    SET admission_status_code = :status, status_code = 'admission_review',
                        admission_decided_at = CURRENT_TIMESTAMP, admission_decision_note = :note,
                        admission_reviewed_by_user_id = :adminUserId,
                        payment_due_at = NULL, payment_deadline_status_code = 'not_started',
                        approved_terms_version = NULL, approved_terms_text = NULL, approved_turnaround_text = NULL,
                        approved_quote_amount = NULL, approved_quote_currency = NULL,
                        accepted_terms_version = NULL, terms_accepted_at = NULL,
                        admission_revision = admission_revision + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :orderId AND admission_revision = :expectedRevision
                    """
                )
                .param("status", status)
                .param("note", note)
                .param("adminUserId", adminUserId)
                .param("orderId", orderId)
                .param("expectedRevision", row.revision())
                .update();
            if (updated != 1) {
                throw conflict("Admission changed; refresh the order before recording a decision");
            }
            insertEvent(orderId, event, title, note, "admin", null, adminUserId);
            enqueue(row.customerId(), row.orderNo(), "reject".equals(decision) ? "admission_rejected" : "admission_needs_information", note);
        }
        return response(reload(orderId));
    }

    public Config getConfig() {
        return config();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Config updateConfig(ConfigUpdate request, long adminUserId) {
        if (request == null || request.paymentDeadlineHours() == null || request.maxCardsPerOrder() == null) {
            throw badRequest("Admission configuration is required");
        }
        int hours = request.paymentDeadlineHours();
        int maxCards = request.maxCardsPerOrder();
        if (hours < 1 || hours > 720) {
            throw badRequest("Payment deadline must be between 1 and 720 hours");
        }
        if (maxCards < 1 || maxCards > 10000) {
            throw badRequest("Order card limit must be between 1 and 10000");
        }
        String version = requireText(request.termsVersion(), "Terms version", 64);
        String text = requireText(request.termsText(), "Terms text", 10000);
        String turnaround = requireText(request.turnaroundText(), "Turnaround text", 1000);
        configForUpdate();
        jdbcClient.sql(
                """
                UPDATE order_admission_config
                SET payment_deadline_hours = :hours, max_cards_per_order = :maxCards,
                    terms_version = :version, terms_text = :text, turnaround_text = :turnaround,
                    config_version = config_version + 1, updated_by_user_id = :adminUserId,
                    updated_at = CURRENT_TIMESTAMP
                WHERE config_id = 1
                """
            )
            .param("hours", hours)
            .param("maxCards", maxCards)
            .param("version", version)
            .param("text", text)
            .param("turnaround", turnaround)
            .param("adminUserId", adminUserId)
            .update();
        return config();
    }

    /** Must run while the caller owns the order lock. Legacy rows without admission metadata remain payable. */
    public void requirePaymentAllowed(long orderId, long customerId) {
        AdmissionRow row = requireById(orderId, true);
        if (row.customerId() != customerId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found");
        }
        if (row.admissionStatus() == null) {
            return;
        }
        if (!"approved".equals(row.admissionStatus()) || row.termsAcceptedAt() == null
            || !"active".equals(row.deadlineStatus()) || isExpired(row) || !quoteSnapshotMatchesOrder(row)) {
            throw conflict("Admission approval, quote acceptance and an active payment deadline are required before payment");
        }
    }

    /** Verified funds are retained, but fulfillment must pause when admission/payment terms are no longer valid. */
    public boolean verifiedPaymentRequiresReview(long orderId) {
        AdmissionRow row = requireById(orderId, true);
        return row.admissionStatus() != null && (!"approved".equals(row.admissionStatus())
            || row.termsAcceptedAt() == null || !"active".equals(row.deadlineStatus()) || isExpired(row)
            || !quoteSnapshotMatchesOrder(row));
    }

    public void requireGenericStatusChangeAllowed(long orderId) {
        AdmissionRow row = requireById(orderId, true);
        if (row.admissionStatus() != null && (row.termsAcceptedAt() == null || isExpired(row))) {
            throw conflict("Admission and payment deadline state can only be changed through the admission workflow");
        }
    }

    @Scheduled(fixedDelayString = "${nxr.order-admission.expiry-scan-ms:60000}")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void expireDuePayments() {
        List<Long> ids = jdbcClient.sql(
                """
                SELECT id FROM grading_order
                WHERE admission_status_code = 'approved'
                  AND payment_deadline_status_code = 'active'
                  AND payment_due_at < :now
                  AND status_code IN ('terms_confirmation', 'awaiting_payment', 'payment_review')
                ORDER BY payment_due_at, id
                LIMIT 100
                """
            )
            .param("now", now())
            .query(Long.class)
            .list();
        for (Long id : ids) {
            AdmissionRow row = requireById(id, true);
            expireIfDue(row);
        }
    }

    private void expireIfDue(AdmissionRow row) {
        if (!isExpired(row) || !"active".equals(row.deadlineStatus())
            || !Set.of("terms_confirmation", "awaiting_payment", "payment_review").contains(row.orderStatus())) {
            return;
        }
        int updated = jdbcClient.sql(
                """
                UPDATE grading_order
                SET payment_deadline_status_code = 'expired', status_code = 'payment_expired',
                    admission_revision = admission_revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :orderId AND payment_deadline_status_code = 'active' AND payment_due_at < :now
                """
            )
            .param("orderId", row.orderId())
            .param("now", now())
            .update();
        if (updated == 1) {
            insertEvent(row.orderId(), "payment_expired", "Payment deadline expired",
                "No new payment can be started. Contact NXR if the order should be reviewed again.", "system", null, null);
            enqueue(row.customerId(), row.orderNo(), "payment_expired",
                "The payment deadline for your approved grading order has expired.");
        }
    }

    private AdmissionRow lockByOrderNo(long customerId, String rawOrderNo) {
        String orderNo = requireText(rawOrderNo, "Order number", 40).toUpperCase(Locale.ROOT);
        return queryAdmission("WHERE o.customer_id = :customerId AND UPPER(o.order_no) = :orderNo", true)
            .param("customerId", customerId).param("orderNo", orderNo)
            .query((rs, rowNum) -> map(rs)).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    private AdmissionRow requireById(long orderId, boolean forUpdate) {
        return queryAdmission("WHERE o.id = :orderId", forUpdate).param("orderId", orderId)
            .query((rs, rowNum) -> map(rs)).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
    }

    private AdmissionRow reload(long orderId) {
        return requireById(orderId, false);
    }

    private JdbcClient.StatementSpec queryAdmission(String where, boolean forUpdate) {
        return jdbcClient.sql(
            """
            SELECT o.id, o.order_no, o.customer_id, o.status_code, o.admission_status_code, o.admission_revision,
                   o.admission_decision_note, o.admission_submitted_at, o.admission_decided_at,
                   o.payment_due_at, o.payment_deadline_status_code,
                   o.approved_terms_version, o.approved_terms_text,
                   o.approved_turnaround_text,
                   o.approved_quote_amount, o.approved_quote_currency,
                   o.accepted_terms_version, o.terms_accepted_at, o.total_amount, o.currency_code
            FROM grading_order o
            """ + where + (forUpdate ? " FOR UPDATE" : "")
        );
    }

    private AdmissionRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdmissionRow(
            rs.getLong("id"), rs.getString("order_no"), rs.getLong("customer_id"), rs.getString("status_code"),
            rs.getString("admission_status_code"), rs.getInt("admission_revision"), rs.getString("admission_decision_note"),
            rs.getObject("admission_submitted_at", LocalDateTime.class), rs.getObject("admission_decided_at", LocalDateTime.class),
            rs.getObject("payment_due_at", LocalDateTime.class), rs.getString("payment_deadline_status_code"),
            rs.getString("approved_terms_version"), rs.getString("approved_terms_text"), rs.getString("approved_turnaround_text"),
            rs.getBigDecimal("approved_quote_amount"), rs.getString("approved_quote_currency"),
            rs.getString("accepted_terms_version"), rs.getObject("terms_accepted_at", LocalDateTime.class),
            rs.getBigDecimal("total_amount"), rs.getString("currency_code")
        );
    }

    private AdmissionResponse response(AdmissionRow row) {
        boolean expired = isExpired(row) || "expired".equals(row.deadlineStatus());
        boolean accepted = row.termsAcceptedAt() != null && row.approvedTermsVersion() != null
            && row.approvedTermsVersion().equals(row.acceptedTermsVersion());
        boolean quoteMatches = quoteSnapshotMatchesOrder(row);
        boolean legacyOrder = row.admissionStatus() == null;
        return new AdmissionResponse(
            row.orderId(), row.orderNo(), row.admissionStatus(), legacyOrder, row.revision(), row.decisionNote(), row.submittedAt(), row.decidedAt(),
            row.paymentDueAt(), paymentDueAtIso(row.paymentDueAt()), expired,
            row.approvedTermsVersion(), row.approvedTermsText(), row.approvedTurnaroundText(),
            row.acceptedTermsVersion(),
            row.termsAcceptedAt(), row.approvedQuoteAmount(), row.approvedQuoteCurrency(),
            "needs_information".equals(row.admissionStatus()),
            "approved".equals(row.admissionStatus()) && "terms_confirmation".equals(row.orderStatus())
                && !accepted && !expired && quoteMatches,
            (legacyOrder && Set.of("awaiting_payment", "payment_review").contains(row.orderStatus()))
                || ("approved".equals(row.admissionStatus()) && Set.of("awaiting_payment", "payment_review").contains(row.orderStatus())
                    && accepted && !expired && quoteMatches), config().maxCardsPerOrder(),
            supplementalPhotoIds(row.orderId()), admissionEvents(row.orderId())
        );
    }

    private boolean hasPaymentActivity(long orderId) {
        boolean paymentRecordStarted = jdbcClient.sql(
                "SELECT status_code FROM payment_record WHERE order_id = :orderId FOR UPDATE"
            )
            .param("orderId", orderId)
            .query(String.class)
            .list()
            .stream()
            .anyMatch(status -> Set.of("proof_submitted", "confirmed", "refunded", "reversed").contains(status));
        if (paymentRecordStarted) return true;
        return !jdbcClient.sql(
                "SELECT id FROM payment_attempt WHERE order_id = :orderId AND active_order_id IS NOT NULL FOR UPDATE"
            )
            .param("orderId", orderId)
            .query(Long.class)
            .list()
            .isEmpty();
    }

    private List<Long> supplementalPhotoIds(long orderId) {
        return jdbcClient.sql(
                "SELECT photo_id FROM order_admission_supplemental_photo WHERE order_id = :orderId ORDER BY id"
            )
            .param("orderId", orderId)
            .query(Long.class)
            .list();
    }

    private List<AdmissionEvent> admissionEvents(long orderId) {
        return jdbcClient.sql(
                """
                SELECT id, event_code, title, detail, actor_type_code, created_at
                FROM order_admission_event WHERE order_id = :orderId ORDER BY created_at, id
                """
            )
            .param("orderId", orderId)
            .query((rs, rowNum) -> new AdmissionEvent(
                rs.getLong("id"), rs.getString("event_code"), rs.getString("title"), rs.getString("detail"),
                rs.getString("actor_type_code"), rs.getObject("created_at", LocalDateTime.class)
            ))
            .list();
    }

    private boolean isExpired(AdmissionRow row) {
        return row.paymentDueAt() != null && now().isAfter(row.paymentDueAt());
    }

    private static boolean quoteSnapshotMatchesOrder(AdmissionRow row) {
        return row.admissionStatus() == null || (row.approvedQuoteAmount() != null && row.orderAmount() != null
            && row.approvedQuoteAmount().compareTo(row.orderAmount()) == 0
            && row.approvedQuoteCurrency() != null && row.approvedQuoteCurrency().equalsIgnoreCase(row.orderCurrency()));
    }

    private Config config() {
        return configQuery(false);
    }

    private Config configForUpdate() {
        return configQuery(true);
    }

    private Config configQuery(boolean forUpdate) {
        return jdbcClient.sql(
                """
                SELECT payment_deadline_hours, max_cards_per_order, terms_version, terms_text, turnaround_text,
                       config_version, updated_at
                FROM order_admission_config WHERE config_id = 1
                """ + (forUpdate ? " FOR UPDATE" : "")
            )
            .query((rs, rowNum) -> new Config(
                rs.getInt("payment_deadline_hours"), rs.getInt("max_cards_per_order"), rs.getString("terms_version"),
                rs.getString("terms_text"), rs.getString("turnaround_text"), rs.getInt("config_version"),
                rs.getObject("updated_at", LocalDateTime.class)
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order admission configuration is not installed"));
    }

    private void insertEvent(long orderId, String event, String title, String detail, String actor, Long customerId, Long adminId) {
        jdbcClient.sql(
                """
                INSERT INTO order_admission_event
                    (order_id, event_code, title, detail, actor_type_code, actor_customer_id, actor_admin_user_id)
                VALUES (:orderId, :event, :title, :detail, :actor, :customerId, :adminId)
                """
            )
            .param("orderId", orderId).param("event", event).param("title", title).param("detail", clean(detail, 2000))
            .param("actor", actor).param("customerId", customerId).param("adminId", adminId).update();
    }

    private void enqueue(long customerId, String orderNo, String status, String message) {
        if (notifications != null) {
            notifications.enqueueOrderStatus(customerId, orderNo, status, message);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String paymentDueAtIso(LocalDateTime paymentDueAt) {
        return paymentDueAt == null ? null : paymentDueAt.atZone(clock.getZone()).toOffsetDateTime().toString();
    }

    private static BigDecimal money(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) throw badRequest(label + " must be positive");
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw badRequest(label + " has unsupported precision");
        }
    }

    private static String currency(String value) {
        String normalized = clean(value, 3).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw badRequest("Accepted currency is invalid");
        return normalized;
    }

    private static String requireText(String value, String label, int max) {
        String normalized = clean(value, max + 1);
        if (normalized.isBlank() || normalized.length() > max) throw badRequest(label + " is required");
        return normalized;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ").replaceAll(" +", " ");
        return normalized.substring(0, Math.min(normalized.length(), max));
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record AdmissionRow(
        long orderId, String orderNo, long customerId, String orderStatus, String admissionStatus, int revision, String decisionNote,
        LocalDateTime submittedAt, LocalDateTime decidedAt, LocalDateTime paymentDueAt, String deadlineStatus,
        String approvedTermsVersion, String approvedTermsText, String approvedTurnaroundText,
        BigDecimal approvedQuoteAmount, String approvedQuoteCurrency,
        String acceptedTermsVersion, LocalDateTime termsAcceptedAt, BigDecimal orderAmount, String orderCurrency
    ) { }

    public record Config(
        int paymentDeadlineHours, int maxCardsPerOrder, String termsVersion, String termsText, String turnaroundText,
        int configVersion, LocalDateTime updatedAt
    ) { }

    public record PublicConfig(
        int paymentDeadlineHours, int maxCardsPerOrder, String termsVersion, String termsText, String turnaroundText
    ) { }

    public record ConfigUpdate(
        Integer paymentDeadlineHours, Integer maxCardsPerOrder, String termsVersion, String termsText, String turnaroundText
    ) { }

    public record DecisionRequest(String decision, String note, Integer expectedRevision) { }

    public record ResubmitRequest(String note, List<Long> supplementalPhotoIds) {
        public ResubmitRequest(String note) {
            this(note, List.of());
        }
    }

    public record AcceptTermsRequest(String termsVersion, BigDecimal acceptedQuotedAmount, String acceptedCurrency) { }

    public record AdmissionEvent(
        long id, String eventCode, String title, String detail, String actorType, LocalDateTime createdAt
    ) { }

    public record AdmissionResponse(
        long orderId, String orderNo, String admissionStatus, boolean legacyOrder, int admissionRevision, String decisionNote,
        LocalDateTime submittedAt, LocalDateTime decidedAt, LocalDateTime paymentDueAt, String paymentDueAtIso,
        boolean paymentExpired,
        String termsVersion, String termsText, String turnaroundText, String acceptedTermsVersion, LocalDateTime termsAcceptedAt,
        BigDecimal quoteAmount, String quoteCurrency, boolean canResubmit, boolean canAcceptTerms, boolean canPay,
        int maxCardsPerOrder, List<Long> supplementalPhotoIds, List<AdmissionEvent> events
    ) { }
}
