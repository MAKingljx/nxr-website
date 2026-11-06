package com.nxr.platform.admin;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Moves an order to Ready to Return only when every card is both graded and
 * has its published front/back images. Called from grading approval and media
 * publication so the order status always follows the real card state.
 */
@Service
public class OrderReturnReadinessService {
    private static final Set<String> READY_FROM_STATUSES =
        Set.of("received", "grading", "review", "quality_check", "quality_hold");

    private final JdbcClient jdbcClient;

    public OrderReturnReadinessService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void markReadyForReturn(long submissionId, Long userId) {
        List<Long> orderIds = jdbcClient.sql(
                "SELECT DISTINCT order_id FROM grading_order_item WHERE grading_submission_id = :submissionId"
            ).param("submissionId", submissionId).query(Long.class).list();
        for (Long orderId : orderIds) {
            if (orderId == null) continue;
            OrderProgress progress = jdbcClient.sql(
                    """
                    SELECT o.status_code AS order_status,
                           COUNT(oi.id) AS total_items,
                           SUM(CASE WHEN s.status_code IN ('approved', 'published')
                                     AND (SELECT COUNT(*) FROM submission_media sm
                                           WHERE sm.submission_id = s.id
                                             AND sm.media_stage_code = 'published'
                                             AND sm.is_active = 1
                                             AND sm.media_side_code IN ('front', 'back')) >= 2
                                    THEN 1 ELSE 0 END) AS ready_items
                    FROM grading_order o
                    JOIN grading_order_item oi ON oi.order_id = o.id
                    LEFT JOIN grading_submission s ON s.id = oi.grading_submission_id
                    WHERE o.id = :orderId
                    GROUP BY o.id, o.status_code
                    """
                ).param("orderId", orderId)
                .query((rs, rowNum) -> new OrderProgress(
                    rs.getString("order_status"), rs.getInt("total_items"), rs.getInt("ready_items")))
                .optional().orElse(null);
            if (progress == null || progress.totalItems() < 1 || progress.readyItems() != progress.totalItems()) {
                continue;
            }
            if (!READY_FROM_STATUSES.contains(progress.statusCode())) {
                continue;
            }
            int changed = jdbcClient.sql(
                    """
                    UPDATE grading_order
                    SET status_code = 'completed', updated_at = CURRENT_TIMESTAMP
                    WHERE id = :orderId
                      AND status_code IN ('received', 'grading', 'review', 'quality_check', 'quality_hold')
                    """
                ).param("orderId", orderId).update();
            if (changed == 1) {
                jdbcClient.sql(
                        """
                        INSERT INTO order_timeline_event
                            (order_id, event_code, title, detail, status_code, visible_to_customer, actor_type_code, actor_admin_user_id)
                        VALUES (:orderId, 'ready_to_return', 'Ready to return',
                                'All cards in this order are graded and their images are published. The order is ready to be returned.',
                                'completed', 1, 'admin', :userId)
                        """
                    ).param("orderId", orderId).param("userId", userId).update();
            }
        }
    }

    private record OrderProgress(String statusCode, int totalItems, int readyItems) {
    }
}
