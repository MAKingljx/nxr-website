package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.admin.storage.LocalMediaStorageProvider;
import com.nxr.platform.admin.storage.MediaStorageProvider;
import com.nxr.platform.admin.storage.MediaStorageRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

class AdminMediaImportTransactionTest {
    @TempDir Path root;
    private JdbcTemplate jdbc;
    private ImportStorage storage;
    private AdminMediaService service;
    private final AtomicBoolean loseCommitResponse = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:import_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        DataSource dataSource = new DelegatingDataSource(h2) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                boolean[] writes = {false};
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        try {
                            if ("prepareStatement".equals(method.getName()) && args[0] instanceof String sql
                                && sql.stripLeading().toUpperCase(java.util.Locale.ROOT).matches("(?s)^(INSERT|UPDATE|DELETE).*")) {
                                writes[0] = true;
                            }
                            Object result = method.invoke(connection, args);
                            if ("commit".equals(method.getName()) && writes[0] && loseCommitResponse.compareAndSet(true, false)) {
                                throw new SQLException("Simulated lost commit response");
                            }
                            return result;
                        } catch (InvocationTargetException exc) {
                            throw exc.getCause();
                        }
                    });
            }
        };
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE grading_submission (
                id BIGINT PRIMARY KEY, cert_id VARCHAR(32) UNIQUE NOT NULL,
                status_code VARCHAR(32) NOT NULL)
            """);
        jdbc.execute("""
            CREATE TABLE submission_media (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, submission_id BIGINT NOT NULL,
                cert_id VARCHAR(32) NOT NULL, media_side_code VARCHAR(16) NOT NULL,
                media_stage_code VARCHAR(16) NOT NULL, storage_provider_code VARCHAR(32) NOT NULL,
                storage_bucket VARCHAR(128), storage_key VARCHAR(255) NOT NULL,
                storage_object_version VARCHAR(128), public_url VARCHAR(255), width_px INT, height_px INT,
                sort_order INT DEFAULT 1, is_active TINYINT DEFAULT 1, original_filename VARCHAR(255),
                mime_type VARCHAR(128), file_size_bytes BIGINT, checksum_sha256 CHAR(64), source_media_id BIGINT,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(submission_id, media_stage_code, media_side_code, sort_order),
                CHECK(original_filename <> 'reject-this-file'))
            """);
        jdbc.execute("""
            CREATE TABLE submission_upload_state (
                submission_id BIGINT PRIMARY KEY, status_code VARCHAR(32) DEFAULT 'not_started',
                claim_token CHAR(36), claimed_front_media_id BIGINT, claimed_back_media_id BIGINT,
                started_at TIMESTAMP, completed_at TIMESTAMP, error_message TEXT,
                response_payload_json TEXT, triggered_by_user_id BIGINT,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        Files.createDirectories(root.resolve("staged"));
        seed(1, "7000000001", "failed", "front", "old-front.png");
        seedMedia(1, "7000000001", "back", "old-back.png");
        JdbcClient client = JdbcClient.create(dataSource);
        AdminMediaPersistenceService target = new AdminMediaPersistenceService(client, new ObjectMapper(), "http://localhost");
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        // Exercise real Spring transaction boundaries, not direct calls to an unproxied service.
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        AdminMediaPersistenceService persistence = (AdminMediaPersistenceService) factory.getProxy();
        storage = new ImportStorage(root);
        service = new AdminMediaService(client, persistence, new MediaStorageRegistry(List.of(storage), "local"),
            12, 24 * 1024 * 1024, 24 * 1024 * 1024, 100_000_000);
    }

    @Test
    void storageFailureLeavesTheWholeBatchAndOriginalFilesUntouched() throws Exception {
        storage.failAt = 2;
        assertThatThrownBy(() -> service.importFolder(images())).isInstanceOf(ResponseStatusException.class);
        assertOriginalBatch();
        assertThat(stagedFiles()).containsExactlyInAnyOrder("old-front.png", "old-back.png");
    }

    @Test
    void laterDatabaseFailureRollsBackEarlierImageAndUploadState() throws Exception {
        storage.rejectAt = 2;
        assertThatThrownBy(() -> service.importFolder(images())).isInstanceOf(RuntimeException.class);
        assertOriginalBatch();
        assertThat(stagedFiles()).containsExactlyInAnyOrder("old-front.png", "old-back.png");
    }

    @Test
    void publisherConflictOnAnotherCardRollsBackTheEntireBatch() throws Exception {
        seed(2, "7000000002", "uploading", "front", "other-front.png");
        assertThatThrownBy(() -> service.importFolder(List.of(image("7000000001_A.png"), image("7000000002_A.png"))))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("currently being published");
        assertOriginalBatch();
        assertThat(stagedFiles()).containsExactlyInAnyOrder("old-front.png", "old-back.png", "other-front.png");
    }

    @Test
    void successfulBatchAndReplayKeepExactlyOneCompletePair() throws Exception {
        AdminMediaService.MediaImportResponse result = service.importFolder(images());
        assertThat(result.savedFiles()).isEqualTo(2);
        assertThat(result.updatedSubmissionIds()).containsExactly(1L);
        assertThat(stagedFiles()).hasSize(2).doesNotContain("old-front.png", "old-back.png");
        assertReferencesExist();
        result = service.importFolder(images());
        assertThat(result.savedFiles()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submission_media", Integer.class)).isEqualTo(2);
        assertThat(stagedFiles()).hasSize(2);
        assertReferencesExist();
    }

    @Test
    void lostCommitResponseNeverDeletesImagesAlreadyReferencedByTheDatabase() throws Exception {
        loseCommitResponse.set(true);
        assertThatThrownBy(() -> service.importFolder(images())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status_code FROM submission_upload_state WHERE submission_id=1", String.class))
            .isEqualTo("not_started");
        assertReferencesExist();
        // Retaining old unreferenced objects is safer than deleting the newly committed pair.
        assertThat(stagedFiles()).hasSize(4);
        assertThat(service.importFolder(images()).savedFiles()).isEqualTo(2);
        assertReferencesExist();
    }

    private void assertOriginalBatch() {
        assertThat(jdbc.queryForList("SELECT storage_key FROM submission_media WHERE submission_id=1", String.class))
            .containsExactlyInAnyOrder("old-front.png", "old-back.png");
        assertThat(jdbc.queryForObject("SELECT status_code FROM submission_upload_state WHERE submission_id=1", String.class))
            .isEqualTo("failed");
    }

    private void assertReferencesExist() {
        for (String key : jdbc.queryForList("SELECT storage_key FROM submission_media", String.class)) {
            assertThat(root.resolve("staged").resolve(key)).isRegularFile();
        }
    }

    private List<String> stagedFiles() throws Exception {
        try (var files = Files.list(root.resolve("staged"))) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    private void seed(long id, String certId, String status, String side, String key) throws Exception {
        jdbc.update("INSERT INTO grading_submission VALUES (?, ?, 'approved')", id, certId);
        jdbc.update("INSERT INTO submission_upload_state(submission_id,status_code) VALUES (?,?)", id, status);
        seedMedia(id, certId, side, key);
    }

    private void seedMedia(long id, String certId, String side, String key) throws Exception {
        Files.write(root.resolve("staged").resolve(key), new byte[] {1, 2, 3});
        jdbc.update("""
            INSERT INTO submission_media(submission_id,cert_id,media_side_code,media_stage_code,
                storage_provider_code,storage_bucket,storage_key,public_url,original_filename)
            VALUES(?,?,?,'staged','local','local-media',?,?,?)
            """, id, certId, side, key, "http://localhost/media/staged/" + key, key);
    }

    private List<MultipartFile> images() throws Exception {
        return List.of(image("7000000001_A.png"), image("7000000001_B.png"));
    }

    private MultipartFile image(String filename) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("image_files", filename, "image/png", bytes.toByteArray());
    }

    private static final class ImportStorage implements MediaStorageProvider {
        private final LocalMediaStorageProvider delegate;
        private int stores;
        private int failAt = -1;
        private int rejectAt = -1;

        private ImportStorage(Path root) {
            delegate = new LocalMediaStorageProvider(root.toString(), "http://localhost", "local-media");
        }

        public String providerCode() { return "local"; }
        public boolean manages(String provider) { return delegate.manages(provider); }
        public StoredMediaObject store(String stage, String certId, String side, String extension, MediaUpload upload) {
            stores++;
            if (stores == failAt) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated storage failure");
            StoredMediaObject stored = delegate.store(stage, certId, side, extension, upload);
            if (stores != rejectAt) return stored;
            return new StoredMediaObject(stored.storageProviderCode(), stored.storageBucket(), stored.storageKey(),
                stored.publicUrl(), "reject-this-file", stored.mimeType(), stored.fileSizeBytes(), stored.checksumSha256(),
                stored.storageObjectVersion(), stored.widthPx(), stored.heightPx());
        }
        public StoredMediaObject copy(String stage, String certId, String side, StoredMediaSource source) {
            return delegate.copy(stage, certId, side, source);
        }
        public ResolvedMediaAsset resolve(StoredMediaLocation location) { return delegate.resolve(location); }
        public void deleteIfPresent(StoredMediaLocation location) { delegate.deleteIfPresent(location); }
    }
}
