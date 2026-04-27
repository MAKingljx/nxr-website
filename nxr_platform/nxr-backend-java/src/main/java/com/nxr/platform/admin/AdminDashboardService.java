package com.nxr.platform.admin;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AdminDashboardService {

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

        List<RecentPublishedCard> recentPublished = jdbcClient.sql(
                """
                SELECT
                    s.cert_id,
                    s.card_name,
                    s.brand_name,
                    g.final_grade_value,
                    g.final_grade_label
                FROM published_certificate pc
                JOIN grading_submission s ON s.id = pc.submission_id
                JOIN grading_score g ON g.submission_id = s.id
                ORDER BY pc.published_at DESC, s.cert_id ASC
                LIMIT 5
                """
            )
            .query((rs, rowNum) -> new RecentPublishedCard(
                rs.getString("cert_id"),
                rs.getString("card_name"),
                rs.getString("brand_name"),
                rs.getBigDecimal("final_grade_value"),
                rs.getString("final_grade_label")
            ))
            .list();

        return new AdminDashboardResponse(
            totalSubmissions,
            pendingReview,
            approvedReady,
            publishedCertificates,
            waitlistCount,
            recentPublished
        );
    }

    public record AdminDashboardResponse(
        int totalSubmissions,
        int pendingReview,
        int approvedReady,
        int publishedCertificates,
        int waitlistCount,
        List<RecentPublishedCard> recentPublished
    ) {
    }

    public record RecentPublishedCard(
        String certId,
        String cardName,
        String brandName,
        BigDecimal finalGradeValue,
        String finalGradeLabel
    ) {
    }
}
