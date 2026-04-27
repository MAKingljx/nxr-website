package com.nxr.platform.publicapi;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class PublicSiteService {

    private final JdbcClient jdbcClient;

    public PublicSiteService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PublicOverviewResponse loadOverview() {
        Integer publishedCertificates = jdbcClient.sql("SELECT COUNT(*) FROM published_certificate")
            .query(Integer.class)
            .single();
        Integer pendingReview = jdbcClient.sql(
                "SELECT COUNT(*) FROM grading_submission WHERE status_code IN ('pending', 'review')"
            )
            .query(Integer.class)
            .single();
        Integer waitlistCount = jdbcClient.sql("SELECT COUNT(*) FROM waitlist_email")
            .query(Integer.class)
            .single();

        List<FeaturedCertificateCard> featuredCards = jdbcClient.sql(
                """
                SELECT
                    s.cert_id,
                    s.card_name,
                    s.brand_name,
                    s.year_label,
                    s.language_code,
                    s.set_name,
                    g.final_grade_value,
                    g.final_grade_label,
                    (
                        SELECT sm.public_url
                        FROM submission_media sm
                        WHERE sm.submission_id = s.id
                          AND sm.media_stage_code = 'published'
                          AND sm.media_side_code = 'front'
                          AND sm.is_active = 1
                        ORDER BY sm.sort_order ASC
                        LIMIT 1
                    ) AS front_image_url
                FROM published_certificate pc
                JOIN grading_submission s ON s.id = pc.submission_id
                JOIN grading_score g ON g.submission_id = s.id
                ORDER BY pc.published_at DESC, s.cert_id ASC
                LIMIT 3
                """
            )
            .query((rs, rowNum) -> new FeaturedCertificateCard(
                rs.getString("cert_id"),
                rs.getString("card_name"),
                rs.getString("brand_name"),
                rs.getString("year_label"),
                rs.getString("language_code"),
                rs.getString("set_name"),
                rs.getBigDecimal("final_grade_value"),
                rs.getString("final_grade_label"),
                rs.getString("front_image_url")
            ))
            .list();

        return new PublicOverviewResponse(
            "NXR Grading",
            "AI + human grading with a public verification trail.",
            "The new platform keeps public certificates, review workflow, and admin operations separated but connected.",
            publishedCertificates,
            pendingReview,
            waitlistCount,
            featuredCards
        );
    }

    public Optional<PublicCardResponse> loadPublishedCard(String certId) {
        return jdbcClient.sql(
                """
                SELECT
                    pc.cert_id,
                    pc.verification_slug,
                    pc.qr_url,
                    pc.published_at,
                    s.card_name,
                    s.year_label,
                    s.brand_name,
                    s.player_name,
                    s.variety_name,
                    s.language_code,
                    s.set_name,
                    s.card_number,
                    s.population_value,
                    g.centering_score,
                    g.edges_score,
                    g.corners_score,
                    g.surface_score,
                    g.final_grade_value,
                    g.final_grade_label,
                    g.decision_method_code,
                    g.decision_notes,
                    (
                        SELECT sm.public_url
                        FROM submission_media sm
                        WHERE sm.submission_id = s.id
                          AND sm.media_stage_code = 'published'
                          AND sm.media_side_code = 'front'
                          AND sm.is_active = 1
                        ORDER BY sm.sort_order ASC
                        LIMIT 1
                    ) AS front_image_url,
                    (
                        SELECT sm.public_url
                        FROM submission_media sm
                        WHERE sm.submission_id = s.id
                          AND sm.media_stage_code = 'published'
                          AND sm.media_side_code = 'back'
                          AND sm.is_active = 1
                        ORDER BY sm.sort_order ASC
                        LIMIT 1
                    ) AS back_image_url
                FROM published_certificate pc
                JOIN grading_submission s ON s.id = pc.submission_id
                JOIN grading_score g ON g.submission_id = s.id
                WHERE UPPER(pc.cert_id) = UPPER(:certId)
                """
            )
            .param("certId", certId)
            .query((rs, rowNum) -> new PublicCardResponse(
                rs.getString("cert_id"),
                rs.getString("verification_slug"),
                rs.getString("qr_url"),
                rs.getObject("published_at", LocalDateTime.class),
                rs.getString("card_name"),
                rs.getString("year_label"),
                rs.getString("brand_name"),
                rs.getString("player_name"),
                rs.getString("variety_name"),
                rs.getString("language_code"),
                rs.getString("set_name"),
                rs.getString("card_number"),
                rs.getInt("population_value"),
                rs.getBigDecimal("centering_score"),
                rs.getBigDecimal("edges_score"),
                rs.getBigDecimal("corners_score"),
                rs.getBigDecimal("surface_score"),
                rs.getBigDecimal("final_grade_value"),
                rs.getString("final_grade_label"),
                rs.getString("decision_method_code"),
                rs.getString("decision_notes"),
                rs.getString("front_image_url"),
                rs.getString("back_image_url")
            ))
            .optional();
    }

    public record PublicOverviewResponse(
        String platformName,
        String headline,
        String subheadline,
        int publishedCertificates,
        int pendingReview,
        int waitlistCount,
        List<FeaturedCertificateCard> featuredCards
    ) {
    }

    public record FeaturedCertificateCard(
        String certId,
        String cardName,
        String brandName,
        String yearLabel,
        String languageCode,
        String setName,
        BigDecimal finalGradeValue,
        String finalGradeLabel,
        String frontImageUrl
    ) {
    }

    public record PublicCardResponse(
        String certId,
        String verificationSlug,
        String qrUrl,
        LocalDateTime publishedAt,
        String cardName,
        String yearLabel,
        String brandName,
        String playerName,
        String varietyName,
        String languageCode,
        String setName,
        String cardNumber,
        int populationValue,
        BigDecimal centeringScore,
        BigDecimal edgesScore,
        BigDecimal cornersScore,
        BigDecimal surfaceScore,
        BigDecimal finalGradeValue,
        String finalGradeLabel,
        String decisionMethodCode,
        String decisionNotes,
        String frontImageUrl,
        String backImageUrl
    ) {
    }
}
