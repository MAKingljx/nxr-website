package com.nxr.platform.admin;

import com.nxr.platform.admin.storage.MediaStorageProvider;
import com.nxr.platform.shared.ProductTypePolicy;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private final MediaStorageProvider mediaStorageProvider;
    private final int maxImportFiles;
    private final long maxImportFileSizeBytes;

    public AdminMediaService(
        JdbcClient jdbcClient,
        AdminMediaPersistenceService adminMediaPersistenceService,
        MediaStorageProvider mediaStorageProvider,
        @Value("${nxr.media.import.max-files:1000}") int maxImportFiles,
        @Value("${nxr.media.import.max-file-size-bytes:52428800}") long maxImportFileSizeBytes
    ) {
        this.jdbcClient = jdbcClient;
        this.adminMediaPersistenceService = adminMediaPersistenceService;
        this.mediaStorageProvider = mediaStorageProvider;
        this.maxImportFiles = Math.max(maxImportFiles, 1);
        this.maxImportFileSizeBytes = Math.max(maxImportFileSizeBytes, 1024L);
    }

    @Transactional(readOnly = true)
    public MediaQueueResponse loadQueue(String query, int page, int pageSize) {
        String normalizedQuery = normalizeFilter(query);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), 100);
        int normalizedPage = Math.max(page, 1);
        int total = jdbcClient.sql(
                """
                SELECT COUNT(*)
                FROM grading_submission s
                LEFT JOIN grading_score g ON g.submission_id = s.id
                WHERE s.status_code IN ('approved', 'published')
                  AND (
                    :query IS NULL
                    OR UPPER(s.cert_id) LIKE :query
                    OR UPPER(s.card_name) LIKE :query
                    OR UPPER(s.set_name) LIKE :query
                  )
                """
            )
            .param("query", normalizedQuery == null ? null : "%" + normalizedQuery.toUpperCase(Locale.ROOT) + "%")
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
                WHERE s.status_code IN ('approved', 'published')
                  AND (
                    :query IS NULL
                    OR UPPER(s.cert_id) LIKE :query
                    OR UPPER(s.card_name) LIKE :query
                    OR UPPER(s.set_name) LIKE :query
                  )
                """
            )
            .param("query", normalizedQuery == null ? null : "%" + normalizedQuery.toUpperCase(Locale.ROOT) + "%")
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
                WHERE s.status_code IN ('approved', 'published')
                  AND (
                    :query IS NULL
                    OR UPPER(s.cert_id) LIKE :query
                    OR UPPER(s.card_name) LIKE :query
                    OR UPPER(s.set_name) LIKE :query
                  )
                ORDER BY COALESCE(s.approved_at, s.published_at, s.updated_at) DESC, s.id DESC
                LIMIT :limit OFFSET :offset
                """
            )
            .param("query", normalizedQuery == null ? null : "%" + normalizedQuery.toUpperCase(Locale.ROOT) + "%")
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

        Map<String, Long> submissionByCertId = adminMediaPersistenceService.loadSubmissionIdsForImport(
            candidates.keySet().stream().map(MediaCandidateKey::certId).toList()
        );
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
        AdminMediaPersistenceService.SubmissionForPublish submission =
            adminMediaPersistenceService.loadSubmissionForPublish(submissionId);

        AdminMediaPersistenceService.ExistingMedia stagedFront = adminMediaPersistenceService
            .findExistingMedia(submissionId, "staged", "front")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Front staged media is required."));
        AdminMediaPersistenceService.ExistingMedia stagedBack = adminMediaPersistenceService
            .findExistingMedia(submissionId, "staged", "back")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Back staged media is required."));

        MediaStorageProvider.StoredMediaObject publishedFront = null;
        MediaStorageProvider.StoredMediaObject publishedBack = null;
        AdminMediaPersistenceService.MediaPublishTransactionResult publishResult;
        try {
            publishedFront = copyManagedMedia(stagedFront, submission.certId(), "front", "published");
            publishedBack = copyManagedMedia(stagedBack, submission.certId(), "back", "published");
            publishResult = adminMediaPersistenceService.publishSubmissionRecords(
                submissionId,
                submission.certId(),
                stagedFront,
                stagedBack,
                publishedFront,
                publishedBack
            );
        } catch (RuntimeException exc) {
            deleteStoredMedia("published", publishedFront);
            deleteStoredMedia("published", publishedBack);
            throw exc;
        }

        deleteStoredMediaIfReplaced(publishResult.replacedFrontMedia(), publishedFront.storageKey());
        deleteStoredMediaIfReplaced(publishResult.replacedBackMedia(), publishedBack.storageKey());

        return new MediaPublishResponse(
            submissionId,
            submission.certId(),
            "published",
            publishResult.publishedAt(),
            publishedFront.publicUrl(),
            publishedBack.publicUrl()
        );
    }

    public MediaBatchPublishResponse publishSubmissions(List<Long> submissionIds) {
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
                published.add(publishSubmission(submissionId));
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

        if (!mediaStorageProvider.manages(media.storageProviderCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found.");
        }

        MediaStorageProvider.ResolvedMediaAsset asset = mediaStorageProvider.resolve(
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
        return mediaStorageProvider.store(
            stage,
            candidate.certId(),
            candidate.sideCode(),
            candidate.extension(),
            new MediaStorageProvider.MediaUpload(
                candidate.sourceName(),
                candidate.contentType(),
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

        return Optional.of(new ValidatedImageFile(expectedContentType));
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
        if (!mediaStorageProvider.manages(sourceMedia.storageProviderCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staged media is stored by an unsupported provider.");
        }

        return mediaStorageProvider.copy(
            stage,
            certId,
            sideCode,
            new MediaStorageProvider.StoredMediaSource(
                sourceMedia.mediaStageCode(),
                sourceMedia.storageBucket(),
                sourceMedia.storageKey(),
                sourceMedia.storageObjectVersion(),
                sourceMedia.originalFilename(),
                sourceMedia.mimeType(),
                sourceMedia.checksumSha256()
            )
        );
    }

    private void deleteStoredMediaIfReplaced(
        AdminMediaPersistenceService.ExistingMedia existingMedia,
        String currentStorageKey
    ) {
        if (existingMedia == null || !isPresent(existingMedia.storageKey()) || existingMedia.storageKey().equals(currentStorageKey)) {
            return;
        }
        if (!mediaStorageProvider.manages(existingMedia.storageProviderCode())) {
            return;
        }
        mediaStorageProvider.deleteIfPresent(new MediaStorageProvider.StoredMediaLocation(
            existingMedia.mediaStageCode(),
            existingMedia.storageBucket(),
            existingMedia.storageKey(),
            existingMedia.storageObjectVersion()
        ));
    }

    private void deleteStoredMedia(String stage, MediaStorageProvider.StoredMediaObject storedMediaObject) {
        if (storedMediaObject == null || !mediaStorageProvider.manages(storedMediaObject.storageProviderCode())) {
            return;
        }
        mediaStorageProvider.deleteIfPresent(new MediaStorageProvider.StoredMediaLocation(
            stage,
            storedMediaObject.storageBucket(),
            storedMediaObject.storageKey(),
            storedMediaObject.storageObjectVersion()
        ));
    }

    private String normalizeFilter(String value) {
        return isPresent(value) ? value.trim() : null;
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
        String sourceName,
        MultipartFile multipartFile
    ) {
    }

    private record ValidatedImageFile(String contentType) {
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
