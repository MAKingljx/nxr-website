package com.nxr.platform.admin;

import com.nxr.platform.shared.ProductTypePolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminExportService {

    private static final Pattern CERT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,64}$");
    private static final DateTimeFormatter FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
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

        try {
            Files.createDirectories(exportRoot);
            String filename = filenameFor(filter);
            Path outputPath = exportRoot.resolve(filename).normalize();
            if (!outputPath.startsWith(exportRoot.toAbsolutePath().normalize()) && exportRoot.isAbsolute()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export path");
            }

            try (var outputStream = Files.newOutputStream(outputPath)) {
                SimpleXlsxWriter.writeWorkbook(outputStream, buildSheets(rows, filter, filename));
            }

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

            return findJob(filename);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write Excel export", exception);
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
        approvedRows.add(List.of(
            "cert_id", "product_type", "vintage_classification", "merch_description", "card_category", "card_name", "brand_name", "year_label", "set_name", "card_number",
            "language_code", "population", "status", "final_grade", "final_grade_text", "centering", "edges",
            "corners", "surface", "landing_page_url"
        ));
        for (ExportRow row : rows) {
            approvedRows.add(List.of(
                row.certId(), row.productType(), cell(row.vintageClassification()), cell(row.merchDescription()), row.cardCategory(), row.cardName(), row.brandName(), cell(row.yearLabel()), row.setName(),
                row.cardNumber(), row.languageCode(), row.populationValue(), row.statusCode(), cell(row.finalGradeValue()),
                cell(row.finalGradeLabel()), cell(row.centeringScore()), cell(row.edgesScore()), cell(row.cornersScore()), cell(row.surfaceScore()),
                "nxrgrading.com/card/" + row.certId()
            ));
        }

        List<List<?>> summaryRows = new ArrayList<>();
        summaryRows.add(List.of("Export Time", LocalDateTime.now().toString()));
        summaryRows.add(List.of("Record Count", rows.size()));
        summaryRows.add(List.of("Filter", filterLabel(filter)));
        summaryRows.add(List.of("Filename", filename));

        Map<String, Integer> gradeStats = new LinkedHashMap<>();
        for (ExportRow row : rows) {
            gradeStats.merge(row.finalGradeLabel() == null ? "Not graded" : row.finalGradeLabel(), 1, Integer::sum);
        }
        List<List<?>> gradeRows = new ArrayList<>();
        gradeRows.add(List.of("Grade", "Count", "Percent"));
        for (Map.Entry<String, Integer> entry : gradeStats.entrySet()) {
            gradeRows.add(List.of(entry.getKey(), entry.getValue(), Math.round((entry.getValue() * 1000.0 / rows.size())) / 10.0 + "%"));
        }

        return List.of(
            new SimpleXlsxWriter.Sheet("Approved Cards", approvedRows),
            new SimpleXlsxWriter.Sheet("Summary", summaryRows),
            new SimpleXlsxWriter.Sheet("Grade Stats", gradeRows)
        );
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
                    s.cert_id,
                    COALESCE(NULLIF(s.product_type_code, ''), 'graded_card') AS product_type_code,
                    s.vintage_classification_code,
                    s.merch_description,
                    COALESCE(NULLIF(s.card_category_code, ''), 'trading_card') AS card_category_code,
                    s.card_name,
                    s.brand_name,
                    s.year_label,
                    s.set_name,
                    s.card_number,
                    s.language_code,
                    s.population_value,
                    s.status_code,
                    g.final_grade_value,
                    g.final_grade_label,
                    g.centering_score,
                    g.edges_score,
                    g.corners_score,
                    g.surface_score
                FROM grading_submission s
                LEFT JOIN grading_score g ON g.submission_id = s.id
                """ + queryParts.whereClause() + """
                ORDER BY s.approved_at DESC, s.created_at DESC, s.cert_id ASC
                LIMIT :limit
                """
            )
            .params(params)
            .query((rs, rowNum) -> new ExportRow(
                rs.getString("cert_id"),
                ProductTypePolicy.normalizeStored(rs.getString("product_type_code")),
                rs.getString("vintage_classification_code"),
                rs.getString("merch_description"),
                rs.getString("card_category_code"),
                rs.getString("card_name"),
                rs.getString("brand_name"),
                rs.getString("year_label"),
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
                rs.getBigDecimal("surface_score")
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
                if ("Pristine 10".equals(filter.gradeFilter())) {
                    where.append(
                        " AND REPLACE(REPLACE(UPPER(TRIM(COALESCE(g.final_grade_label, ''))), ' ', ''), '-', '') LIKE '%STINE10%'"
                    );
                } else {
                    where.append(" AND UPPER(TRIM(COALESCE(g.final_grade_label, ''))) IN (:gradeAliases)");
                    params.put("gradeAliases", gradeLabelAliases(filter.gradeFilter()));
                }
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
        String rawSelection = request == null ? "" : firstNonBlank(request.exportFilter(), request.gradeFilter());
        if (rawSelection == null || rawSelection.equalsIgnoreCase(EXPORT_FILTER_ALL)) {
            return new ExportSelection(EXPORT_FILTER_ALL, ExportFilterKind.ALL, null, null);
        }

        if (rawSelection.regionMatches(true, 0, EXPORT_FILTER_GRADE_PREFIX, 0, EXPORT_FILTER_GRADE_PREFIX.length())) {
            String grade = normalizeGrade(rawSelection.substring(EXPORT_FILTER_GRADE_PREFIX.length()));
            if (grade == null) {
                throw unsupportedFilter();
            }
            return new ExportSelection(EXPORT_FILTER_GRADE_PREFIX + grade, ExportFilterKind.GRADED, grade, null);
        }

        if (rawSelection.equalsIgnoreCase(EXPORT_FILTER_MERCH)) {
            return new ExportSelection(EXPORT_FILTER_MERCH, ExportFilterKind.MERCH, null, null);
        }

        if (rawSelection.regionMatches(true, 0, EXPORT_FILTER_VINTAGE_PREFIX, 0, EXPORT_FILTER_VINTAGE_PREFIX.length())) {
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

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
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

    private List<String> gradeLabelAliases(String grade) {
        return switch (grade) {
            case "8" -> List.of("8", "8.0", "8.00", "NEAR MINT-MINT 8");
            case "8.5" -> List.of("8.5", "8.50", "NEAR MINT-MINT+ 8.5");
            case "9" -> List.of("9", "9.0", "9.00", "MINT 9");
            case "9.5" -> List.of("9.5", "9.50", "GEM MINT 9.5");
            case "10" -> List.of("10", "10.0", "10.00");
            default -> throw unsupportedFilter();
        };
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
        String grade = filter.selection().replaceAll("[^A-Za-z0-9_-]", "_");
        String ids = filter.certIds().isEmpty() ? "" : "_ids_" + filter.certIds().size();
        return "approved_cards_" + grade + ids + "_" + LocalDateTime.now().format(FILENAME_TIMESTAMP) + ".xlsx";
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
        String certId,
        String productType,
        String vintageClassification,
        String merchDescription,
        String cardCategory,
        String cardName,
        String brandName,
        String yearLabel,
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
        BigDecimal surfaceScore
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
