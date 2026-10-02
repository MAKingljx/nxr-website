package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.nio.file.Path;
import com.nxr.platform.admin.storage.LocalMediaStorageProvider;
import com.nxr.platform.admin.storage.MediaStorageRegistry;
import org.junit.jupiter.api.io.TempDir;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class AdminDashboardServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AdminDashboardService service;
    @TempDir Path mediaRoot;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_dashboard;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        createSchema();
        seedDashboardData();
        service = new AdminDashboardService(JdbcClient.create(jdbcTemplate));
    }

    @Test
    void attentionQueueFindsSupportAndFinanceOnDeliveredOrdersAndKeepsDeepLinks() {
        jdbcTemplate.update("INSERT INTO support_ticket (id, order_id, ticket_no, subject, status_code) VALUES (1, 3, 'TICKET-1', 'Return needs help', 'open')");
        jdbcTemplate.update("INSERT INTO payment_finance_exception (id, order_id, exception_type_code, resolution_status_code) VALUES (1, 3, 'reversed', 'open')");
        var items = service.loadDashboard().actionItems();
        assertThat(items).filteredOn(item -> "support".equals(item.kind())).singleElement()
            .satisfies(item -> assertThat(item.targetPath()).isEqualTo("/nxr/submissions/orders?orderId=3&section=support"));
        assertThat(items).filteredOn(item -> "finance".equals(item.kind())).singleElement()
            .satisfies(item -> assertThat(item.targetPath()).isEqualTo("/nxr/submissions/orders?orderId=3&section=finance"));
        assertThat(items.subList(0, 2)).extracting(AdminDashboardService.ActionItem::kind)
            .containsExactlyInAnyOrder("support", "finance");
        assertThat(service.loadDashboard(false, false).actionItems()).extracting(AdminDashboardService.ActionItem::kind)
            .doesNotContain("support", "finance");
        assertThat(items).filteredOn(item -> "review".equals(item.kind())).singleElement()
            .satisfies(item -> assertThat(item.targetPath()).isEqualTo("/nxr/cards/pending-review?status=pending&certId=CARD001"));
        jdbcTemplate.update("UPDATE support_ticket SET status_code='resolved' WHERE id=1");
        jdbcTemplate.update("UPDATE payment_finance_exception SET resolution_status_code='resolved' WHERE id=1");
        assertThat(service.loadDashboard().actionItems()).extracting(AdminDashboardService.ActionItem::kind)
            .doesNotContain("support", "finance");
    }

    @Test
    void readyMetricChecksStagedFilesWithoutScanningPublishedCatalogue() throws Exception {
        var provider = new LocalMediaStorageProvider(mediaRoot.toString(), "http://localhost:8090", "local-media");
        var media = new AdminMediaService(JdbcClient.create(jdbcTemplate), jdbcTemplate, null,
            new MediaStorageRegistry(java.util.List.of(provider), "local"), 12, 1024, 2048, 1000000);
        jdbcTemplate.update("UPDATE submission_media SET storage_provider_code='local', storage_bucket='local-media', storage_key=CONCAT(media_side_code, '.png') WHERE submission_id=2");
        var checked = new AdminDashboardService(JdbcClient.create(jdbcTemplate), media);
        assertThat(checked.loadDashboard().approvedReady()).isOne();
        assertThat(checked.loadDashboard().mediaStatus().ready()).isZero();
        java.nio.file.Files.createDirectories(mediaRoot.resolve("staged"));
        java.nio.file.Files.writeString(mediaRoot.resolve("staged/front.png"), "front");
        java.nio.file.Files.writeString(mediaRoot.resolve("staged/back.png"), "back");
        assertThat(checked.loadDashboard().mediaStatus().ready()).isOne();
    }

    @Test
    void aggregatesDashboardChartsQueuesAndRecentActivity() {
        AdminDashboardService.AdminDashboardResponse dashboard = service.loadDashboard();

        assertThat(dashboard.totalSubmissions()).isEqualTo(3);
        assertThat(dashboard.pendingReview()).isOne();
        assertThat(dashboard.approvedReady()).isOne();
        assertThat(dashboard.publishedCertificates()).isOne();
        assertThat(dashboard.waitlistCount()).isOne();

        assertThat(dashboard.productMix())
            .extracting(AdminDashboardService.CategoryCount::code, AdminDashboardService.CategoryCount::count)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("graded_card", 1),
                org.assertj.core.groups.Tuple.tuple("merch_product", 1),
                org.assertj.core.groups.Tuple.tuple("vintage_product", 1)
            );

        assertThat(dashboard.mediaStatus())
            .isEqualTo(new AdminDashboardService.MediaStatus(2, 0, 1, 1));
        assertThat(dashboard.orderPipeline())
            .extracting(AdminDashboardService.PipelineStage::code, AdminDashboardService.PipelineStage::orders)
            .contains(
                org.assertj.core.groups.Tuple.tuple("payment", 1),
                org.assertj.core.groups.Tuple.tuple("grading", 1),
                org.assertj.core.groups.Tuple.tuple("completed", 1)
            );
        assertThat(dashboard.actionItems())
            .extracting(AdminDashboardService.ActionItem::kind)
            .contains("review", "publication", "payment", "order");
        assertThat(dashboard.recentEntries()).hasSize(3);
        assertThat(dashboard.recentOrders()).hasSize(3);
        assertThat(dashboard.recentPublished()).singleElement().satisfies(card -> {
            assertThat(card.productType()).isEqualTo("vintage_product");
            assertThat(card.publishedAt()).isNotNull();
        });

        assertThat(dashboard.activityTrend()).hasSize(30);
        assertThat(dashboard.activityTrend().get(29)).satisfies(today -> {
            assertThat(today.date()).isEqualTo(LocalDate.now());
            assertThat(today.created()).isEqualTo(3);
            assertThat(today.published()).isOne();
        });
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
                card_name VARCHAR(255) NOT NULL,
                brand_name VARCHAR(64) NOT NULL,
                status_code VARCHAR(32) NOT NULL,
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
                final_grade_value DECIMAL(4,1),
                final_grade_label VARCHAR(64)
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE published_certificate (
                id BIGINT PRIMARY KEY,
                submission_id BIGINT NOT NULL,
                cert_id VARCHAR(32) NOT NULL,
                published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
                storage_provider_code VARCHAR(32), storage_bucket VARCHAR(128), storage_key VARCHAR(255),
                storage_object_version VARCHAR(128), public_url VARCHAR(255), sort_order INT DEFAULT 1,
                is_active TINYINT NOT NULL DEFAULT 1
            )
            """
        );
        jdbcTemplate.execute("CREATE TABLE submission_upload_state (submission_id BIGINT PRIMARY KEY, status_code VARCHAR(32))");
        jdbcTemplate.execute("CREATE TABLE support_ticket (id BIGINT PRIMARY KEY, order_id BIGINT, ticket_no VARCHAR(40), subject VARCHAR(200), status_code VARCHAR(32), updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE payment_finance_exception (id BIGINT PRIMARY KEY, order_id BIGINT, exception_type_code VARCHAR(32), resolution_status_code VARCHAR(32), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_order (
                id BIGINT PRIMARY KEY,
                order_no VARCHAR(40) NOT NULL,
                status_code VARCHAR(32) NOT NULL,
                total_card_count INT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE waitlist_email (
                id BIGINT PRIMARY KEY,
                email VARCHAR(255) NOT NULL
            )
            """
        );
    }

    private void seedDashboardData() {
        jdbcTemplate.update(
            """
            INSERT INTO grading_submission
                (id, cert_id, product_type_code, card_name, brand_name, status_code)
            VALUES
                (1, 'CARD001', 'graded_card', 'Pending Card', 'NXR', 'pending'),
                (2, 'MERCH01', 'label_product', 'Ready Merch', 'NXR', 'approved'),
                (3, 'OLD0001', 'vintage_product', 'Published Vintage', 'NXR', 'published')
            """
        );
        jdbcTemplate.update("UPDATE grading_submission SET approved_at = CURRENT_TIMESTAMP WHERE id = 2");
        jdbcTemplate.update("UPDATE grading_submission SET published_at = CURRENT_TIMESTAMP WHERE id = 3");
        jdbcTemplate.update(
            "INSERT INTO published_certificate (id, submission_id, cert_id) VALUES (1, 3, 'OLD0001')"
        );
        jdbcTemplate.update(
            """
            INSERT INTO submission_media (submission_id, media_side_code, media_stage_code) VALUES
                (2, 'front', 'staged'), (2, 'back', 'staged'),
                (3, 'front', 'published'), (3, 'back', 'published')
            """
        );
        jdbcTemplate.update(
            """
            INSERT INTO grading_order (id, order_no, status_code, total_card_count) VALUES
                (1, 'ORDER-PAY', 'awaiting_payment', 2),
                (2, 'ORDER-GRADE', 'grading', 3),
                (3, 'ORDER-DONE', 'delivered', 1)
            """
        );
        jdbcTemplate.update("INSERT INTO waitlist_email (id, email) VALUES (1, 'wait@example.com')");
    }
}
