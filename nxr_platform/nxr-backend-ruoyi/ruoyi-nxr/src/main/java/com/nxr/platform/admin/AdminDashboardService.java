package com.nxr.platform.admin;

import com.nxr.platform.shared.ProductTypePolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AdminDashboardService {

    private static final int TREND_DAYS = 30;
    private static final int RECENT_LIMIT = 5;
    private static final int ACTION_LIMIT = 8;

    private final JdbcClient jdbcClient;

    public AdminDashboardService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AdminDashboardResponse loadDashboard() {
        Integer totalSubmissions = jdbcClient.sql("SELECT COUNT(*) FROM grading_submission")
            .query(Integer.class)
            .single();
        Integer pendingReview = jdbcClient.sql(
                "SELECT COUNT(*) FROM grading_submission WHERE status_code IN ('pending', 'review')"
            )
            .query(Integer.class)
            .single();
        Integer approvedReady = jdbcClient.sql(
                "SELECT COUNT(*) FROM grading_submission WHERE status_code = 'approved'"
            )
            .query(Integer.class)
            .single();
        Integer publishedCertificates = jdbcClient.sql("SELECT COUNT(*) FROM published_certificate")
            .query(Integer.class)
            .single();
        Integer waitlistCount = jdbcClient.sql("SELECT COUNT(*) FROM waitlist_email")
            .query(Integer.class)
            .single();

        return new AdminDashboardResponse(
            totalSubmissions,
            pendingReview,
            approvedReady,
            publishedCertificates,
            waitlistCount,
            loadActivityTrend(),
            loadProductMix(),
            loadOrderPipeline(),
            loadMediaStatus(),
            loadActionItems(),
            loadRecentEntries(),
            loadRecentOrders(),
            loadRecentPublished()
        );
    }

    private List<DailyActivity> loadActivityTrend() {
        LocalDate today = LocalDate.now();
        LocalDate firstDay = today.minusDays(TREND_DAYS - 1L);
        LocalDateTime fromDate = firstDay.atStartOfDay();
        Map<LocalDate, DailyActivityAccumulator> activityByDate = new LinkedHashMap<>();
        for (int dayOffset = 0; dayOffset < TREND_DAYS; dayOffset += 1) {
            activityByDate.put(firstDay.plusDays(dayOffset), new DailyActivityAccumulator());
        }

        jdbcClient.sql(
                """
                SELECT CAST(created_at AS DATE) AS activity_date, COUNT(*) AS activity_count
                FROM grading_submission
                WHERE created_at >= :fromDate
                GROUP BY CAST(created_at AS DATE)
                ORDER BY activity_date
                """
            )
            .param("fromDate", fromDate)
            .query((rs, rowNum) -> new DatedCount(
                rs.getObject("activity_date", LocalDate.class),
                rs.getInt("activity_count")
            ))
            .list()
            .forEach(item -> {
                DailyActivityAccumulator accumulator = activityByDate.get(item.date());
                if (accumulator != null) {
                    accumulator.created = item.count();
                }
            });

        jdbcClient.sql(
                """
                SELECT CAST(published_at AS DATE) AS activity_date, COUNT(*) AS activity_count
                FROM published_certificate
                WHERE published_at >= :fromDate
                GROUP BY CAST(published_at AS DATE)
                ORDER BY activity_date
                """
            )
            .param("fromDate", fromDate)
            .query((rs, rowNum) -> new DatedCount(
                rs.getObject("activity_date", LocalDate.class),
                rs.getInt("activity_count")
            ))
            .list()
            .forEach(item -> {
                DailyActivityAccumulator accumulator = activityByDate.get(item.date());
                if (accumulator != null) {
                    accumulator.published = item.count();
                }
            });

        return activityByDate.entrySet().stream()
            .map(entry -> new DailyActivity(entry.getKey(), entry.getValue().created, entry.getValue().published))
            .toList();
    }

    private List<CategoryCount> loadProductMix() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(ProductTypePolicy.GRADED_CARD, 0);
        counts.put(ProductTypePolicy.MERCH_PRODUCT, 0);
        counts.put(ProductTypePolicy.VINTAGE_PRODUCT, 0);

        jdbcClient.sql(
                """
                SELECT COALESCE(NULLIF(product_type_code, ''), 'graded_card') AS product_type_code,
                       COUNT(*) AS product_count
                FROM grading_submission
                GROUP BY COALESCE(NULLIF(product_type_code, ''), 'graded_card')
                """
            )
            .query((rs, rowNum) -> new CategoryCount(
                ProductTypePolicy.normalizeStored(rs.getString("product_type_code")),
                rs.getInt("product_count")
            ))
            .list()
            .forEach(item -> counts.merge(item.code(), item.count(), Integer::sum));

        return counts.entrySet().stream()
            .map(entry -> new CategoryCount(entry.getKey(), entry.getValue()))
            .toList();
    }

    private List<PipelineStage> loadOrderPipeline() {
        Map<String, PipelineAccumulator> stages = new LinkedHashMap<>();
        stages.put("payment", new PipelineAccumulator());
        stages.put("inbound", new PipelineAccumulator());
        stages.put("grading", new PipelineAccumulator());
        stages.put("return", new PipelineAccumulator());
        stages.put("completed", new PipelineAccumulator());
        stages.put("cancelled", new PipelineAccumulator());

        jdbcClient.sql(
                """
                SELECT status_code, COUNT(*) AS order_count, COALESCE(SUM(total_card_count), 0) AS card_count
                FROM grading_order
                GROUP BY status_code
                """
            )
            .query((rs, rowNum) -> new RawOrderStage(
                rs.getString("status_code"),
                rs.getInt("order_count"),
                rs.getInt("card_count")
            ))
            .list()
            .forEach(item -> {
                String stageCode = orderStage(item.statusCode());
                PipelineAccumulator stage = stages.get(stageCode);
                stage.orders += item.orderCount();
                stage.cards += item.cardCount();
            });

        return stages.entrySet().stream()
            .map(entry -> new PipelineStage(entry.getKey(), entry.getValue().orders, entry.getValue().cards))
            .toList();
    }

    private MediaStatus loadMediaStatus() {
        return jdbcClient.sql(
                """
                SELECT
                    COUNT(*) AS tracked_entries,
                    COALESCE(SUM(CASE WHEN
                        EXISTS (
                            SELECT 1 FROM submission_media sm
                            WHERE sm.submission_id = s.id
                              AND sm.media_stage_code = 'published'
                              AND sm.media_side_code = 'front'
                              AND sm.is_active = 1
                        )
                        AND EXISTS (
                            SELECT 1 FROM submission_media sm
                            WHERE sm.submission_id = s.id
                              AND sm.media_stage_code = 'published'
                              AND sm.media_side_code = 'back'
                              AND sm.is_active = 1
                        )
                        THEN 1 ELSE 0 END), 0) AS published,
                    COALESCE(SUM(CASE WHEN
                        NOT (
                            EXISTS (
                                SELECT 1 FROM submission_media sm
                                WHERE sm.submission_id = s.id
                                  AND sm.media_stage_code = 'published'
                                  AND sm.media_side_code = 'front'
                                  AND sm.is_active = 1
                            )
                            AND EXISTS (
                                SELECT 1 FROM submission_media sm
                                WHERE sm.submission_id = s.id
                                  AND sm.media_stage_code = 'published'
                                  AND sm.media_side_code = 'back'
                                  AND sm.is_active = 1
                            )
                        )
                        AND EXISTS (
                            SELECT 1 FROM submission_media sm
                            WHERE sm.submission_id = s.id
                              AND sm.media_stage_code = 'staged'
                              AND sm.media_side_code = 'front'
                              AND sm.is_active = 1
                        )
                        AND EXISTS (
                            SELECT 1 FROM submission_media sm
                            WHERE sm.submission_id = s.id
                              AND sm.media_stage_code = 'staged'
                              AND sm.media_side_code = 'back'
                              AND sm.is_active = 1
                        )
                        THEN 1 ELSE 0 END), 0) AS ready_to_publish
                FROM grading_submission s
                WHERE s.status_code IN ('approved', 'published')
                """
            )
            .query((rs, rowNum) -> {
                int tracked = rs.getInt("tracked_entries");
                int published = rs.getInt("published");
                int ready = rs.getInt("ready_to_publish");
                return new MediaStatus(tracked, Math.max(0, tracked - published - ready), ready, published);
            })
            .single();
    }

    private List<ActionItem> loadActionItems() {
        List<ActionItem> items = new ArrayList<>();

        items.addAll(jdbcClient.sql(
                """
                SELECT id, cert_id, card_name, status_code, created_at
                FROM grading_submission
                WHERE status_code IN ('pending', 'review')
                ORDER BY created_at ASC, id ASC
                LIMIT 5
                """
            )
            .query((rs, rowNum) -> new ActionItem(
                rs.getLong("id"),
                "review",
                rs.getString("cert_id"),
                rs.getString("card_name"),
                rs.getString("status_code"),
                rs.getObject("created_at", LocalDateTime.class),
                "/nxr/main/pending-review"
            ))
            .list());

        items.addAll(jdbcClient.sql(
                """
                SELECT queue.id, queue.cert_id, queue.card_name, queue.action_kind, queue.action_status, queue.action_at
                FROM (
                    SELECT
                        s.id,
                        s.cert_id,
                        s.card_name,
                        CASE WHEN
                            EXISTS (
                                SELECT 1 FROM submission_media sm
                                WHERE sm.submission_id = s.id
                                  AND sm.media_stage_code = 'staged'
                                  AND sm.media_side_code = 'front'
                                  AND sm.is_active = 1
                            )
                            AND EXISTS (
                                SELECT 1 FROM submission_media sm
                                WHERE sm.submission_id = s.id
                                  AND sm.media_stage_code = 'staged'
                                  AND sm.media_side_code = 'back'
                                  AND sm.is_active = 1
                            )
                            THEN 'publication' ELSE 'media' END AS action_kind,
                        CASE WHEN
                            EXISTS (
                                SELECT 1 FROM submission_media sm
                                WHERE sm.submission_id = s.id
                                  AND sm.media_stage_code = 'staged'
                                  AND sm.media_side_code = 'front'
                                  AND sm.is_active = 1
                            )
                            AND EXISTS (
                                SELECT 1 FROM submission_media sm
                                WHERE sm.submission_id = s.id
                                  AND sm.media_stage_code = 'staged'
                                  AND sm.media_side_code = 'back'
                                  AND sm.is_active = 1
                            )
                            THEN 'ready_to_publish' ELSE 'missing_images' END AS action_status,
                        COALESCE(s.approved_at, s.updated_at, s.created_at) AS action_at
                    FROM grading_submission s
                    WHERE s.status_code = 'approved'
                ) queue
                ORDER BY queue.action_at ASC, queue.id ASC
                LIMIT 5
                """
            )
            .query((rs, rowNum) -> new ActionItem(
                rs.getLong("id"),
                rs.getString("action_kind"),
                rs.getString("cert_id"),
                rs.getString("card_name"),
                rs.getString("action_status"),
                rs.getObject("action_at", LocalDateTime.class),
                "/nxr/upload"
            ))
            .list());

        items.addAll(jdbcClient.sql(
                """
                SELECT id, order_no, status_code, created_at
                FROM grading_order
                WHERE status_code NOT IN ('delivered', 'cancelled')
                ORDER BY created_at ASC, id ASC
                LIMIT 5
                """
            )
            .query((rs, rowNum) -> new ActionItem(
                rs.getLong("id"),
                isPaymentStatus(rs.getString("status_code")) ? "payment" : "order",
                rs.getString("order_no"),
                null,
                rs.getString("status_code"),
                rs.getObject("created_at", LocalDateTime.class),
                "/nxr/submissions/orders"
            ))
            .list());

        return items.stream()
            .sorted(Comparator.comparing(ActionItem::actionAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(ACTION_LIMIT)
            .toList();
    }

    private List<RecentEntry> loadRecentEntries() {
        return jdbcClient.sql(
                """
                SELECT id, cert_id, COALESCE(NULLIF(product_type_code, ''), 'graded_card') AS product_type_code,
                       card_name, status_code, created_at
                FROM grading_submission
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """
            )
            .param("limit", RECENT_LIMIT)
            .query((rs, rowNum) -> new RecentEntry(
                rs.getLong("id"),
                rs.getString("cert_id"),
                ProductTypePolicy.normalizeStored(rs.getString("product_type_code")),
                rs.getString("card_name"),
                rs.getString("status_code"),
                rs.getObject("created_at", LocalDateTime.class)
            ))
            .list();
    }

    private List<RecentOrder> loadRecentOrders() {
        return jdbcClient.sql(
                """
                SELECT id, order_no, status_code, total_card_count, created_at
                FROM grading_order
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """
            )
            .param("limit", RECENT_LIMIT)
            .query((rs, rowNum) -> new RecentOrder(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("status_code"),
                rs.getInt("total_card_count"),
                rs.getObject("created_at", LocalDateTime.class)
            ))
            .list();
    }

    private List<RecentPublishedCard> loadRecentPublished() {
        return jdbcClient.sql(
                """
                SELECT
                    s.cert_id,
                    COALESCE(NULLIF(s.product_type_code, ''), 'graded_card') AS product_type_code,
                    s.vintage_classification_code,
                    s.merch_description,
                    s.card_name,
                    s.brand_name,
                    g.final_grade_value,
                    g.final_grade_label,
                    pc.published_at
                FROM published_certificate pc
                JOIN grading_submission s ON s.id = pc.submission_id
                LEFT JOIN grading_score g ON g.submission_id = s.id
                ORDER BY pc.published_at DESC, s.cert_id ASC
                LIMIT :limit
                """
            )
            .param("limit", RECENT_LIMIT)
            .query((rs, rowNum) -> new RecentPublishedCard(
                rs.getString("cert_id"),
                ProductTypePolicy.normalizeStored(rs.getString("product_type_code")),
                rs.getString("vintage_classification_code"),
                rs.getString("merch_description"),
                rs.getString("card_name"),
                rs.getString("brand_name"),
                rs.getBigDecimal("final_grade_value"),
                rs.getString("final_grade_label"),
                rs.getObject("published_at", LocalDateTime.class)
            ))
            .list();
    }

    private static String orderStage(String statusCode) {
        if (statusCode == null) {
            return "grading";
        }
        return switch (statusCode) {
            case "awaiting_payment", "payment_review" -> "payment";
            case "awaiting_inbound", "inbound_shipped", "intake_exception" -> "inbound";
            case "received", "grading", "review", "quality_check", "quality_hold" -> "grading";
            case "completed", "return_shipped" -> "return";
            case "delivered" -> "completed";
            case "cancelled" -> "cancelled";
            default -> "grading";
        };
    }

    private static boolean isPaymentStatus(String statusCode) {
        return "awaiting_payment".equals(statusCode) || "payment_review".equals(statusCode);
    }

    private static final class DailyActivityAccumulator {
        private int created;
        private int published;
    }

    private static final class PipelineAccumulator {
        private int orders;
        private int cards;
    }

    private record DatedCount(LocalDate date, int count) {
    }

    private record RawOrderStage(String statusCode, int orderCount, int cardCount) {
    }

    public record AdminDashboardResponse(
        int totalSubmissions,
        int pendingReview,
        int approvedReady,
        int publishedCertificates,
        int waitlistCount,
        List<DailyActivity> activityTrend,
        List<CategoryCount> productMix,
        List<PipelineStage> orderPipeline,
        MediaStatus mediaStatus,
        List<ActionItem> actionItems,
        List<RecentEntry> recentEntries,
        List<RecentOrder> recentOrders,
        List<RecentPublishedCard> recentPublished
    ) {
    }

    public record DailyActivity(LocalDate date, int created, int published) {
    }

    public record CategoryCount(String code, int count) {
    }

    public record PipelineStage(String code, int orders, int cards) {
    }

    public record MediaStatus(int tracked, int missing, int ready, int published) {
    }

    public record ActionItem(
        long id,
        String kind,
        String reference,
        String title,
        String statusCode,
        LocalDateTime actionAt,
        String targetPath
    ) {
    }

    public record RecentEntry(
        long id,
        String certId,
        String productType,
        String cardName,
        String statusCode,
        LocalDateTime createdAt
    ) {
    }

    public record RecentOrder(long id, String orderNo, String statusCode, int cardCount, LocalDateTime createdAt) {
    }

    public record RecentPublishedCard(
        String certId,
        String productType,
        String vintageClassification,
        String merchDescription,
        String cardName,
        String brandName,
        BigDecimal finalGradeValue,
        String finalGradeLabel,
        LocalDateTime publishedAt
    ) {
    }
}
