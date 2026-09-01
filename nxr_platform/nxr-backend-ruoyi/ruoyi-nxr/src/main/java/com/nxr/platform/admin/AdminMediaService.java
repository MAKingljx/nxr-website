package com.nxr.platform.admin;

import com.nxr.platform.admin.storage.MediaStorageProvider;
import com.nxr.platform.admin.storage.MediaStorageRegistry;
import com.nxr.platform.shared.ProductTypePolicy;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminMediaService {

    private static final Logger log = LoggerFactory.getLogger(AdminMediaService.class);
    private static final int MAX_BATCH_PUBLISH_ITEMS = 100;

    private static final Pattern IMPORT_FILE_PATTERN = Pattern.compile(
        "(^|[\\\\/])(?<certId>[A-Za-z0-9]+)_(?<side>[AB])(?:_\\d+)?\\.(?<ext>webp|jpg|jpeg|png)$",
        Pattern.CASE_INSENSITIVE
    );

    private final JdbcClient jdbcClient;
    private final AdminMediaPersistenceService adminMediaPersistenceService;
    private final MediaStorageRegistry mediaStorageRegistry;
    private final int maxImportFiles;
    private final long maxImportFileSizeBytes;
    private final long maxImportTotalSizeBytes;
    private final long maxImagePixels;

    public AdminMediaService(
        JdbcClient jdbcClient,
        AdminMediaPersistenceService adminMediaPersistenceService,
        MediaStorageRegistry mediaStorageRegistry,
        @Value("${nxr.media.import.max-files:12}") int maxImportFiles,
        @Value("${nxr.media.import.max-file-size-bytes:25165824}") long maxImportFileSizeBytes,
        @Value("${nxr.media.import.max-total-size-bytes:25165824}") long maxImportTotalSizeBytes,
        @Value("${nxr.media.import.max-image-pixels:100000000}") long maxImagePixels
    ) {
        this.jdbcClient = jdbcClient;
        this.adminMediaPersistenceService = adminMediaPersistenceService;
        this.mediaStorageRegistry = mediaStorageRegistry;
        this.maxImportFiles = Math.max(maxImportFiles, 1);
        this.maxImportFileSizeBytes = Math.max(maxImportFileSizeBytes, 1024L);
        this.maxImportTotalSizeBytes = Math.max(maxImportTotalSizeBytes, 1024L);
        this.maxImagePixels = Math.max(maxImagePixels, 1L);
    }

    @Transactional(readOnly = true)
    public MediaQueueResponse loadQueue(String query, int page, int pageSize) {
        return loadQueue(query, null, null, false, page, pageSize);
    }

    @Transactional(readOnly = true)
    public MediaQueueResponse loadQueue(
        String query,
        String uploadStatus,
        String imageStatus,
        boolean showClientPushed,
        int page,
        int pageSize
    ) {
        String normalizedQuery = normalizeFilter(query);
        String normalizedUploadStatus = normalizeUploadStatus(uploadStatus);
        String normalizedImageStatus = normalizeImageStatus(imageStatus);
        Map<String, Object> queueParams = new LinkedHashMap<>();
        queueParams.put("query", normalizedQuery == null ? null : "%" + normalizedQuery.toUpperCase(Locale.ROOT) + "%");
        queueParams.put("uploadStatus", normalizedUploadStatus);
        queueParams.put("imageStatus", normalizedImageStatus);
        queueParams.put("showClientPushed", showClientPushed ? 1 : 0);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), 100);
        int normalizedPage = Math.max(page, 1);
        int total = jdbcClient.sql(
                """
                SELECT COUNT(*)
                FROM grading_submission s
                LEFT JOIN grading_score g ON g.submission_id = s.id
                LEFT JOIN submission_upload_state us ON us.submission_id = s.id
                WHERE s.status_code IN ('approved', 'published')
                  AND (
                    :query IS NULL
                    OR UPPER(s.cert_id) LIKE :query
                    OR UPPER(s.card_name) LIKE :query
                    OR UPPER(s.set_name) LIKE :query
                  )
                  AND (:showClientPushed=1 OR COALESCE(us.status_code, 'not_started')<>'client_pushed')
                  AND (:uploadStatus IS NULL OR COALESCE(us.status_code, 'not_started')=:uploadStatus)
                  AND (
                    :imageStatus IS NULL
                    OR (:imageStatus='ready' AND
                        EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='front' AND sm.is_active=1)
                        AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='back' AND sm.is_active=1))
                    OR (:imageStatus='published' AND
                        EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='front' AND sm.is_active=1)
                        AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='back' AND sm.is_active=1))
                    OR (:imageStatus='waiting'
                        AND NOT (EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='front' AND sm.is_active=1)
                             AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='back' AND sm.is_active=1))
                        AND NOT (EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='front' AND sm.is_active=1)
                             AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='back' AND sm.is_active=1)))
                  )
                """
            )
            .params(queueParams)
            .query(Integer.class)
            .single();

        int totalPages = Math.max(1, (int) Math.ceil((double) total / normalizedPageSize));
        normalizedPage = Math.min(normalizedPage, totalPages);
        int offset = (normalizedPage - 1) * normalizedPageSize;

        MediaQueueSummary summary = jdbcClient.sql(
                """
                SELECT
                    COUNT(*) AS tracked_entries,
                    COALESCE(SUM(CASE WHEN
                        EXISTS (
                            SELECT 1 FROM submission_media sm
                            WHERE sm.submission_id = s.id
                              AND sm.media_stage_code = 'staged'
                              AND sm.media_side_code = 'front'
                              AND sm.is_active = 1
                        )
                        AND EXISTS (
                            SELECT 1 FROM submission_media sm
                            WHERE sm.submission_id = s.id
                              AND sm.media_stage_code = 'staged'
                              AND sm.media_side_code = 'back'
                              AND sm.is_active = 1
                        )
                        THEN 1 ELSE 0 END), 0) AS ready_to_publish,
                    COALESCE(SUM(CASE WHEN
                        EXISTS (
                            SELECT 1 FROM submission_media sm
                            WHERE sm.submission_id = s.id
                              AND sm.media_stage_code = 'published'
                              AND sm.media_side_code = 'front'
                              AND sm.is_active = 1
                        )
                        AND EXISTS (
                            SELECT 1 FROM submission_media sm
                            WHERE sm.submission_id = s.id
                              AND sm.media_stage_code = 'published'
                              AND sm.media_side_code = 'back'
                              AND sm.is_active = 1
                        )
                        THEN 1 ELSE 0 END), 0) AS live_published,
                    COALESCE(SUM(CASE WHEN
                        NOT (
                            EXISTS (
                                SELECT 1 FROM submission_media sm
                                WHERE sm.submission_id = s.id
                                  AND sm.media_stage_code = 'staged'
                                  AND sm.media_side_code = 'front'
                                  AND sm.is_active = 1
                            )
                            AND EXISTS (
                                SELECT 1 FROM submission_media sm
                                WHERE sm.submission_id = s.id
                                  AND sm.media_stage_code = 'staged'
                                  AND sm.media_side_code = 'back'
                                  AND sm.is_active = 1
                            )
                        )
                        AND NOT (
                            EXISTS (
                                SELECT 1 FROM submission_media sm
                                WHERE sm.submission_id = s.id
                                  AND sm.media_stage_code = 'published'
                                  AND sm.media_side_code = 'front'
                                  AND sm.is_active = 1
                            )
                            AND EXISTS (
                                SELECT 1 FROM submission_media sm
                                WHERE sm.submission_id = s.id
                                  AND sm.media_stage_code = 'published'
                                  AND sm.media_side_code = 'back'
                                  AND sm.is_active = 1
                            )
                        )
                        THEN 1 ELSE 0 END), 0) AS missing_media
                FROM grading_submission s
                LEFT JOIN grading_score g ON g.submission_id = s.id
                LEFT JOIN submission_upload_state us ON us.submission_id = s.id
                WHERE s.status_code IN ('approved', 'published')
                  AND (
                    :query IS NULL
                    OR UPPER(s.cert_id) LIKE :query
                    OR UPPER(s.card_name) LIKE :query
                    OR UPPER(s.set_name) LIKE :query
                  )
                  AND (:showClientPushed=1 OR COALESCE(us.status_code, 'not_started')<>'client_pushed')
                  AND (:uploadStatus IS NULL OR COALESCE(us.status_code, 'not_started')=:uploadStatus)
                  AND (
                    :imageStatus IS NULL
                    OR (:imageStatus='ready' AND
                        EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='front' AND sm.is_active=1)
                        AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='back' AND sm.is_active=1))
                    OR (:imageStatus='published' AND
                        EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='front' AND sm.is_active=1)
                        AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='back' AND sm.is_active=1))
                    OR (:imageStatus='waiting'
                        AND NOT (EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='front' AND sm.is_active=1)
                             AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='back' AND sm.is_active=1))
                        AND NOT (EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='front' AND sm.is_active=1)
                             AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='back' AND sm.is_active=1)))
                  )
                """
            )
            .params(queueParams)
            .query((rs, rowNum) -> new MediaQueueSummary(
                rs.getInt("tracked_entries"),
                rs.getInt("ready_to_publish"),
                rs.getInt("live_published"),
                rs.getInt("missing_media")
            ))
            .single();

        List<MediaQueueItem> items = jdbcClient.sql(
                """
                SELECT
                    s.id,
                    s.cert_id,
                    COALESCE(NULLIF(s.product_type_code, ''), 'graded_card') AS product_type_code,
                    s.vintage_classification_code,
                    s.merch_description,
                    s.card_name,
                    s.status_code,
                    s.approved_at,
                    s.published_at,
                    COALESCE(us.status_code, 'not_started') AS upload_status,
                    us.started_at AS upload_started_at,
                    us.completed_at AS upload_completed_at,
                    us.error_message AS upload_error,
                    g.final_grade_value,
                    g.final_grade_label,
                    (
                        SELECT sm.public_url
                        FROM submission_media sm
                        WHERE sm.submission_id = s.id
                          AND sm.media_stage_code = 'staged'
                          AND sm.media_side_code = 'front'
                          AND sm.is_active = 1
                        LIMIT 1
                    ) AS staged_front_url,
                    (
                        SELECT sm.public_url
                        FROM submission_media sm
                        WHERE sm.submission_id = s.id
                          AND sm.media_stage_code = 'staged'
                          AND sm.media_side_code = 'back'
                          AND sm.is_active = 1
                        LIMIT 1
                    ) AS staged_back_url,
                    (
                        SELECT sm.public_url
                        FROM submission_media sm
                        WHERE sm.submission_id = s.id
                          AND sm.media_stage_code = 'published'
                          AND sm.media_side_code = 'front'
                          AND sm.is_active = 1
                        LIMIT 1
                    ) AS published_front_url,
                    (
                        SELECT sm.public_url
                        FROM submission_media sm
                        WHERE sm.submission_id = s.id
                          AND sm.media_stage_code = 'published'
                          AND sm.media_side_code = 'back'
                          AND sm.is_active = 1
                        LIMIT 1
                    ) AS published_back_url
                FROM grading_submission s
                LEFT JOIN grading_score g ON g.submission_id = s.id
                LEFT JOIN submission_upload_state us ON us.submission_id = s.id
                WHERE s.status_code IN ('approved', 'published')
                  AND (
                    :query IS NULL
                    OR UPPER(s.cert_id) LIKE :query
                    OR UPPER(s.card_name) LIKE :query
                    OR UPPER(s.set_name) LIKE :query
                  )
                  AND (:showClientPushed=1 OR COALESCE(us.status_code, 'not_started')<>'client_pushed')
                  AND (:uploadStatus IS NULL OR COALESCE(us.status_code, 'not_started')=:uploadStatus)
                  AND (
                    :imageStatus IS NULL
                    OR (:imageStatus='ready' AND
                        EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='front' AND sm.is_active=1)
                        AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='back' AND sm.is_active=1))
                    OR (:imageStatus='published' AND
                        EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='front' AND sm.is_active=1)
                        AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='back' AND sm.is_active=1))
                    OR (:imageStatus='waiting'
                        AND NOT (EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='front' AND sm.is_active=1)
                             AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='staged' AND sm.media_side_code='back' AND sm.is_active=1))
                        AND NOT (EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='front' AND sm.is_active=1)
                             AND EXISTS (SELECT 1 FROM submission_media sm WHERE sm.submission_id=s.id AND sm.media_stage_code='published' AND sm.media_side_code='back' AND sm.is_active=1)))
                  )
                ORDER BY COALESCE(s.approved_at, s.published_at, s.updated_at) DESC, s.id DESC
                LIMIT :limit OFFSET :offset
                """
            )
            .params(queueParams)
            .param("limit", normalizedPageSize)
            .param("offset", offset)
            .query((rs, rowNum) -> {
                String stagedFrontUrl = rs.getString("staged_front_url");
                String stagedBackUrl = rs.getString("staged_back_url");
                String publishedFrontUrl = rs.getString("published_front_url");
                String publishedBackUrl = rs.getString("published_back_url");
                boolean hasStagedFront = isPresent(stagedFrontUrl);
                boolean hasStagedBack = isPresent(stagedBackUrl);
                boolean hasPublishedFront = isPresent(publishedFrontUrl);
                boolean hasPublishedBack = isPresent(publishedBackUrl);
                String uploadState = rs.getString("upload_status");
                return new MediaQueueItem(
                    rs.getLong("id"),
                    rs.getString("cert_id"),
                    ProductTypePolicy.normalizeStored(rs.getString("product_type_code")),
                    rs.getString("vintage_classification_code"),
                    rs.getString("merch_description"),
                    rs.getString("card_name"),
                    rs.getString("status_code"),
                    rs.getObject("approved_at", LocalDateTime.class),
                    rs.getObject("published_at", LocalDateTime.class),
                    uploadState,
                    rs.getObject("upload_started_at", LocalDateTime.class),
                    rs.getObject("upload_completed_at", LocalDateTime.class),
                    rs.getString("upload_error"),
                    rs.getBigDecimal("final_grade_value"),
                    rs.getString("final_grade_label"),
                    stagedFrontUrl,
                    stagedBackUrl,
                    publishedFrontUrl,
                    publishedBackUrl,
                    hasStagedFront,
                    hasStagedBack,
                    hasPublishedFront,
                    hasPublishedBack,
                    hasStagedFront && hasStagedBack
                        && !"uploading".equals(uploadState)
                        && !"client_pushed".equals(uploadState)
                );
            })
            .list();

        return new MediaQueueResponse(
            items,
            summary,
            normalizedPage,
            normalizedPageSize,
            total
        );
    }

    public MediaImportResponse importFolder(List<MultipartFile> imageFiles) {
        return importMedia(imageFiles, null);
    }

    public MediaImportResponse importSubmissionMedia(long submissionId, List<MultipartFile> imageFiles) {
        AdminMediaPersistenceService.SubmissionForMediaImport submission =
            adminMediaPersistenceService.loadSubmissionForMediaImport(submissionId);
        return importMedia(
            imageFiles,
            Map.of(submission.certId().toUpperCase(Locale.ROOT), submission.id())
        );
    }

    private MediaImportResponse importMedia(
        List<MultipartFile> imageFiles,
        Map<String, Long> allowedSubmissionByCertId
    ) {
        List<MultipartFile> uploadedFiles = imageFiles == null
            ? List.of()
            : imageFiles.stream().filter(file -> file != null && isPresent(file.getOriginalFilename())).toList();

        if (uploadedFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an image folder first.");
        }
        if (uploadedFiles.size() > maxImportFiles) {
            throw new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Too many files selected. Import at most " + maxImportFiles + " images at once."
            );
        }
        long totalSizeBytes = 0;
        for (MultipartFile uploadedFile : uploadedFiles) {
            try {
                totalSizeBytes = Math.addExact(totalSizeBytes, Math.max(uploadedFile.getSize(), 0L));
            } catch (ArithmeticException exc) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The selected files are too large.");
            }
        }
        if (totalSizeBytes > maxImportTotalSizeBytes) {
            throw new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "The selected files exceed the " + maxImportTotalSizeBytes + " byte batch limit."
            );
        }

        Map<MediaCandidateKey, MediaCandidate> candidates = new LinkedHashMap<>();
        List<String> invalidNames = new ArrayList<>();
        List<String> duplicateNames = new ArrayList<>();

        for (MultipartFile imageFile : uploadedFiles) {
            String rawName = imageFile.getOriginalFilename();
            Optional<ParsedImportName> parsed = parseImportFileName(rawName);
            if (parsed.isEmpty()) {
                invalidNames.add(rawName);
                continue;
            }

            MediaCandidateKey key = new MediaCandidateKey(
                parsed.get().certId().toUpperCase(Locale.ROOT),
                parsed.get().sideCode()
            );
            if (candidates.containsKey(key)) {
                duplicateNames.add(rawName);
                continue;
            }

            Optional<ValidatedImageFile> validatedImageFile = validateImageFile(
                imageFile,
                parsed.get().extension()
            );
            if (validatedImageFile.isEmpty()) {
                invalidNames.add(rawName + " (invalid image content)");
                continue;
            }

            candidates.put(key, new MediaCandidate(
                key.certId(),
                key.sideCode(),
                parsed.get().extension(),
                validatedImageFile.get().contentType(),
                validatedImageFile.get().widthPx(),
                validatedImageFile.get().heightPx(),
                rawName,
                imageFile
            ));
        }

        if (candidates.isEmpty()) {
            return new MediaImportResponse(
                0,
                0,
                0,
                List.of(),
                invalidNames,
                duplicateNames,
                List.of()
            );
        }

        List<String> candidateCertIds = candidates.keySet().stream()
            .map(MediaCandidateKey::certId)
            .distinct()
            .toList();
        Map<String, Long> submissionByCertId;
        if (allowedSubmissionByCertId == null) {
            submissionByCertId = adminMediaPersistenceService.loadSubmissionIdsForImport(candidateCertIds);
        } else {
            submissionByCertId = new LinkedHashMap<>();
            for (String certId : candidateCertIds) {
                Long allowedSubmissionId = allowedSubmissionByCertId.get(certId);
                if (allowedSubmissionId != null) {
                    submissionByCertId.put(certId, allowedSubmissionId);
                }
            }
        }
        List<String> missingCertIds = candidates.keySet().stream()
            .map(MediaCandidateKey::certId)
            .distinct()
            .filter(certId -> !submissionByCertId.containsKey(certId))
            .toList();

        int savedFiles = 0;
        int updatedSides = 0;
        List<Long> updatedSubmissionIds = new ArrayList<>();

        for (MediaCandidate candidate : candidates.values()) {
            Long submissionId = submissionByCertId.get(candidate.certId());
            if (submissionId == null) {
                continue;
            }

            MediaStorageProvider.StoredMediaObject storedFile = storeUploadedMedia(candidate, "staged");
            AdminMediaPersistenceService.MediaReplaceResult replaceResult;
            try {
                replaceResult = adminMediaPersistenceService.replaceMediaRecord(
                    submissionId,
                    candidate.certId(),
                    candidate.sideCode(),
                    "staged",
                    storedFile,
                    null
                );
            } catch (RuntimeException exc) {
                deleteStoredMedia("staged", storedFile);
                throw exc;
            }

            deleteStoredMediaIfReplaced(replaceResult.replacedMedia(), storedFile.storageKey());

            savedFiles += 1;
            updatedSides += 1;
            if (!updatedSubmissionIds.contains(submissionId)) {
                updatedSubmissionIds.add(submissionId);
            }
        }

        return new MediaImportResponse(
            submissionByCertId.size(),
            savedFiles,
            updatedSides,
            missingCertIds,
            invalidNames,
            duplicateNames,
            updatedSubmissionIds
        );
    }

    public MediaPublishResponse publishSubmission(long submissionId) {
        return publishSubmission(submissionId, null);
    }

    public MediaPublishResponse publishSubmission(long submissionId, Long triggeredByUserId) {
        Optional<AdminMediaPersistenceService.CompletedPublication> completed =
            adminMediaPersistenceService.findCompletedPublication(submissionId);
        if (completed.isPresent()) {
            return completedResponse(completed.get());
        }

        String claimToken = UUID.randomUUID().toString();
        AdminMediaPersistenceService.MediaPublishClaim claim =
            adminMediaPersistenceService.claimSubmissionForPublish(submissionId, claimToken, triggeredByUserId);
        AdminMediaPersistenceService.SubmissionForPublish submission = claim.submission();
        AdminMediaPersistenceService.ExistingMedia stagedFront = claim.stagedFront();
        AdminMediaPersistenceService.ExistingMedia stagedBack = claim.stagedBack();

        MediaStorageProvider.StoredMediaObject publishedFront = null;
        MediaStorageProvider.StoredMediaObject publishedBack = null;
        AdminMediaPersistenceService.MediaPublishTransactionResult publishResult;
        try {
            publishedFront = copyManagedMedia(stagedFront, submission.certId(), "front", "published");
            publishedBack = copyManagedMedia(stagedBack, submission.certId(), "back", "published");
            publishResult = adminMediaPersistenceService.publishSubmissionRecords(
                submissionId,
                submission.certId(),
                claimToken,
                stagedFront,
                stagedBack,
                publishedFront,
                publishedBack
            );
        } catch (RuntimeException exc) {
            deleteStoredMedia("published", publishedFront);
            deleteStoredMedia("published", publishedBack);
            adminMediaPersistenceService.markPublishFailed(
                submissionId,
                claimToken,
                exc instanceof ResponseStatusException response && response.getReason() != null
                    ? response.getReason()
                    : exc.getMessage()
            );
            throw exc;
        }

        deleteStoredMediaIfReplaced(publishResult.replacedFrontMedia(), publishedFront.storageKey());
        deleteStoredMediaIfReplaced(publishResult.replacedBackMedia(), publishedBack.storageKey());
        adminMediaPersistenceService.deleteClaimedStagedRecords(submissionId, stagedFront.id(), stagedBack.id());
        deleteExistingMedia(stagedFront);
        deleteExistingMedia(stagedBack);

        return new MediaPublishResponse(
            submissionId,
            submission.certId(),
            "published",
            publishResult.publishedAt(),
            publishedFront.publicUrl(),
            publishedBack.publicUrl()
        );
    }

    public AdminMediaPersistenceService.UploadState markClientPushed(long submissionId, Long triggeredByUserId) {
        return adminMediaPersistenceService.markClientPushed(submissionId, triggeredByUserId);
    }

    public MediaBatchPublishResponse publishSubmissions(List<Long> submissionIds) {
        return publishSubmissions(submissionIds, null);
    }

    public MediaBatchPublishResponse publishSubmissions(List<Long> submissionIds, Long triggeredByUserId) {
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        if (submissionIds != null) {
            for (Long submissionId : submissionIds) {
                if (submissionId != null && submissionId > 0) {
                    uniqueIds.add(submissionId);
                }
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one ready submission.");
        }
        if (uniqueIds.size() > MAX_BATCH_PUBLISH_ITEMS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Publish at most " + MAX_BATCH_PUBLISH_ITEMS + " submissions at once."
            );
        }

        List<MediaPublishResponse> published = new ArrayList<>();
        List<MediaBatchPublishFailure> failures = new ArrayList<>();
        for (Long submissionId : uniqueIds) {
            try {
                published.add(
                    triggeredByUserId == null
                        ? publishSubmission(submissionId)
                        : publishSubmission(submissionId, triggeredByUserId)
                );
            } catch (ResponseStatusException exc) {
                failures.add(new MediaBatchPublishFailure(
                    submissionId,
                    exc.getReason() == null ? "Publish failed." : exc.getReason()
                ));
            } catch (RuntimeException exc) {
                log.error("Unexpected batch media publish failure for submission {}", submissionId, exc);
                failures.add(new MediaBatchPublishFailure(submissionId, "Unexpected publish failure."));
            }
        }

        return new MediaBatchPublishResponse(
            uniqueIds.size(),
            published.size(),
            failures.size(),
            published,
            failures
        );
    }

    public ResolvedMediaAsset resolveMediaAsset(String stage, String filename) {
        StoredMediaLookup media = jdbcClient.sql(
                """
                SELECT storage_provider_code, storage_bucket, storage_key, storage_object_version
                FROM submission_media
                WHERE media_stage_code = :stage
                  AND storage_key = :storageKey
                  AND is_active = 1
                LIMIT 1
                """
            )
            .params(Map.of(
                "stage", stage == null ? "" : stage.trim().toLowerCase(Locale.ROOT),
                "storageKey", filename == null ? "" : filename.trim()
            ))
            .query((rs, rowNum) -> new StoredMediaLookup(
                rs.getString("storage_provider_code"),
                rs.getString("storage_bucket"),
                rs.getString("storage_key"),
                rs.getString("storage_object_version")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found."));

        if (!mediaStorageRegistry.supports(media.storageProviderCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found.");
        }

        MediaStorageProvider.ResolvedMediaAsset asset = mediaStorageRegistry.providerFor(media.storageProviderCode()).resolve(
            new MediaStorageProvider.StoredMediaLocation(stage, media.storageBucket(), media.storageKey(), media.storageObjectVersion())
        );
        return new ResolvedMediaAsset(asset.resource(), asset.contentType(), asset.filename());
    }

    private Optional<ParsedImportName> parseImportFileName(String rawFilename) {
        if (!isPresent(rawFilename)) {
            return Optional.empty();
        }

        Matcher matcher = IMPORT_FILE_PATTERN.matcher(rawFilename.trim());
        if (!matcher.find()) {
            return Optional.empty();
        }

        String sideCode = switch (matcher.group("side").toUpperCase(Locale.ROOT)) {
            case "A" -> "front";
            case "B" -> "back";
            default -> "";
        };
        if (!isPresent(sideCode)) {
            return Optional.empty();
        }

        return Optional.of(new ParsedImportName(
            matcher.group("certId").toUpperCase(Locale.ROOT),
            sideCode,
            matcher.group("ext").toLowerCase(Locale.ROOT)
        ));
    }

    private MediaStorageProvider.StoredMediaObject storeUploadedMedia(MediaCandidate candidate, String stage) {
        return mediaStorageRegistry.active().store(
            stage,
            candidate.certId(),
            candidate.sideCode(),
            candidate.extension(),
            new MediaStorageProvider.MediaUpload(
                candidate.sourceName(),
                candidate.contentType(),
                candidate.multipartFile().getSize(),
                candidate.widthPx(),
                candidate.heightPx(),
                candidate.multipartFile()::getInputStream
            )
        );
    }

    private Optional<ValidatedImageFile> validateImageFile(
        MultipartFile imageFile,
        String extension
    ) {
        long fileSize = imageFile.getSize();
        if (fileSize <= 0 || fileSize > maxImportFileSizeBytes) {
            return Optional.empty();
        }

        String expectedContentType = contentTypeForExtension(extension);
        if (expectedContentType == null) {
            return Optional.empty();
        }

        String declaredContentType = normalizeContentType(imageFile.getContentType());
        if (isPresent(declaredContentType)
            && !"application/octet-stream".equals(declaredContentType)
            && !expectedContentType.equals(declaredContentType)) {
            return Optional.empty();
        }

        boolean hasValidContent = "image/webp".equals(expectedContentType)
            ? hasWebpHeader(imageFile)
            : hasSupportedRasterHeader(imageFile, expectedContentType);
        if (!hasValidContent) {
            return Optional.empty();
        }

        Optional<ImageDimensions> dimensions = readImageDimensions(imageFile, expectedContentType);
        if (dimensions.isEmpty()
            || (long) dimensions.get().widthPx() * dimensions.get().heightPx() > maxImagePixels) {
            return Optional.empty();
        }

        return Optional.of(new ValidatedImageFile(
            expectedContentType,
            dimensions.get().widthPx(),
            dimensions.get().heightPx()
        ));
    }

    private boolean hasSupportedRasterHeader(MultipartFile imageFile, String expectedContentType) {
        try (InputStream inputStream = imageFile.getInputStream()) {
            byte[] header = inputStream.readNBytes(32);
            if ("image/png".equals(expectedContentType)) {
                return hasPngHeaderWithDimensions(header);
            }
            if ("image/jpeg".equals(expectedContentType)) {
                return header.length >= 3
                    && (header[0] & 0xff) == 0xff
                    && (header[1] & 0xff) == 0xd8
                    && (header[2] & 0xff) == 0xff;
            }
            return false;
        } catch (IOException | RuntimeException exc) {
            return false;
        }
    }

    private boolean hasPngHeaderWithDimensions(byte[] header) {
        byte[] pngSignature = new byte[] {
            (byte) 0x89,
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a
        };
        if (header.length < 24) {
            return false;
        }
        for (int index = 0; index < pngSignature.length; index += 1) {
            if (header[index] != pngSignature[index]) {
                return false;
            }
        }
        return "IHDR".equals(new String(header, 12, 4, java.nio.charset.StandardCharsets.US_ASCII))
            && readBigEndianInt(header, 16) > 0
            && readBigEndianInt(header, 20) > 0;
    }

    private int readBigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
            | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }

    private boolean hasWebpHeader(MultipartFile imageFile) {
        try (InputStream inputStream = imageFile.getInputStream()) {
            byte[] header = inputStream.readNBytes(12);
            return header.length == 12
                && header[0] == 'R'
                && header[1] == 'I'
                && header[2] == 'F'
                && header[3] == 'F'
                && header[8] == 'W'
                && header[9] == 'E'
                && header[10] == 'B'
                && header[11] == 'P';
        } catch (IOException exc) {
            return false;
        }
    }

    private Optional<ImageDimensions> readImageDimensions(MultipartFile imageFile, String contentType) {
        try (InputStream input = imageFile.getInputStream()) {
            if ("image/png".equals(contentType)) {
                byte[] header = input.readNBytes(24);
                if (!hasPngHeaderWithDimensions(header)) {
                    return Optional.empty();
                }
                return validDimensions(readBigEndianInt(header, 16), readBigEndianInt(header, 20));
            }
            if ("image/webp".equals(contentType)) {
                return readWebpDimensions(input.readNBytes(30));
            }
            if ("image/jpeg".equals(contentType)) {
                return readJpegDimensions(new DataInputStream(input));
            }
            return Optional.empty();
        } catch (IOException | RuntimeException exc) {
            return Optional.empty();
        }
    }

    private Optional<ImageDimensions> readJpegDimensions(DataInputStream input) throws IOException {
        if (input.readUnsignedShort() != 0xffd8) {
            return Optional.empty();
        }
        while (true) {
            int markerPrefix;
            do {
                markerPrefix = input.readUnsignedByte();
            } while (markerPrefix != 0xff);
            int marker;
            do {
                marker = input.readUnsignedByte();
            } while (marker == 0xff);
            if (marker == 0xd9 || marker == 0xda) {
                return Optional.empty();
            }
            if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                continue;
            }
            int segmentLength = input.readUnsignedShort();
            if (segmentLength < 2) {
                return Optional.empty();
            }
            if (isJpegStartOfFrame(marker)) {
                if (segmentLength < 7) {
                    return Optional.empty();
                }
                input.readUnsignedByte();
                int height = input.readUnsignedShort();
                int width = input.readUnsignedShort();
                return validDimensions(width, height);
            }
            input.skipNBytes(segmentLength - 2L);
        }
    }

    private boolean isJpegStartOfFrame(int marker) {
        return (marker >= 0xc0 && marker <= 0xc3)
            || (marker >= 0xc5 && marker <= 0xc7)
            || (marker >= 0xc9 && marker <= 0xcb)
            || (marker >= 0xcd && marker <= 0xcf);
    }

    private Optional<ImageDimensions> readWebpDimensions(byte[] bytes) {
        if (bytes.length < 25 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
            || bytes[8] != 'W' || bytes[9] != 'E' || bytes[10] != 'B' || bytes[11] != 'P') {
            return Optional.empty();
        }
        String chunk = new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        if ("VP8X".equals(chunk) && bytes.length >= 30) {
            return validDimensions(1 + littleEndian24(bytes, 24), 1 + littleEndian24(bytes, 27));
        }
        if ("VP8L".equals(chunk) && bytes.length >= 25 && (bytes[20] & 0xff) == 0x2f) {
            int width = 1 + ((bytes[21] & 0xff) | ((bytes[22] & 0x3f) << 8));
            int height = 1 + (((bytes[22] & 0xc0) >> 6) | ((bytes[23] & 0xff) << 2) | ((bytes[24] & 0x0f) << 10));
            return validDimensions(width, height);
        }
        if ("VP8 ".equals(chunk) && bytes.length >= 30
            && (bytes[23] & 0xff) == 0x9d && (bytes[24] & 0xff) == 0x01 && (bytes[25] & 0xff) == 0x2a) {
            int width = ((bytes[26] & 0xff) | ((bytes[27] & 0x3f) << 8));
            int height = ((bytes[28] & 0xff) | ((bytes[29] & 0x3f) << 8));
            return validDimensions(width, height);
        }
        return Optional.empty();
    }

    private int littleEndian24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
            | ((bytes[offset + 1] & 0xff) << 8)
            | ((bytes[offset + 2] & 0xff) << 16);
    }

    private Optional<ImageDimensions> validDimensions(int width, int height) {
        return width > 0 && height > 0
            ? Optional.of(new ImageDimensions(width, height))
            : Optional.empty();
    }

    private String contentTypeForExtension(String extension) {
        if (extension == null) {
            return null;
        }

        return switch (extension.trim().toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> null;
        };
    }

    private String normalizeContentType(String contentType) {
        if (!isPresent(contentType)) {
            return "";
        }

        int parametersIndex = contentType.indexOf(';');
        String normalized = parametersIndex >= 0
            ? contentType.substring(0, parametersIndex)
            : contentType;
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private MediaStorageProvider.StoredMediaObject copyManagedMedia(
        AdminMediaPersistenceService.ExistingMedia sourceMedia,
        String certId,
        String sideCode,
        String stage
    ) {
        if (!mediaStorageRegistry.supports(sourceMedia.storageProviderCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staged media is stored by an unsupported provider.");
        }

        MediaStorageProvider sourceProvider = mediaStorageRegistry.providerFor(sourceMedia.storageProviderCode());
        MediaStorageProvider targetProvider = mediaStorageRegistry.active();
        MediaStorageProvider.StoredMediaSource source = new MediaStorageProvider.StoredMediaSource(
            sourceMedia.mediaStageCode(),
            sourceMedia.storageBucket(),
            sourceMedia.storageKey(),
            sourceMedia.storageObjectVersion(),
            sourceMedia.originalFilename(),
            sourceMedia.mimeType(),
            sourceMedia.checksumSha256(),
            sourceMedia.widthPx(),
            sourceMedia.heightPx()
        );

        if (sourceProvider == targetProvider) {
            return sourceProvider.copy(stage, certId, sideCode, source);
        }

        MediaStorageProvider.ResolvedMediaAsset sourceAsset = sourceProvider.resolve(
            new MediaStorageProvider.StoredMediaLocation(
                sourceMedia.mediaStageCode(),
                sourceMedia.storageBucket(),
                sourceMedia.storageKey(),
                sourceMedia.storageObjectVersion()
            )
        );
        if (sourceAsset.resource() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The staged media stream is unavailable.");
        }

        long contentLength = sourceMedia.fileSizeBytes() == null ? -1L : sourceMedia.fileSizeBytes();
        if (contentLength <= 0) {
            try {
                contentLength = sourceAsset.resource().contentLength();
            } catch (IOException exc) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to inspect staged media.", exc);
            }
        }
        String originalFilename = isPresent(sourceMedia.originalFilename())
            ? sourceMedia.originalFilename()
            : sourceAsset.filename();
        String contentType = isPresent(sourceMedia.mimeType())
            ? sourceMedia.mimeType()
            : sourceAsset.contentType();

        return targetProvider.store(
            stage,
            certId,
            sideCode,
            extensionFromFilename(sourceMedia.storageKey()),
            new MediaStorageProvider.MediaUpload(
                originalFilename,
                contentType,
                contentLength,
                sourceMedia.widthPx(),
                sourceMedia.heightPx(),
                sourceAsset.resource()::getInputStream
            )
        );
    }

    private String extensionFromFilename(String filename) {
        int extensionIndex = filename == null ? -1 : filename.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == filename.length() - 1) {
            return "jpg";
        }
        return filename.substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
    }

    private void deleteStoredMediaIfReplaced(
        AdminMediaPersistenceService.ExistingMedia existingMedia,
        String currentStorageKey
    ) {
        if (existingMedia == null || !isPresent(existingMedia.storageKey()) || existingMedia.storageKey().equals(currentStorageKey)) {
            return;
        }
        if (!mediaStorageRegistry.supports(existingMedia.storageProviderCode())) {
            return;
        }
        mediaStorageRegistry.providerFor(existingMedia.storageProviderCode()).deleteIfPresent(new MediaStorageProvider.StoredMediaLocation(
            existingMedia.mediaStageCode(),
            existingMedia.storageBucket(),
            existingMedia.storageKey(),
            existingMedia.storageObjectVersion()
        ));
    }

    private void deleteStoredMedia(String stage, MediaStorageProvider.StoredMediaObject storedMediaObject) {
        if (storedMediaObject == null || !mediaStorageRegistry.supports(storedMediaObject.storageProviderCode())) {
            return;
        }
        mediaStorageRegistry.providerFor(storedMediaObject.storageProviderCode()).deleteIfPresent(new MediaStorageProvider.StoredMediaLocation(
            stage,
            storedMediaObject.storageBucket(),
            storedMediaObject.storageKey(),
            storedMediaObject.storageObjectVersion()
        ));
    }

    private void deleteExistingMedia(AdminMediaPersistenceService.ExistingMedia media) {
        if (media == null || !mediaStorageRegistry.supports(media.storageProviderCode())) {
            return;
        }
        mediaStorageRegistry.providerFor(media.storageProviderCode()).deleteIfPresent(
            new MediaStorageProvider.StoredMediaLocation(
                media.mediaStageCode(),
                media.storageBucket(),
                media.storageKey(),
                media.storageObjectVersion()
            )
        );
    }

    private MediaPublishResponse completedResponse(AdminMediaPersistenceService.CompletedPublication completed) {
        AdminMediaPersistenceService.ExistingMedia stagedFront = adminMediaPersistenceService
            .findExistingMedia(completed.submissionId(), "staged", "front")
            .filter(media -> completed.claimedFrontMediaId() != null && media.id() == completed.claimedFrontMediaId())
            .orElse(null);
        AdminMediaPersistenceService.ExistingMedia stagedBack = adminMediaPersistenceService
            .findExistingMedia(completed.submissionId(), "staged", "back")
            .filter(media -> completed.claimedBackMediaId() != null && media.id() == completed.claimedBackMediaId())
            .orElse(null);
        adminMediaPersistenceService.deleteClaimedStagedRecords(
            completed.submissionId(),
            completed.claimedFrontMediaId(),
            completed.claimedBackMediaId()
        );
        deleteExistingMedia(stagedFront);
        deleteExistingMedia(stagedBack);
        return new MediaPublishResponse(
            completed.submissionId(),
            completed.certId(),
            "published",
            completed.completedAt(),
            completed.publishedFrontUrl(),
            completed.publishedBackUrl()
        );
    }

    private String normalizeFilter(String value) {
        return isPresent(value) ? value.trim() : null;
    }

    private String normalizeUploadStatus(String value) {
        String normalized = normalizeFilter(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "not_started", "uploading", "uploaded", "failed", "client_pushed" -> normalized;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported upload status filter.");
        };
    }

    private String normalizeImageStatus(String value) {
        String normalized = normalizeFilter(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ready", "waiting", "published" -> normalized;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image status filter.");
        };
    }

    private static boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record ParsedImportName(String certId, String sideCode, String extension) {
    }

    private record MediaCandidateKey(String certId, String sideCode) {
    }

    private record MediaCandidate(
        String certId,
        String sideCode,
        String extension,
        String contentType,
        int widthPx,
        int heightPx,
        String sourceName,
        MultipartFile multipartFile
    ) {
    }

    private record ValidatedImageFile(String contentType, int widthPx, int heightPx) {
    }

    private record ImageDimensions(int widthPx, int heightPx) {
    }

    private record StoredMediaLookup(
        String storageProviderCode,
        String storageBucket,
        String storageKey,
        String storageObjectVersion
    ) {
    }

    public record MediaQueueResponse(
        List<MediaQueueItem> items,
        MediaQueueSummary summary,
        int page,
        int pageSize,
        int total
    ) {
    }

    public record MediaQueueSummary(
        int trackedEntries,
        int readyToPublish,
        int livePublished,
        int missingMedia
    ) {
    }

    public record MediaQueueItem(
        long submissionId,
        String certId,
        String productType,
        String vintageClassification,
        String merchDescription,
        String cardName,
        String statusCode,
        LocalDateTime approvedAt,
        LocalDateTime publishedAt,
        String uploadStatus,
        LocalDateTime uploadStartedAt,
        LocalDateTime uploadCompletedAt,
        String uploadError,
        java.math.BigDecimal finalGradeValue,
        String finalGradeLabel,
        String stagedFrontUrl,
        String stagedBackUrl,
        String publishedFrontUrl,
        String publishedBackUrl,
        boolean hasStagedFront,
        boolean hasStagedBack,
        boolean hasPublishedFront,
        boolean hasPublishedBack,
        boolean readyToPublish
    ) {
    }

    public record MediaImportResponse(
        int matchedEntries,
        int savedFiles,
        int updatedSides,
        List<String> missingCertIds,
        List<String> invalidNames,
        List<String> duplicateNames,
        List<Long> updatedSubmissionIds
    ) {
    }

    public record MediaPublishResponse(
        long submissionId,
        String certId,
        String statusCode,
        LocalDateTime publishedAt,
        String publishedFrontUrl,
        String publishedBackUrl
    ) {
    }

    public record MediaBatchPublishRequest(List<Long> submissionIds) {
    }

    public record MediaBatchPublishFailure(long submissionId, String message) {
    }

    public record MediaBatchPublishResponse(
        int requestedCount,
        int publishedCount,
        int failedCount,
        List<MediaPublishResponse> published,
        List<MediaBatchPublishFailure> failures
    ) {
    }

    public record ResolvedMediaAsset(Resource resource, String contentType, String filename) {
    }
}
