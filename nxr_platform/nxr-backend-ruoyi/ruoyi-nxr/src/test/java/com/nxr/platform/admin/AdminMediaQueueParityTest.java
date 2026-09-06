package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.nxr.platform.admin.storage.LocalMediaStorageProvider;
import com.nxr.platform.admin.storage.MediaStorageRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminMediaQueueParityTest {

    @TempDir
    Path storageRoot;

    private JdbcTemplate jdbcTemplate;
    private AdminMediaService mediaService;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:media-queue-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        seedQueue();

        LocalMediaStorageProvider localProvider = new LocalMediaStorageProvider(
            storageRoot.toString(),
            "http://127.0.0.1:8088",
            "local-media"
        );
        mediaService = new AdminMediaService(
            JdbcClient.create(jdbcTemplate),
            null,
            new MediaStorageRegistry(java.util.List.of(localProvider), "local"),
            12,
            24L * 1024 * 1024,
            24L * 1024 * 1024,
            100_000_000L
        );
    }

    @Test
    void combinesPythonQueueFiltersWhileKeepingSummaryGlobal() {
        AdminMediaService.MediaQueueResponse response = mediaService.loadQueue(
            null,
            "CERT004",
            "Char",
            "sports_card",
            "graded_card",
            "Pokemon",
            "EN",
            "10",
            "remaining_uploads",
            "ready",
            false,
            1,
            20
        );

        assertThat(response.total()).isOne();
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.certId()).isEqualTo("CERT004");
            assertThat(item.readyToPublish()).isTrue();
        });
        assertThat(response.summary()).satisfies(summary -> {
            assertThat(summary.totalApproved()).isEqualTo(4);
            assertThat(summary.clientPushed()).isOne();
            assertThat(summary.readyToPublish()).isOne();
            assertThat(summary.waitingForUpload()).isEqualTo(2);
            assertThat(summary.uploadedToServer()).isOne();
            assertThat(summary.remainingUploadCount()).isEqualTo(3);
            assertThat(summary.statusCounts()).containsEntry("failed", 2).containsEntry("client_pushed", 1);
        });
        assertThat(filteredTotal("remaining_uploads", null)).isEqualTo(response.summary().remainingUploadCount());
        assertThat(filteredTotal("uploaded_to_server", null)).isEqualTo(response.summary().uploadedToServer());
        assertThat(filteredTotal("client_pushed", null)).isEqualTo(response.summary().clientPushed());
        assertThat(filteredTotal(null, "ready")).isEqualTo(response.summary().readyToPublish());
        assertThat(filteredTotal(null, "waiting")).isEqualTo(response.summary().waitingForUpload());
    }

    private int filteredTotal(String uploadStatus, String imageStatus) {
        return mediaService.loadQueue(
            null, null, null, null, null, null, null, null,
            uploadStatus, imageStatus, true, 1, 20
        ).total();
    }

    @Test
    void missingFileFiltersUseTheFilesystemRatherThanOnlyMediaRows() {
        AdminMediaService.MediaQueueResponse response = mediaService.loadQueue(
            null, null, null, null, null, null, null, null,
            null, "missing_back", true, 1, 20
        );

        assertThat(response.items()).extracting(AdminMediaService.MediaQueueItem::certId)
            .containsExactlyInAnyOrder("CERT001", "ABC003");
        AdminMediaService.MediaQueueItem missingRecordedFile = response.items().stream()
            .filter(item -> "CERT001".equals(item.certId()))
            .findFirst()
            .orElseThrow();
        assertThat(missingRecordedFile.stagedBackUrl()).isNotBlank();
        assertThat(missingRecordedFile.stagedBackMissing()).isTrue();
        assertThat(missingRecordedFile.hasStagedBack()).isFalse();
        assertThat(response.summary().missingMedia()).isEqualTo(2);
    }

    private void createSchema() {
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_submission (
                id BIGINT PRIMARY KEY,
                cert_id VARCHAR(32) NOT NULL,
                product_type_code VARCHAR(32),
                card_category_code VARCHAR(32),
                vintage_classification_code VARCHAR(64),
                merch_description TEXT,
                card_name VARCHAR(255),
                set_name VARCHAR(255),
                brand_name VARCHAR(128),
                language_code VARCHAR(16),
                status_code VARCHAR(32) NOT NULL,
                approved_at TIMESTAMP,
                published_at TIMESTAMP,
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
            CREATE TABLE submission_upload_state (
                submission_id BIGINT PRIMARY KEY,
                status_code VARCHAR(32) NOT NULL,
                started_at TIMESTAMP,
                completed_at TIMESTAMP,
                error_message TEXT
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
                storage_provider_code VARCHAR(32),
                storage_bucket VARCHAR(128),
                storage_key VARCHAR(255),
                storage_object_version VARCHAR(128),
                public_url VARCHAR(255),
                sort_order INT NOT NULL DEFAULT 1,
                is_active TINYINT NOT NULL DEFAULT 1
            )
            """
        );
    }

    private void seedQueue() throws Exception {
        insertSubmission(1, "CERT001", "graded_card", "trading_card", "Pikachu", "Base", "Pokemon", "EN", "approved", "failed", "9.5");
        insertSubmission(2, "CERT002", "graded_card", "trading_card", "Eevee", "Jungle", "Pokemon", "JP", "published", "client_pushed", "10");
        insertSubmission(3, "ABC003", "merch_product", "trading_card", "Display Stand", "", "NXR", "EN", "approved", "not_started", null);
        insertSubmission(4, "CERT004", "graded_card", "sports_card", "Charizard", "Promo", "Pokemon", "EN", "approved", "failed", "10");

        Files.createDirectories(storageRoot.resolve("staged"));
        Files.createDirectories(storageRoot.resolve("published"));
        Files.writeString(storageRoot.resolve("staged/cert001-front.webp"), "front");
        Files.writeString(storageRoot.resolve("staged/cert004-front.webp"), "front");
        Files.writeString(storageRoot.resolve("staged/cert004-back.webp"), "back");
        Files.writeString(storageRoot.resolve("published/cert002-front.webp"), "front");
        Files.writeString(storageRoot.resolve("published/cert002-back.webp"), "back");

        insertMedia(1, "front", "staged", "cert001-front.webp");
        insertMedia(1, "back", "staged", "cert001-back-missing.webp");
        insertMedia(2, "front", "published", "cert002-front.webp");
        insertMedia(2, "back", "published", "cert002-back.webp");
        insertMedia(4, "front", "staged", "cert004-front.webp");
        insertMedia(4, "back", "staged", "cert004-back.webp");
    }

    private void insertSubmission(
        long id,
        String certId,
        String productType,
        String category,
        String cardName,
        String setName,
        String brand,
        String language,
        String status,
        String uploadStatus,
        String grade
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO grading_submission (
                id,cert_id,product_type_code,card_category_code,card_name,set_name,
                brand_name,language_code,status_code,approved_at,published_at
            ) VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CASE WHEN ?='published' THEN CURRENT_TIMESTAMP ELSE NULL END)
            """,
            id, certId, productType, category, cardName, setName, brand, language, status, status
        );
        jdbcTemplate.update(
            "INSERT INTO submission_upload_state (submission_id,status_code) VALUES (?,?)",
            id, uploadStatus
        );
        jdbcTemplate.update(
            "INSERT INTO grading_score (submission_id,final_grade_value,final_grade_label) VALUES (?,?,?)",
            id, grade == null ? null : new java.math.BigDecimal(grade), grade
        );
    }

    private void insertMedia(long submissionId, String side, String stage, String storageKey) {
        jdbcTemplate.update(
            """
            INSERT INTO submission_media (
                submission_id,media_side_code,media_stage_code,storage_provider_code,
                storage_bucket,storage_key,public_url
            ) VALUES (?,?,?,'local','local-media',?,?)
            """,
            submissionId, side, stage, storageKey, "/media/" + stage + "/" + storageKey
        );
    }
}
