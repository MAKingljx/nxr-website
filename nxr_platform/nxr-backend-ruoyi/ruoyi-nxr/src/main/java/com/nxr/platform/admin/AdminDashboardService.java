package com.nxr.platform.admin;

import com.nxr.platform.shared.ProductTypePolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminDashboardService {

    private static final int TREND_DAYS = 30;
    private static final int RECENT_LIMIT = 5;
    private static final int ACTION_LIMIT = 8;

    private final JdbcClient jdbcClient;
    private final AdminMediaService mediaService;

    public AdminDashboardService(JdbcClient jdbcClient) {
        this(jdbcClient, null);
    }

    @Autowired
    public AdminDashboardService(JdbcClient jdbcClient, AdminMediaService mediaService) {
        this.jdbcClient = jdbcClient;
        this.mediaService = mediaService;
    }

    public AdminDashboardResponse loadDashboard() {
        return loadDashboard(true, true);
    }

    public AdminDashboardResponse loadDashboard(boolean supportAccess, boolean financeAccess) {
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
            loadActionItems(supportAccess, financeAccess),
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
                if (mediaService != null) ready = loadAvailableStagedCount();
                return new MediaStatus(tracked, Math.max(0, tracked - published - ready), ready, published);
            })
            .single();
    }

    private int loadAvailableStagedCount() {
        // Inspect only staged pairs awaiting initial publication. This keeps the
        // homepage fast even when the published catalogue contains many images.
        return jdbcClient.sql("""
            SELECT sf.storage_provider_code AS front_provider, sf.storage_bucket AS front_bucket,
                   sf.storage_key AS front_key, sf.storage_object_version AS front_version,
                   sf.public_url AS front_url,
                   sb.storage_provider_code AS back_provider, sb.storage_bucket AS back_bucket,
                   sb.storage_key AS back_key, sb.storage_object_version AS back_version,
                   sb.public_url AS back_url
            FROM grading_submission s
            JOIN submission_media sf ON sf.submission_id=s.id AND sf.media_stage_code='staged'
                AND sf.media_side_code='front' AND sf.sort_order=1 AND sf.is_active=1
            JOIN submission_media sb ON sb.submission_id=s.id AND sb.media_stage_code='staged'
                AND sb.media_side_code='back' AND sb.sort_order=1 AND sb.is_active=1
            LEFT JOIN submission_upload_state us ON us.submission_id=s.id
            WHERE s.status_code IN ('approved', 'published')
              AND COALESCE(us.status_code, 'not_started') NOT IN ('uploading', 'client_pushed')
              AND NOT (
                  EXISTS (SELECT 1 FROM submission_media p WHERE p.submission_id=s.id
                      AND p.media_stage_code='published' AND p.media_side_code='front' AND p.is_active=1)
                  AND EXISTS (SELECT 1 FROM submission_media p WHERE p.submission_id=s.id
                      AND p.media_stage_code='published' AND p.media_side_code='back' AND p.is_active=1)
              )
            """)
            .query((rs, row) -> mediaService.isReferenceAvailable("staged", rs.getString("front_provider"),
                rs.getString("front_bucket"), rs.getString("front_key"), rs.getString("front_version"),
                rs.getString("front_url")) && mediaService.isReferenceAvailable("staged", rs.getString("back_provider"),
                rs.getString("back_bucket"), rs.getString("back_key"), rs.getString("back_version"), rs.getString("back_url")))
            .list().stream().mapToInt(available -> available ? 1 : 0).sum();
    }

    private List<ActionItem> loadActionItems(boolean supportAccess, boolean financeAccess) {
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
                "/nxr/cards/pending-review?status=pending&certId=" + queryValue(rs.getString("cert_id"))
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
                "/nxr/cards/upload?certId=" + queryValue(rs.getString("cert_id"))
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
                "/nxr/submissions/orders?orderId=" + rs.getLong("id")
            ))
            .list());

        if (supportAccess) items.addAll(jdbcClient.sql("""
                SELECT t.id, t.order_id, t.ticket_no, t.subject, t.status_code, t.updated_at
                FROM support_ticket t
                WHERE t.status_code IN ('open', 'assigned')
                ORDER BY t.updated_at ASC, t.id ASC LIMIT 5
                """)
            .query((rs, row) -> new ActionItem(rs.getLong("id"), "support", rs.getString("ticket_no"),
                rs.getString("subject"), rs.getString("status_code"), rs.getObject("updated_at", LocalDateTime.class),
                "/nxr/submissions/orders?orderId=" + rs.getLong("order_id") + "&section=support"))
            .list());
        if (financeAccess) items.addAll(jdbcClient.sql("""
                SELECT e.id, e.order_id, o.order_no, e.exception_type_code, e.resolution_status_code, e.created_at
                FROM payment_finance_exception e JOIN grading_order o ON o.id=e.order_id
                WHERE e.resolution_status_code IN ('open','manual_review')
                ORDER BY e.created_at ASC, e.id ASC LIMIT 5
                """)
            .query((rs, row) -> new ActionItem(rs.getLong("id"), "finance", rs.getString("order_no"),
                null, rs.getString("exception_type_code"), rs.getObject("created_at", LocalDateTime.class),
                "/nxr/submissions/orders?orderId=" + rs.getLong("order_id") + "&section=finance"))
            .list());

        return items.stream()
            .sorted(Comparator.comparingInt((ActionItem item) ->
                    "finance".equals(item.kind()) || "support".equals(item.kind()) ? 0 : 1)
                .thenComparing(ActionItem::actionAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(ACTION_LIMIT)
            .toList();
    }

    private static String queryValue(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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
