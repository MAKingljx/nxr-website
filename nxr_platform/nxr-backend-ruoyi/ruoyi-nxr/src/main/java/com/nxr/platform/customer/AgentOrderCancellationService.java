package com.nxr.platform.customer;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Releases current inventory links inside the owning order-cancellation transaction, retaining its history. */
@Service
public class AgentOrderCancellationService {
    private final JdbcClient jdbc;

    public AgentOrderCancellationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void releaseCancelledOrder(long orderId) {
        List<IntakeLink> intakes = jdbc.sql("""
            SELECT i.id, i.merchant_customer_id, i.client_id, i.expected_card_count, i.status_code,
                   o.order_no, i.batch_id,
                   (SELECT b.batch_no FROM merchant_order_batch b
                    WHERE b.id = i.batch_id AND b.merchant_customer_id = i.merchant_customer_id) AS batch_no
            FROM agent_intake i
            JOIN grading_order o ON o.id = i.order_id AND o.customer_id = i.merchant_customer_id
            WHERE o.id = :orderId AND o.status_code = 'cancelled' FOR UPDATE
            """).param("orderId", orderId).query((rs, n) -> new IntakeLink(
                rs.getLong("id"), rs.getLong("merchant_customer_id"), rs.getLong("client_id"),
                rs.getInt("expected_card_count"), rs.getString("status_code"), rs.getString("order_no"),
                rs.getObject("batch_id", Long.class), rs.getString("batch_no")
            )).list();
        for (IntakeLink intake : intakes) {
            // The caller already locks the order. Do not also lock the batch: shipment takes those locks in reverse order.
            if (intake.batchId() != null && jdbc.sql("""
                SELECT COUNT(*) FROM merchant_batch_shipment WHERE batch_id = :batch AND direction_code = 'inbound'
                """).param("batch", intake.batchId()).query(Long.class).single() > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The master parcel has already shipped; review card custody before cancelling this order");
            }
            List<CardLink> cards = jdbc.sql("""
                SELECT c.id, c.inventory_code, c.order_item_id, c.status_code, c.checked_in_at,
                       c.returned_at, c.return_shipment_id
                FROM agent_card c JOIN grading_order_item oi ON oi.id = c.order_item_id AND oi.order_id = :orderId
                WHERE c.intake_id = :intake AND c.merchant_customer_id = :owner ORDER BY c.id FOR UPDATE
                """).param("orderId", orderId).param("intake", intake.id()).param("owner", intake.owner())
                .query((rs, n) -> new CardLink(rs.getLong("id"), rs.getString("inventory_code"),
                    rs.getLong("order_item_id"), rs.getString("status_code"),
                    rs.getObject("checked_in_at", LocalDateTime.class), rs.getObject("returned_at", LocalDateTime.class),
                    rs.getObject("return_shipment_id", Long.class))).list();
            if (!"submitted".equals(intake.status()) || cards.size() != intake.expectedCount()
                || cards.stream().anyMatch(card -> !"submitted".equals(card.status()) || card.checkedInAt() == null
                    || card.returnedAt() != null || card.returnShipmentId() != null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The intake has changed; review its card custody before cancelling this order");
            }
            String source = "Cancelled order " + intake.orderNo() + " (#" + orderId + ")"
                + (intake.batchId() == null ? "" : " from batch " + intake.batchNo() + " (#" + intake.batchId() + ")");
            // Audit the exact former card/item mapping before clearing only its current submission pointer.
            for (CardLink card : cards) {
                event(intake, card.id(), card.inventoryCode(), "card_submission_cancelled",
                    source + ", order item #" + card.orderItemId() + ". Card is available for resubmission.");
                int changed = jdbc.sql("""
                    UPDATE agent_card SET status_code = 'in_stock', order_item_id = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :card AND merchant_customer_id = :owner AND order_item_id = :item AND status_code = 'submitted'
                    """).param("card", card.id()).param("owner", intake.owner()).param("item", card.orderItemId()).update();
                if (changed != 1) throw new IllegalStateException("Cancelled card submission changed while locked");
            }
            int changed = jdbc.sql("""
                UPDATE agent_intake SET status_code = 'ready', order_id = NULL, batch_id = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = :intake AND merchant_customer_id = :owner AND order_id = :orderId AND status_code = 'submitted'
                """).param("intake", intake.id()).param("owner", intake.owner()).param("orderId", orderId).update();
            if (changed != 1) throw new IllegalStateException("Cancelled intake submission changed while locked");
            event(intake, null, null, "intake_submission_cancelled", source + ". Intake is ready for resubmission.");
        }
    }

    private void event(IntakeLink intake, Long cardId, String inventoryCode, String code, String note) {
        jdbc.sql("""
            INSERT INTO agent_event (merchant_customer_id, client_id, intake_id, card_id, event_code, note, inventory_code)
            VALUES (:owner, :client, :intake, :card, :code, :note, :inventory)
            """).param("owner", intake.owner()).param("client", intake.clientId()).param("intake", intake.id())
            .param("card", cardId).param("code", code).param("note", note).param("inventory", inventoryCode).update();
    }

    private record IntakeLink(long id, long owner, long clientId, int expectedCount, String status,
                             String orderNo, Long batchId, String batchNo) { }
    private record CardLink(long id, String inventoryCode, long orderItemId, String status, LocalDateTime checkedInAt,
                            LocalDateTime returnedAt, Long returnShipmentId) { }
}
