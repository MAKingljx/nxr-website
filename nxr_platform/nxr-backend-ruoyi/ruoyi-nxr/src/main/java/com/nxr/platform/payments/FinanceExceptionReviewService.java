package com.nxr.platform.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.customer.AgentOrderCancellationService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Records staff attestations of verified funds; never transfers money or requests a refund. */
@Service
public class FinanceExceptionReviewService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> RESUMABLE = Set.of("awaiting_inbound", "inbound_shipped", "received", "intake_exception", "grading", "review", "quality_check", "quality_hold", "completed", "return_shipped", "delivered");
    private static final Set<String> RECEIVED = Set.of("received", "intake_exception", "grading", "review", "quality_check", "quality_hold", "completed", "return_shipped", "delivered");
    private final JdbcClient jdbcClient;
    private final AgentOrderCancellationService cancellations;
    private final TransactionTemplate transactions;

    public FinanceExceptionReviewService(JdbcClient jdbcClient, PlatformTransactionManager manager, AgentOrderCancellationService cancellations) {
        this.jdbcClient = jdbcClient;
        this.cancellations = cancellations;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public List<FinanceException> listForOrder(long orderId) {
        return exceptions(orderId, false, false).stream().map(row -> {
            ReviewNote note = readNote(row.note());
            return new FinanceException(row.id(), row.orderId(), row.paymentId(), row.attemptId(), row.provider(), row.eventId(),
                row.transactionId(), row.type(), row.amount(), row.currency(), row.resolution(), note.reason(), row.resolvedBy(),
                note.evidenceReference(), note.action(), note.replacementPaymentId(), row.resolvedAt(), row.createdAt());
        }).toList();
    }

    public ReviewContext reviewContext(long orderId) { return context(order(orderId, false), exceptions(orderId, true, false)); }

    public ReviewContext review(long orderId, long actorId, ReviewRequest request) {
        if (request == null || actorId <= 0) throw badRequest("Finance review is required");
        String action = text(request.action(), "Review action", 32);
        if (!Set.of("restore", "cancel", "manual_review").contains(action)) throw badRequest("Invalid finance review action");
        String evidence = text(request.evidenceReference(), "Evidence reference", 255);
        String reason = text(request.reason(), "Review reason", 1000);
        List<Long> ids = request.exceptionIds();
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)
            || new HashSet<>(ids).size() != ids.size()) throw badRequest("Payment exception ids are required");
        return transactions.execute(ignored -> {
            // Same lock order as gateway callbacks: order, then exceptions/payments. Review
            // the entire pending set, so a newer refund cannot silently lose its hold.
            Order order = order(orderId, true);
            List<ExceptionRow> pending = exceptions(orderId, true, true);
            if (!"payment_exception".equals(order.status())) throw conflict("This order is not paused for financial review");
            if (!new HashSet<>(ids).equals(new HashSet<>(pending.stream().map(ExceptionRow::id).toList())))
                throw conflict("The payment exceptions changed; refresh and review again");
            ReviewContext context = context(order, pending);
            String target = "payment_exception", resolution = "manual_review";
            Long receiptId = null;
            if ("restore".equals(action)) {
                if (!context.canRestore()) throw conflict(context.restoreBlockReason());
                if (request.amount() == null || request.amount().signum() <= 0 || request.amount().compareTo(order.amount()) != 0)
                    throw badRequest("Verified payment amount must equal the full order total");
                if (request.currencyCode() == null || !order.currency().equalsIgnoreCase(request.currencyCode().trim()))
                    throw badRequest("Verified payment currency must match the order");
                receiptId = recordReceipt(order, actorId, evidence, reason);
                target = context.resumeStatusCode();
                resolution = "funds_verified";
            } else if ("cancel".equals(action)) {
                if (!context.canCancel()) throw conflict(context.cancelBlockReason());
                target = "cancelled";
                resolution = "refund_verified";
            }
            if (!"manual_review".equals(action)) {
                jdbcClient.sql("UPDATE grading_order SET status_code=:target,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                    .param("target", target).param("id", orderId).update();
                // Retain original order/item/photo history while releasing only current inventory links.
                if ("cancel".equals(action)) cancellations.releaseCancelledOrder(orderId);
            }
            String note = writeNote(new ReviewNote(context.resumeStatusCode(), reason, evidence, action, receiptId));
            for (ExceptionRow row : pending) {
                jdbcClient.sql("""
                    UPDATE payment_finance_exception SET resolution_status_code=:resolution,resolved_by_user_id=:actor,
                        resolution_note=:note,resolved_at=CASE WHEN :resolution='manual_review' THEN NULL ELSE CURRENT_TIMESTAMP END
                    WHERE id=:id AND order_id=:order
                    """).param("resolution", resolution).param("actor", actorId).param("note", note)
                    .param("id", row.id()).param("order", orderId).update();
            }
            timeline(orderId, actorId, "finance_review", "Financial review recorded", reason + "\nEvidence: " + evidence + "\nAction: " + action, target, false);
            if (!"manual_review".equals(action)) timeline(orderId, actorId, "finance_review_completed",
                "restore".equals(action) ? "Payment verified" : "Order cancelled",
                "restore".equals(action) ? "Payment was verified. The previous order progress has been restored." : "Refund was verified. This order is cancelled.", target, true);
            return context(order(orderId, false), exceptions(orderId, true, false));
        });
    }

    private long recordReceipt(Order order, long actor, String evidence, String reason) {
        if (jdbcClient.sql("SELECT COUNT(*) FROM payment_record WHERE proof_reference=:ref OR provider_transaction_id=:ref")
            .param("ref", evidence).query(Long.class).single() > 0)
            throw conflict("A new payment reference is required; this reference was already recorded");
        String paymentNo = "PAY-FIN-" + UUID.randomUUID().toString().replace("-", "");
        try {
            jdbcClient.sql("""
                INSERT INTO payment_record(order_id,direction_code,payment_type_code,payment_no,provider_code,method_label,
                    status_code,amount,currency_code,proof_reference,provider_transaction_id,confirmed_by_user_id,submitted_at,confirmed_at,note)
                VALUES(:order,'receivable','grading_fee',:number,'manual_transfer','Verified receipt','confirmed',
                    :amount,:currency,:evidence,:evidence,:actor,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,:reason)
                """).param("order", order.id()).param("number", paymentNo).param("amount", order.amount()).param("currency", order.currency())
                .param("evidence", evidence).param("actor", actor).param("reason", reason).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw conflict("A new payment reference is required; this reference was already recorded");
        }
        return jdbcClient.sql("SELECT id FROM payment_record WHERE payment_no=:number").param("number", paymentNo).query(Long.class).single();
    }

    private ReviewContext context(Order order, List<ExceptionRow> pending) {
        String previous = resumeStatus(order.id(), pending);
        boolean received = RECEIVED.contains(previous == null ? "" : previous)
            || count("SELECT COUNT(*) FROM order_intake_receipt WHERE order_id=:id", order.id()) > 0
            || count("SELECT COUNT(*) FROM grading_order_item WHERE order_id=:id AND status_code IN ('received','intake_exception','grading','review','quality_check','quality_hold','quality_passed','completed','return_shipped','delivered')", order.id()) > 0
            || count("SELECT COUNT(*) FROM order_shipment WHERE order_id=:id AND direction_code='outbound'", order.id()) > 0;
        boolean batchShipped = count("""
            SELECT COUNT(*) FROM merchant_order_batch_item bi JOIN merchant_order_batch b ON b.id=bi.batch_id
            JOIN merchant_batch_shipment s ON s.batch_id=b.id AND s.direction_code='inbound'
            WHERE bi.order_id=:id AND b.status_code<>'cancelled'
            """, order.id()) > 0;
        String shared = !"payment_exception".equals(order.status()) ? "This order is not paused for financial review"
            : pending.isEmpty() ? "The payment exceptions changed; refresh and review again"
            : pending.stream().anyMatch(row -> !Set.of("refunded","reversed").contains(row.type())) ? "This payment exception requires manual review"
            : count("SELECT COUNT(*) FROM payment_attempt WHERE order_id=:id AND active_order_id IS NOT NULL", order.id()) > 0 ? "An active checkout requires manual review"
            : count("SELECT COUNT(*) FROM payment_record WHERE order_id=:id AND direction_code='receivable' AND payment_type_code='grading_fee' AND status_code='confirmed'", order.id()) > 0 ? "Other confirmed payments require manual review" : null;
        String restore = shared != null ? shared : previous == null || !RESUMABLE.contains(previous) ? "The previous order progress could not be verified"
            : received && !RECEIVED.contains(previous) ? "The order custody has changed; keep the order paused for manual review" : null;
        String cancel = shared != null ? shared : received ? "Cards have already been received; keep the order paused for manual review"
            : batchShipped ? "The master parcel has already shipped; keep the order paused for manual review" : null;
        return new ReviewContext(order.id(), order.status(), previous, order.amount(), order.currency(), pending.stream().map(ExceptionRow::id).toList(),
            restore == null, cancel == null, received, restore, cancel);
    }

    private String resumeStatus(long id, List<ExceptionRow> pending) {
        if (pending.isEmpty()) return null;
        Set<String> snapshots = new HashSet<>();
        for (ExceptionRow row : pending) {
            String state = readNote(row.note()).resumeStatusCode();
            if (state != null && !state.isBlank()) snapshots.add(state);
        }
        if (snapshots.size() == 1) return snapshots.iterator().next();
        if (!snapshots.isEmpty()) return null;
        // Legacy holds have no checkpoint: trust only an actual progress event before
        // the earliest pending hold. Missing or conflicting history stays under review.
        return jdbcClient.sql("""
            SELECT status_code FROM order_timeline_event WHERE order_id=:id
                AND status_code IN ('awaiting_inbound','inbound_shipped','received','intake_exception','grading','review','quality_check','quality_hold','completed','return_shipped','delivered')
                AND created_at <= (SELECT MIN(created_at) FROM payment_finance_exception
                    WHERE order_id=:id AND resolution_status_code IN ('open','manual_review'))
            ORDER BY created_at DESC,id DESC LIMIT 1
            """).param("id", id).query(String.class).optional().orElse(null);
    }

    private Order order(long id, boolean lock) {
        return jdbcClient.sql("SELECT id,status_code,total_amount,currency_code FROM grading_order WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
            .param("id", id).query((rs, n) -> new Order(rs.getLong("id"), rs.getString("status_code"), rs.getBigDecimal("total_amount"),
                rs.getString("currency_code").toUpperCase(Locale.ROOT))).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    private List<ExceptionRow> exceptions(long id, boolean pendingOnly, boolean lock) {
        return jdbcClient.sql("""
            SELECT id,order_id,payment_record_id,payment_attempt_id,provider_code,provider_event_id,provider_transaction_id,
                exception_type_code,amount,currency_code,resolution_status_code,resolved_by_user_id,resolution_note,resolved_at,created_at
            FROM payment_finance_exception WHERE order_id=:id
            """ + (pendingOnly ? " AND resolution_status_code IN ('open','manual_review')" : "") + " ORDER BY id DESC" + (lock ? " FOR UPDATE" : ""))
            .param("id", id).query((rs, n) -> new ExceptionRow(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("payment_record_id"),
                rs.getLong("payment_attempt_id"), rs.getString("provider_code"), rs.getString("provider_event_id"), rs.getString("provider_transaction_id"),
                rs.getString("exception_type_code"), rs.getBigDecimal("amount"), rs.getString("currency_code"), rs.getString("resolution_status_code"),
                rs.getObject("resolved_by_user_id", Long.class), rs.getString("resolution_note"), rs.getObject("resolved_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class))).list();
    }

    private long count(String sql, long id) { return jdbcClient.sql(sql).param("id", id).query(Long.class).single(); }
    private void timeline(long id, long actor, String code, String title, String detail, String status, boolean visible) {
        jdbcClient.sql("""
            INSERT INTO order_timeline_event(order_id,event_code,title,detail,status_code,visible_to_customer,actor_type_code,actor_admin_user_id)
            VALUES(:id,:code,:title,:detail,:status,:visible,'admin',:actor)
            """).param("id", id).param("code", code).param("title", title).param("detail", detail).param("status", status).param("visible", visible ? 1 : 0).param("actor", actor).update();
    }
    static String pauseMetadata(String previous) { return writeNote(new ReviewNote(previous, null, null, null, null)); }
    static String pausedStatus(String note) { return readNote(note).resumeStatusCode(); }
    private static ReviewNote readNote(String raw) {
        if (raw == null || raw.isBlank()) return new ReviewNote(null, null, null, null, null);
        try { return JSON.readValue(raw, ReviewNote.class); }
        catch (Exception legacy) { return new ReviewNote(null, raw, null, null, null); }
    }
    private static String writeNote(ReviewNote note) {
        try { return JSON.writeValueAsString(note); }
        catch (Exception invalid) { throw new IllegalStateException("Unable to record financial review", invalid); }
    }
    private static String text(String value, String label, int limit) {
        if (value == null || value.trim().isEmpty()) throw badRequest(label + " is required");
        if (value.trim().length() > limit) throw badRequest(label + " is too long");
        return value.trim();
    }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private record Order(long id, String status, BigDecimal amount, String currency) { }
    private record ReviewNote(String resumeStatusCode, String reason, String evidenceReference, String action, Long replacementPaymentId) { }
    private record ExceptionRow(long id, long orderId, long paymentId, long attemptId, String provider, String eventId, String transactionId,
        String type, BigDecimal amount, String currency, String resolution, Long resolvedBy, String note, LocalDateTime resolvedAt, LocalDateTime createdAt) { }
    public record ReviewRequest(String action, List<Long> exceptionIds, String evidenceReference, String reason, BigDecimal amount, String currencyCode) { }
    public record ReviewContext(long orderId, String statusCode, String resumeStatusCode, BigDecimal amount, String currencyCode,
        List<Long> pendingExceptionIds, boolean canRestore, boolean canCancel, boolean hasReceived, String restoreBlockReason, String cancelBlockReason) { }
    public record FinanceException(long id, long orderId, long paymentRecordId, long paymentAttemptId, String providerCode,
        String providerEventId, String providerTransactionId, String exceptionTypeCode, BigDecimal amount, String currencyCode,
        String resolutionStatusCode, String resolutionNote, Long resolvedByUserId, String evidenceReference, String resolutionAction,
        Long replacementPaymentId, LocalDateTime resolvedAt, LocalDateTime createdAt) { }
}
