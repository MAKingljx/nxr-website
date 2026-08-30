package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
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
                brand_name VARCHAR(128) NOT NULL,
                year_label VARCHAR(16),
                set_name VARCHAR(255) NOT NULL,
                card_number VARCHAR(64) NOT NULL,
                language_code VARCHAR(16) NOT NULL,
                population_value INT NOT NULL,
                status_code VARCHAR(32) NOT NULL,
                approved_at TIMESTAMP,
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
                surface_score DECIMAL(4,1)
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
