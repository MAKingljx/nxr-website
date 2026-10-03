package com.nxr.platform.payments;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import com.nxr.platform.customer.AgentOrderCancellationService;
import com.nxr.platform.payments.FinanceExceptionReviewService.ReviewRequest;

/** Real JDBC transactions exercise money, custody and concurrency without touching a runtime database. */
class FinanceExceptionWorkflowTest {
    private JdbcTemplate jdbc;
    private JdbcClient client;
    private DataSourceTransactionManager manager;
    private FinanceExceptionReviewService service;
    private AgentOrderCancellationService cancellations;
    private static final BigDecimal TOTAL = new BigDecimal("100.00");

    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:finance_workflow_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        client = JdbcClient.create(jdbc);
        // Keep the production receipt uniqueness rule: provider plus transaction reference.
        jdbc.execute("CREATE TABLE grading_order(id BIGINT PRIMARY KEY,order_no VARCHAR(40),customer_id BIGINT,status_code VARCHAR(32),total_amount DECIMAL(18,2),currency_code VARCHAR(8),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE grading_order_item(id BIGINT PRIMARY KEY,order_id BIGINT,status_code VARCHAR(32),front_photo_id BIGINT,back_photo_id BIGINT)");
        jdbc.execute("CREATE TABLE payment_record(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT,direction_code VARCHAR(32),payment_type_code VARCHAR(32),payment_no VARCHAR(80) UNIQUE,provider_code VARCHAR(32),method_label VARCHAR(64),status_code VARCHAR(32),amount DECIMAL(18,2),currency_code VARCHAR(8),proof_reference VARCHAR(512),provider_transaction_id VARCHAR(255),confirmed_by_user_id BIGINT,submitted_at TIMESTAMP,confirmed_at TIMESTAMP,note VARCHAR(2000),UNIQUE(provider_code,provider_transaction_id))");
        jdbc.execute("CREATE TABLE payment_attempt(id BIGINT PRIMARY KEY,order_id BIGINT,active_order_id BIGINT,status_code VARCHAR(32))");
        jdbc.execute("CREATE TABLE payment_finance_exception(id BIGINT PRIMARY KEY,order_id BIGINT,payment_record_id BIGINT,payment_attempt_id BIGINT,provider_code VARCHAR(32),provider_event_id VARCHAR(128),provider_transaction_id VARCHAR(255),exception_type_code VARCHAR(32),amount DECIMAL(18,2),currency_code VARCHAR(8),resolution_status_code VARCHAR(32),resolved_by_user_id BIGINT,resolution_note VARCHAR(4000),resolved_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE order_timeline_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT,event_code VARCHAR(48),title VARCHAR(128),detail VARCHAR(4000),status_code VARCHAR(32),visible_to_customer TINYINT,actor_type_code VARCHAR(32),actor_admin_user_id BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE order_intake_receipt(id BIGINT PRIMARY KEY,order_id BIGINT)");
        jdbc.execute("CREATE TABLE order_shipment(id BIGINT PRIMARY KEY,order_id BIGINT,direction_code VARCHAR(32))");
        jdbc.execute("CREATE TABLE merchant_order_batch(id BIGINT PRIMARY KEY,merchant_customer_id BIGINT,batch_no VARCHAR(48),status_code VARCHAR(32))");
        jdbc.execute("CREATE TABLE merchant_order_batch_item(id BIGINT PRIMARY KEY,batch_id BIGINT,order_id BIGINT,client_reference VARCHAR(128))");
        jdbc.execute("CREATE TABLE merchant_batch_shipment(id BIGINT PRIMARY KEY,batch_id BIGINT,direction_code VARCHAR(32))");
        jdbc.execute("CREATE TABLE agent_intake(id BIGINT PRIMARY KEY,merchant_customer_id BIGINT,client_id BIGINT,expected_card_count INT,status_code VARCHAR(32),order_id BIGINT,batch_id BIGINT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE agent_card(id BIGINT PRIMARY KEY,merchant_customer_id BIGINT,intake_id BIGINT,inventory_code VARCHAR(64),order_item_id BIGINT,status_code VARCHAR(32),checked_in_at TIMESTAMP,returned_at TIMESTAMP,return_shipment_id BIGINT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE agent_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,merchant_customer_id BIGINT,client_id BIGINT,intake_id BIGINT,card_id BIGINT,event_code VARCHAR(48),note VARCHAR(2000),inventory_code VARCHAR(64))");
        cancellations = new AgentOrderCancellationService(client);
        service = new FinanceExceptionReviewService(client, manager, cancellations);
        hold(1, 11, "awaiting_inbound");
    }

    @ParameterizedTest
    @ValueSource(strings = {"awaiting_inbound", "inbound_shipped", "received", "intake_exception", "grading", "review", "quality_check", "quality_hold", "completed", "return_shipped", "delivered"})
    void verifiedFundsRestoreExactCheckpointAndWriteOneAuditedFullReceipt(String stage) {
        checkpoint(11, stage);
        var context = service.review(1, 9, request("restore", List.of(11L), "BANK-NEW-RECEIPT", TOTAL, "usd"));
        assertThat(context.statusCode()).isEqualTo(stage);
        assertThat(context.pendingExceptionIds()).isEmpty();
        var receipts = jdbc.queryForList("SELECT * FROM payment_record WHERE order_id=1 AND status_code='confirmed'");
        assertThat(receipts).singleElement().satisfies(row -> {
            assertThat(row.get("amount")).isEqualTo(TOTAL);
            assertThat(row.get("currency_code")).isEqualTo("USD");
            assertThat(row.get("provider_code")).isEqualTo("manual_transfer");
            assertThat(row.get("proof_reference")).isEqualTo("BANK-NEW-RECEIPT");
            assertThat(row.get("provider_transaction_id")).isEqualTo("BANK-NEW-RECEIPT");
            assertThat(((Number)row.get("confirmed_by_user_id")).longValue()).isEqualTo(9L);
        });
        // The reversed source payment is history; recovery appends a new full receipt instead of reviving it.
        assertThat(jdbc.queryForObject("SELECT status_code FROM payment_record WHERE payment_no='OLD-1'", String.class)).isEqualTo("reversed");
        assertThat(service.listForOrder(1)).singleElement().satisfies(row -> {
            assertThat(row.resolutionStatusCode()).isEqualTo("funds_verified");
            assertThat(row.resolutionAction()).isEqualTo("restore");
            assertThat(row.evidenceReference()).isEqualTo("BANK-NEW-RECEIPT");
            assertThat(row.resolutionNote()).isEqualTo("Reviewed provider reversal and verified new funds");
            assertThat(row.replacementPaymentId()).isEqualTo(((Number)receipts.get(0).get("id")).longValue());
            assertThat(row.resolvedByUserId()).isEqualTo(9L);
            assertThat(row.resolvedAt()).isNotNull();
        });
        assertThat(jdbc.queryForList("SELECT visible_to_customer,detail,actor_admin_user_id FROM order_timeline_event ORDER BY id")).hasSize(2).satisfies(rows -> {
            assertThat(((Number)rows.get(0).get("visible_to_customer")).intValue()).isZero();
            assertThat(rows.get(0).get("detail")).asString().contains("BANK-NEW-RECEIPT", "Action: restore");
            assertThat(((Number)rows.get(1).get("visible_to_customer")).intValue()).isEqualTo(1);
            assertThat(rows.get(1).get("detail")).asString().doesNotContain("BANK-NEW-RECEIPT");
        });
        Snapshot after = snapshot();
        assertRejectedUnchanged(() -> service.review(1, 9, request("restore", List.of(11L), "BANK-OTHER", TOTAL, "USD")), HttpStatus.CONFLICT, after);
    }

    @Test void invalidProofAmountCurrencyAndReasonCannotMutateFinanceState() {
        for (ReviewRequest r : List.of(
            request("restore", List.of(11L), "", TOTAL, "USD"),
            request("restore", List.of(11L), "  ", TOTAL, "USD"),
            request("restore", List.of(11L), "NEW", null, "USD"),
            request("restore", List.of(11L), "NEW", BigDecimal.ZERO, "USD"),
            request("restore", List.of(11L), "NEW", new BigDecimal("99.99"), "USD"),
            request("restore", List.of(11L), "NEW", new BigDecimal("100.01"), "USD"),
            request("restore", List.of(11L), "NEW", TOTAL, "EUR"),
            request("restore", List.of(11L), "NEW", TOTAL, null),
            new ReviewRequest("restore", List.of(11L), "NEW", "", TOTAL, "USD"))) {
            assertRejectedUnchanged(() -> service.review(1, 9, r), HttpStatus.BAD_REQUEST, snapshot());
        }
    }

    @Test void oldProofOrProviderTransactionCannotBeRecordedAsNewMoney() {
        for (String reference : List.of("OLD-PROOF-1", "OLD-TXN-1")) {
            assertRejectedUnchanged(() -> service.review(1, 9, request("restore", List.of(11L), reference, TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
        }
        hold(2, 22, "awaiting_inbound");
        assertRejectedUnchanged(() -> service.review(1, 9, request("restore", List.of(11L), "OLD-PROOF-2", TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
    }

    @Test void staleMissingAndCrossOrderExceptionIdsCannotResolveAnyHold() {
        hold(2, 22, "awaiting_inbound");
        pending(12, 1, "awaiting_inbound");
        for (List<Long> ids : List.of(List.of(11L), List.of(999L), List.of(22L), List.of(11L, 12L, 22L))) {
            assertRejectedUnchanged(() -> service.review(1, 9, request("restore", ids, "NEW", TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
        }
        for (List<Long> ids : List.of(List.<Long>of(), List.of(11L, 11L), List.of(-1L))) {
            assertRejectedUnchanged(() -> service.review(1, 9, request("restore", ids, "NEW", TOTAL, "USD")), HttpStatus.BAD_REQUEST, snapshot());
        }
        service.review(1, 9, request("restore", List.of(11L, 12L), "NEW", TOTAL, "USD"));
        assertThat(service.listForOrder(1)).allMatch(e -> "funds_verified".equals(e.resolutionStatusCode()));
        assertThat(service.listForOrder(2)).singleElement().extracting(FinanceExceptionReviewService.FinanceException::resolutionStatusCode).isEqualTo("open");
    }

    @Test void manualReviewRemainsPendingAndRetainsCheckpointForLaterRecovery() {
        checkpoint(11, "grading");
        var context = service.review(1, 9, request("manual_review", List.of(11L), "CASE-REVIEW", null, null));
        assertThat(context.statusCode()).isEqualTo("payment_exception");
        assertThat(context.resumeStatusCode()).isEqualTo("grading");
        assertThat(context.pendingExceptionIds()).containsExactly(11L);
        assertThat(service.listForOrder(1)).singleElement().satisfies(e -> {
            assertThat(e.resolutionStatusCode()).isEqualTo("manual_review");
            assertThat(e.resolvedAt()).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE status_code='confirmed'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_timeline_event WHERE visible_to_customer=1", Long.class)).isZero();
        assertThat(service.review(1, 9, request("restore", List.of(11L), "NEW-AFTER-MANUAL", TOTAL, "USD")).statusCode()).isEqualTo("grading");
    }

    @Test void receivedCheckpointCannotCancelAndActualReceiptPreventsCustodyRollback() {
        checkpoint(11, "received");
        assertRejectedUnchanged(() -> service.review(1, 9, request("cancel", List.of(11L), "REFUND-NEW", null, null)), HttpStatus.CONFLICT, snapshot());
        checkpoint(11, "awaiting_inbound");
        jdbc.update("INSERT INTO order_intake_receipt VALUES(1,1)");
        assertThat(service.reviewContext(1).hasReceived()).isTrue();
        assertRejectedUnchanged(() -> service.review(1, 9, request("restore", List.of(11L), "NEW", TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
        assertRejectedUnchanged(() -> service.review(1, 9, request("cancel", List.of(11L), "REFUND-NEW", null, null)), HttpStatus.CONFLICT, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"received", "intake_exception", "grading", "review", "quality_check", "quality_hold", "quality_passed", "completed", "return_shipped", "delivered"})
    void actualItemProgressPreventsRestoringOrCancellingBeforeCustody(String itemStage) {
        jdbc.update("UPDATE grading_order_item SET status_code=? WHERE order_id=1", itemStage);
        assertRejectedUnchanged(() -> service.review(1, 9, request("restore", List.of(11L), "NEW", TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
        assertRejectedUnchanged(() -> service.review(1, 9, request("cancel", List.of(11L), "REFUND-NEW", null, null)), HttpStatus.CONFLICT, snapshot());
    }

    @Test void outboundShipmentAlsoPreventsCustodyRollback() {
        jdbc.update("INSERT INTO order_shipment VALUES(1,1,'outbound')");
        assertRejectedUnchanged(() -> service.review(1, 9, request("restore", List.of(11L), "NEW", TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
        assertRejectedUnchanged(() -> service.review(1, 9, request("cancel", List.of(11L), "REFUND-NEW", null, null)), HttpStatus.CONFLICT, snapshot());
    }

    @Test void masterShipmentBlocksCancellationWithoutAnyInventoryOrFinanceMutation() {
        agentInventory();
        jdbc.update("INSERT INTO merchant_batch_shipment VALUES(1,3,'inbound')");
        assertThat(service.reviewContext(1).canCancel()).isFalse();
        assertRejectedUnchanged(() -> service.review(1, 9, request("cancel", List.of(11L), "REFUND-NEW", null, null)), HttpStatus.CONFLICT, snapshot());
    }

    @Test void verifiedPreInboundCancellationReleasesOnlyCurrentAgentPointersAndRetainsHistory() {
        agentInventory();
        service.review(1, 9, request("cancel", List.of(11L), "REFUND-NEW", null, null));
        assertThat(status()).isEqualTo("cancelled");
        assertThat(jdbc.queryForMap("SELECT status_code,order_id,batch_id FROM agent_intake WHERE id=4")).containsEntry("status_code", "ready").containsEntry("order_id", null).containsEntry("batch_id", null);
        assertThat(jdbc.queryForMap("SELECT status_code,order_item_id FROM agent_card WHERE id=5")).containsEntry("status_code", "in_stock").containsEntry("order_item_id", null);
        assertThat(jdbc.queryForMap("SELECT order_id,front_photo_id FROM grading_order_item WHERE id=101")).containsEntry("order_id", 1L).containsEntry("front_photo_id", 700L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_batch_item WHERE order_id=1", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_code,note FROM agent_event ORDER BY id")).hasSize(2).allSatisfy(e -> assertThat(e.get("note")).asString().contains("ORDER-1", "#1"));
        assertThat(service.listForOrder(1)).singleElement().extracting(FinanceExceptionReviewService.FinanceException::resolutionStatusCode).isEqualTo("refund_verified");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE status_code='confirmed'", Long.class)).isZero();
    }

    @Test void releaseFailureRollsBackOrderExceptionsAuditAndRealInventoryChanges() {
        agentInventory();
        var failAfterRelease = new AgentOrderCancellationService(client) {
            @Override public void releaseCancelledOrder(long orderId) {
                super.releaseCancelledOrder(orderId);
                throw new IllegalStateException("Inventory audit write failed");
            }
        };
        var failing = new FinanceExceptionReviewService(client, manager, failAfterRelease);
        Snapshot before = snapshot();
        assertThatThrownBy(() -> failing.review(1, 9, request("cancel", List.of(11L), "REFUND-NEW", null, null))).isInstanceOf(IllegalStateException.class).hasMessageContaining("audit write failed");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void legacyHoldDoesNotGuessProgressAndOnlyUsesPriorTimeline() {
        jdbc.update("UPDATE payment_finance_exception SET resolution_note=NULL,created_at=? WHERE id=11", Timestamp.valueOf("2026-01-02 00:00:00"));
        assertThat(service.reviewContext(1).resumeStatusCode()).isNull();
        assertRejectedUnchanged(() -> service.review(1, 9, request("restore", List.of(11L), "NEW", TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
        timeline("grading", "2026-01-03 00:00:00");
        assertThat(service.reviewContext(1).canRestore()).isFalse();
        timeline("review", "2026-01-01 00:00:00");
        assertThat(service.reviewContext(1).resumeStatusCode()).isEqualTo("review");
        assertThat(service.review(1, 9, request("restore", List.of(11L), "NEW", TOTAL, "USD")).statusCode()).isEqualTo("review");
    }

    @ParameterizedTest
    @ValueSource(strings = {"in_transit", "quality_passed", "not_a_stage"})
    void unregisteredOrderCheckpointCannotBeUsedToGuessARestoredStage(String checkpoint) {
        checkpoint(11, checkpoint);
        assertThat(service.reviewContext(1).canRestore()).isFalse();
        assertRejectedUnchanged(() -> service.review(1, 9, request("restore", List.of(11L), "NEW", TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
    }

    @Test void conflictingCheckpointsStayPausedRatherThanChoosingOne() {
        pending(12, 1, "grading");
        assertThat(service.reviewContext(1).resumeStatusCode()).isNull();
        assertRejectedUnchanged(() -> service.review(1, 9, request("restore", List.of(11L, 12L), "NEW", TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
    }

    @Test void activeCheckoutAndOtherConfirmedFundsRequireManualReview() {
        jdbc.update("INSERT INTO payment_attempt VALUES(1,1,1,'created')");
        for (String action : List.of("restore", "cancel")) {
            assertRejectedUnchanged(() -> service.review(1, 9, request(action, List.of(11L), "NEW", TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
        }
        jdbc.update("UPDATE payment_attempt SET active_order_id=NULL,status_code='reversed'");
        jdbc.update("INSERT INTO payment_record(order_id,direction_code,payment_type_code,payment_no,provider_code,status_code,amount,currency_code,provider_transaction_id) VALUES(1,'receivable','grading_fee','OTHER','manual_transfer','confirmed',100,'USD','OTHER-PAID')");
        for (String action : List.of("restore", "cancel")) {
            assertRejectedUnchanged(() -> service.review(1, 9, request(action, List.of(11L), "NEW", TOTAL, "USD")), HttpStatus.CONFLICT, snapshot());
        }
        assertThat(service.review(1, 9, request("manual_review", List.of(11L), "CASE-OTHER-FUNDS", null, null)).statusCode()).isEqualTo("payment_exception");
    }

    @Test void twoStaffRestoringSameOrderProduceOneSuccessAndOneReceipt() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(pool.submit(() -> restoreAfter(start, "CONCURRENT-A")), pool.submit(() -> restoreAfter(start, "CONCURRENT-B")));
            start.countDown();
            assertThat(List.of(futures.get(0).get(5, TimeUnit.SECONDS), futures.get(1).get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        assertThat(status()).isEqualTo("awaiting_inbound");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE status_code='confirmed'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_timeline_event WHERE event_code='finance_review_completed'", Long.class)).isEqualTo(1);
    }

    @Test void twoDifferentOrdersCannotBothReuseOneNewBankReceipt() throws Exception {
        hold(2, 22, "awaiting_inbound");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(pool.submit(() -> restoreAfter(start, "SHARED-BANK-RECEIPT", 1, 11)), pool.submit(() -> restoreAfter(start, "SHARED-BANK-RECEIPT", 2, 22)));
            start.countDown();
            assertThat(List.of(futures.get(0).get(5, TimeUnit.SECONDS), futures.get(1).get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE status_code='confirmed'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT status_code FROM grading_order ORDER BY id")).extracting(row -> row.get("status_code")).containsExactlyInAnyOrder("awaiting_inbound", "payment_exception");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_finance_exception WHERE resolution_status_code='open'", Long.class)).isEqualTo(1);
    }

    private int restoreAfter(CountDownLatch start, String proof) throws Exception {
        return restoreAfter(start, proof, 1, 11);
    }
    private int restoreAfter(CountDownLatch start, String proof, long orderId, long exceptionId) throws Exception {
        if (!start.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
        try { service.review(orderId, 9, request("restore", List.of(exceptionId), proof, TOTAL, "USD")); return 200; }
        catch (ResponseStatusException rejected) { return rejected.getStatusCode().value(); }
    }
    private ReviewRequest request(String action, List<Long> ids, String proof, BigDecimal amount, String currency) {
        return new ReviewRequest(action, ids, proof, "Reviewed provider reversal and verified new funds", amount, currency);
    }
    private void hold(long order, long exception, String stage) {
        jdbc.update("INSERT INTO grading_order VALUES(?, ?,1,'payment_exception',100,'USD',CURRENT_TIMESTAMP)", order, "ORDER-" + order);
        jdbc.update("INSERT INTO grading_order_item VALUES(?,?,'awaiting_inbound',700,701)", order * 100 + 1, order);
        jdbc.update("INSERT INTO payment_record(order_id,direction_code,payment_type_code,payment_no,provider_code,status_code,amount,currency_code,proof_reference,provider_transaction_id) VALUES(?,'receivable','grading_fee',?,'paypal','reversed',100,'USD',?,?)", order, "OLD-" + order, "OLD-PROOF-" + order, "OLD-TXN-" + order);
        pending(exception, order, stage);
    }
    private void pending(long id, long order, String stage) {
        long payment = jdbc.queryForObject("SELECT id FROM payment_record WHERE payment_no=?", Long.class, "OLD-" + order);
        jdbc.update("INSERT INTO payment_finance_exception(id,order_id,payment_record_id,payment_attempt_id,provider_code,provider_event_id,provider_transaction_id,exception_type_code,amount,currency_code,resolution_status_code,resolution_note) VALUES(?, ?, ?,1,'paypal',?,?,'reversed',48,'USD','open',?)", id, order, payment, "EVENT-" + id, "REVERSAL-" + id, FinanceExceptionReviewService.pauseMetadata(stage));
    }
    private void checkpoint(long id, String stage) { jdbc.update("UPDATE payment_finance_exception SET resolution_note=? WHERE id=?", FinanceExceptionReviewService.pauseMetadata(stage), id); }
    private void timeline(String stage, String time) {
        jdbc.update("INSERT INTO order_timeline_event(order_id,event_code,status_code,created_at) VALUES(1,'status_changed',?,?)", stage, Timestamp.valueOf(time));
    }
    private void agentInventory() {
        jdbc.update("INSERT INTO merchant_order_batch VALUES(3,1,'BATCH-3','open')");
        jdbc.update("INSERT INTO merchant_order_batch_item VALUES(31,3,1,'INTAKE-4')");
        jdbc.update("INSERT INTO agent_intake VALUES(4,1,2,1,'submitted',1,3,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO agent_card VALUES(5,1,4,'CARD-5',101,'submitted',CURRENT_TIMESTAMP,NULL,NULL,CURRENT_TIMESTAMP)");
    }
    private String status() { return jdbc.queryForObject("SELECT status_code FROM grading_order WHERE id=1", String.class); }
    private Snapshot snapshot() {
        return new Snapshot(jdbc.queryForList("SELECT * FROM grading_order ORDER BY id"), jdbc.queryForList("SELECT * FROM payment_record ORDER BY id"), jdbc.queryForList("SELECT * FROM payment_finance_exception ORDER BY id"), jdbc.queryForList("SELECT * FROM order_timeline_event ORDER BY id"), jdbc.queryForList("SELECT * FROM agent_intake ORDER BY id"), jdbc.queryForList("SELECT * FROM agent_card ORDER BY id"), jdbc.queryForList("SELECT * FROM agent_event ORDER BY id"));
    }
    private void assertRejectedUnchanged(Runnable action, HttpStatus expected, Snapshot before) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(expected));
        assertThat(snapshot()).isEqualTo(before);
    }
    private record Snapshot(List<Map<String,Object>> orders, List<Map<String,Object>> payments, List<Map<String,Object>> exceptions, List<Map<String,Object>> timeline, List<Map<String,Object>> intakes, List<Map<String,Object>> cards, List<Map<String,Object>> events) { }
}
