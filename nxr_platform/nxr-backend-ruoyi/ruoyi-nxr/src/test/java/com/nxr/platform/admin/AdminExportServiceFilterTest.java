package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class AdminExportServiceFilterTest {

    @TempDir
    Path exportRoot;

    private JdbcTemplate jdbcTemplate;
    private AdminExportService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_export_filters;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        createSchema();
        seedProducts();
        service = new AdminExportService(JdbcClient.create(jdbcTemplate), exportRoot.toString());
    }

    @Test
    void newExportSelectionsKeepTheRequestedProductTypeIsolated() {
        assertPreview("all", 6, null, null);
        assertPreview("grade:9.5", 1, "graded_card", null);
        assertPreview("merch_product", 1, "merch_product", null);
        assertPreview("vintage_product:Pristine", 1, "vintage_product", "Pristine");
        assertPreview("vintage_product:Nova", 1, "vintage_product", "Nova");
        assertPreview("vintage_product:Legacy", 1, "vintage_product", "Legacy");
        assertPreview("vintage_product:Helix", 1, "vintage_product", "Helix");
    }

    @Test
    void legacyGradeFilterRemainsSupportedAndNewSelectionTakesPriority() {
        AdminExportService.ExportPreviewResponse legacy = service.preview(
            new AdminExportService.ExportRequest("9.50", "")
        );
        AdminExportService.ExportPreviewResponse preferred = service.preview(
            new AdminExportService.ExportRequest("merch_product", "9.5", "")
        );

        assertThat(legacy.gradeFilter()).isEqualTo("grade:9.5");
        assertThat(legacy.rows()).singleElement().satisfies(row ->
            assertThat(row.productType()).isEqualTo("graded_card")
        );
        assertThat(preferred.gradeFilter()).isEqualTo("merch_product");
        assertThat(preferred.rows()).singleElement().satisfies(row ->
            assertThat(row.productType()).isEqualTo("merch_product")
        );
    }

    @Test
    void unsupportedExportSelectionIsRejected() {
        assertThatThrownBy(() -> service.preview(
            new AdminExportService.ExportRequest("vintage_product:Unknown", null, "")
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void statusAndPeriodFiltersNarrowTheExportSelection() {
        AdminExportService.ExportPreviewResponse published = service.preview(
            new AdminExportService.ExportRequest("all", null, "", "published", null, null)
        );
        assertThat(published.totalCount()).isEqualTo(1);
        assertThat(published.rows()).allSatisfy(row ->
            assertThat(row.statusCode()).isEqualTo("published")
        );

        AdminExportService.ExportPreviewResponse approved = service.preview(
            new AdminExportService.ExportRequest("all", null, "", "approved", null, null)
        );
        assertThat(approved.totalCount()).isEqualTo(5);

        LocalDate today = LocalDate.now();
        AdminExportService.ExportPreviewResponse inPeriod = service.preview(
            new AdminExportService.ExportRequest("all", null, "", null, today.toString(), today.toString())
        );
        assertThat(inPeriod.totalCount()).isEqualTo(6);

        AdminExportService.ExportPreviewResponse outsidePeriod = service.preview(
            new AdminExportService.ExportRequest(
                "all", null, "", null, today.minusDays(10).toString(), today.minusDays(5).toString()
            )
        );
        assertThat(outsidePeriod.totalCount()).isZero();

        AdminExportService.ExportPreviewResponse combined = service.preview(
            new AdminExportService.ExportRequest("grade:9.5", null, "", "approved", today.toString(), today.toString())
        );
        assertThat(combined.totalCount()).isEqualTo(1);
    }

    @Test
    void previewSupportsSelectablePageSize() {
        AdminExportService.ExportPreviewResponse firstPage = service.preview(
            new AdminExportService.ExportRequest("all", null, "", null, null, null, 1, 2)
        );
        assertThat(firstPage.totalCount()).isEqualTo(6);
        assertThat(firstPage.previewLimit()).isEqualTo(2);
        assertThat(firstPage.rows()).hasSize(2);

        AdminExportService.ExportPreviewResponse secondPage = service.preview(
            new AdminExportService.ExportRequest("all", null, "", null, null, null, 2, 2)
        );
        assertThat(secondPage.rows()).hasSize(2);
        assertThat(secondPage.rows()).extracting(AdminExportService.ExportRow::submissionId)
            .doesNotContainAnyElementsOf(
                firstPage.rows().stream().map(AdminExportService.ExportRow::submissionId).toList()
            );

        AdminExportService.ExportPreviewResponse oversized = service.preview(
            new AdminExportService.ExportRequest("all", null, "", null, null, null, 1, 5000)
        );
        assertThat(oversized.previewLimit()).isEqualTo(100);

        AdminExportService.ExportPreviewResponse defaulted = service.preview(
            new AdminExportService.ExportRequest("all", null, "", null, null, null, null, null)
        );
        assertThat(defaulted.previewLimit()).isEqualTo(20);
        assertThat(defaulted.rows()).hasSize(6);
    }

    @Test
    void exportHistorySupportsKeywordAndPeriodFilters() {
        jdbcTemplate.update(
            """
            INSERT INTO export_job (filename, filter_label, grade_filter, cert_ids, record_count,
                                    file_size_bytes, storage_path, created_by_user_id, created_at)
            VALUES ('approved_cards_all_20260105.xlsx', 'All Approved Products', 'all', '', 3, 120,
                    '/tmp/a.xlsx', 1, '2026-01-05 10:00:00')
            """
        );
        jdbcTemplate.update(
            """
            INSERT INTO export_job (filename, filter_label, grade_filter, cert_ids, record_count,
                                    file_size_bytes, storage_path, created_by_user_id, created_at)
            VALUES ('approved_cards_9.5_20260620.xlsx', 'Graded Card - 9.5', 'grade:9.5', '', 5, 240,
                    '/tmp/b.xlsx', 1, '2026-06-20 10:00:00')
            """
        );

        assertThat(service.listExports(1, 20, null, null, null).total()).isEqualTo(2);
        assertThat(service.listExports(1, 20, "9.5", null, null).total()).isEqualTo(1);
        assertThat(service.listExports(1, 20, "20260105", null, null).total()).isEqualTo(1);
        assertThat(service.listExports(1, 20, null, "2026-06-01", "2026-06-30").total()).isEqualTo(1);
        assertThat(service.listExports(1, 20, null, "2026-01-01", "2026-06-30").total()).isEqualTo(2);
        assertThat(service.listExports(1, 20, null, "2026-07-01", "2026-07-31").total()).isZero();

        assertThatThrownBy(() -> service.listExports(1, 20, null, "2026-07-01", "2026-06-01"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void exportHistoryPaginationAlwaysResolvesAUsablePage() {
        AdminExportService.ExportListResponse oversized = service.listExports(1, 500);
        assertThat(oversized.pageSize()).isEqualTo(200);
        assertThat(oversized.page()).isEqualTo(1);

        AdminExportService.ExportListResponse normalized = service.listExports(0, 0);
        assertThat(normalized.page()).isEqualTo(1);
        assertThat(normalized.pageSize()).isEqualTo(1);
    }

    @Test
    void unsupportedStatusAndReversedPeriodAreRejected() {
        assertThatThrownBy(() -> service.preview(
            new AdminExportService.ExportRequest("all", null, "", "draft", null, null)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));

        LocalDate today = LocalDate.now();
        assertThatThrownBy(() -> service.preview(
            new AdminExportService.ExportRequest(
                "all", null, "", null, today.toString(), today.minusDays(1).toString()
            )
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void generatedWorkbookMatchesLivePythonColumnsAndNeverCollides() throws Exception {
        jdbcTemplate.update(
            "UPDATE grading_submission SET entry_by_label='Python Operator', entry_notes='Intake note' WHERE id=1"
        );
        jdbcTemplate.update(
            """
            UPDATE grading_score
            SET ai_grade_value=9.4, ai_centering_score=9.3, ai_edges_score=9.2,
                ai_corners_score=9.1, ai_surface_score=9.0, ai_confidence_value=97.5,
                decision_method_code='hybrid', decision_notes='Reviewed'
            WHERE submission_id=1
            """
        );
        jdbcTemplate.update(
            """
            INSERT INTO submission_upload_state (
                submission_id, status_code, started_at, completed_at,
                error_message, response_payload_json
            ) VALUES (1, 'failed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                      'R2 timeout', '{"ok":false}')
            """
        );
        jdbcTemplate.update(
            """
            INSERT INTO submission_media (submission_id, media_stage_code, media_side_code, public_url, storage_key)
            VALUES (1, 'staged', 'front', '/staged/front.webp', 'front.webp'),
                   (1, 'staged', 'back', '/staged/back.webp', 'back.webp'),
                   (1, 'published', 'front', 'https://r2.example/front.webp', 'front.webp'),
                   (1, 'published', 'back', 'https://r2.example/back.webp', 'back.webp')
            """
        );

        AdminExportService.ExportRequest request = new AdminExportService.ExportRequest("grade:9.5", null, "");
        AdminExportService.ExportJobResponse first = service.generate(request, 1L);
        AdminExportService.ExportJobResponse second = service.generate(request, 1L);

        assertThat(first.filename()).isNotEqualTo(second.filename());
        assertThat(Files.isRegularFile(exportRoot.resolve(first.filename()))).isTrue();
        assertThat(Files.isRegularFile(exportRoot.resolve(second.filename()))).isTrue();
        try (ZipFile workbook = new ZipFile(exportRoot.resolve(first.filename()).toFile())) {
            String sheet = new String(
                workbook.getInputStream(workbook.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),
                StandardCharsets.UTF_8
            );
            assertThat(sheet)
                .doesNotContain("<t>ai_centering</t>")
                .contains("<t>server_response</t>")
                .contains("<t>Python Operator</t>")
                .contains("<t>front.webp</t>")
                .doesNotContain("<t>/staged/front.webp</t>")
                .contains("<t>R2 timeout</t>")
                .contains("<t>{\"ok\":false}</t>");
            // Golden order from Python's actual SELECT * export, including appended columns.
            assertThat(cells(first.filename(), 1, 1)).containsExactly(
                "id", "cert_id", "card_name", "year", "brand", "variety", "pop", "language", "set_name", "card_number",
                "centering", "edges", "corners", "surface", "final_grade", "final_grade_text", "front_image", "back_image",
                "entry_notes", "entry_by", "entry_date", "status", "created_at", "updated_at", "upload_status",
                "upload_started", "upload_completed", "upload_error", "server_response", "published_front_image",
                "published_back_image", "approved_at", "approval_sequence", "card_category", "movie_name", "release_year",
                "production_company", "film_type", "sports_type", "group_name", "product_type", "vintage_classification",
                "merch_description", "landing_page_url"
            );
        }
        try (var files = Files.list(exportRoot)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".part"))).isEmpty();
        }
    }

    @Test
    void workbookUsesPythonSummaryStatisticsAndUnscoredProductRules() throws Exception {
        jdbcTemplate.update("UPDATE grading_submission SET approved_at='2026-10-12 09:00:00' WHERE id<>2");
        jdbcTemplate.update("UPDATE grading_submission SET approved_at=NULL, updated_at='2026-10-13 12:00:00' WHERE id=2");
        jdbcTemplate.update("UPDATE grading_score SET final_grade_value=9.25 WHERE submission_id=1");
        jdbcTemplate.update("INSERT INTO grading_score (submission_id, final_grade_value, final_grade_label, centering_score) VALUES (2,9.5,'9.5',9.5),(3,9.5,'9.5',9.5)");
        var job = service.generate(new AdminExportService.ExportRequest("all", null, ""), 1L);
        try (ZipFile workbook = new ZipFile(exportRoot.resolve(job.filename()).toFile())) {
            String book = new String(workbook.getInputStream(workbook.getEntry("xl/workbook.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(book).contains("name=\"Approved Cards\"", "name=\"导出汇总\"", "name=\"评分统计\"");
        }
        assertThat(cells(job.filename(), 2, 1)).containsExactly("导出时间", "总记录数", "筛选条件", "数据范围", "包含字段数", "文件名称");
        assertThat(cells(job.filename(), 2, 2).subList(1, 5)).containsExactly("6", "All Approved Products", "2026-10-12 至 2026-10-12", "44");
        assertThat(cells(job.filename(), 3, 1)).containsExactly("评分等级", "数量", "占比");
        assertThat(cells(job.filename(), 3, 2)).containsExactly("9.5", "1", "16.7%");
        // Merch comes first via the timestamp fallback; its stale scores are not exported.
        assertThat(cells(job.filename(), 1, 2).subList(10, 16)).containsExactly("", "", "", "", "", "");
        assertThat(cells(job.filename(), 1, 3).get(14)).isEqualTo("9.2");
        var merch = service.generate(new AdminExportService.ExportRequest("merch_product", null, ""), 1L);
        assertThat(cells(merch.filename(), 3, 2)).isEmpty();
    }

    @Test
    void exportOrderPreservesApprovalSequenceAndFinalIdTieBreak() {
        jdbcTemplate.update("UPDATE grading_submission SET approved_at='2026-10-12 09:00:00', approval_sequence=NULL");
        jdbcTemplate.update("UPDATE grading_submission SET approval_sequence=1 WHERE id IN(3,6)");
        jdbcTemplate.update("UPDATE grading_submission SET approval_sequence=2, created_at='2026-10-13 15:00:00' WHERE id=1");
        assertThat(service.preview(new AdminExportService.ExportRequest("all", null, "")).rows())
            .extracting(AdminExportService.ExportRow::submissionId).containsExactly(3L,6L,1L,2L,4L,5L);
    }

    @Test
    void certificateFilterUsesPythonTenDigitContractWithoutChangingAllRecords() {
        var valid = service.preview(new AdminExportService.ExportRequest("all", null, "9000000001，9000000001;9000000002"));
        assertThat(valid.certIds()).containsExactly("9000000001", "9000000002");
        assertThat(valid.totalCount()).isEqualTo(2);
        var invalid = service.preview(new AdminExportService.ExportRequest("all", null, "VRA003 123 12345678901"));
        assertThat(invalid.canExport()).isFalse();
        assertThat(invalid.invalidCertIds()).containsExactly("VRA003", "123", "12345678901");
        assertThatThrownBy(() -> service.generate(new AdminExportService.ExportRequest("all", null, "VRA003"), 1L))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(service.preview(new AdminExportService.ExportRequest("", "9.5", "")).totalCount()).isEqualTo(6);
    }

    private List<String> cells(String filename, int sheet, int row) throws Exception {
        try (ZipFile workbook = new ZipFile(exportRoot.resolve(filename).toFile())) {
            var bytes = workbook.getInputStream(workbook.getEntry("xl/worksheets/sheet" + sheet + ".xml")).readAllBytes();
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            var rows = doc.getElementsByTagName("row");
            if (row > rows.getLength()) return List.of();
            var nodes = ((Element) rows.item(row - 1)).getElementsByTagName("c");
            List<String> result = new ArrayList<>();
            for (int i=0; i<nodes.getLength(); i++) result.add(nodes.item(i).getTextContent());
            return result;
        }
    }

    private void assertPreview(String selection, int expectedCount, String productType, String vintageClassification) {
        AdminExportService.ExportPreviewResponse preview = service.preview(
            new AdminExportService.ExportRequest(selection, null, "")
        );

        assertThat(preview.canExport()).isTrue();
        assertThat(preview.totalCount()).isEqualTo(expectedCount);
        if (productType != null) {
            assertThat(preview.rows()).allSatisfy(row -> assertThat(row.productType()).isEqualTo(productType));
        }
        if (vintageClassification != null) {
            assertThat(preview.rows()).allSatisfy(row ->
                assertThat(row.vintageClassification()).isEqualTo(vintageClassification)
            );
        }
    }

    private void createSchema() {
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_submission (
                id BIGINT PRIMARY KEY,
                cert_id VARCHAR(32) NOT NULL,
                product_type_code VARCHAR(32),
                vintage_classification_code VARCHAR(64),
                merch_description TEXT,
                card_category_code VARCHAR(32),
                card_name VARCHAR(255) NOT NULL,
                movie_name VARCHAR(255),
                release_year VARCHAR(16),
                production_company VARCHAR(128),
                film_type VARCHAR(128),
                sports_type VARCHAR(64),
                group_name VARCHAR(128),
                brand_name VARCHAR(128) NOT NULL,
                year_label VARCHAR(16),
                player_name VARCHAR(128),
                variety_name VARCHAR(255),
                set_name VARCHAR(255) NOT NULL,
                card_number VARCHAR(64) NOT NULL,
                language_code VARCHAR(16) NOT NULL,
                population_value INT NOT NULL,
                status_code VARCHAR(32) NOT NULL,
                grading_phase_code VARCHAR(32) DEFAULT 'human_review',
                entry_notes TEXT,
                entry_by_user_id BIGINT,
                entry_by_label VARCHAR(128),
                approval_sequence BIGINT,
                approved_at TIMESTAMP,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE export_job (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                filename VARCHAR(255) NOT NULL UNIQUE,
                filter_label VARCHAR(255),
                grade_filter VARCHAR(128),
                cert_ids TEXT,
                record_count INT NOT NULL,
                file_size_bytes BIGINT NOT NULL,
                storage_path VARCHAR(1024) NOT NULL,
                created_by_user_id BIGINT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_score (
                submission_id BIGINT PRIMARY KEY,
                final_grade_value DECIMAL(4,2),
                final_grade_label VARCHAR(64),
                centering_score DECIMAL(4,1),
                edges_score DECIMAL(4,1),
                corners_score DECIMAL(4,1),
                surface_score DECIMAL(4,1),
                ai_grade_value DECIMAL(4,1),
                ai_centering_score DECIMAL(4,1),
                ai_edges_score DECIMAL(4,1),
                ai_corners_score DECIMAL(4,1),
                ai_surface_score DECIMAL(4,1),
                ai_confidence_value DECIMAL(5,2),
                decision_method_code VARCHAR(32),
                decision_notes TEXT
            )
            """
        );
        jdbcTemplate.execute("CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY, user_name VARCHAR(64), nick_name VARCHAR(64))");
        jdbcTemplate.execute(
            """
            CREATE TABLE submission_upload_state (
                submission_id BIGINT PRIMARY KEY, status_code VARCHAR(32), started_at TIMESTAMP,
                completed_at TIMESTAMP, error_message TEXT, response_payload_json TEXT
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE submission_media (
                id BIGINT PRIMARY KEY AUTO_INCREMENT, submission_id BIGINT, media_stage_code VARCHAR(16),
                media_side_code VARCHAR(16), public_url VARCHAR(255), storage_key VARCHAR(255), is_active TINYINT DEFAULT 1
            )
            """
        );
    }

    private void seedProducts() {
        insertSubmission(1, "9000000001", "graded_card", null, null, "approved");
        jdbcTemplate.update(
            """
            INSERT INTO grading_score (
                submission_id, final_grade_value, final_grade_label,
                centering_score, edges_score, corners_score, surface_score
            ) VALUES (1, 9.5, '9.50', 9.5, 9.5, 9.5, 9.5)
            """
        );
        insertSubmission(2, "9000000002", "label_product", null, "Collector pin", "approved");
        insertSubmission(3, "9000000003", "vintage_product", "Pristine", null, "approved");
        insertSubmission(4, "9000000004", "vintage_product", "Nova", null, "approved");
        insertSubmission(5, "9000000005", "vintage_product", "Legacy", null, "published");
        insertSubmission(6, "9000000006", "vintage_product", "Helix", null, "approved");
        insertSubmission(7, "9000000007", "merch_product", null, "Pending merch", "pending");
    }

    private void insertSubmission(
        long id,
        String certId,
        String productType,
        String vintageClassification,
        String merchDescription,
        String status
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO grading_submission (
                id, cert_id, product_type_code, vintage_classification_code, merch_description,
                card_category_code, card_name, brand_name, year_label, set_name, card_number,
                language_code, population_value, status_code, approved_at
            ) VALUES (?, ?, ?, ?, ?, 'trading_card', 'Test Product', 'NXR', '2026',
                      'Test Set', ?, 'EN', 1, ?, CURRENT_TIMESTAMP)
            """,
            id,
            certId,
            productType,
            vintageClassification,
            merchDescription,
            String.valueOf(id),
            status
        );
    }
}
