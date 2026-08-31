package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.nxr.platform.customer.CustomerPortalService;
import com.nxr.platform.publicapi.PublicSiteService;
import java.nio.file.Path;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class NonGradedReadPathTest {

    @TempDir
    Path exportRoot;

    private JdbcTemplate jdbcTemplate;
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_non_graded_reads;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcClient = JdbcClient.create(jdbcTemplate);
        resetSchema();
        seedLabelProduct();
    }

    @Test
    void dashboardPublicMediaExportAndCustomerReadsKeepProductsWithoutScores() {
        AdminDashboardService.AdminDashboardResponse dashboard = new AdminDashboardService(jdbcClient).loadDashboard();
        assertThat(dashboard.recentPublished()).singleElement().satisfies(card -> {
            assertThat(card.productType()).isEqualTo("merch_product");
            assertThat(card.merchDescription()).isEqualTo("Limited legacy merchandise");
            assertThat(card.finalGradeValue()).isNull();
            assertThat(card.finalGradeLabel()).isNull();
        });

        PublicSiteService publicSiteService = new PublicSiteService(jdbcClient);
        assertThat(publicSiteService.loadOverview().featuredCards()).singleElement().satisfies(card -> {
            assertThat(card.productType()).isEqualTo("merch_product");
            assertThat(card.merchDescription()).isEqualTo("Limited legacy merchandise");
            assertThat(card.finalGradeValue()).isNull();
        });
        assertThat(publicSiteService.loadPublishedCard("5703018299")).get().satisfies(card -> {
            assertThat(card.productType()).isEqualTo("merch_product");
            assertThat(card.merchDescription()).isEqualTo("Limited legacy merchandise");
            assertThat(card.finalGradeLabel()).isNull();
        });

        AdminMediaService.MediaQueueResponse mediaQueue = new AdminMediaService(
            jdbcClient,
            null,
            null,
            100,
            1024
        ).loadQueue(null, 1, 20);
        assertThat(mediaQueue.total()).isOne();
        assertThat(mediaQueue.items()).singleElement().satisfies(item -> {
            assertThat(item.productType()).isEqualTo("merch_product");
            assertThat(item.merchDescription()).isEqualTo("Limited legacy merchandise");
            assertThat(item.finalGradeValue()).isNull();
        });

        AdminExportService.ExportPreviewResponse exportPreview = new AdminExportService(
            jdbcClient,
            exportRoot.toString()
        ).preview(new AdminExportService.ExportRequest("all", ""));
        assertThat(exportPreview.totalCount()).isOne();
        assertThat(exportPreview.rows()).singleElement().satisfies(row -> {
            assertThat(row.productType()).isEqualTo("merch_product");
            assertThat(row.merchDescription()).isEqualTo("Limited legacy merchandise");
            assertThat(row.finalGradeValue()).isNull();
        });

        CustomerPortalService customerPortalService = new CustomerPortalService(jdbcClient, jdbcTemplate);
        assertThat(customerPortalService.listCustomerCards(7L)).singleElement().satisfies(card -> {
            assertThat(card.productType()).isEqualTo("merch_product");
            assertThat(card.merchDescription()).isEqualTo("Limited legacy merchandise");
            assertThat(card.finalGradeValue()).isNull();
        });
    }

    private void seedLabelProduct() {
        jdbcTemplate.update(
            """
            INSERT INTO grading_submission (
                id, cert_id, product_type_code, merch_description, card_category_code, card_name, brand_name,
                set_name, card_number, language_code, population_value, status_code,
                grading_phase_code, approved_at, published_at
            ) VALUES (1, '5703018299', 'label_product', 'Limited legacy merchandise', 'trading_card', 'Test Label', 'Pokemon',
                      'Base Set', '4/102', 'EN', 1, 'published', 'human_review', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """
        );
        jdbcTemplate.update(
            """
            INSERT INTO published_certificate (
                id, submission_id, cert_id, verification_slug, published_at
            ) VALUES (1, 1, '5703018299', 'label-test', CURRENT_TIMESTAMP)
            """
        );
        jdbcTemplate.update(
            """
            INSERT INTO certificate_ownership (
                id, cert_id, customer_id, ownership_status_code, visibility_code, bound_at
            ) VALUES (1, '5703018299', 7, 'active', 'public', CURRENT_TIMESTAMP)
            """
        );
    }

    private void resetSchema() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_submission (
                id BIGINT PRIMARY KEY,
                cert_id VARCHAR(32) NOT NULL,
                product_type_code VARCHAR(32) NOT NULL DEFAULT 'graded_card',
                vintage_classification_code VARCHAR(64),
                merch_description TEXT,
                card_category_code VARCHAR(32) NOT NULL DEFAULT 'trading_card',
                card_name VARCHAR(255) NOT NULL,
                movie_name VARCHAR(255),
                release_year VARCHAR(16),
                production_company VARCHAR(128),
                film_type VARCHAR(128),
                sports_type VARCHAR(64),
                group_name VARCHAR(128),
                year_label VARCHAR(16),
                brand_name VARCHAR(64) NOT NULL,
                player_name VARCHAR(128),
                variety_name VARCHAR(255),
                set_name VARCHAR(255) NOT NULL,
                card_number VARCHAR(64) NOT NULL,
                language_code VARCHAR(16) NOT NULL,
                population_value INT NOT NULL,
                status_code VARCHAR(32) NOT NULL,
                grading_phase_code VARCHAR(32) NOT NULL,
                entry_notes TEXT,
                approved_at TIMESTAMP,
                published_at TIMESTAMP,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_score (
                submission_id BIGINT PRIMARY KEY,
                centering_score DECIMAL(4,1),
                edges_score DECIMAL(4,1),
                corners_score DECIMAL(4,1),
                surface_score DECIMAL(4,1),
                final_grade_value DECIMAL(4,1),
                final_grade_label VARCHAR(64),
                decision_method_code VARCHAR(32),
                decision_notes TEXT
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE published_certificate (
                id BIGINT PRIMARY KEY,
                submission_id BIGINT NOT NULL,
                cert_id VARCHAR(32) NOT NULL,
                verification_slug VARCHAR(64) NOT NULL,
                qr_url VARCHAR(255),
                published_at TIMESTAMP NOT NULL,
                published_front_media_id BIGINT,
                published_back_media_id BIGINT
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE submission_media (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                submission_id BIGINT NOT NULL,
                media_side_code VARCHAR(16) NOT NULL,
                media_stage_code VARCHAR(16) NOT NULL,
                public_url VARCHAR(255),
                sort_order INT NOT NULL DEFAULT 1,
                is_active TINYINT NOT NULL DEFAULT 1
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE waitlist_email (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                email VARCHAR(255)
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_order (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                order_no VARCHAR(40) NOT NULL,
                status_code VARCHAR(32) NOT NULL,
                total_card_count INT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE certificate_ownership (
                id BIGINT PRIMARY KEY,
                cert_id VARCHAR(32) NOT NULL,
                customer_id BIGINT NOT NULL,
                ownership_status_code VARCHAR(32) NOT NULL,
                visibility_code VARCHAR(32) NOT NULL,
                note TEXT,
                bound_at TIMESTAMP NOT NULL
            )
            """
        );
    }
}
