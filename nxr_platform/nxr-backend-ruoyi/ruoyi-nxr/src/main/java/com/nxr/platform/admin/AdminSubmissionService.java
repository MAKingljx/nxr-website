package com.nxr.platform.admin;

import com.nxr.platform.shared.CertificateIdPolicy;
import com.nxr.platform.shared.GradeLabelResolver;
import com.nxr.platform.shared.NxrDictionaryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminSubmissionService {

    private static final String DEFAULT_CARD_CATEGORY = "trading_card";
    private static final Map<String, String> CARD_CATEGORY_ALIASES = Map.ofEntries(
        Map.entry("", DEFAULT_CARD_CATEGORY),
        Map.entry("trading_card", "trading_card"),
        Map.entry("trading card", "trading_card"),
        Map.entry("card", "trading_card"),
        Map.entry("movie_film", "movie_film"),
        Map.entry("movie film", "movie_film"),
        Map.entry("film", "movie_film"),
        Map.entry("sports_card", "sports_card"),
        Map.entry("sports card", "sports_card"),
        Map.entry("sports", "sports_card"),
        Map.entry("celebrity_card", "celebrity_card"),
        Map.entry("celebrity card", "celebrity_card"),
        Map.entry("celebrity", "celebrity_card"),
        Map.entry("star card", "celebrity_card")
    );
    private static final Map<String, String> CARD_CATEGORY_LABELS = Map.of(
        "trading_card", "Trading Card",
        "movie_film", "Movie Film",
        "sports_card", "Sports Card",
        "celebrity_card", "Celebrity Card"
    );
    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
        Map.entry("en", "EN"),
        Map.entry("english", "EN"),
        Map.entry("jp", "JP"),
        Map.entry("ja", "JP"),
        Map.entry("japanese", "JP"),
        Map.entry("ct", "CT"),
        Map.entry("traditional chinese", "CT"),
        Map.entry("chinese traditional", "CT"),
        Map.entry("cs", "CS"),
        Map.entry("simplified chinese", "CS"),
        Map.entry("chinese simplified", "CS"),
        Map.entry("in", "IN"),
        Map.entry("indonesian", "IN"),
        Map.entry("ko", "KO"),
        Map.entry("korean", "KO"),
        Map.entry("th", "TH"),
        Map.entry("thai", "TH"),
        Map.entry("other", "Other")
    );
    private static final Map<String, List<String>> LANGUAGE_DB_VARIANTS = Map.of(
        "EN", List.of("EN", "English"),
        "JP", List.of("JP", "Japanese"),
        "CT", List.of("CT", "Traditional Chinese", "Chinese Traditional"),
        "CS", List.of("CS", "Simplified Chinese", "Chinese Simplified"),
        "IN", List.of("IN", "Indonesian"),
        "KO", List.of("KO", "Korean"),
        "TH", List.of("TH", "Thai"),
        "Other", List.of("Other")
    );

    private final JdbcClient jdbcClient;
    private final SimpleJdbcInsert submissionInsert;
    private final SimpleJdbcInsert scoreInsert;
    private final CertificateIdPolicy certificateIdPolicy;
    private final GradeLabelResolver gradeLabelResolver;
    private final NxrDictionaryService nxrDictionaryService;

    public AdminSubmissionService(
        JdbcClient jdbcClient,
        JdbcTemplate jdbcTemplate,
        CertificateIdPolicy certificateIdPolicy,
        GradeLabelResolver gradeLabelResolver,
        NxrDictionaryService nxrDictionaryService
    ) {
        this.jdbcClient = jdbcClient;
        this.certificateIdPolicy = certificateIdPolicy;
        this.gradeLabelResolver = gradeLabelResolver;
        this.nxrDictionaryService = nxrDictionaryService;
        this.submissionInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("grading_submission")
            .usingColumns(
                "cert_id",
                "card_category_code",
                "card_name",
                "movie_name",
                "release_year",
                "production_company",
                "film_type",
                "sports_type",
                "group_name",
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
        params.put("query", normalizedQuery == null ? null : "%" + normalizedQuery.toUpperCase(Locale.ROOT) + "%");
        params.put("limit", resolvedPageSize);
        params.put("offset", offset);

        String whereClause = """
            WHERE (:status IS NULL OR s.status_code = :status)
              AND (
                :query IS NULL
                OR UPPER(s.cert_id) LIKE :query
                OR UPPER(s.card_name) LIKE :query
                OR UPPER(COALESCE(s.movie_name, '')) LIKE :query
                OR UPPER(COALESCE(s.production_company, '')) LIKE :query
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
                    s.card_category_code,
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
                normalizeCardCategory(rs.getString("card_category_code")),
                cardCategoryLabel(rs.getString("card_category_code")),
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
                    s.card_category_code,
                    s.card_name,
                    s.movie_name,
                    s.release_year,
                    s.production_company,
                    s.film_type,
                    s.sports_type,
                    s.group_name,
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
                normalizeCardCategory(rs.getString("card_category_code")),
                cardCategoryLabel(rs.getString("card_category_code")),
                rs.getString("card_name"),
                rs.getString("movie_name"),
                rs.getString("release_year"),
                rs.getString("production_company"),
                rs.getString("film_type"),
                rs.getString("sports_type"),
                rs.getString("group_name"),
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

    @Transactional
    public SubmissionDetailResponse createSubmission(MutateSubmissionRequest request) {
        NormalizedSubmission normalized = normalizeSubmission(request, null);

        Map<String, Object> submissionParams = new LinkedHashMap<>();
        putSubmissionParams(submissionParams, normalized);
        submissionParams.put("status_code", "pending");
        submissionParams.put("grading_phase_code", "human_review");
        submissionParams.put("entry_notes", normalized.entryNotes());
        submissionParams.put("entry_by_user_id", request.actorUserId());

        Number key;
        try {
            key = submissionInsert.executeAndReturnKey(submissionParams);
        } catch (DuplicateKeyException duplicateKeyException) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Certificate ID already exists");
        }

        long submissionId = key.longValue();
        Map<String, Object> scoreParams = new LinkedHashMap<>();
        putScoreParams(scoreParams, submissionId, normalized, "Created from admin platform workflow.");
        scoreInsert.execute(scoreParams);

        return loadSubmission(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Submission load failed"));
    }

    @Transactional
    public SubmissionDetailResponse updateSubmission(long submissionId, MutateSubmissionRequest request) {
        ensureSubmissionExists(submissionId);
        NormalizedSubmission normalized = normalizeSubmission(request, submissionId);

        Map<String, Object> params = new LinkedHashMap<>();
        putSubmissionParams(params, normalized);
        params.put("entryNotes", normalized.entryNotes());
        params.put("submissionId", submissionId);

        try {
            jdbcClient.sql(
                    """
                    UPDATE grading_submission
                    SET cert_id = :certId,
                        card_category_code = :cardCategory,
                        card_name = :cardName,
                        movie_name = :movieName,
                        release_year = :releaseYear,
                        production_company = :productionCompany,
                        film_type = :filmType,
                        sports_type = :sportsType,
                        group_name = :groupName,
                        year_label = :yearLabel,
                        brand_name = :brandName,
                        player_name = :playerName,
                        variety_name = :varietyName,
                        set_name = :setName,
                        card_number = :cardNumber,
                        language_code = :languageCode,
                        population_value = :populationValue,
                        entry_notes = :entryNotes,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :submissionId
                    """
                )
                .params(params)
                .update();
        } catch (DuplicateKeyException duplicateKeyException) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Certificate ID already exists");
        }

        Map<String, Object> scoreParams = new LinkedHashMap<>();
        putScoreParams(scoreParams, submissionId, normalized, "Updated from admin platform workflow.");
        jdbcClient.sql(
                """
                UPDATE grading_score
                SET centering_score = :centeringScore,
                    edges_score = :edgesScore,
                    corners_score = :cornersScore,
                    surface_score = :surfaceScore,
                    final_grade_value = :finalGradeValue,
                    final_grade_label = :finalGradeLabel,
                    decision_method_code = :decisionMethodCode,
                    decision_notes = :decisionNotes,
                    updated_at = CURRENT_TIMESTAMP
                WHERE submission_id = :submissionId
                """
            )
            .params(scoreParams)
            .update();

        return loadSubmission(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Submission load failed"));
    }

    public GradeCalculationResponse calculateGrade(ScoreRequest request) {
        BigDecimal centering = normalizeScore(request.centeringScore(), "Centering");
        BigDecimal edges = normalizeScore(request.edgesScore(), "Edges");
        BigDecimal corners = normalizeScore(request.cornersScore(), "Corners");
        BigDecimal surface = normalizeScore(request.surfaceScore(), "Surface");
        BigDecimal finalGrade = gradeLabelResolver.calculateFinalGrade(centering, edges, corners, surface);
        String finalGradeLabel = gradeLabelResolver.resolveLabel(finalGrade);

        return new GradeCalculationResponse(
            finalGrade,
            finalGradeLabel,
            "(%s + %s + %s + %s) / 4 = %s".formatted(centering, edges, corners, surface, finalGrade)
        );
    }

    public PopulationCalculationResponse calculatePopulation(PopulationCalculationRequest request) {
        String category = normalizeCardCategory(request.cardCategory());
        String finalGradeLabel = normalizeOptional(request.finalGradeLabel());
        if (finalGradeLabel == null && request.hasScores()) {
            finalGradeLabel = calculateGrade(new ScoreRequest(
                request.centeringScore(),
                request.edgesScore(),
                request.cornersScore(),
                request.surfaceScore()
            )).finalGradeLabel();
        }

        PopulationIdentity identity = buildPopulationIdentity(
            category,
            request.cardName(),
            request.setName(),
            request.cardNumber(),
            request.languageCode(),
            request.movieName(),
            request.releaseYear(),
            request.productionCompany(),
            request.filmType(),
            request.sportsType(),
            request.groupName()
        );

        if (finalGradeLabel == null || !identity.complete()) {
            return new PopulationCalculationResponse(
                1,
                "Incomplete data for POP calculation",
                new PopulationDetails(category, identity.displayValue(), finalGradeLabel, 0)
            );
        }

        int existingCount = countMatchingPopulation(identity, finalGradeLabel, request.currentSubmissionId());
        int population = existingCount + 1;
        return new PopulationCalculationResponse(
            population,
            "Java submissions: %d + 1 = %d".formatted(existingCount, population),
            new PopulationDetails(category, identity.displayValue(), finalGradeLabel, existingCount)
        );
    }

    public MatchCardResponse matchCard(MatchCardRequest request) {
        String category = normalizeCardCategory(request.cardCategory());
        if ("movie_film".equals(category)) {
            return MatchCardResponse.notFound("Movie Film entries are matched by movie details, not set number.");
        }

        String setName = normalizeOptional(request.setName());
        String cardNumber = normalizeOptional(request.cardNumber());
        if (setName == null || cardNumber == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Set name and card number are required");
        }

        return jdbcClient.sql(
                """
                SELECT
                    card_name,
                    brand_name,
                    year_label,
                    variety_name,
                    language_code,
                    sports_type,
                    group_name
                FROM grading_submission
                WHERE COALESCE(NULLIF(card_category_code, ''), 'trading_card') = :category
                  AND UPPER(set_name) = UPPER(:setName)
                  AND UPPER(card_number) = UPPER(:cardNumber)
                ORDER BY
                    CASE WHEN status_code = 'approved' THEN 0 WHEN status_code = 'published' THEN 1 ELSE 2 END,
                    updated_at DESC,
                    id DESC
                LIMIT 1
                """
            )
            .params(Map.of(
                "category", category,
                "setName", setName,
                "cardNumber", cardNumber
            ))
            .query((rs, rowNum) -> new MatchCardResponse(
                true,
                rs.getString("card_name"),
                rs.getString("brand_name"),
                rs.getString("year_label"),
                rs.getString("variety_name"),
                rs.getString("language_code"),
                rs.getString("sports_type"),
                rs.getString("group_name"),
                "grading_submission",
                "Matched from Java submissions."
            ))
            .optional()
            .orElseGet(() -> MatchCardResponse.notFound("No matching card found in database"));
    }

    public String generateCertificateId() {
        for (int attempt = 0; attempt < 100; attempt += 1) {
            String certId = certificateIdPolicy.generateCandidate();
            boolean exists = jdbcClient.sql("SELECT COUNT(*) FROM grading_submission WHERE cert_id = :certId")
                .param("certId", certId)
                .query(Integer.class)
                .single() > 0;
            if (!exists) {
                return certId;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to generate certificate ID");
    }

    @Transactional
    public SubmissionDetailResponse approveSubmission(long submissionId, long userId) {
        ensureSubmissionExists(submissionId);
        LocalDateTime approvedAt = LocalDateTime.now();
        long nextSequence = nextApprovalSequence();
        jdbcClient.sql(
                """
                UPDATE grading_submission
                SET status_code = 'approved',
                    approved_by_user_id = :userId,
                    approved_at = COALESCE(approved_at, :approvedAt),
                    approval_sequence = COALESCE(approval_sequence, :approvalSequence),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :submissionId
                  AND status_code IN ('pending', 'review')
                """
            )
            .params(Map.of(
                "submissionId", submissionId,
                "userId", userId,
                "approvedAt", approvedAt,
                "approvalSequence", nextSequence
            ))
            .update();

        return loadSubmission(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Submission load failed"));
    }

    @Transactional
    public BatchApprovalResponse approveSubmissions(List<Long> submissionIds, long userId) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No submissions selected");
        }

        List<Long> distinctIds = new ArrayList<>();
        for (Long submissionId : submissionIds) {
            if (submissionId != null && !distinctIds.contains(submissionId)) {
                distinctIds.add(submissionId);
            }
        }
        if (distinctIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No submissions selected");
        }

        LocalDateTime approvedAt = LocalDateTime.now();
        long nextSequence = nextApprovalSequence();
        int updated = 0;
        for (Long submissionId : distinctIds) {
            int rowCount = jdbcClient.sql(
                    """
                    UPDATE grading_submission
                    SET status_code = 'approved',
                        approved_by_user_id = :userId,
                        approved_at = COALESCE(approved_at, :approvedAt),
                        approval_sequence = COALESCE(approval_sequence, :approvalSequence),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = :submissionId
                      AND status_code IN ('pending', 'review')
                    """
                )
                .params(Map.of(
                    "submissionId", submissionId,
                    "userId", userId,
                    "approvedAt", approvedAt,
                    "approvalSequence", nextSequence
                ))
                .update();
            if (rowCount > 0) {
                updated += 1;
                nextSequence += 1;
            }
        }

        return new BatchApprovalResponse(true, updated, approvedAt, distinctIds);
    }

    private void putSubmissionParams(Map<String, Object> params, NormalizedSubmission normalized) {
        params.put("certId", normalized.certId());
        params.put("cardCategory", normalized.cardCategory());
        params.put("cardName", normalized.cardName());
        params.put("movieName", normalized.movieName());
        params.put("releaseYear", normalized.releaseYear());
        params.put("productionCompany", normalized.productionCompany());
        params.put("filmType", normalized.filmType());
        params.put("sportsType", normalized.sportsType());
        params.put("groupName", normalized.groupName());
        params.put("yearLabel", normalized.yearLabel());
        params.put("brandName", normalized.brandName());
        params.put("playerName", normalized.playerName());
        params.put("varietyName", normalized.varietyName());
        params.put("setName", normalized.setName());
        params.put("cardNumber", normalized.cardNumber());
        params.put("languageCode", normalized.languageCode());
        params.put("populationValue", normalized.populationValue());
        params.put("cert_id", normalized.certId());
        params.put("card_category_code", normalized.cardCategory());
        params.put("card_name", normalized.cardName());
        params.put("movie_name", normalized.movieName());
        params.put("release_year", normalized.releaseYear());
        params.put("production_company", normalized.productionCompany());
        params.put("film_type", normalized.filmType());
        params.put("sports_type", normalized.sportsType());
        params.put("group_name", normalized.groupName());
        params.put("year_label", normalized.yearLabel());
        params.put("brand_name", normalized.brandName());
        params.put("player_name", normalized.playerName());
        params.put("variety_name", normalized.varietyName());
        params.put("set_name", normalized.setName());
        params.put("card_number", normalized.cardNumber());
        params.put("language_code", normalized.languageCode());
        params.put("population_value", normalized.populationValue());
    }

    private void putScoreParams(
        Map<String, Object> params,
        long submissionId,
        NormalizedSubmission normalized,
        String decisionNotes
    ) {
        params.put("submissionId", submissionId);
        params.put("submission_id", submissionId);
        params.put("centeringScore", normalized.centeringScore());
        params.put("edgesScore", normalized.edgesScore());
        params.put("cornersScore", normalized.cornersScore());
        params.put("surfaceScore", normalized.surfaceScore());
        params.put("finalGradeValue", normalized.finalGradeValue());
        params.put("finalGradeLabel", normalized.finalGradeLabel());
        params.put("decisionMethodCode", "human_only");
        params.put("decisionNotes", decisionNotes);
        params.put("centering_score", normalized.centeringScore());
        params.put("edges_score", normalized.edgesScore());
        params.put("corners_score", normalized.cornersScore());
        params.put("surface_score", normalized.surfaceScore());
        params.put("final_grade_value", normalized.finalGradeValue());
        params.put("final_grade_label", normalized.finalGradeLabel());
        params.put("decision_method_code", "human_only");
        params.put("decision_notes", decisionNotes);
    }

    private NormalizedSubmission normalizeSubmission(MutateSubmissionRequest request, Long excludeSubmissionId) {
        String category = normalizeCardCategory(request.cardCategory());
        BigDecimal centering = normalizeScore(request.centeringScore(), "Centering");
        BigDecimal edges = normalizeScore(request.edgesScore(), "Edges");
        BigDecimal corners = normalizeScore(request.cornersScore(), "Corners");
        BigDecimal surface = normalizeScore(request.surfaceScore(), "Surface");
        BigDecimal finalGrade = gradeLabelResolver.calculateFinalGrade(centering, edges, corners, surface);
        String finalGradeLabel = gradeLabelResolver.resolveLabel(finalGrade);

        String certId = normalizeCertificateId(request.certId(), excludeSubmissionId);
        NormalizedIdentity normalizedIdentity = normalizeIdentity(category, request);
        int populationValue = countMatchingPopulation(normalizedIdentity.populationIdentity(), finalGradeLabel, excludeSubmissionId) + 1;

        return new NormalizedSubmission(
            certId,
            category,
            cardCategoryLabel(category),
            normalizedIdentity.cardName(),
            normalizedIdentity.movieName(),
            normalizedIdentity.releaseYear(),
            normalizedIdentity.productionCompany(),
            normalizedIdentity.filmType(),
            normalizedIdentity.sportsType(),
            normalizedIdentity.groupName(),
            normalizedIdentity.yearLabel(),
            normalizedIdentity.brandName(),
            normalizedIdentity.playerName(),
            normalizedIdentity.varietyName(),
            normalizedIdentity.setName(),
            normalizedIdentity.cardNumber(),
            normalizedIdentity.languageCode(),
            populationValue,
            centering,
            edges,
            corners,
            surface,
            finalGrade,
            finalGradeLabel,
            normalizeOptional(request.entryNotes())
        );
    }

    private NormalizedIdentity normalizeIdentity(String category, MutateSubmissionRequest request) {
        if ("movie_film".equals(category)) {
            String movieName = requireValue(firstPresent(request.movieName(), request.cardName()), "Movie Name");
            String releaseYear = requireValue(firstPresent(request.releaseYear(), request.yearLabel()), "Release Year");
            String productionCompany = requireValue(firstPresent(request.productionCompany(), request.brandName()), "Production Company");
            String filmType = requireValue(firstPresent(request.filmType(), request.varietyName()), "Film Type");
            return new NormalizedIdentity(
                movieName,
                movieName,
                releaseYear,
                productionCompany,
                filmType,
                null,
                null,
                releaseYear,
                productionCompany,
                normalizeOptional(request.playerName()),
                filmType,
                "",
                "",
                "",
                buildPopulationIdentity(category, movieName, "", "", "", movieName, releaseYear, productionCompany, filmType, "", "")
            );
        }

        String cardName = requireValue(request.cardName(), "Card Name");
        String brandName = requireValue(request.brandName(), "Brand");
        String setName = requireValue(request.setName(), "Set Name");
        String cardNumber = requireValue(request.cardNumber(), "Card Number");
        String languageCode = requireValue(normalizeLanguage(request.languageCode()), "Language");
        String sportsType = "sports_card".equals(category)
            ? nxrDictionaryService.normalizeSportsType(requireValue(request.sportsType(), "Sports Type"))
            : null;
        String groupName = "celebrity_card".equals(category) ? requireValue(request.groupName(), "Group Name") : null;
        return new NormalizedIdentity(
            cardName,
            null,
            null,
            null,
            null,
            sportsType,
            groupName,
            normalizeOptional(request.yearLabel()),
            brandName,
            normalizeOptional(request.playerName()),
            normalizeOptional(request.varietyName()),
            setName,
            cardNumber,
            languageCode,
            buildPopulationIdentity(category, cardName, setName, cardNumber, languageCode, "", "", "", "", sportsType, groupName)
        );
    }

    private PopulationIdentity buildPopulationIdentity(
        String category,
        String cardName,
        String setName,
        String cardNumber,
        String languageCode,
        String movieName,
        String releaseYear,
        String productionCompany,
        String filmType,
        String sportsType,
        String groupName
    ) {
        if ("movie_film".equals(category)) {
            List<String> identity = new ArrayList<>();
            identity.add(normalizeFilter(firstPresent(movieName, cardName)));
            identity.add(normalizeFilter(releaseYear));
            identity.add(normalizeFilter(productionCompany));
            identity.add(normalizeFilter(filmType));
            return new PopulationIdentity(category, identity, List.of(), "", identity.stream().allMatch(value -> value != null));
        }

        String normalizedLanguage = normalizeLanguage(languageCode);
        List<String> identity = new ArrayList<>();
        identity.add(normalizeFilter(cardName));
        identity.add(normalizeFilter(setName));
        identity.add(normalizeFilter(cardNumber));
        if ("sports_card".equals(category)) {
            identity.add(normalizeFilter(sportsType));
        }
        if ("celebrity_card".equals(category)) {
            identity.add(normalizeFilter(groupName));
        }
        identity.add(normalizedLanguage);
        return new PopulationIdentity(
            category,
            identity,
            languageVariants(normalizedLanguage),
            normalizedLanguage,
            identity.stream().allMatch(value -> value != null && !value.isBlank())
        );
    }

    private int countMatchingPopulation(PopulationIdentity identity, String finalGradeLabel, Long excludeSubmissionId) {
        if (identity == null || !identity.complete() || finalGradeLabel == null || finalGradeLabel.isBlank()) {
            return 0;
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("category", identity.category());
        params.put("finalGradeLabel", finalGradeLabel);
        params.put("excludeSubmissionId", excludeSubmissionId);

        StringBuilder where = new StringBuilder(
            """
            WHERE COALESCE(NULLIF(s.card_category_code, ''), 'trading_card') = :category
              AND g.final_grade_label = :finalGradeLabel
              AND (:excludeSubmissionId IS NULL OR s.id <> :excludeSubmissionId)
            """
        );

        if ("movie_film".equals(identity.category())) {
            params.put("movieName", identity.parts().get(0));
            params.put("releaseYear", identity.parts().get(1));
            params.put("productionCompany", identity.parts().get(2));
            params.put("filmType", identity.parts().get(3));
            where.append(
                """
                  AND UPPER(COALESCE(s.movie_name, s.card_name)) = UPPER(:movieName)
                  AND UPPER(COALESCE(s.release_year, s.year_label)) = UPPER(:releaseYear)
                  AND UPPER(COALESCE(s.production_company, s.brand_name)) = UPPER(:productionCompany)
                  AND UPPER(COALESCE(s.film_type, s.variety_name)) = UPPER(:filmType)
                """
            );
        } else {
            params.put("cardName", identity.parts().get(0));
            params.put("setName", identity.parts().get(1));
            params.put("cardNumber", identity.parts().get(2));
            params.put("languageVariants", identity.languageVariants());
            where.append(
                """
                  AND UPPER(s.card_name) = UPPER(:cardName)
                  AND UPPER(s.set_name) = UPPER(:setName)
                  AND UPPER(s.card_number) = UPPER(:cardNumber)
                  AND s.language_code IN (:languageVariants)
                """
            );
            if ("sports_card".equals(identity.category())) {
                params.put("sportsType", identity.parts().get(3));
                where.append(" AND UPPER(COALESCE(s.sports_type, '')) = UPPER(:sportsType)\n");
            } else if ("celebrity_card".equals(identity.category())) {
                params.put("groupName", identity.parts().get(3));
                where.append(" AND UPPER(COALESCE(s.group_name, '')) = UPPER(:groupName)\n");
            }
        }

        return jdbcClient.sql(
                """
                SELECT COUNT(*)
                FROM grading_submission s
                JOIN grading_score g ON g.submission_id = s.id
                """ + where
            )
            .params(params)
            .query(Integer.class)
            .single();
    }

    private long nextApprovalSequence() {
        Long maxSequence = jdbcClient.sql("SELECT COALESCE(MAX(approval_sequence), 0) FROM grading_submission")
            .query(Long.class)
            .single();
        return (maxSequence == null ? 0 : maxSequence) + 1;
    }

    private void ensureSubmissionExists(long submissionId) {
        boolean exists = jdbcClient.sql("SELECT COUNT(*) FROM grading_submission WHERE id = :submissionId")
            .param("submissionId", submissionId)
            .query(Integer.class)
            .single() > 0;
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found");
        }
    }

    private String normalizeCardCategory(String value) {
        String rawValue = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', ' ');
        return CARD_CATEGORY_ALIASES.getOrDefault(rawValue, DEFAULT_CARD_CATEGORY);
    }

    private String cardCategoryLabel(String value) {
        return CARD_CATEGORY_LABELS.getOrDefault(normalizeCardCategory(value), "Trading Card");
    }

    private String normalizeLanguage(String value) {
        String rawValue = normalizeFilter(value);
        if (rawValue == null) {
            return "";
        }
        String alias = LANGUAGE_ALIASES.get(rawValue.toLowerCase(Locale.ROOT));
        if (alias != null) {
            return alias;
        }
        String upperValue = rawValue.toUpperCase(Locale.ROOT);
        if (LANGUAGE_DB_VARIANTS.containsKey(upperValue)) {
            return upperValue;
        }
        return rawValue;
    }

    private List<String> languageVariants(String value) {
        String normalized = normalizeLanguage(value);
        return LANGUAGE_DB_VARIANTS.getOrDefault(normalized, normalized.isBlank() ? List.of() : List.of(normalized));
    }

    private BigDecimal normalizeScore(BigDecimal score, String label) {
        if (score == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " score is required");
        }
        BigDecimal normalized = score.setScale(1, RoundingMode.HALF_UP);
        if (normalized.compareTo(BigDecimal.ONE) < 0 || normalized.compareTo(BigDecimal.TEN) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " score must be between 1 and 10");
        }
        return normalized;
    }

    private String requireValue(String value, String label) {
        String normalized = normalizeFilter(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        }
        return normalized;
    }

    private String normalizeCertificateId(String value, Long currentSubmissionId) {
        String normalized = certificateIdPolicy.normalize(requireValue(value, "Cert ID"));
        if (certificateIdPolicy.isCanonical(normalized)) {
            return normalized;
        }

        if (currentSubmissionId != null) {
            String existingValue = jdbcClient.sql("SELECT cert_id FROM grading_submission WHERE id = :submissionId")
                .param("submissionId", currentSubmissionId)
                .query(String.class)
                .single();
            if (certificateIdPolicy.preservesExistingValue(normalized, existingValue)) {
                return existingValue;
            }
        }

        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Certificate ID must be exactly 10 digits and cannot start with zero"
        );
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

    private String firstPresent(String first, String second) {
        String normalizedFirst = normalizeFilter(first);
        return normalizedFirst == null ? normalizeFilter(second) : normalizedFirst;
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
        String cardCategory,
        String cardCategoryLabel,
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
        String cardCategory,
        String cardCategoryLabel,
        String cardName,
        String movieName,
        String releaseYear,
        String productionCompany,
        String filmType,
        String sportsType,
        String groupName,
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
                cardCategory,
                cardCategoryLabel,
                cardName,
                movieName,
                releaseYear,
                productionCompany,
                filmType,
                sportsType,
                groupName,
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

    public record MutateSubmissionRequest(
        String certId,
        String cardCategory,
        String cardName,
        String movieName,
        String releaseYear,
        String productionCompany,
        String filmType,
        String sportsType,
        String groupName,
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
        String entryNotes,
        long actorUserId
    ) {
    }

    public record ScoreRequest(
        BigDecimal centeringScore,
        BigDecimal edgesScore,
        BigDecimal cornersScore,
        BigDecimal surfaceScore
    ) {
    }

    public record GradeCalculationResponse(
        BigDecimal finalGradeValue,
        String finalGradeLabel,
        String calculation
    ) {
    }

    public record PopulationCalculationRequest(
        String cardCategory,
        String cardName,
        String setName,
        String cardNumber,
        String languageCode,
        String movieName,
        String releaseYear,
        String productionCompany,
        String filmType,
        String sportsType,
        String groupName,
        String finalGradeLabel,
        BigDecimal centeringScore,
        BigDecimal edgesScore,
        BigDecimal cornersScore,
        BigDecimal surfaceScore,
        Long currentSubmissionId
    ) {
        boolean hasScores() {
            return centeringScore != null && edgesScore != null && cornersScore != null && surfaceScore != null;
        }
    }

    public record PopulationCalculationResponse(
        int populationValue,
        String calculation,
        PopulationDetails details
    ) {
    }

    public record PopulationDetails(
        String cardCategory,
        String cardIdentity,
        String finalGradeLabel,
        int existingCount
    ) {
    }

    public record MatchCardRequest(String cardCategory, String setName, String cardNumber) {
    }

    public record MatchCardResponse(
        boolean found,
        String cardName,
        String brandName,
        String yearLabel,
        String varietyName,
        String languageCode,
        String sportsType,
        String groupName,
        String source,
        String message
    ) {
        static MatchCardResponse notFound(String message) {
            return new MatchCardResponse(false, "", "", "", "", "", "", "", "", message);
        }
    }

    public record BatchApprovalResponse(
        boolean success,
        int count,
        LocalDateTime approvedAt,
        List<Long> submissionIds
    ) {
    }

    private record NormalizedIdentity(
        String cardName,
        String movieName,
        String releaseYear,
        String productionCompany,
        String filmType,
        String sportsType,
        String groupName,
        String yearLabel,
        String brandName,
        String playerName,
        String varietyName,
        String setName,
        String cardNumber,
        String languageCode,
        PopulationIdentity populationIdentity
    ) {
    }

    private record PopulationIdentity(
        String category,
        List<String> parts,
        List<String> languageVariants,
        String normalizedLanguage,
        boolean complete
    ) {
        String displayValue() {
            return parts.stream().filter(value -> value != null && !value.isBlank()).reduce((left, right) -> left + " / " + right).orElse("");
        }
    }

    private record NormalizedSubmission(
        String certId,
        String cardCategory,
        String cardCategoryLabel,
        String cardName,
        String movieName,
        String releaseYear,
        String productionCompany,
        String filmType,
        String sportsType,
        String groupName,
        String yearLabel,
        String brandName,
        String playerName,
        String varietyName,
        String setName,
        String cardNumber,
        String languageCode,
        int populationValue,
        BigDecimal centeringScore,
        BigDecimal edgesScore,
        BigDecimal cornersScore,
        BigDecimal surfaceScore,
        BigDecimal finalGradeValue,
        String finalGradeLabel,
        String entryNotes
    ) {
    }
}
