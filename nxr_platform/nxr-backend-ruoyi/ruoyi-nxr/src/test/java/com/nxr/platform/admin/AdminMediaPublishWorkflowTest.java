package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.admin.storage.MediaStorageProvider;
import com.nxr.platform.admin.storage.MediaStorageRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
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
    private JdbcClient jdbcClient;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nxr_media_publish;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        createSchema();
        seedReadySubmission();

        jdbcClient = JdbcClient.create(jdbcTemplate);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        ProxyFactory persistenceProxy = new ProxyFactory(new AdminMediaPersistenceService(
            jdbcClient, new ObjectMapper(), "https://nxrgrading.com/"));
        persistenceProxy.setProxyTargetClass(true);
        persistenceProxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        persistenceService = (AdminMediaPersistenceService) persistenceProxy.getProxy();
        storageProvider = new FakeR2StorageProvider();
        storageProvider.objects.add("staged-front");
        storageProvider.objects.add("staged-back");
        mediaService = new AdminMediaService(
            jdbcClient,
            jdbcTemplate,
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
    void expiredClaimCanBeExplicitlyRetriedAndLatePublisherCannotCommitOrMarkFailure() {
        var now=java.time.LocalDateTime.of(2026,10,19,12,0);
        useLease(java.time.Clock.fixed(now.toInstant(java.time.ZoneOffset.UTC),java.time.ZoneOffset.UTC));
        var old=persistenceService.claimSubmissionForPublish(1L,"interrupted-owner",7L);
        String version=currentVersion();
        jdbcTemplate.update("UPDATE submission_upload_state SET started_at=? WHERE submission_id=1",now.minusMinutes(15));
        String proof=value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1");
        assertThat(proof).isNull();
        var published=mediaService.publishSubmission(1L,8L,version);
        assertThat(published.stagedVersion()).isEqualTo(version);
        String currentProof=value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1");
        String publicSnapshot=value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1");
        assertConflict(()->persistenceService.publishSubmissionRecords(1L,"CERT001","interrupted-owner",old.stagedFront(),old.stagedBack(),
            storageProvider.object("late-front","front"),storageProvider.object("late-back","back")));
        persistenceService.markPublishFailed(1L,"interrupted-owner","late failure");
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("uploaded");
        assertThat(value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1")).isEqualTo(currentProof);
        assertThat(value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1")).isEqualTo(publicSnapshot);
        assertThat(storageProvider.copyCount).isEqualTo(2);
        assertThat(mediaService.publishSubmission(1L,8L,version)).isEqualTo(published);
        assertThat(storageProvider.copyCount).isEqualTo(2);
    }

    @Test
    void freshOrUnknownLeaseCannotBeStolenAndDoesNotChangeAnyBusinessRow() {
        var now=java.time.LocalDateTime.of(2026,10,19,12,0);
        useLease(java.time.Clock.fixed(now.toInstant(java.time.ZoneOffset.UTC),java.time.ZoneOffset.UTC));
        persistenceService.claimSubmissionForPublish(1L,"active-owner",7L);
        for(java.time.LocalDateTime started:java.util.Arrays.asList(now.minusMinutes(15).plusSeconds(1),null)) {
            jdbcTemplate.update("UPDATE submission_upload_state SET started_at=? WHERE submission_id=1",started);
            var before=publishSnapshot();
            assertConflict(()->mediaService.publishSubmission(1L,8L,currentVersion()));
            assertThat(publishSnapshot()).isEqualTo(before);assertThat(storageProvider.copyCount).isZero();
        }
    }

    @Test
    void clientPushedRequiresUploadedAndRejectsRepeatedOrIncompleteStatesWithoutChanges() {
        for(String state:List.of("not_started","failed","uploading","client_pushed")) {
            jdbcTemplate.update("UPDATE submission_upload_state SET status_code=?,claim_token='keep-claim',error_message='keep-error',response_payload_json='keep-proof' WHERE submission_id=1",state);
            var before=publishSnapshot();
            assertConflict(()->persistenceService.markClientPushed(1L,9L));
            assertThat(publishSnapshot()).isEqualTo(before);
        }
        jdbcTemplate.update("UPDATE submission_upload_state SET status_code='not_started',claim_token=NULL,started_at=NULL,error_message=NULL,response_payload_json=NULL WHERE submission_id=1");
        mediaService.publishSubmission(1L,7L,currentVersion());
        String proof=value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1");
        var publicBefore=jdbcTemplate.queryForList("SELECT * FROM published_certificate");
        var mediaBefore=jdbcTemplate.queryForList("SELECT * FROM submission_media ORDER BY id");
        var marked=persistenceService.markClientPushed(1L,9L);
        assertThat(marked.statusCode()).isEqualTo("client_pushed");
        assertThat(value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1")).isEqualTo(proof);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM published_certificate")).isEqualTo(publicBefore);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM submission_media ORDER BY id")).isEqualTo(mediaBefore);
        var beforeRepeat=publishSnapshot();assertConflict(()->persistenceService.markClientPushed(1L,9L));
        assertThat(publishSnapshot()).isEqualTo(beforeRepeat);
    }

    @Test
    void expiredSingleSidedImportCanBeRepairedBeforeRetryWithoutTouchingPublicProof() {
        var now=java.time.LocalDateTime.of(2026,10,19,12,0);
        useLease(java.time.Clock.fixed(now.toInstant(java.time.ZoneOffset.UTC),java.time.ZoneOffset.UTC));
        mediaService.publishSubmission(1L,7L,currentVersion());
        String publicBefore=value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1");
        String proofBefore=value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1");
        importPair("repair");
        var old=persistenceService.claimSubmissionForPublish(1L,"interrupted-repair-owner",7L);
        jdbcTemplate.update("UPDATE submission_upload_state SET started_at=? WHERE submission_id=1",now.minusMinutes(15));
        jdbcTemplate.update("DELETE FROM submission_media WHERE id=?",old.stagedBack().id());
        assertThat(countWhere("submission_media","media_stage_code='staged'")).isOne();
        storageProvider.objects.add("repaired-back");
        persistenceService.replaceMediaRecord(1L,"CERT001","back","staged",storageProvider.object("repaired-back","back"),null);
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("not_started");
        assertThat(value("SELECT claim_token FROM submission_upload_state WHERE submission_id=1")).isNull();
        assertThat(value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1")).isEqualTo(publicBefore);
        assertThat(value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1")).isEqualTo(proofBefore);
        var repaired=publishSnapshot();
        assertConflict(()->persistenceService.publishSubmissionRecords(1L,"CERT001","interrupted-repair-owner",old.stagedFront(),old.stagedBack(),
            storageProvider.object("late-front","front"),storageProvider.object("late-back","back")));
        persistenceService.markPublishFailed(1L,"interrupted-repair-owner","late failure");
        assertThat(publishSnapshot()).isEqualTo(repaired);
        String version=currentVersion();
        assertThat(mediaService.publishSubmission(1L,8L,version).stagedVersion()).isEqualTo(version);
        assertThat(countWhere("submission_media","media_stage_code='staged'")).isZero();
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("uploaded");
    }

    @Test
    void bothImportPathsKeepFreshOrUnknownClaimsAndBatchCanExplicitlyReplaceAnExpiredClaim() {
        var now=java.time.LocalDateTime.of(2026,10,19,12,0);
        useLease(java.time.Clock.fixed(now.toInstant(java.time.ZoneOffset.UTC),java.time.ZoneOffset.UTC));
        mediaService.publishSubmission(1L,7L,currentVersion());
        importPair("replacement");
        persistenceService.claimSubmissionForPublish(1L,"active-import-owner",7L);
        var replacement=replacement("replacement-new-back","back");
        for(java.time.LocalDateTime started:java.util.Arrays.asList(now.minusMinutes(15).plusSeconds(1),null)) {
            jdbcTemplate.update("UPDATE submission_upload_state SET started_at=? WHERE submission_id=1",started);
            var before=publishSnapshot();
            assertConflict(()->persistenceService.replaceImportedMedia(List.of(replacement),true));
            assertConflict(()->persistenceService.replaceMediaRecord(1L,"CERT001","back","staged",replacement.storedMedia(),null));
            assertThat(publishSnapshot()).isEqualTo(before);
        }
        jdbcTemplate.update("UPDATE submission_upload_state SET started_at=? WHERE submission_id=1",now.minusMinutes(15));
        String proof=value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1");
        String publicBefore=value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1");
        persistenceService.replaceImportedMedia(List.of(replacement),true);
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("not_started");
        assertThat(value("SELECT claim_token FROM submission_upload_state WHERE submission_id=1")).isNull();
        assertThat(value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1")).isEqualTo(proof);
        assertThat(value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1")).isEqualTo(publicBefore);
        assertThat(currentVersion()).isNotNull();
    }

    private void useLease(java.time.Clock clock) {
        var lease=new MediaUploadLease(clock);
        var manager=new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        ProxyFactory proxy=new ProxyFactory(new AdminMediaPersistenceService(jdbcClient,new ObjectMapper(),"https://nxrgrading.com/",lease));
        proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        persistenceService=(AdminMediaPersistenceService)proxy.getProxy();
        mediaService=new AdminMediaService(jdbcClient,jdbcTemplate,persistenceService,new MediaStorageRegistry(List.of(storageProvider),"r2"),12,25165824,25165824,100000000,lease);
    }
    private Map<String,List<Map<String,Object>>> publishSnapshot() {
        Map<String,List<Map<String,Object>>> rows=new java.util.LinkedHashMap<>();
        for(String table:List.of("grading_submission","submission_media","submission_upload_state","published_certificate"))
            rows.put(table,jdbcTemplate.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        return rows;
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
            jdbcTemplate,
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

    @Test
    void locallyStagedImagesArePublishedToR2WithoutLeavingStagedRows() {
        jdbcTemplate.update("""
            UPDATE submission_media SET storage_provider_code='local',storage_bucket='local-media',
                public_url='/media/staged/CERT001.webp'
            WHERE submission_id=1 AND media_stage_code='staged'
            """);
        FakeLocalStorageProvider localProvider = new FakeLocalStorageProvider();
        JdbcClient client = JdbcClient.create(jdbcTemplate);
        mediaService = new AdminMediaService(
            client, jdbcTemplate, persistenceService,
            new MediaStorageRegistry(java.util.List.of(localProvider, storageProvider), "local", "local", "r2"),
            12, 24L * 1024 * 1024, 24L * 1024 * 1024, 100_000_000L
        );

        AdminMediaService.MediaPublishResponse response = mediaService.publishSubmission(1L, 7L);

        assertThat(response.statusCode()).isEqualTo("published");
        assertThat(storageProvider.storeCount).isEqualTo(2);
        assertThat(storageProvider.copyCount).isZero();
        assertThat(localProvider.resolveCount).isEqualTo(2);
        assertThat(localProvider.deleteCount).isEqualTo(2);
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isZero();
        assertThat(countWhere("submission_media", "media_stage_code='published' AND storage_provider_code='r2'")).isEqualTo(2);
    }

    @Test
    void legacyUploadedStateWithFreshStagingPublishesTheNewPair() {
        AdminMediaService.MediaPublishResponse old = mediaService.publishSubmission(1L, 7L);
        importPair("fresh");
        jdbcTemplate.update("UPDATE submission_upload_state SET status_code='uploaded' WHERE submission_id=1");
        storageProvider.uniqueKeys = true;

        AdminMediaService.MediaPublishResponse fresh = mediaService.publishSubmission(1L, 7L);

        assertThat(fresh.stagedVersion()).isNotEqualTo(old.stagedVersion());
        assertThat(fresh.publishedFrontUrl()).isNotEqualTo(old.publishedFrontUrl());
        assertThat(storageProvider.copyCount).isEqualTo(4);
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isZero();
        assertThat(storageProvider.objects).doesNotContain("fresh-front", "fresh-back");
    }

    @Test
    void expectedCompletedVersionReturnsOldResultAndNeverTouchesFreshStagingOrActiveClaim() {
        AdminMediaService.MediaPublishResponse old = mediaService.publishSubmission(1L, 7L);
        importPair("fresh");
        String freshVersion = currentVersion();
        String retainedProof = value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1");
        persistenceService.claimSubmissionForPublish(1L, "new-publisher", 8L);

        AdminMediaService.MediaPublishResponse retried = mediaService.publishSubmission(1L, 7L, old.stagedVersion());

        assertThat(retried).isEqualTo(old);
        assertThat(currentVersion()).isEqualTo(freshVersion);
        assertThat(storageProvider.copyCount).isEqualTo(2);
        assertThat(storageProvider.objects).contains("fresh-front", "fresh-back");
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("uploading");
        assertThat(value("SELECT claim_token FROM submission_upload_state WHERE submission_id=1")).isEqualTo("new-publisher");
        assertThat(value("SELECT response_payload_json FROM submission_upload_state WHERE submission_id=1")).isEqualTo(retainedProof);
    }

    @Test
    void reusedRowIdWithDifferentObjectOrChecksumInvalidatesTheSelectedVersion() {
        String selectedVersion = currentVersion();
        long frontId = persistenceService.findExistingMedia(1L, "staged", "front").orElseThrow().id();
        importPair("fresh");
        assertThat(persistenceService.findExistingMedia(1L, "staged", "front").orElseThrow().id()).isEqualTo(frontId);
        assertConflict(() -> mediaService.publishSubmission(1L, 7L, selectedVersion));
        assertThat(storageProvider.copyCount).isZero();
        assertThat(count("published_certificate")).isZero();
        String beforeChecksumChange = currentVersion();
        jdbcTemplate.update("UPDATE submission_media SET checksum_sha256='different-checksum' WHERE id=?", frontId);
        assertThat(currentVersion()).isNotEqualTo(beforeChecksumChange);
        assertConflict(() -> mediaService.publishSubmission(1L, 7L, beforeChecksumChange));
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isEqualTo(2);
    }

    @Test
    void commitChecksFullClaimedObjectIdentityRatherThanReusedRowIds() {
        AdminMediaPersistenceService.MediaPublishClaim claim = persistenceService.claimSubmissionForPublish(1L, "claimed", 7L);
        jdbcTemplate.update("UPDATE submission_media SET storage_object_version='new-version', checksum_sha256='new-checksum' WHERE id=?",
            claim.stagedFront().id());
        assertConflict(() -> persistenceService.publishSubmissionRecords(1L, "CERT001", "claimed", claim.stagedFront(), claim.stagedBack(),
            storageProvider.object("new-public-front", "front"), storageProvider.object("new-public-back", "back")));
        assertThat(count("published_certificate")).isZero();
        assertThat(countWhere("submission_media", "media_stage_code='published'")).isZero();
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isEqualTo(2);
    }

    @Test
    void legacyCompletedRowIdsCannotAuthorizeDeletingAOneSidedNewImport() {
        AdminMediaService.MediaPublishResponse old = mediaService.publishSubmission(1L, 7L);
        persistenceService.replaceImportedMedia(List.of(replacement("new-front", "front")), true);
        jdbcTemplate.update("UPDATE submission_upload_state SET status_code='uploaded',response_payload_json=NULL WHERE submission_id=1");
        String originalPublic = value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1");

        assertThatThrownBy(() -> mediaService.publishSubmission(1L, 7L)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Back staged media is required");
        assertThat(storageProvider.copyCount).isEqualTo(2);
        assertThat(storageProvider.objects).contains("new-front");
        assertThat(value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1")).isEqualTo(originalPublic);
        assertThat(old.publishedFrontUrl()).contains("published-CERT001-front");
        assertConflict(() -> mediaService.publishSubmission(1L, 7L, old.stagedVersion()));
    }

    @Test
    void copyFailurePreservesThePreviousPublicationAndEveryNewStagedObject() {
        mediaService.publishSubmission(1L, 7L);
        importPair("fresh");
        storageProvider.uniqueKeys = true;
        storageProvider.failNextBackCopy = true;
        String originalPublic = value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1");
        String selectedVersion = currentVersion();

        assertThatThrownBy(() -> mediaService.publishSubmission(1L, 7L, selectedVersion))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Simulated R2 failure");

        assertThat(storageProvider.objects).contains("published-CERT001-front", "published-CERT001-back", "fresh-front", "fresh-back")
            .doesNotContain("published-CERT001-front-3");
        assertThat(value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1")).isEqualTo(originalPublic);
        assertThat(currentVersion()).isEqualTo(selectedVersion);
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("failed");
    }

    @Test
    void storageCleanupFailureCannotTurnCommittedSuccessIntoAFailureOrCauseDuplicateCopies() {
        String selectedVersion = currentVersion();
        storageProvider.failDeletes = true;
        AdminMediaService.MediaPublishResponse published = mediaService.publishSubmission(1L, 7L, selectedVersion);
        AdminMediaService.MediaPublishResponse retried = mediaService.publishSubmission(1L, 7L, selectedVersion);

        assertThat(retried).isEqualTo(published);
        assertThat(storageProvider.copyCount).isEqualTo(2);
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isZero();
        assertThat(storageProvider.objects).contains("staged-front", "staged-back", "published-CERT001-front", "published-CERT001-back");
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("uploaded");
    }

    @Test
    void droppedCommitReplyIsResolvedBeforeCompensationAndDoesNotDeleteTheCommittedObjects() {
        String selectedVersion = currentVersion();
        AdminMediaPersistenceService delegate = persistenceService;
        usePersistence(new AdminMediaPersistenceService(jdbcClient, new ObjectMapper(), "https://nxrgrading.com/") {
            @Override public MediaPublishStart startSubmissionForPublish(long id, String token, Long userId, String version) {
                return delegate.startSubmissionForPublish(id, token, userId, version);
            }
            @Override public MediaPublishTransactionResult publishSubmissionRecords(long id, String certId, String token,
                ExistingMedia front, ExistingMedia back, MediaStorageProvider.StoredMediaObject publishedFront,
                MediaStorageProvider.StoredMediaObject publishedBack) {
                delegate.publishSubmissionRecords(id, certId, token, front, back, publishedFront, publishedBack);
                importPair("concurrent");
                throw new IllegalStateException("COMMIT response lost");
            }
            @Override public java.util.Optional<CompletedPublication> resolvePublicationAttempt(long id, String version) {
                return delegate.resolvePublicationAttempt(id, version);
            }
        });

        AdminMediaService.MediaPublishResponse published = mediaService.publishSubmission(1L, 7L, selectedVersion);
        AdminMediaService.MediaPublishResponse retried = mediaService.publishSubmission(1L, 7L, selectedVersion);

        assertThat(retried).isEqualTo(published);
        assertThat(storageProvider.copyCount).isEqualTo(2);
        assertThat(storageProvider.objects).contains("published-CERT001-front", "published-CERT001-back", "concurrent-front", "concurrent-back");
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isEqualTo(2);
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("not_started");
    }

    @Test
    void uncertainReadbackRetainsAllObjectsAndDoesNotMarkACommittedPublicationFailed() {
        AdminMediaPersistenceService delegate = persistenceService;
        usePersistence(new AdminMediaPersistenceService(jdbcClient, new ObjectMapper(), "https://nxrgrading.com/") {
            @Override public MediaPublishStart startSubmissionForPublish(long id, String token, Long userId, String version) {
                return delegate.startSubmissionForPublish(id, token, userId, version);
            }
            @Override public MediaPublishTransactionResult publishSubmissionRecords(long id, String certId, String token,
                ExistingMedia front, ExistingMedia back, MediaStorageProvider.StoredMediaObject publishedFront,
                MediaStorageProvider.StoredMediaObject publishedBack) {
                delegate.publishSubmissionRecords(id, certId, token, front, back, publishedFront, publishedBack);
                throw new IllegalStateException("COMMIT response lost");
            }
            @Override public java.util.Optional<CompletedPublication> resolvePublicationAttempt(long id, String version) {
                throw new IllegalStateException("Database unavailable");
            }
        });
        assertThatThrownBy(() -> mediaService.publishSubmission(1L, 7L, currentVersion())).hasMessage("COMMIT response lost");
        assertThat(storageProvider.objects).contains("published-CERT001-front", "published-CERT001-back", "staged-front", "staged-back");
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("uploaded");
        assertThat(count("published_certificate")).isOne();
    }

    @Test
    void concurrentImportCommitsBeforeVersionDecisionAndStalePublisherHasZeroEffect() throws Exception {
        jdbcTemplate.update("INSERT INTO submission_upload_state(submission_id,status_code) VALUES (1,'not_started')");
        String selectedVersion = currentVersion();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch imported = new CountDownLatch(1);
        CountDownLatch commitImport = new CountDownLatch(1);
        CountDownLatch publisherStarted = new CountDownLatch(1);
        try {
            var importing = executor.submit(() -> transactions.execute(status -> {
                importPair("concurrent");
                imported.countDown();
                await(commitImport);
                return null;
            }));
            assertThat(imported.await(5, TimeUnit.SECONDS)).isTrue();
            var publishing = executor.submit(() -> {
                publisherStarted.countDown();
                assertConflict(() -> mediaService.publishSubmission(1L, 7L, selectedVersion));
            });
            assertThat(publisherStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(publishing.isDone()).isFalse();
            commitImport.countDown();
            importing.get(5, TimeUnit.SECONDS);
            publishing.get(5, TimeUnit.SECONDS);
            assertThat(storageProvider.copyCount).isZero();
            assertThat(count("published_certificate")).isZero();
            assertThat(storageProvider.objects).contains("concurrent-front", "concurrent-back");
            assertThat(countWhere("submission_media", "media_stage_code='staged'")).isEqualTo(2);
        } finally {
            commitImport.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void versionedBatchRequiresEverySelectedVersionAndKeepsPerItemFailureIsolation() {
        AdminMediaService.MediaBatchPublishResponse missing = mediaService.publishSubmissions(List.of(1L), 7L, Map.of());
        assertThat(missing.failedCount()).isOne();
        assertThat(storageProvider.copyCount).isZero();
        AdminMediaService.MediaBatchPublishResponse good = mediaService.publishSubmissions(List.of(1L), 7L, Map.of(1L, currentVersion()));
        assertThat(good.publishedCount()).isOne();
        assertThat(good.failedCount()).isZero();
        assertThat(good.published()).singleElement().extracting(AdminMediaService.MediaPublishResponse::stagedVersion).isNotNull();
    }

    @Test
    void readbackWaitsForALateCommitBeforeDecidingWhetherObjectsAreReferenced() throws Exception {
        var claim = persistenceService.claimSubmissionForPublish(1L, "late-commit", 7L);
        String version = currentVersion();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch persisted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch readbackStarted = new CountDownLatch(1);
        try {
            var publishing = executor.submit(() -> transactions.execute(status -> {
                persistenceService.publishSubmissionRecords(1L, "CERT001", "late-commit", claim.stagedFront(), claim.stagedBack(),
                    storageProvider.object("committed-front", "front"), storageProvider.object("committed-back", "back"));
                persisted.countDown();
                await(commit);
                return null;
            }));
            assertThat(persisted.await(5, TimeUnit.SECONDS)).isTrue();
            var readback = executor.submit(() -> {
                readbackStarted.countDown();
                return persistenceService.resolvePublicationAttempt(1L, version);
            });
            assertThat(readbackStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(readback.isDone()).isFalse();
            commit.countDown();
            publishing.get(5, TimeUnit.SECONDS);
            assertThat(readback.get(5, TimeUnit.SECONDS)).isPresent()
                .get().extracting(AdminMediaPersistenceService.CompletedPublication::stagedVersion).isEqualTo(version);
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void fullTransactionRollbackRestoresOldPublicRowsAndKeepsStagingAfterAnUpsertFailure() {
        mediaService.publishSubmission(1L, 7L);
        importPair("fresh");
        storageProvider.uniqueKeys = true;
        String originalPublic = value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1");
        String selectedVersion = currentVersion();
        ObjectMapper brokenMapper = new ObjectMapper() {
            @Override public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                throw new com.fasterxml.jackson.core.JsonProcessingException("Snapshot serialization failed") { };
            }
        };
        var transactionManager = new DataSourceTransactionManager(jdbcTemplate.getDataSource());
        ProxyFactory proxy = new ProxyFactory(new AdminMediaPersistenceService(jdbcClient, brokenMapper, "https://nxrgrading.com/"));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        usePersistence((AdminMediaPersistenceService) proxy.getProxy());

        assertThatThrownBy(() -> mediaService.publishSubmission(1L, 7L, selectedVersion))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("serialize publish snapshot");

        assertThat(value("SELECT published_media_snapshot_json FROM published_certificate WHERE submission_id=1")).isEqualTo(originalPublic);
        assertThat(value("SELECT storage_key FROM submission_media WHERE media_stage_code='published' AND media_side_code='front'"))
            .isEqualTo("published-CERT001-front");
        assertThat(currentVersion()).isEqualTo(selectedVersion);
        assertThat(storageProvider.objects).contains("published-CERT001-front", "published-CERT001-back", "fresh-front", "fresh-back")
            .doesNotContain("published-CERT001-front-3", "published-CERT001-back-4");
    }

    @Test
    void postCommitCleanupRetainsAnObjectReferencedByAConcurrentFreshImport() {
        String selectedVersion = currentVersion();
        AdminMediaPersistenceService delegate = persistenceService;
        usePersistence(new AdminMediaPersistenceService(jdbcClient, new ObjectMapper(), "https://nxrgrading.com/") {
            @Override public MediaPublishStart startSubmissionForPublish(long id, String token, Long userId, String version) {
                return delegate.startSubmissionForPublish(id, token, userId, version);
            }
            @Override public MediaPublishTransactionResult publishSubmissionRecords(long id, String certId, String token,
                ExistingMedia front, ExistingMedia back, MediaStorageProvider.StoredMediaObject publishedFront,
                MediaStorageProvider.StoredMediaObject publishedBack) {
                var result = delegate.publishSubmissionRecords(id, certId, token, front, back, publishedFront, publishedBack);
                delegate.replaceImportedMedia(List.of(replacement("staged-front", "front"), replacement("new-back", "back")), true);
                return result;
            }
        });
        AdminMediaService.MediaPublishResponse response = mediaService.publishSubmission(1L, 7L, selectedVersion);
        assertThat(response.stagedVersion()).isEqualTo(selectedVersion);
        assertThat(storageProvider.objects).contains("staged-front", "new-back", "published-CERT001-front", "published-CERT001-back");
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isEqualTo(2);
        assertThat(value("SELECT status_code FROM submission_upload_state WHERE submission_id=1")).isEqualTo("not_started");
    }

    @Test
    void missingOrDamagedCompletionProofCannotStartAStaleRetryOrDeleteANewerPair() {
        String completedVersion = mediaService.publishSubmission(1L, 7L).stagedVersion();
        importPair("fresh");
        for (String invalidProof : List.of("", "null", "{}", "{broken", "{\"stagedVersion\":\"" + completedVersion + "\"}")) {
            jdbcTemplate.update("UPDATE submission_upload_state SET response_payload_json=? WHERE submission_id=1", invalidProof);
            assertConflict(() -> mediaService.publishSubmission(1L, 7L, completedVersion));
        }
        assertThat(storageProvider.copyCount).isEqualTo(2);
        assertThat(storageProvider.objects).contains("fresh-front", "fresh-back");
        assertThat(countWhere("submission_media", "media_stage_code='staged'")).isEqualTo(2);
    }

    private void importPair(String prefix) {
        persistenceService.replaceImportedMedia(List.of(replacement(prefix + "-front", "front"), replacement(prefix + "-back", "back")), true);
    }

    private AdminMediaPersistenceService.ImportedMediaReplacement replacement(String key, String side) {
        storageProvider.objects.add(key);
        return new AdminMediaPersistenceService.ImportedMediaReplacement(1L, "CERT001", side, storageProvider.object(key, side));
    }

    private String currentVersion() {
        return MediaStageVersion.of(persistenceService.findExistingMedia(1L, "staged", "front").orElse(null),
            persistenceService.findExistingMedia(1L, "staged", "back").orElse(null));
    }

    private void usePersistence(AdminMediaPersistenceService persistence) {
        mediaService = new AdminMediaService(jdbcClient, jdbcTemplate, persistence,
            new MediaStorageRegistry(List.of(storageProvider), "r2"), 12, 24L * 1024 * 1024, 24L * 1024 * 1024, 100_000_000L);
    }

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ResponseStatusException.class)
            .satisfies(exc -> assertThat(((ResponseStatusException) exc).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test transaction");
            }
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exc);
        }
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
        private boolean uniqueKeys;
        private boolean failDeletes;

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
            StoredMediaObject stored = object(targetStage + "-" + certId + "-" + sideCode + (uniqueKeys ? "-" + copyCount : ""), sideCode);
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
            if (failDeletes) { throw new IllegalStateException("Simulated cleanup failure"); }
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

    private static final class FakeLocalStorageProvider implements MediaStorageProvider {
        private int resolveCount;
        private int deleteCount;

        @Override public String providerCode() { return "local"; }
        @Override public boolean manages(String code) { return "local".equals(code); }
        @Override public StoredMediaObject store(String stage, String certId, String sideCode,
            String extension, MediaUpload upload) { throw new UnsupportedOperationException(); }
        @Override public StoredMediaObject copy(String stage, String certId, String sideCode,
            StoredMediaSource source) { throw new UnsupportedOperationException(); }
        @Override public ResolvedMediaAsset resolve(StoredMediaLocation location) {
            resolveCount++;
            return new ResolvedMediaAsset(
                new ByteArrayResource(new byte[] {'R','I','F','F',1,2,3,4,5,6}),
                "image/webp", location.storageKey()
            );
        }
        @Override public void deleteIfPresent(StoredMediaLocation location) { deleteCount++; }
    }
}
