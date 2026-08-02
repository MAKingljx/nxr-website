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
        ExistingMedia existingMedia = findExistingMediaInternal(submissionId, stage, sideCode).orElse(null);
        upsertMediaRecord(existingMedia, submissionId, certId, sideCode, stage, storedFile, sourceMediaId);
        return new MediaReplaceResult(existingMedia);
    }

    @Transactional
    public MediaPublishTransactionResult publishSubmissionRecords(
        long submissionId,
        String certId,
        ExistingMedia stagedFront,
        ExistingMedia stagedBack,
        MediaStorageProvider.StoredMediaObject publishedFront,
        MediaStorageProvider.StoredMediaObject publishedBack
    ) {
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

        return new MediaPublishTransactionResult(
            publishedAt,
            existingPublishedFront,
            existingPublishedBack,
            currentPublishedFront,
            currentPublishedBack
        );
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

    public record SubmissionForPublish(long id, String certId, String statusCode) {
    }

    public record ExistingMedia(
        long id,
        String mediaStageCode,
        String storageProviderCode,
        String storageBucket,
        String storageKey,
        String storageObjectVersion,
        String publicUrl,
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
}
