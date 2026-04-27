package com.nxr.platform.admin;

import com.nxr.platform.shared.GradeLabelResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminSubmissionService {

    private final JdbcClient jdbcClient;
    private final SimpleJdbcInsert submissionInsert;
    private final SimpleJdbcInsert scoreInsert;
    private final GradeLabelResolver gradeLabelResolver;

    public AdminSubmissionService(
        JdbcClient jdbcClient,
        JdbcTemplate jdbcTemplate,
        GradeLabelResolver gradeLabelResolver
    ) {
        this.jdbcClient = jdbcClient;
        this.gradeLabelResolver = gradeLabelResolver;
        this.submissionInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("grading_submission")
            .usingColumns(
                "cert_id",
                "card_name",
                "year_label",
                "brand_name",
                "player_name",
                "variety_name",
                "set_name",
                "card_number",
                "language_code",
                "population_value",
                "status_code",
                "grading_phase_code",
                "entry_notes",
                "entry_by_user_id"
            )
            .usingGeneratedKeyColumns("id");
        this.scoreInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("grading_score")
            .usingColumns(
                "submission_id",
                "centering_score",
                "edges_score",
                "corners_score",
                "surface_score",
                "final_grade_value",
                "final_grade_label",
                "decision_method_code",
                "decision_notes"
            );
    }

    public SubmissionListResponse listSubmissions(int page, int pageSize, String status, String query) {
        int resolvedPage = Math.max(page, 1);
        int resolvedPageSize = Math.min(Math.max(pageSize, 1), 50);
        int offset = (resolvedPage - 1) * resolvedPageSize;
        String normalizedStatus = normalizeFilter(status);
        String normalizedQuery = normalizeFilter(query);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", normalizedStatus);
        params.put("query", normalizedQuery == null ? null : "%" + normalizedQuery.toUpperCase() + "%");
        params.put("limit", resolvedPageSize);
        params.put("offset", offset);

        String whereClause = """
            WHERE (:status IS NULL OR s.status_code = :status)
              AND (
                :query IS NULL
                OR UPPER(s.cert_id) LIKE :query
                OR UPPER(s.card_name) LIKE :query
                OR UPPER(s.set_name) LIKE :query
              )
            """;

        Integer total = jdbcClient.sql(
                """
                SELECT COUNT(*)
                FROM grading_submission s
                """ + whereClause
            )
            .params(params)
            .query(Integer.class)
            .single();

        List<SubmissionListItem> items = jdbcClient.sql(
                """
                SELECT
                    s.id,
                    s.cert_id,
                    s.card_name,
                    s.brand_name,
                    s.year_label,
                    s.language_code,
                    s.status_code,
                    s.created_at,
                    s.updated_at,
                    g.final_grade_value,
                    g.final_grade_label
                FROM grading_submission s
                JOIN grading_score g ON g.submission_id = s.id
                """ + whereClause + """
                ORDER BY s.created_at DESC, s.id DESC
                LIMIT :limit OFFSET :offset
                """
            )
            .params(params)
            .query((rs, rowNum) -> new SubmissionListItem(
                rs.getLong("id"),
                rs.getString("cert_id"),
                rs.getString("card_name"),
                rs.getString("brand_name"),
                rs.getString("year_label"),
                rs.getString("language_code"),
                rs.getString("status_code"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getBigDecimal("final_grade_value"),
                rs.getString("final_grade_label")
            ))
            .list();

        return new SubmissionListResponse(items, resolvedPage, resolvedPageSize, total);
    }

    public Optional<SubmissionDetailResponse> loadSubmission(long submissionId) {
        Optional<SubmissionDetailResponse> submission = jdbcClient.sql(
                """
                SELECT
                    s.id,
                    s.cert_id,
                    s.card_name,
                    s.year_label,
                    s.brand_name,
                    s.player_name,
                    s.variety_name,
                    s.set_name,
                    s.card_number,
                    s.language_code,
                    s.population_value,
                    s.status_code,
                    s.grading_phase_code,
                    s.entry_notes,
                    s.created_at,
                    s.updated_at,
                    s.approved_at,
                    s.published_at,
                    g.centering_score,
                    g.edges_score,
                    g.corners_score,
                    g.surface_score,
                    g.final_grade_value,
                    g.final_grade_label,
                    g.ai_grade_value,
                    g.ai_confidence_value,
                    g.decision_method_code,
                    g.decision_notes
                FROM grading_submission s
                JOIN grading_score g ON g.submission_id = s.id
                WHERE s.id = :submissionId
                """
            )
            .param("submissionId", submissionId)
            .query((rs, rowNum) -> new SubmissionDetailResponse(
                rs.getLong("id"),
                rs.getString("cert_id"),
                rs.getString("card_name"),
                rs.getString("year_label"),
                rs.getString("brand_name"),
                rs.getString("player_name"),
                rs.getString("variety_name"),
                rs.getString("set_name"),
                rs.getString("card_number"),
                rs.getString("language_code"),
                rs.getInt("population_value"),
                rs.getString("status_code"),
                rs.getString("grading_phase_code"),
                rs.getString("entry_notes"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getObject("published_at", LocalDateTime.class),
                rs.getBigDecimal("centering_score"),
                rs.getBigDecimal("edges_score"),
                rs.getBigDecimal("corners_score"),
                rs.getBigDecimal("surface_score"),
                rs.getBigDecimal("final_grade_value"),
                rs.getString("final_grade_label"),
                rs.getBigDecimal("ai_grade_value"),
                rs.getBigDecimal("ai_confidence_value"),
                rs.getString("decision_method_code"),
                rs.getString("decision_notes"),
                List.of()
            ))
            .optional();

        if (submission.isEmpty()) {
            return Optional.empty();
        }

        List<SubmissionMediaItem> media = jdbcClient.sql(
                """
                SELECT media_side_code, media_stage_code, public_url
                FROM submission_media
                WHERE submission_id = :submissionId
                  AND is_active = 1
                ORDER BY media_stage_code ASC, sort_order ASC
                """
            )
            .param("submissionId", submissionId)
            .query((rs, rowNum) -> new SubmissionMediaItem(
                rs.getString("media_side_code"),
                rs.getString("media_stage_code"),
                rs.getString("public_url")
            ))
            .list();

        SubmissionDetailResponse detail = submission.get();
        return Optional.of(detail.withMedia(media));
    }

    public SubmissionDetailResponse createSubmission(CreateSubmissionRequest request) {
        BigDecimal centering = request.centeringScore().setScale(1, RoundingMode.HALF_UP);
        BigDecimal edges = request.edgesScore().setScale(1, RoundingMode.HALF_UP);
        BigDecimal corners = request.cornersScore().setScale(1, RoundingMode.HALF_UP);
        BigDecimal surface = request.surfaceScore().setScale(1, RoundingMode.HALF_UP);
        BigDecimal finalGrade = gradeLabelResolver.calculateFinalGrade(centering, edges, corners, surface);
        String finalGradeLabel = gradeLabelResolver.resolveLabel(finalGrade);

        Map<String, Object> submissionParams = new LinkedHashMap<>();
        submissionParams.put("cert_id", request.certId().trim().toUpperCase());
        submissionParams.put("card_name", request.cardName().trim());
        submissionParams.put("year_label", normalizeOptional(request.yearLabel()));
        submissionParams.put("brand_name", request.brandName().trim());
        submissionParams.put("player_name", normalizeOptional(request.playerName()));
        submissionParams.put("variety_name", normalizeOptional(request.varietyName()));
        submissionParams.put("set_name", request.setName().trim());
        submissionParams.put("card_number", request.cardNumber().trim());
        submissionParams.put("language_code", request.languageCode().trim().toUpperCase());
        submissionParams.put("population_value", request.populationValue());
        submissionParams.put("status_code", "pending");
        submissionParams.put("grading_phase_code", "human_review");
        submissionParams.put("entry_notes", normalizeOptional(request.entryNotes()));
        submissionParams.put("entry_by_user_id", 1L);

        Number key;
        try {
            key = submissionInsert.executeAndReturnKey(submissionParams);
        } catch (DuplicateKeyException duplicateKeyException) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Certificate ID already exists");
        }

        long submissionId = key.longValue();
        Map<String, Object> scoreParams = new LinkedHashMap<>();
        scoreParams.put("submission_id", submissionId);
        scoreParams.put("centering_score", centering);
        scoreParams.put("edges_score", edges);
        scoreParams.put("corners_score", corners);
        scoreParams.put("surface_score", surface);
        scoreParams.put("final_grade_value", finalGrade);
        scoreParams.put("final_grade_label", finalGradeLabel);
        scoreParams.put("decision_method_code", "human_only");
        scoreParams.put("decision_notes", "Created from admin platform phase-1 workflow.");
        scoreInsert.execute(scoreParams);

        return loadSubmission(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Submission load failed"));
    }

    private String normalizeFilter(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeOptional(String value) {
        String normalized = normalizeFilter(value);
        return normalized == null ? null : normalized;
    }

    public record SubmissionListResponse(
        List<SubmissionListItem> items,
        int page,
        int pageSize,
        int total
    ) {
    }

    public record SubmissionListItem(
        long id,
        String certId,
        String cardName,
        String brandName,
        String yearLabel,
        String languageCode,
        String statusCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        BigDecimal finalGradeValue,
        String finalGradeLabel
    ) {
    }

    public record SubmissionDetailResponse(
        long id,
        String certId,
        String cardName,
        String yearLabel,
        String brandName,
        String playerName,
        String varietyName,
        String setName,
        String cardNumber,
        String languageCode,
        int populationValue,
        String statusCode,
        String gradingPhaseCode,
        String entryNotes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime approvedAt,
        LocalDateTime publishedAt,
        BigDecimal centeringScore,
        BigDecimal edgesScore,
        BigDecimal cornersScore,
        BigDecimal surfaceScore,
        BigDecimal finalGradeValue,
        String finalGradeLabel,
        BigDecimal aiGradeValue,
        BigDecimal aiConfidenceValue,
        String decisionMethodCode,
        String decisionNotes,
        List<SubmissionMediaItem> media
    ) {
        SubmissionDetailResponse withMedia(List<SubmissionMediaItem> updatedMedia) {
            return new SubmissionDetailResponse(
                id,
                certId,
                cardName,
                yearLabel,
                brandName,
                playerName,
                varietyName,
                setName,
                cardNumber,
                languageCode,
                populationValue,
                statusCode,
                gradingPhaseCode,
                entryNotes,
                createdAt,
                updatedAt,
                approvedAt,
                publishedAt,
                centeringScore,
                edgesScore,
                cornersScore,
                surfaceScore,
                finalGradeValue,
                finalGradeLabel,
                aiGradeValue,
                aiConfidenceValue,
                decisionMethodCode,
                decisionNotes,
                updatedMedia
            );
        }
    }

    public record SubmissionMediaItem(
        String mediaSideCode,
        String mediaStageCode,
        String publicUrl
    ) {
    }

    public record CreateSubmissionRequest(
        String certId,
        String cardName,
        String yearLabel,
        String brandName,
        String playerName,
        String varietyName,
        String setName,
        String cardNumber,
        String languageCode,
        Integer populationValue,
        BigDecimal centeringScore,
        BigDecimal edgesScore,
        BigDecimal cornersScore,
        BigDecimal surfaceScore,
        String entryNotes
    ) {
    }
}
