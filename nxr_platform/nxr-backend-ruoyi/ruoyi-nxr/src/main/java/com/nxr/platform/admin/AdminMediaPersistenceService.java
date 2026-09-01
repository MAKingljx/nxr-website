package com.nxr.platform.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nxr.platform.admin.storage.MediaStorageProvider;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminMediaPersistenceService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final String publicSiteBaseUrl;

    public AdminMediaPersistenceService(
        JdbcClient jdbcClient,
        ObjectMapper objectMapper,
        @Value("${nxr.public-site.base-url:http://127.0.0.1:3000}") String publicSiteBaseUrl
    ) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.publicSiteBaseUrl = trimTrailingSlash(publicSiteBaseUrl);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> loadSubmissionIdsForImport(List<String> certIds) {
        Map<String, Long> submissionByCertId = new LinkedHashMap<>();
        for (String certId : certIds) {
            jdbcClient.sql(
                    """
                    SELECT id
                    FROM grading_submission
                    WHERE UPPER(cert_id) = UPPER(:certId)
                      AND status_code IN ('approved', 'published')
                    """
                )
                .param("certId", certId)
                .query(Long.class)
                .optional()
                .ifPresent(submissionId -> submissionByCertId.put(certId, submissionId));
        }
        return submissionByCertId;
    }

    @Transactional(readOnly = true)
    public SubmissionForMediaImport loadSubmissionForMediaImport(long submissionId) {
        return jdbcClient.sql(
                """
                SELECT id, cert_id
                FROM grading_submission
                WHERE id = :submissionId
                """
            )
            .param("submissionId", submissionId)
            .query((rs, rowNum) -> new SubmissionForMediaImport(
                rs.getLong("id"),
                rs.getString("cert_id")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found."));
    }

    @Transactional(readOnly = true)
    public SubmissionForPublish loadSubmissionForPublish(long submissionId) {
        return jdbcClient.sql(
                """
                SELECT id, cert_id, status_code
                FROM grading_submission
                WHERE id = :submissionId
                  AND status_code IN ('approved', 'published')
                """
            )
            .param("submissionId", submissionId)
            .query((rs, rowNum) -> new SubmissionForPublish(
                rs.getLong("id"),
                rs.getString("cert_id"),
                rs.getString("status_code")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Approved submission not found."));
    }

    @Transactional(readOnly = true)
    public Optional<ExistingMedia> findExistingMedia(long submissionId, String stage, String sideCode) {
        return findExistingMediaInternal(submissionId, stage, sideCode);
    }

    @Transactional
    public MediaReplaceResult replaceMediaRecord(
        long submissionId,
        String certId,
        String sideCode,
        String stage,
        MediaStorageProvider.StoredMediaObject storedFile,
        Long sourceMediaId
    ) {
        ensureUploadState(submissionId);
        String uploadStatus = lockUploadStatus(submissionId);
        if ("uploading".equals(uploadStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This submission is currently being published.");
        }
        ExistingMedia existingMedia = findExistingMediaInternal(submissionId, stage, sideCode).orElse(null);
        upsertMediaRecord(existingMedia, submissionId, certId, sideCode, stage, storedFile, sourceMediaId);
        jdbcClient.sql(
                """
                UPDATE submission_upload_state
                SET status_code='not_started', claim_token=NULL,
                    claimed_front_media_id=NULL, claimed_back_media_id=NULL,
                    started_at=NULL, completed_at=NULL, error_message=NULL,
                    response_payload_json=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE submission_id=:submissionId
                """
            )
            .param("submissionId", submissionId)
            .update();
        return new MediaReplaceResult(existingMedia);
    }

    @Transactional
    public MediaPublishClaim claimSubmissionForPublish(long submissionId, String claimToken, Long triggeredByUserId) {
        SubmissionForPublish submission = loadSubmissionForPublish(submissionId);
        ensureUploadState(submissionId);
        UploadState currentState = loadUploadStateForUpdate(submissionId);
        if ("uploading".equals(currentState.statusCode())
            && currentState.startedAt() != null
            && currentState.startedAt().isAfter(LocalDateTime.now().minusMinutes(15))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This submission is already being published.");
        }

        ExistingMedia stagedFront = findExistingMediaInternal(submissionId, "staged", "front")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Front staged media is required."));
        ExistingMedia stagedBack = findExistingMediaInternal(submissionId, "staged", "back")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Back staged media is required."));
        LocalDateTime startedAt = LocalDateTime.now();
        jdbcClient.sql(
                """
                UPDATE submission_upload_state
                SET status_code='uploading', claim_token=:claimToken,
                    claimed_front_media_id=:frontMediaId, claimed_back_media_id=:backMediaId,
                    started_at=:startedAt, completed_at=NULL, error_message=NULL,
                    response_payload_json=NULL, triggered_by_user_id=:triggeredByUserId,
                    updated_at=CURRENT_TIMESTAMP
                WHERE submission_id=:submissionId
                """
            )
            .params(Map.of(
                "submissionId", submissionId,
                "claimToken", claimToken,
                "frontMediaId", stagedFront.id(),
                "backMediaId", stagedBack.id(),
                "startedAt", startedAt
            ))
            .param("triggeredByUserId", triggeredByUserId)
            .update();
        return new MediaPublishClaim(claimToken, submission, stagedFront, stagedBack, startedAt);
    }

    @Transactional(readOnly = true)
    public Optional<CompletedPublication> findCompletedPublication(long submissionId) {
        return jdbcClient.sql(
                """
                SELECT s.id,s.cert_id,u.status_code,u.completed_at,
                       u.claimed_front_media_id,u.claimed_back_media_id,
                       front.public_url AS front_url,back.public_url AS back_url
                FROM grading_submission s
                JOIN submission_upload_state u ON u.submission_id=s.id
                JOIN published_certificate p ON p.submission_id=s.id
                JOIN submission_media front ON front.id=p.published_front_media_id AND front.is_active=1
                JOIN submission_media back ON back.id=p.published_back_media_id AND back.is_active=1
                WHERE s.id=:submissionId AND u.status_code='uploaded'
                """
            )
            .param("submissionId", submissionId)
            .query((rs, rowNum) -> new CompletedPublication(
                rs.getLong("id"),
                rs.getString("cert_id"),
                rs.getString("status_code"),
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getObject("claimed_front_media_id", Long.class),
                rs.getObject("claimed_back_media_id", Long.class),
                rs.getString("front_url"),
                rs.getString("back_url")
            ))
            .optional();
    }

    @Transactional
    public void markPublishFailed(long submissionId, String claimToken, String message) {
        jdbcClient.sql(
                """
                UPDATE submission_upload_state
                SET status_code='failed', completed_at=CURRENT_TIMESTAMP,
                    error_message=:message, updated_at=CURRENT_TIMESTAMP
                WHERE submission_id=:submissionId
                  AND status_code='uploading' AND claim_token=:claimToken
                """
            )
            .params(Map.of(
                "submissionId", submissionId,
                "claimToken", claimToken,
                "message", truncate(message, 2000)
            ))
            .update();
    }

    @Transactional
    public UploadState markClientPushed(long submissionId, Long triggeredByUserId) {
        loadSubmissionForPublish(submissionId);
        ensureUploadState(submissionId);
        UploadState state = loadUploadStateForUpdate(submissionId);
        if ("uploading".equals(state.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This submission is currently being published.");
        }
        jdbcClient.sql(
                """
                UPDATE submission_upload_state
                SET status_code='client_pushed', claim_token=NULL,
                    claimed_front_media_id=NULL, claimed_back_media_id=NULL,
                    completed_at=CURRENT_TIMESTAMP, error_message=NULL,
                    response_payload_json=NULL, triggered_by_user_id=:triggeredByUserId,
                    updated_at=CURRENT_TIMESTAMP
                WHERE submission_id=:submissionId
                """
            )
            .param("submissionId", submissionId)
            .param("triggeredByUserId", triggeredByUserId)
            .update();
        return loadUploadState(submissionId);
    }

    @Transactional
    public MediaPublishTransactionResult publishSubmissionRecords(
        long submissionId,
        String certId,
        String claimToken,
        ExistingMedia stagedFront,
        ExistingMedia stagedBack,
        MediaStorageProvider.StoredMediaObject publishedFront,
        MediaStorageProvider.StoredMediaObject publishedBack
    ) {
        UploadState uploadState = loadUploadStateForUpdate(submissionId);
        if (!"uploading".equals(uploadState.statusCode()) || !claimToken.equals(uploadState.claimToken())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The media publish claim is no longer valid.");
        }
        ExistingMedia currentStagedFront = findExistingMediaInternal(submissionId, "staged", "front")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The staged front image changed during publication."));
        ExistingMedia currentStagedBack = findExistingMediaInternal(submissionId, "staged", "back")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The staged back image changed during publication."));
        if (currentStagedFront.id() != stagedFront.id() || currentStagedBack.id() != stagedBack.id()
            || uploadState.claimedFrontMediaId() == null || uploadState.claimedFrontMediaId() != stagedFront.id()
            || uploadState.claimedBackMediaId() == null || uploadState.claimedBackMediaId() != stagedBack.id()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The staged images changed during publication.");
        }
        ExistingMedia existingPublishedFront = findExistingMediaInternal(submissionId, "published", "front").orElse(null);
        ExistingMedia existingPublishedBack = findExistingMediaInternal(submissionId, "published", "back").orElse(null);

        upsertMediaRecord(existingPublishedFront, submissionId, certId, "front", "published", publishedFront, stagedFront.id());
        upsertMediaRecord(existingPublishedBack, submissionId, certId, "back", "published", publishedBack, stagedBack.id());

        ExistingMedia currentPublishedFront = findExistingMediaInternal(submissionId, "published", "front")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Published front media was not persisted."));
        ExistingMedia currentPublishedBack = findExistingMediaInternal(submissionId, "published", "back")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Published back media was not persisted."));

        LocalDateTime publishedAt = LocalDateTime.now();
        Optional<Long> publishedCertificateId = jdbcClient.sql(
                "SELECT id FROM published_certificate WHERE submission_id = :submissionId LIMIT 1"
            )
            .param("submissionId", submissionId)
            .query(Long.class)
            .optional();

        Map<String, Object> publishedCertificateParams = new LinkedHashMap<>();
        publishedCertificateParams.put("submissionId", submissionId);
        publishedCertificateParams.put("certId", certId);
        publishedCertificateParams.put("verificationSlug", certId.toLowerCase(Locale.ROOT));
        publishedCertificateParams.put("qrUrl", publicSiteBaseUrl + "/card/" + certId);
        publishedCertificateParams.put("publishedAt", publishedAt);
        publishedCertificateParams.put("publishedFrontMediaId", currentPublishedFront.id());
        publishedCertificateParams.put("publishedBackMediaId", currentPublishedBack.id());
        publishedCertificateParams.put(
            "publishedMediaSnapshotJson",
            buildPublishedMediaSnapshot(certId, publishedAt, currentPublishedFront, currentPublishedBack)
        );

        if (publishedCertificateId.isEmpty()) {
            jdbcClient.sql(
                    """
                    INSERT INTO published_certificate (
                        submission_id,
                        cert_id,
                        verification_slug,
                        qr_url,
                        published_at,
                        published_front_media_id,
                        published_back_media_id,
                        published_media_snapshot_json
                    ) VALUES (
                        :submissionId,
                        :certId,
                        :verificationSlug,
                        :qrUrl,
                        :publishedAt,
                        :publishedFrontMediaId,
                        :publishedBackMediaId,
                        :publishedMediaSnapshotJson
                    )
                    """
                )
                .params(publishedCertificateParams)
                .update();
        } else {
            publishedCertificateParams.put("publishedCertificateId", publishedCertificateId.get());
            jdbcClient.sql(
                    """
                    UPDATE published_certificate
                    SET cert_id = :certId,
                        verification_slug = :verificationSlug,
                        qr_url = :qrUrl,
                        published_at = :publishedAt,
                        updated_at = CURRENT_TIMESTAMP,
                        published_front_media_id = :publishedFrontMediaId,
                        published_back_media_id = :publishedBackMediaId,
                        published_media_snapshot_json = :publishedMediaSnapshotJson
                    WHERE id = :publishedCertificateId
                    """
                )
                .params(publishedCertificateParams)
                .update();
        }

        jdbcClient.sql(
                """
                UPDATE grading_submission
                SET status_code = 'published',
                    published_at = :publishedAt,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :submissionId
                """
            )
            .params(Map.of("submissionId", submissionId, "publishedAt", publishedAt))
            .update();

        Map<String, Object> responsePayload = new LinkedHashMap<>();
        responsePayload.put("submissionId", submissionId);
        responsePayload.put("certId", certId);
        responsePayload.put("statusCode", "published");
        responsePayload.put("publishedAt", publishedAt.toString());
        responsePayload.put("publishedFrontUrl", currentPublishedFront.publicUrl());
        responsePayload.put("publishedBackUrl", currentPublishedBack.publicUrl());
        jdbcClient.sql(
                """
                UPDATE submission_upload_state
                SET status_code='uploaded', completed_at=:publishedAt,
                    error_message=NULL, response_payload_json=:responsePayload,
                    updated_at=CURRENT_TIMESTAMP
                WHERE submission_id=:submissionId
                  AND status_code='uploading' AND claim_token=:claimToken
                """
            )
            .params(Map.of(
                "submissionId", submissionId,
                "claimToken", claimToken,
                "publishedAt", publishedAt,
                "responsePayload", writeJson(responsePayload, "Failed to serialize publish response.")
            ))
            .update();

        return new MediaPublishTransactionResult(
            publishedAt,
            existingPublishedFront,
            existingPublishedBack,
            currentPublishedFront,
            currentPublishedBack
        );
    }

    @Transactional
    public void deleteClaimedStagedRecords(long submissionId, Long frontMediaId, Long backMediaId) {
        if (frontMediaId == null && backMediaId == null) {
            return;
        }
        jdbcClient.sql(
                """
                DELETE FROM submission_media
                WHERE submission_id=:submissionId AND media_stage_code='staged'
                  AND (id=:frontMediaId OR id=:backMediaId)
                """
            )
            .param("submissionId", submissionId)
            .param("frontMediaId", frontMediaId == null ? -1L : frontMediaId)
            .param("backMediaId", backMediaId == null ? -1L : backMediaId)
            .update();
    }

    private Optional<ExistingMedia> findExistingMediaInternal(long submissionId, String stage, String sideCode) {
        return jdbcClient.sql(
                """
                SELECT
                    id,
                    media_stage_code,
                    storage_provider_code,
                    storage_bucket,
                    storage_key,
                    storage_object_version,
                    public_url,
                    width_px,
                    height_px,
                    original_filename,
                    mime_type,
                    file_size_bytes,
                    checksum_sha256,
                    source_media_id
                FROM submission_media
                WHERE submission_id = :submissionId
                  AND media_stage_code = :stage
                  AND media_side_code = :sideCode
                  AND sort_order = 1
                LIMIT 1
                """
            )
            .params(Map.of(
                "submissionId", submissionId,
                "stage", requireStage(stage),
                "sideCode", sideCode
            ))
            .query((rs, rowNum) -> new ExistingMedia(
                rs.getLong("id"),
                rs.getString("media_stage_code"),
                rs.getString("storage_provider_code"),
                rs.getString("storage_bucket"),
                rs.getString("storage_key"),
                rs.getString("storage_object_version"),
                rs.getString("public_url"),
                rs.getObject("width_px", Integer.class),
                rs.getObject("height_px", Integer.class),
                rs.getString("original_filename"),
                rs.getString("mime_type"),
                rs.getObject("file_size_bytes", Long.class),
                rs.getString("checksum_sha256"),
                rs.getObject("source_media_id", Long.class)
            ))
            .optional();
    }

    private void upsertMediaRecord(
        ExistingMedia existingMedia,
        long submissionId,
        String certId,
        String sideCode,
        String stage,
        MediaStorageProvider.StoredMediaObject storedFile,
        Long sourceMediaId
    ) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("submissionId", submissionId);
        params.put("certId", certId);
        params.put("sideCode", sideCode);
        params.put("stage", requireStage(stage));
        params.put("storageProviderCode", storedFile.storageProviderCode());
        params.put("storageBucket", storedFile.storageBucket());
        params.put("storageKey", storedFile.storageKey());
        params.put("storageObjectVersion", storedFile.storageObjectVersion());
        params.put("publicUrl", storedFile.publicUrl());
        params.put("widthPx", storedFile.widthPx());
        params.put("heightPx", storedFile.heightPx());
        params.put("originalFilename", storedFile.originalFilename());
        params.put("mimeType", storedFile.mimeType());
        params.put("fileSizeBytes", storedFile.fileSizeBytes());
        params.put("checksumSha256", storedFile.checksumSha256());
        params.put("sourceMediaId", sourceMediaId);

        if (existingMedia == null) {
            jdbcClient.sql(
                    """
                    INSERT INTO submission_media (
                        submission_id,
                        cert_id,
                        media_side_code,
                        media_stage_code,
                        storage_provider_code,
                        storage_bucket,
                        storage_key,
                        storage_object_version,
                        public_url,
                        width_px,
                        height_px,
                        sort_order,
                        is_active,
                        original_filename,
                        mime_type,
                        file_size_bytes,
                        checksum_sha256,
                        source_media_id
                    ) VALUES (
                        :submissionId,
                        :certId,
                        :sideCode,
                        :stage,
                        :storageProviderCode,
                        :storageBucket,
                        :storageKey,
                        :storageObjectVersion,
                        :publicUrl,
                        :widthPx,
                        :heightPx,
                        1,
                        1,
                        :originalFilename,
                        :mimeType,
                        :fileSizeBytes,
                        :checksumSha256,
                        :sourceMediaId
                    )
                    """
                )
                .params(params)
                .update();
            return;
        }

        params.put("mediaId", existingMedia.id());
        jdbcClient.sql(
                """
                UPDATE submission_media
                SET cert_id = :certId,
                    storage_provider_code = :storageProviderCode,
                    storage_bucket = :storageBucket,
                    storage_key = :storageKey,
                    storage_object_version = :storageObjectVersion,
                    public_url = :publicUrl,
                    width_px = :widthPx,
                    height_px = :heightPx,
                    sort_order = 1,
                    is_active = 1,
                    original_filename = :originalFilename,
                    mime_type = :mimeType,
                    file_size_bytes = :fileSizeBytes,
                    checksum_sha256 = :checksumSha256,
                    source_media_id = :sourceMediaId,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :mediaId
                """
            )
            .params(params)
            .update();
    }

    private String buildPublishedMediaSnapshot(
        String certId,
        LocalDateTime publishedAt,
        ExistingMedia publishedFront,
        ExistingMedia publishedBack
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("certId", certId);
        snapshot.put("publishedAt", publishedAt.toString());
        snapshot.put("front", buildMediaSnapshotSide(publishedFront));
        snapshot.put("back", buildMediaSnapshotSide(publishedBack));

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize publish snapshot.", exc);
        }
    }

    private Map<String, Object> buildMediaSnapshotSide(ExistingMedia media) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mediaId", media.id());
        snapshot.put("storageProviderCode", media.storageProviderCode());
        snapshot.put("storageBucket", media.storageBucket());
        snapshot.put("storageKey", media.storageKey());
        snapshot.put("storageObjectVersion", media.storageObjectVersion());
        snapshot.put("publicUrl", media.publicUrl());
        snapshot.put("widthPx", media.widthPx());
        snapshot.put("heightPx", media.heightPx());
        snapshot.put("checksumSha256", media.checksumSha256());
        snapshot.put("sourceMediaId", media.sourceMediaId());
        return snapshot;
    }

    private String requireStage(String stage) {
        if (stage == null || stage.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported media stage.");
        }
        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "staged", "published" -> normalized;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported media stage.");
        };
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void ensureUploadState(long submissionId) {
        jdbcClient.sql(
                """
                INSERT INTO submission_upload_state (submission_id, status_code)
                VALUES (:submissionId, 'not_started')
                ON DUPLICATE KEY UPDATE submission_id=VALUES(submission_id)
                """
            )
            .param("submissionId", submissionId)
            .update();
    }

    private String lockUploadStatus(long submissionId) {
        return jdbcClient.sql(
                "SELECT status_code FROM submission_upload_state WHERE submission_id=:submissionId FOR UPDATE"
            )
            .param("submissionId", submissionId)
            .query(String.class)
            .single();
    }

    private UploadState loadUploadStateForUpdate(long submissionId) {
        return loadUploadStateSql(submissionId, true);
    }

    private UploadState loadUploadState(long submissionId) {
        return loadUploadStateSql(submissionId, false);
    }

    private UploadState loadUploadStateSql(long submissionId, boolean forUpdate) {
        return jdbcClient.sql(
                """
                SELECT submission_id,status_code,claim_token,claimed_front_media_id,
                       claimed_back_media_id,started_at,completed_at,error_message,
                       response_payload_json,triggered_by_user_id
                FROM submission_upload_state
                WHERE submission_id=:submissionId
                """ + (forUpdate ? " FOR UPDATE" : "")
            )
            .param("submissionId", submissionId)
            .query((rs, rowNum) -> new UploadState(
                rs.getLong("submission_id"),
                rs.getString("status_code"),
                rs.getString("claim_token"),
                rs.getObject("claimed_front_media_id", Long.class),
                rs.getObject("claimed_back_media_id", Long.class),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getString("error_message"),
                rs.getString("response_payload_json"),
                rs.getObject("triggered_by_user_id", Long.class)
            ))
            .single();
    }

    private String writeJson(Object value, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, exc);
        }
    }

    private String truncate(String value, int limit) {
        String normalized = value == null || value.isBlank() ? "Publish failed." : value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    public record SubmissionForPublish(long id, String certId, String statusCode) {
    }

    public record SubmissionForMediaImport(long id, String certId) {
    }

    public record ExistingMedia(
        long id,
        String mediaStageCode,
        String storageProviderCode,
        String storageBucket,
        String storageKey,
        String storageObjectVersion,
        String publicUrl,
        Integer widthPx,
        Integer heightPx,
        String originalFilename,
        String mimeType,
        Long fileSizeBytes,
        String checksumSha256,
        Long sourceMediaId
    ) {
    }

    public record MediaReplaceResult(ExistingMedia replacedMedia) {
    }

    public record MediaPublishTransactionResult(
        LocalDateTime publishedAt,
        ExistingMedia replacedFrontMedia,
        ExistingMedia replacedBackMedia,
        ExistingMedia currentFrontMedia,
        ExistingMedia currentBackMedia
    ) {
    }

    public record MediaPublishClaim(
        String claimToken,
        SubmissionForPublish submission,
        ExistingMedia stagedFront,
        ExistingMedia stagedBack,
        LocalDateTime startedAt
    ) {
    }

    public record CompletedPublication(
        long submissionId,
        String certId,
        String statusCode,
        LocalDateTime completedAt,
        Long claimedFrontMediaId,
        Long claimedBackMediaId,
        String publishedFrontUrl,
        String publishedBackUrl
    ) {
    }

    public record UploadState(
        long submissionId,
        String statusCode,
        String claimToken,
        Long claimedFrontMediaId,
        Long claimedBackMediaId,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String errorMessage,
        String responsePayloadJson,
        Long triggeredByUserId
    ) {
    }
}
