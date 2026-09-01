package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.admin.storage.MediaStorageProvider;
import com.nxr.platform.admin.storage.MediaStorageRegistry;
import java.util.LinkedHashSet;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class AdminMediaPublishWorkflowTest {

    private JdbcTemplate jdbcTemplate;
    private AdminMediaPersistenceService persistenceService;
    private AdminMediaService mediaService;
    private FakeR2StorageProvider storageProvider;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_media_publish;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        createSchema();
        seedReadySubmission();

        JdbcClient jdbcClient = JdbcClient.create(jdbcTemplate);
        persistenceService = new AdminMediaPersistenceService(
            jdbcClient,
            new ObjectMapper(),
            "https://nxrgrading.com/"
        );
        storageProvider = new FakeR2StorageProvider();
        storageProvider.objects.add("staged-front");
        storageProvider.objects.add("staged-back");
        mediaService = new AdminMediaService(
            jdbcClient,
            persistenceService,
            new MediaStorageRegistry(java.util.List.of(storageProvider), "r2"),
            12,
            24L * 1024 * 1024,
            24L * 1024 * 1024,
            100_000_000L
        );
    }

    @Test
    void successfulPublishIsIdempotentAndCleansStagedMedia() {
        AdminMediaService.MediaPublishResponse first = mediaService.publishSubmission(1L, 7L);

        assertThat(first.statusCode()).isEqualTo("published");
        assertThat(value("SELECT status_code FROM grading_submission WHERE id=1")).isEqualTo("published");
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("uploaded");
        assertThat(count("published_certificate")).isOne();
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isZero();
        assertThat(countWhere("submission_media", "media_stage_code='published'")).isEqualTo(2);
        assertThat(storageProvider.objects).contains("published-CERT001-front", "published-CERT001-back");
        assertThat(storageProvider.objects).doesNotContain("staged-front", "staged-back");
        assertThat(storageProvider.copyCount).isEqualTo(2);

        AdminMediaService.MediaPublishResponse retried = mediaService.publishSubmission(1L, 7L);

        assertThat(retried.publishedFrontUrl()).isEqualTo(first.publishedFrontUrl());
        assertThat(retried.publishedBackUrl()).isEqualTo(first.publishedBackUrl());
        assertThat(storageProvider.copyCount).isEqualTo(2);
        assertThat(count("published_certificate")).isOne();
    }

    @Test
    void failedR2CopyKeepsStagedMediaAndCanRetrySafely() {
        storageProvider.failNextBackCopy = true;

        assertThatThrownBy(() -> mediaService.publishSubmission(1L, 8L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Simulated R2 failure");

        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("failed");
        assertThat(value("SELECT error_message FROM submission_upload_state WHERE submission_id=1"))
            .isEqualTo("Simulated R2 failure");
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isEqualTo(2);
        assertThat(count("published_certificate")).isZero();
        assertThat(storageProvider.objects).contains("staged-front", "staged-back");
        assertThat(storageProvider.objects).doesNotContain("published-CERT001-front");

        AdminMediaService.MediaPublishResponse retried = mediaService.publishSubmission(1L, 8L);

        assertThat(retried.statusCode()).isEqualTo("published");
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("uploaded");
        assertThat(count("published_certificate")).isOne();
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isZero();
    }

    @Test
    void claimCasRejectsConcurrentOrStalePublishers() {
        AdminMediaPersistenceService.MediaPublishClaim claim =
            persistenceService.claimSubmissionForPublish(1L, "claim-a", 9L);

        assertThatThrownBy(() -> persistenceService.claimSubmissionForPublish(1L, "claim-b", 9L))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> persistenceService.publishSubmissionRecords(
            1L,
            "CERT001",
            "stale-claim",
            claim.stagedFront(),
            claim.stagedBack(),
            storageProvider.object("published-CERT001-front", "front"),
            storageProvider.object("published-CERT001-back", "back")
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));

        assertThat(count("published_certificate")).isZero();
        assertThat(countWhere("submission_media", "media_stage_code='published'")).isZero();
    }

    @Test
    void legacyPythonImagesAreStreamedIntoTheActiveJavaProvider() {
        jdbcTemplate.update(
            """
            UPDATE submission_media
            SET storage_provider_code='legacy-python',storage_bucket='python-admin-uploads',
                storage_key=CASE media_side_code
                    WHEN 'front' THEN 'front_CERT001_deadbeef.webp'
                    ELSE 'back_CERT001_deadbeef.webp' END,
                public_url=CASE media_side_code
                    WHEN 'front' THEN '/media/staged/front_CERT001_deadbeef.webp'
                    ELSE '/media/staged/back_CERT001_deadbeef.webp' END
            WHERE submission_id=1 AND media_stage_code='staged'
            """
        );
        FakeLegacyStorageProvider legacyProvider = new FakeLegacyStorageProvider();
        JdbcClient jdbcClient = JdbcClient.create(jdbcTemplate);
        mediaService = new AdminMediaService(
            jdbcClient,
            persistenceService,
            new MediaStorageRegistry(java.util.List.of(storageProvider, legacyProvider), "r2"),
            12,
            24L * 1024 * 1024,
            24L * 1024 * 1024,
            100_000_000L
        );

        AdminMediaService.MediaPublishResponse response = mediaService.publishSubmission(1L, 7L);

        assertThat(response.statusCode()).isEqualTo("published");
        assertThat(storageProvider.storeCount).isEqualTo(2);
        assertThat(storageProvider.copyCount).isZero();
        assertThat(legacyProvider.resolveCount).isEqualTo(2);
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isZero();
        assertThat(storageProvider.objects).contains("published-CERT001-front", "published-CERT001-back");
    }

    private void createSchema() {
        jdbcTemplate.execute(
            """
            CREATE TABLE grading_submission (
                id BIGINT PRIMARY KEY,
                cert_id VARCHAR(32) NOT NULL UNIQUE,
                status_code VARCHAR(32) NOT NULL,
                published_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE submission_media (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                submission_id BIGINT NOT NULL,
                cert_id VARCHAR(32) NOT NULL,
                media_side_code VARCHAR(16) NOT NULL,
                media_stage_code VARCHAR(16) NOT NULL,
                storage_provider_code VARCHAR(32) NOT NULL,
                storage_bucket VARCHAR(128),
                storage_key VARCHAR(255) NOT NULL,
                storage_object_version VARCHAR(128),
                public_url VARCHAR(255),
                width_px INT,
                height_px INT,
                sort_order INT NOT NULL DEFAULT 1,
                source_media_id BIGINT,
                is_active TINYINT NOT NULL DEFAULT 1,
                original_filename VARCHAR(255),
                mime_type VARCHAR(128),
                file_size_bytes BIGINT,
                checksum_sha256 CHAR(64),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE (submission_id, media_stage_code, media_side_code, sort_order)
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE submission_upload_state (
                submission_id BIGINT PRIMARY KEY,
                status_code VARCHAR(32) NOT NULL DEFAULT 'not_started',
                claim_token CHAR(36),
                claimed_front_media_id BIGINT,
                claimed_back_media_id BIGINT,
                started_at TIMESTAMP,
                completed_at TIMESTAMP,
                error_message TEXT,
                response_payload_json LONGTEXT,
                triggered_by_user_id BIGINT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
        jdbcTemplate.execute(
            """
            CREATE TABLE published_certificate (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                submission_id BIGINT NOT NULL UNIQUE,
                cert_id VARCHAR(32) NOT NULL UNIQUE,
                verification_slug VARCHAR(64) NOT NULL UNIQUE,
                qr_url VARCHAR(255),
                published_at TIMESTAMP NOT NULL,
                published_front_media_id BIGINT,
                published_back_media_id BIGINT,
                published_media_snapshot_json LONGTEXT,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """
        );
    }

    private void seedReadySubmission() {
        jdbcTemplate.update(
            "INSERT INTO grading_submission (id, cert_id, status_code) VALUES (1, 'CERT001', 'approved')"
        );
        jdbcTemplate.update(
            """
            INSERT INTO submission_media (
                submission_id, cert_id, media_side_code, media_stage_code,
                storage_provider_code, storage_bucket, storage_key, public_url,
                width_px, height_px, original_filename, mime_type,
                file_size_bytes, checksum_sha256
            ) VALUES
                (1, 'CERT001', 'front', 'staged', 'r2', 'cards', 'staged-front',
                 'https://r2.example/staged-front', 100, 200, 'CERT001_A.webp',
                 'image/webp', 10, 'front-checksum'),
                (1, 'CERT001', 'back', 'staged', 'r2', 'cards', 'staged-back',
                 'https://r2.example/staged-back', 100, 200, 'CERT001_B.webp',
                 'image/webp', 10, 'back-checksum')
            """
        );
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countWhere(String table, String where) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE " + where,
            Integer.class
        );
    }

    private String value(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private static final class FakeR2StorageProvider implements MediaStorageProvider {
        private final Set<String> objects = new LinkedHashSet<>();
        private int storeCount;
        private int copyCount;
        private boolean failNextBackCopy;

        @Override
        public String providerCode() {
            return "r2";
        }

        @Override
        public boolean manages(String storageProviderCode) {
            return storageProviderCode != null && storageProviderCode.startsWith("r2");
        }

        @Override
        public StoredMediaObject store(
            String stage,
            String certId,
            String sideCode,
            String extension,
            MediaUpload mediaUpload
        ) {
            try {
                assertThat(mediaUpload.inputStreamSource().openStream().readAllBytes()).isNotEmpty();
            } catch (java.io.IOException exc) {
                throw new IllegalStateException(exc);
            }
            storeCount += 1;
            StoredMediaObject stored = object(targetKey(stage, certId, sideCode), sideCode);
            objects.add(stored.storageKey());
            return stored;
        }

        @Override
        public StoredMediaObject copy(
            String targetStage,
            String certId,
            String sideCode,
            StoredMediaSource source
        ) {
            copyCount += 1;
            if (failNextBackCopy && "back".equals(sideCode)) {
                failNextBackCopy = false;
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated R2 failure");
            }
            StoredMediaObject stored = object(targetStage + "-" + certId + "-" + sideCode, sideCode);
            objects.add(stored.storageKey());
            return stored;
        }

        private StoredMediaObject object(String key, String sideCode) {
            return new StoredMediaObject(
                "r2",
                "cards",
                key,
                "https://r2.example/" + key,
                "CERT001_" + ("front".equals(sideCode) ? "A" : "B") + ".webp",
                "image/webp",
                10L,
                sideCode + "-checksum",
                "version-1",
                100,
                200
            );
        }

        private String targetKey(String stage, String certId, String sideCode) {
            return stage + "-" + certId + "-" + sideCode;
        }

        @Override
        public ResolvedMediaAsset resolve(StoredMediaLocation location) {
            return new ResolvedMediaAsset((Resource) null, "image/webp", location.storageKey());
        }

        @Override
        public void deleteIfPresent(StoredMediaLocation location) {
            objects.remove(location.storageKey());
        }
    }

    private static final class FakeLegacyStorageProvider implements MediaStorageProvider {
        private int resolveCount;

        @Override
        public String providerCode() {
            return "legacy-python";
        }

        @Override
        public boolean manages(String storageProviderCode) {
            return "legacy-python".equals(storageProviderCode);
        }

        @Override
        public StoredMediaObject store(
            String stage,
            String certId,
            String sideCode,
            String extension,
            MediaUpload mediaUpload
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredMediaObject copy(
            String targetStage,
            String certId,
            String sideCode,
            StoredMediaSource source
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResolvedMediaAsset resolve(StoredMediaLocation location) {
            resolveCount += 1;
            return new ResolvedMediaAsset(
                new ByteArrayResource(new byte[] {'R', 'I', 'F', 'F', 1, 2, 3}),
                "image/webp",
                location.storageKey()
            );
        }

        @Override
        public void deleteIfPresent(StoredMediaLocation location) {
            // Python owns this source.
        }
    }
}
