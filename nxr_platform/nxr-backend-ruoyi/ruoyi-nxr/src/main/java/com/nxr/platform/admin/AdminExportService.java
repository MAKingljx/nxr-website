package com.nxr.platform.admin;

import com.nxr.platform.shared.ProductTypePolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminExportService {

    private static final Pattern CERT_ID_PATTERN = Pattern.compile("^[0-9]{10}$");
    private static final DateTimeFormatter FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter SUMMARY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ROW_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    // Python exports SELECT * in its live table's historical column order.
    // Keep the workbook contract explicit; Java-only fields stay out of this legacy format.
    static final List<String> PYTHON_EXPORT_COLUMNS = List.of(
        "id", "cert_id", "card_name", "year", "brand", "variety", "pop", "language", "set_name", "card_number",
        "centering", "edges", "corners", "surface", "final_grade", "final_grade_text", "front_image", "back_image",
        "entry_notes", "entry_by", "entry_date", "status", "created_at", "updated_at", "upload_status",
        "upload_started", "upload_completed", "upload_error", "server_response", "published_front_image",
        "published_back_image", "approved_at", "approval_sequence", "card_category", "movie_name", "release_year",
        "production_company", "film_type", "sports_type", "group_name", "product_type", "vintage_classification",
        "merch_description", "landing_page_url"
    );
    private static final Set<String> SCORE_COLUMNS = Set.of(
        "centering", "edges", "corners", "surface", "final_grade", "final_grade_text"
    );
    private static final String EXPORT_FILTER_ALL = "all";
    private static final String EXPORT_FILTER_GRADE_PREFIX = "grade:";
    private static final String EXPORT_FILTER_MERCH = ProductTypePolicy.MERCH_PRODUCT;
    private static final String EXPORT_FILTER_VINTAGE_PREFIX = ProductTypePolicy.VINTAGE_PRODUCT + ":";
    private static final String PRODUCT_TYPE_SQL = ProductTypePolicy.canonicalSql("s.product_type_code");
    private static final Map<String, String> VINTAGE_CLASSIFICATIONS = Map.of(
        "pristine", "Pristine",
        "nova", "Nova",
        "legacy", "Legacy",
        "helix", "Helix"
    );

    private final JdbcClient jdbcClient;
    private final Path exportRoot;

    public AdminExportService(
        JdbcClient jdbcClient,
        @Value("${nxr.exports.storage-root:./.local-data/exports}") String exportRoot
    ) {
        this.jdbcClient = jdbcClient;
        this.exportRoot = Path.of(exportRoot).normalize();
    }

    public ExportPreviewResponse preview(ExportRequest request) {
        ExportFilter filter = normalizeFilter(request);
        List<ExportRow> rows = loadRows(filter, 20);
        int total = countRows(filter);
        List<String> missingCertIds = missingCertIds(filter);

        return new ExportPreviewResponse(
            total > 0 && filter.invalidCertIds().isEmpty() && missingCertIds.isEmpty(),
            total,
            20,
            filter.selection(),
            filter.certIds(),
            filter.invalidCertIds(),
            missingCertIds,
            rows
        );
    }

    public ExportJobResponse generate(ExportRequest request, Long createdByUserId) {
        ExportFilter filter = normalizeFilter(request);
        List<String> missingCertIds = missingCertIds(filter);
        if (!filter.invalidCertIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cert IDs: " + String.join(", ", filter.invalidCertIds()));
        }
        if (!missingCertIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing approved cert IDs: " + String.join(", ", missingCertIds));
        }

        List<ExportRow> rows = loadRows(filter, Integer.MAX_VALUE);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No approved cards matched the export filters");
        }

        Path pendingPath = null;
        Path outputPath = null;
        boolean historySaved = false;
        try {
            Files.createDirectories(exportRoot);
            String filename = filenameFor(filter);
            outputPath = exportRoot.toAbsolutePath().normalize().resolve(filename).normalize();
            if (!outputPath.startsWith(exportRoot.toAbsolutePath().normalize()) && exportRoot.isAbsolute()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export path");
            }

            pendingPath = outputPath.resolveSibling("." + filename + "." + UUID.randomUUID() + ".part");
            try (var outputStream = Files.newOutputStream(pendingPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                SimpleXlsxWriter.writeWorkbook(outputStream, buildSheets(rows, filter, filename));
            }
            try {
                Files.move(pendingPath, outputPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(pendingPath, outputPath);
            }
            pendingPath = null;

            long fileSize = Files.size(outputPath);
            jdbcClient.sql(
                    """
                    INSERT INTO export_job (filename, filter_label, grade_filter, cert_ids, record_count, file_size_bytes, storage_path, created_by_user_id)
                    VALUES (:filename, :filterLabel, :gradeFilter, :certIds, :recordCount, :fileSizeBytes, :storagePath, :createdByUserId)
                    """
                )
                .param("filename", filename)
                .param("filterLabel", filterLabel(filter))
                .param("gradeFilter", filter.selection())
                .param("certIds", String.join(",", filter.certIds()))
                .param("recordCount", rows.size())
                .param("fileSizeBytes", fileSize)
                .param("storagePath", outputPath.toAbsolutePath().toString())
                .param("createdByUserId", createdByUserId)
                .update();
            historySaved = true;

            return findJob(filename);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write Excel export", exception);
        } finally {
            deleteQuietly(pendingPath);
            if (!historySaved) {
                deleteQuietly(outputPath);
            }
        }
    }

    public ExportListResponse listExports(int page, int pageSize) {
        int resolvedPage = Math.max(1, page);
        int resolvedPageSize = Math.min(Math.max(1, pageSize), 100);
        int offset = (resolvedPage - 1) * resolvedPageSize;
        Integer total = jdbcClient.sql("SELECT COUNT(*) FROM export_job")
            .query(Integer.class)
            .single();
        List<ExportJobResponse> items = jdbcClient.sql(
                """
                SELECT id, filename, filter_label, grade_filter, cert_ids, record_count, file_size_bytes, created_at
                FROM export_job
                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """
            )
            .param("limit", resolvedPageSize)
            .param("offset", offset)
            .query((rs, rowNum) -> new ExportJobResponse(
                rs.getLong("id"),
                rs.getString("filename"),
                rs.getString("filter_label"),
                rs.getString("grade_filter"),
                rs.getString("cert_ids"),
                rs.getInt("record_count"),
                rs.getLong("file_size_bytes"),
                rs.getObject("created_at", LocalDateTime.class)
            ))
            .list();
        return new ExportListResponse(items, resolvedPage, resolvedPageSize, total);
    }

    public DownloadableExport resolveDownload(String filename) {
        ExportJobResponse job = findJob(filename);
        Path filePath = safeExportPath(job.filename());
        if (!Files.exists(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Export file not found");
        }
        return new DownloadableExport(job.filename(), new FileSystemResource(filePath));
    }

    public DeleteExportResponse deleteExport(String filename) {
        ExportJobResponse job = findJob(filename);
        Path filePath = safeExportPath(job.filename());
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete export file", exception);
        }
        jdbcClient.sql("DELETE FROM export_job WHERE filename = :filename")
            .param("filename", job.filename())
            .update();
        return new DeleteExportResponse(true, job.filename());
    }

    private List<SimpleXlsxWriter.Sheet> buildSheets(List<ExportRow> rows, ExportFilter filter, String filename) {
        List<List<?>> approvedRows = new ArrayList<>();
        approvedRows.add(PYTHON_EXPORT_COLUMNS);
        Map<Object, Integer> gradeCounts = new LinkedHashMap<>();
        for (ExportRow row : rows) {
            Object grade = exportGradeLabel(row);
            approvedRows.add(List.of(
                row.submissionId(), row.certId(), cell(row.cardName()), cell(row.yearLabel()), cell(row.brandName()),
                cell(row.varietyName()), String.valueOf(row.populationValue()), cell(row.languageCode()),
                cell(row.setName()), cell(row.cardNumber()), exportScore(row, row.centeringScore()),
                exportScore(row, row.edgesScore()), exportScore(row, row.cornersScore()), exportScore(row, row.surfaceScore()),
                exportScore(row, row.finalGradeValue()), grade, cell(row.stagedFrontKey()), cell(row.stagedBackKey()),
                cell(row.entryNotes()), cell(row.enteredBy()), exportTimestamp(row.entryDate()),
                // Python publication changes upload_status while retaining status=approved.
                "approved", exportTimestamp(row.createdAt()), exportTimestamp(row.updatedAt()), cell(row.uploadStatus()),
                exportTimestamp(row.uploadStartedAt()), exportTimestamp(row.uploadCompletedAt()), cell(row.uploadError()),
                cell(row.serverResponse()), cell(row.publishedFrontUrl()), cell(row.publishedBackUrl()),
                exportTimestamp(row.approvedAt()), cell(row.approvalSequence()), cell(row.cardCategory()),
                cell(row.movieName()), cell(row.releaseYear()), cell(row.productionCompany()), cell(row.filmType()),
                cell(row.sportsType()), cell(row.groupName()), row.productType(), cell(row.vintageClassification()),
                cell(row.merchDescription()), "nxrgrading.com/card/" + row.certId().trim()
            ));
            if (!"".equals(grade)) {
                gradeCounts.merge(grade, 1, Integer::sum);
            }
        }

        List<List<?>> summaryRows = List.of(
            List.of("导出时间", "总记录数", "筛选条件", "数据范围", "包含字段数", "文件名称"),
            List.of(LocalDateTime.now().format(SUMMARY_TIMESTAMP), rows.size(), filterLabel(filter),
                exportDateRange(rows), PYTHON_EXPORT_COLUMNS.size(), filename)
        );
        List<List<?>> gradeRows = new ArrayList<>();
        gradeRows.add(List.of("评分等级", "数量", "占比"));
        // pandas value_counts orders by frequency, retaining first appearance for ties.
        gradeCounts.entrySet().stream()
            .sorted(Map.Entry.<Object, Integer>comparingByValue(Comparator.reverseOrder()))
            .forEach(entry -> gradeRows.add(List.of(entry.getKey(), entry.getValue(),
                roundScore(BigDecimal.valueOf(entry.getValue() * 100.0d / rows.size())).toPlainString() + "%")));

        return List.of(
            new SimpleXlsxWriter.Sheet("Approved Cards", approvedRows, SCORE_COLUMNS),
            new SimpleXlsxWriter.Sheet("导出汇总", summaryRows),
            new SimpleXlsxWriter.Sheet("评分统计", gradeRows, Set.of("评分等级"))
        );
    }

    private Object exportScore(ExportRow row, BigDecimal score) {
        return ProductTypePolicy.GRADED_CARD.equals(row.productType()) && score != null ? roundScore(score) : "";
    }

    private Object exportGradeLabel(ExportRow row) {
        if (!ProductTypePolicy.GRADED_CARD.equals(row.productType()) || row.finalGradeLabel() == null) return "";
        String canonical = normalizeGrade(row.finalGradeLabel());
        String label = canonical == null ? row.finalGradeLabel() : canonical;
        try {
            return roundScore(new BigDecimal(label));
        } catch (NumberFormatException ignored) {
            return label;
        }
    }

    private BigDecimal roundScore(BigDecimal value) {
        // pandas/NumPy round(1) uses binary64 multiplication and ties-to-even.
        return BigDecimal.valueOf(Math.rint(value.doubleValue() * 10.0d) / 10.0d).setScale(1);
    }

    private String exportTimestamp(LocalDateTime value) {
        if (value == null) return "";
        String timestamp = value.format(ROW_TIMESTAMP);
        int micros = value.getNano() / 1000;
        return micros == 0 ? timestamp : timestamp + String.format(Locale.ROOT, ".%06d", micros);
    }

    private String exportDateRange(List<ExportRow> rows) {
        // Python chooses the first non-empty whole column, not a per-row fallback.
        List<List<LocalDateTime>> candidates = List.of(
            rows.stream().map(ExportRow::approvedAt).filter(java.util.Objects::nonNull).toList(),
            rows.stream().map(ExportRow::entryDate).filter(java.util.Objects::nonNull).toList(),
            rows.stream().map(ExportRow::createdAt).filter(java.util.Objects::nonNull).toList(),
            rows.stream().map(ExportRow::updatedAt).filter(java.util.Objects::nonNull).toList()
        );
        for (List<LocalDateTime> values : candidates) {
            if (!values.isEmpty()) {
                return values.stream().min(Comparator.naturalOrder()).orElseThrow().toLocalDate()
                    + " 至 " + values.stream().max(Comparator.naturalOrder()).orElseThrow().toLocalDate();
            }
        }
        return "无数据";
    }

    private int countRows(ExportFilter filter) {
        QueryParts queryParts = buildWhereClause(filter);
        return jdbcClient.sql("SELECT COUNT(*) FROM grading_submission s LEFT JOIN grading_score g ON g.submission_id = s.id " + queryParts.whereClause())
            .params(queryParts.params())
            .query(Integer.class)
            .single();
    }

    private List<ExportRow> loadRows(ExportFilter filter, int limit) {
        QueryParts queryParts = buildWhereClause(filter);
        Map<String, Object> params = new LinkedHashMap<>(queryParts.params());
        params.put("limit", limit);
        return jdbcClient.sql(
                """
                SELECT
                    s.id,
                    s.cert_id,
                    COALESCE(NULLIF(s.product_type_code, ''), 'graded_card') AS product_type_code,
                    s.vintage_classification_code,
                    s.merch_description,
                    COALESCE(NULLIF(s.card_category_code, ''), 'trading_card') AS card_category_code,
                    s.card_name,
                    s.movie_name,
                    s.release_year,
                    s.production_company,
                    s.film_type,
                    s.sports_type,
                    s.group_name,
                    s.brand_name,
                    s.year_label,
                    s.player_name,
                    s.variety_name,
                    s.set_name,
                    s.card_number,
                    s.language_code,
                    s.population_value,
                    s.status_code,
                    s.grading_phase_code,
                    s.entry_notes,
                    COALESCE(NULLIF(s.entry_by_label, ''), NULLIF(u.user_name, ''), NULLIF(u.nick_name, '')) AS entered_by,
                    s.created_at AS entry_date,
                    s.approved_at,
                    s.approval_sequence,
                    s.created_at,
                    s.updated_at,
                    g.final_grade_value,
                    g.final_grade_label,
                    g.centering_score,
                    g.edges_score,
                    g.corners_score,
                    g.surface_score,
                    g.ai_grade_value,
                    g.ai_centering_score,
                    g.ai_edges_score,
                    g.ai_corners_score,
                    g.ai_surface_score,
                    g.ai_confidence_value,
                    g.decision_method_code,
                    g.decision_notes,
                    COALESCE(us.status_code, 'not_started') AS upload_status,
                    us.started_at AS upload_started_at,
                    us.completed_at AS upload_completed_at,
                    us.error_message AS upload_error,
                    us.response_payload_json AS server_response,
                    (SELECT sm.public_url FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='front' AND sm.is_active=1 LIMIT 1) AS staged_front_url,
                    (SELECT sm.storage_key FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='front' AND sm.is_active=1 LIMIT 1) AS staged_front_key,
                    (SELECT sm.public_url FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='back' AND sm.is_active=1 LIMIT 1) AS staged_back_url,
                    (SELECT sm.storage_key FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='back' AND sm.is_active=1 LIMIT 1) AS staged_back_key,
                    (SELECT sm.public_url FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='front' AND sm.is_active=1 LIMIT 1) AS published_front_url,
                    (SELECT sm.public_url FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='back' AND sm.is_active=1 LIMIT 1) AS published_back_url
                FROM grading_submission s
                LEFT JOIN grading_score g ON g.submission_id = s.id
                LEFT JOIN sys_user u ON u.user_id=s.entry_by_user_id
                LEFT JOIN submission_upload_state us ON us.submission_id=s.id
                """ + queryParts.whereClause() + """
                ORDER BY COALESCE(s.approved_at, s.updated_at, s.created_at) DESC,
                         COALESCE(s.approval_sequence, 9223372036854775807) ASC, s.id ASC
                LIMIT :limit
                """
            )
            .params(params)
            .query((rs, rowNum) -> new ExportRow(
                rs.getLong("id"),
                rs.getString("cert_id"),
                ProductTypePolicy.normalizeStored(rs.getString("product_type_code")),
                rs.getString("vintage_classification_code"),
                rs.getString("merch_description"),
                rs.getString("card_category_code"),
                rs.getString("card_name"),
                rs.getString("movie_name"),
                rs.getString("release_year"),
                rs.getString("production_company"),
                rs.getString("film_type"),
                rs.getString("sports_type"),
                rs.getString("group_name"),
                rs.getString("brand_name"),
                rs.getString("year_label"),
                rs.getString("player_name"),
                rs.getString("variety_name"),
                rs.getString("set_name"),
                rs.getString("card_number"),
                rs.getString("language_code"),
                rs.getInt("population_value"),
                rs.getString("status_code"),
                rs.getBigDecimal("final_grade_value"),
                rs.getString("final_grade_label"),
                rs.getBigDecimal("centering_score"),
                rs.getBigDecimal("edges_score"),
                rs.getBigDecimal("corners_score"),
                rs.getBigDecimal("surface_score"),
                rs.getBigDecimal("ai_grade_value"),
                rs.getBigDecimal("ai_centering_score"),
                rs.getBigDecimal("ai_edges_score"),
                rs.getBigDecimal("ai_corners_score"),
                rs.getBigDecimal("ai_surface_score"),
                rs.getBigDecimal("ai_confidence_value"),
                rs.getString("decision_method_code"),
                rs.getString("decision_notes"),
                rs.getString("grading_phase_code"),
                rs.getString("staged_front_url"),
                rs.getString("staged_back_url"),
                rs.getString("published_front_url"),
                rs.getString("published_back_url"),
                rs.getString("entry_notes"),
                rs.getString("entered_by"),
                rs.getObject("entry_date", LocalDateTime.class),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getObject("approval_sequence", Long.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getString("upload_status"),
                rs.getObject("upload_started_at", LocalDateTime.class),
                rs.getObject("upload_completed_at", LocalDateTime.class),
                rs.getString("upload_error"),
                rs.getString("server_response"),
                rs.getString("staged_front_key"),
                rs.getString("staged_back_key")
            ))
            .list();
    }

    private List<String> missingCertIds(ExportFilter filter) {
        if (filter.certIds().isEmpty()) {
            return List.of();
        }
        QueryParts queryParts = buildWhereClause(filter);
        List<String> existing = jdbcClient.sql("SELECT UPPER(s.cert_id) FROM grading_submission s LEFT JOIN grading_score g ON g.submission_id = s.id " + queryParts.whereClause())
            .params(queryParts.params())
            .query(String.class)
            .list();
        return filter.certIds().stream()
            .filter(certId -> !existing.contains(certId.toUpperCase(Locale.ROOT)))
            .toList();
    }

    private QueryParts buildWhereClause(ExportFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder("WHERE s.status_code IN ('approved', 'published')");
        switch (filter.kind()) {
            case GRADED -> {
                where.append(" AND ").append(PRODUCT_TYPE_SQL).append(" = 'graded_card'");
                where.append(" AND ").append(canonicalExportGradeSql("g.final_grade_label"))
                    .append(" = :gradeFilter");
                params.put("gradeFilter", filter.gradeFilter());
            }
            case MERCH -> where.append(" AND ").append(PRODUCT_TYPE_SQL).append(" = 'merch_product'");
            case VINTAGE -> {
                where.append(" AND ").append(PRODUCT_TYPE_SQL).append(" = 'vintage_product'");
                where.append(" AND UPPER(TRIM(COALESCE(s.vintage_classification_code, ''))) = :vintageClassification");
                params.put("vintageClassification", filter.vintageClassification().toUpperCase(Locale.ROOT));
            }
            case ALL -> {
                // All approved products intentionally includes graded, merchandise, and vintage records.
            }
        }
        if (!filter.certIds().isEmpty()) {
            List<String> normalizedCertIds = filter.certIds().stream().map(certId -> certId.toUpperCase(Locale.ROOT)).toList();
            where.append(" AND UPPER(s.cert_id) IN (:certIds)");
            params.put("certIds", normalizedCertIds);
        }
        return new QueryParts(where.append('\n').toString(), params);
    }

    private Object cell(Object value) {
        return value == null ? "" : value;
    }

    private ExportFilter normalizeFilter(ExportRequest request) {
        ExportSelection selection = parseSelection(request);
        List<String> certIds = new ArrayList<>();
        List<String> invalidCertIds = new ArrayList<>();
        String rawCertIds = request == null || request.certIds() == null ? "" : request.certIds();
        for (String token : rawCertIds.split("[\\s,，;；]+")) {
            if (token.isBlank()) {
                continue;
            }
            String normalized = token.trim();
            if (!CERT_ID_PATTERN.matcher(normalized).matches()) {
                invalidCertIds.add(normalized);
            } else if (certIds.stream().noneMatch(existing -> existing.equalsIgnoreCase(normalized))) {
                certIds.add(normalized);
            }
        }
        return new ExportFilter(
            selection.value(),
            selection.kind(),
            selection.gradeFilter(),
            selection.vintageClassification(),
            certIds,
            invalidCertIds
        );
    }

    private ExportSelection parseSelection(ExportRequest request) {
        String rawSelection = request == null ? ""
            : request.exportFilter() != null ? request.exportFilter().trim()
            : request.gradeFilter() == null ? "" : request.gradeFilter().trim();
        if (rawSelection.isEmpty() || rawSelection.equals(EXPORT_FILTER_ALL)) {
            return new ExportSelection(EXPORT_FILTER_ALL, ExportFilterKind.ALL, null, null);
        }

        if (rawSelection.startsWith(EXPORT_FILTER_GRADE_PREFIX)) {
            String grade = normalizeGrade(rawSelection.substring(EXPORT_FILTER_GRADE_PREFIX.length()));
            if (grade == null) {
                throw unsupportedFilter();
            }
            return new ExportSelection(EXPORT_FILTER_GRADE_PREFIX + grade, ExportFilterKind.GRADED, grade, null);
        }

        if (rawSelection.equals(EXPORT_FILTER_MERCH)) {
            return new ExportSelection(EXPORT_FILTER_MERCH, ExportFilterKind.MERCH, null, null);
        }

        if (rawSelection.startsWith(EXPORT_FILTER_VINTAGE_PREFIX)) {
            String requested = rawSelection.substring(EXPORT_FILTER_VINTAGE_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
            String classification = VINTAGE_CLASSIFICATIONS.get(requested);
            if (classification == null) {
                throw unsupportedFilter();
            }
            return new ExportSelection(
                EXPORT_FILTER_VINTAGE_PREFIX + classification,
                ExportFilterKind.VINTAGE,
                null,
                classification
            );
        }

        String legacyGrade = normalizeGrade(rawSelection);
        if (legacyGrade != null) {
            return new ExportSelection(EXPORT_FILTER_GRADE_PREFIX + legacyGrade, ExportFilterKind.GRADED, legacyGrade, null);
        }
        throw unsupportedFilter();
    }

    private String normalizeGrade(String value) {
        String compact = value == null
            ? ""
            : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "");
        return switch (compact) {
            case "8", "8.0", "8.00" -> "8";
            case "8.5", "8.50" -> "8.5";
            case "9", "9.0", "9.00" -> "9";
            case "9.5", "9.50" -> "9.5";
            case "10", "10.0", "10.00" -> "10";
            default -> compact.contains("stine10") ? "Pristine 10" : null;
        };
    }

    private static String canonicalExportGradeSql(String column) {
        String value = "TRIM(" + column + ")";
        return "CASE WHEN " + value + " IN ('8','8.0','8.00') THEN '8' "
            + "WHEN " + value + " IN ('8.5','8.50') THEN '8.5' "
            + "WHEN " + value + " IN ('9','9.0','9.00') THEN '9' "
            + "WHEN " + value + " IN ('9.5','9.50') THEN '9.5' "
            + "WHEN " + value + " IN ('10','10.0','10.00') THEN '10' "
            + "WHEN REPLACE(REPLACE(LOWER(" + value + "), ' ', ''), '-', '') LIKE '%stine10%' THEN 'Pristine 10' "
            + "ELSE NULL END";
    }

    private ResponseStatusException unsupportedFilter() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported export filter");
    }

    private ExportJobResponse findJob(String filename) {
        return jdbcClient.sql(
                """
                SELECT id, filename, filter_label, grade_filter, cert_ids, record_count, file_size_bytes, created_at
                FROM export_job
                WHERE filename = :filename
                """
            )
            .param("filename", filename)
            .query((rs, rowNum) -> new ExportJobResponse(
                rs.getLong("id"),
                rs.getString("filename"),
                rs.getString("filter_label"),
                rs.getString("grade_filter"),
                rs.getString("cert_ids"),
                rs.getInt("record_count"),
                rs.getLong("file_size_bytes"),
                rs.getObject("created_at", LocalDateTime.class)
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Export not found"));
    }

    private Path safeExportPath(String filename) {
        if (filename.contains("/") || filename.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
        return exportRoot.resolve(filename).normalize();
    }

    private String filenameFor(ExportFilter filter) {
        String suffix = switch (filter.kind()) {
            case ALL -> "all";
            case GRADED -> filter.gradeFilter();
            case MERCH -> EXPORT_FILTER_MERCH;
            case VINTAGE -> "vintage_" + filter.vintageClassification();
        };
        String grade = suffix.replaceAll("[^A-Za-z0-9_.-]", "_");
        String ids = filter.certIds().isEmpty() ? "" : "_ids_" + filter.certIds().size();
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "approved_cards_" + grade + ids + "_" + LocalDateTime.now().format(FILENAME_TIMESTAMP) + "_" + nonce + ".xlsx";
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private String filterLabel(ExportFilter filter) {
        String label = switch (filter.kind()) {
            case ALL -> "All Approved Products";
            case GRADED -> "Graded Card - " + filter.gradeFilter();
            case MERCH -> "Merch Product";
            case VINTAGE -> "Vintage Card - " + filter.vintageClassification();
        };
        if (!filter.certIds().isEmpty()) {
            label += "; Cert IDs = " + String.join(", ", filter.certIds());
        }
        return label;
    }

    public record ExportRequest(String exportFilter, String gradeFilter, String certIds) {
        public ExportRequest(String gradeFilter, String certIds) {
            this(null, gradeFilter, certIds);
        }
    }

    public record ExportPreviewResponse(
        boolean canExport,
        int totalCount,
        int previewLimit,
        String gradeFilter,
        List<String> certIds,
        List<String> invalidCertIds,
        List<String> missingCertIds,
        List<ExportRow> rows
    ) {
    }

    public record ExportRow(
        long submissionId,
        String certId,
        String productType,
        String vintageClassification,
        String merchDescription,
        String cardCategory,
        String cardName,
        String movieName,
        String releaseYear,
        String productionCompany,
        String filmType,
        String sportsType,
        String groupName,
        String brandName,
        String yearLabel,
        String playerName,
        String varietyName,
        String setName,
        String cardNumber,
        String languageCode,
        int populationValue,
        String statusCode,
        BigDecimal finalGradeValue,
        String finalGradeLabel,
        BigDecimal centeringScore,
        BigDecimal edgesScore,
        BigDecimal cornersScore,
        BigDecimal surfaceScore,
        BigDecimal aiGradeValue,
        BigDecimal aiCenteringScore,
        BigDecimal aiEdgesScore,
        BigDecimal aiCornersScore,
        BigDecimal aiSurfaceScore,
        BigDecimal aiConfidenceValue,
        String decisionMethod,
        String decisionNotes,
        String gradingPhase,
        String stagedFrontUrl,
        String stagedBackUrl,
        String publishedFrontUrl,
        String publishedBackUrl,
        String entryNotes,
        String enteredBy,
        LocalDateTime entryDate,
        LocalDateTime approvedAt,
        Long approvalSequence,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String uploadStatus,
        LocalDateTime uploadStartedAt,
        LocalDateTime uploadCompletedAt,
        String uploadError,
        String serverResponse,
        String stagedFrontKey,
        String stagedBackKey
    ) {
    }

    public record ExportListResponse(List<ExportJobResponse> items, int page, int pageSize, int total) {
    }

    public record ExportJobResponse(
        long id,
        String filename,
        String filterLabel,
        String gradeFilter,
        String certIds,
        int recordCount,
        long fileSizeBytes,
        LocalDateTime createdAt
    ) {
    }

    public record DownloadableExport(String filename, Resource resource) {
    }

    public record DeleteExportResponse(boolean success, String filename) {
    }

    private enum ExportFilterKind {
        ALL,
        GRADED,
        MERCH,
        VINTAGE
    }

    private record ExportSelection(
        String value,
        ExportFilterKind kind,
        String gradeFilter,
        String vintageClassification
    ) {
    }

    private record ExportFilter(
        String selection,
        ExportFilterKind kind,
        String gradeFilter,
        String vintageClassification,
        List<String> certIds,
        List<String> invalidCertIds
    ) {
    }

    private record QueryParts(String whereClause, Map<String, Object> params) {
    }
}
