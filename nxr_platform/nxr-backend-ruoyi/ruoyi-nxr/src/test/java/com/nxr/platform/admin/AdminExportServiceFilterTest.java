package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
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
    void generatedWorkbookContainsFullPythonFieldsAndNeverCollides() throws Exception {
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
            INSERT INTO submission_media (submission_id, media_stage_code, media_side_code, public_url)
            VALUES (1, 'staged', 'front', '/staged/front.webp'),
                   (1, 'staged', 'back', '/staged/back.webp'),
                   (1, 'published', 'front', 'https://r2.example/front.webp'),
                   (1, 'published', 'back', 'https://r2.example/back.webp')
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
                .contains("<t>ai_centering</t>")
                .contains("<t>server_response</t>")
                .contains("<t>Python Operator</t>")
                .contains("<t>R2 timeout</t>")
                .contains("<t>{\"ok\":false}</t>");
        }
        try (var files = Files.list(exportRoot)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".part"))).isEmpty();
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
                final_grade_value DECIMAL(4,1),
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
                media_side_code VARCHAR(16), public_url VARCHAR(255), is_active TINYINT DEFAULT 1
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
