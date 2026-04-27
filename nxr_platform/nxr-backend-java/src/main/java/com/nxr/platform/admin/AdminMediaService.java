package com.nxr.platform.admin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminMediaService {

    private static final Pattern IMPORT_FILE_PATTERN = Pattern.compile(
        "(^|[\\\\/])(?<certId>[A-Za-z0-9]+)_(?<side>[AB])(?:_\\d+)?\\.(?<ext>webp|jpg|jpeg|png)$",
        Pattern.CASE_INSENSITIVE
    );

    private final JdbcClient jdbcClient;
    private final Path storageRoot;
    private final String mediaPublicBaseUrl;
    private final String publicSiteBaseUrl;

    public AdminMediaService(
        JdbcClient jdbcClient,
        @Value("${nxr.media.storage-root:./.local-data/media}") String storageRoot,
        @Value("${nxr.media.public-base-url:http://127.0.0.1:8088}") String mediaPublicBaseUrl,
        @Value("${nxr.public-site.base-url:http://127.0.0.1:3000}") String publicSiteBaseUrl
    ) {
        this.jdbcClient = jdbcClient;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.mediaPublicBaseUrl = trimTrailingSlash(mediaPublicBaseUrl);
        this.publicSiteBaseUrl = trimTrailingSlash(publicSiteBaseUrl);
    }

    public MediaQueueResponse loadQueue(String query) {
        String normalizedQuery = normalizeFilter(query);
        List<MediaQueueItem> items = jdbcClient.sql(
                """
                SELECT
                    s.id,
                    s.cert_id,
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
                JOIN grading_score g ON g.submission_id = s.id
                WHERE s.status_code IN ('approved', 'published')
                  AND (
                    :query IS NULL
                    OR UPPER(s.cert_id) LIKE :query
                    OR UPPER(s.card_name) LIKE :query
                    OR UPPER(s.set_name) LIKE :query
                  )
                ORDER BY COALESCE(s.approved_at, s.published_at, s.updated_at) DESC, s.id DESC
                LIMIT 40
                """
            )
            .param("query", normalizedQuery == null ? null : "%" + normalizedQuery.toUpperCase(Locale.ROOT) + "%")
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

        int readyToPublish = 0;
        int livePublished = 0;
        int missingMedia = 0;
        for (MediaQueueItem item : items) {
            if (item.readyToPublish()) {
                readyToPublish += 1;
            }
            if (item.hasPublishedFront() && item.hasPublishedBack()) {
                livePublished += 1;
            }
            if (!(item.hasStagedFront() && item.hasStagedBack())
                && !(item.hasPublishedFront() && item.hasPublishedBack())) {
                missingMedia += 1;
            }
        }

        return new MediaQueueResponse(
            items,
            new MediaQueueSummary(items.size(), readyToPublish, livePublished, missingMedia)
        );
    }

    public MediaImportResponse importFolder(List<MultipartFile> imageFiles) {
        List<MultipartFile> uploadedFiles = imageFiles == null
            ? List.of()
            : imageFiles.stream().filter(file -> file != null && isPresent(file.getOriginalFilename())).toList();

        if (uploadedFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an image folder first.");
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

            candidates.put(key, new MediaCandidate(
                key.certId(),
                key.sideCode(),
                parsed.get().extension(),
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

        Map<String, Long> submissionByCertId = loadSubmissionIdsForImport(candidates.keySet().stream().map(MediaCandidateKey::certId).toList());
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

            StoredMediaFile storedFile = persistUploadedFile(candidate, "staged");
            ExistingMedia existingMedia = findExistingMedia(submissionId, "staged", candidate.sideCode()).orElse(null);
            upsertMediaRecord(
                existingMedia,
                submissionId,
                candidate.certId(),
                candidate.sideCode(),
                "staged",
                "local-staged",
                storedFile
            );
            deleteStoredMediaIfReplaced(existingMedia, storedFile.storageKey());

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
        SubmissionForPublish submission = jdbcClient.sql(
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

        ExistingMedia stagedFront = findExistingMedia(submissionId, "staged", "front")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Front staged media is required."));
        ExistingMedia stagedBack = findExistingMedia(submissionId, "staged", "back")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Back staged media is required."));

        StoredMediaFile publishedFront = copyStoredMedia(stagedFront, submission.certId(), "front", "published");
        StoredMediaFile publishedBack = copyStoredMedia(stagedBack, submission.certId(), "back", "published");

        ExistingMedia existingPublishedFront = findExistingMedia(submissionId, "published", "front").orElse(null);
        ExistingMedia existingPublishedBack = findExistingMedia(submissionId, "published", "back").orElse(null);

        upsertMediaRecord(existingPublishedFront, submissionId, submission.certId(), "front", "published", "local-published", publishedFront);
        upsertMediaRecord(existingPublishedBack, submissionId, submission.certId(), "back", "published", "local-published", publishedBack);
        deleteStoredMediaIfReplaced(existingPublishedFront, publishedFront.storageKey());
        deleteStoredMediaIfReplaced(existingPublishedBack, publishedBack.storageKey());

        LocalDateTime publishedAt = LocalDateTime.now();
        int publishedCertificateCount = jdbcClient.sql(
                "SELECT COUNT(*) FROM published_certificate WHERE submission_id = :submissionId"
            )
            .param("submissionId", submissionId)
            .query(Integer.class)
            .single();

        if (publishedCertificateCount == 0) {
            jdbcClient.sql(
                    """
                    INSERT INTO published_certificate (
                        submission_id,
                        cert_id,
                        verification_slug,
                        qr_url,
                        published_at
                    ) VALUES (
                        :submissionId,
                        :certId,
                        :verificationSlug,
                        :qrUrl,
                        :publishedAt
                    )
                    """
                )
                .params(Map.of(
                    "submissionId", submissionId,
                    "certId", submission.certId(),
                    "verificationSlug", submission.certId().toLowerCase(Locale.ROOT),
                    "qrUrl", publicSiteBaseUrl + "/card/" + submission.certId(),
                    "publishedAt", publishedAt
                ))
                .update();
        } else {
            jdbcClient.sql(
                    """
                    UPDATE published_certificate
                    SET cert_id = :certId,
                        qr_url = :qrUrl,
                        published_at = :publishedAt,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE submission_id = :submissionId
                    """
                )
                .params(Map.of(
                    "submissionId", submissionId,
                    "certId", submission.certId(),
                    "qrUrl", publicSiteBaseUrl + "/card/" + submission.certId(),
                    "publishedAt", publishedAt
                ))
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

        return new MediaPublishResponse(
            submissionId,
            submission.certId(),
            "published",
            publishedAt,
            publishedFront.publicUrl(),
            publishedBack.publicUrl()
        );
    }

    public ResolvedMediaAsset resolveMediaAsset(String stage, String filename) {
        String normalizedStage = normalizeStage(stage);
        if (normalizedStage == null || !isPresent(filename) || filename.contains("/") || filename.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found.");
        }

        Path assetPath = storageRoot.resolve(normalizedStage).resolve(filename).normalize();
        if (!assetPath.startsWith(storageRoot) || !Files.isRegularFile(assetPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found.");
        }

        try {
            String contentType = Files.probeContentType(assetPath);
            return new ResolvedMediaAsset(assetPath, contentType == null ? "application/octet-stream" : contentType);
        } catch (IOException exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to load media asset.", exc);
        }
    }

    private Map<String, Long> loadSubmissionIdsForImport(List<String> certIds) {
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

    private StoredMediaFile persistUploadedFile(MediaCandidate candidate, String stage) {
        String normalizedStage = requireStage(stage);
        String sanitizedCertId = candidate.certId().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        String storageKey = normalizedStage
            + "_"
            + sanitizedCertId
            + "_"
            + candidate.sideCode()
            + "_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            + "."
            + candidate.extension();
        Path stageDirectory = storageRoot.resolve(normalizedStage);
        Path outputPath = stageDirectory.resolve(storageKey).normalize();

        try {
            Files.createDirectories(stageDirectory);
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            long fileSizeBytes = 0L;
            try (
                InputStream inputStream = candidate.multipartFile().getInputStream();
                DigestInputStream digestInputStream = new DigestInputStream(inputStream, messageDigest);
                OutputStream outputStream = Files.newOutputStream(outputPath)
            ) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = digestInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    fileSizeBytes += bytesRead;
                }
            }

            return new StoredMediaFile(
                storageKey,
                buildMediaUrl(normalizedStage, storageKey),
                candidate.sourceName(),
                candidate.multipartFile().getContentType(),
                fileSizeBytes,
                HexFormat.of().formatHex(messageDigest.digest())
            );
        } catch (IOException exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded media.", exc);
        } catch (NoSuchAlgorithmException exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Missing SHA-256 support.", exc);
        }
    }

    private StoredMediaFile copyStoredMedia(ExistingMedia sourceMedia, String certId, String sideCode, String stage) {
        String normalizedStage = requireStage(stage);
        Path sourcePath = storageRoot.resolve(requireStage(sourceMedia.mediaStageCode())).resolve(sourceMedia.storageKey()).normalize();
        if (!Files.isRegularFile(sourcePath)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staged file is missing on disk.");
        }

        String extension = extractExtension(sourceMedia.storageKey());
        String sanitizedCertId = certId.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        String targetStorageKey = normalizedStage
            + "_"
            + sanitizedCertId
            + "_"
            + sideCode
            + "_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            + "."
            + extension;
        Path targetDirectory = storageRoot.resolve(normalizedStage);
        Path targetPath = targetDirectory.resolve(targetStorageKey).normalize();

        try {
            Files.createDirectories(targetDirectory);
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            long fileSizeBytes = Files.size(targetPath);
            return new StoredMediaFile(
                targetStorageKey,
                buildMediaUrl(normalizedStage, targetStorageKey),
                sourceMedia.originalFilename(),
                sourceMedia.mimeType(),
                fileSizeBytes,
                sourceMedia.checksumSha256()
            );
        } catch (IOException exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to publish staged media.", exc);
        }
    }

    private Optional<ExistingMedia> findExistingMedia(long submissionId, String stage, String sideCode) {
        return jdbcClient.sql(
                """
                SELECT
                    id,
                    media_stage_code,
                    storage_provider_code,
                    storage_key,
                    public_url,
                    original_filename,
                    mime_type,
                    file_size_bytes,
                    checksum_sha256
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
                rs.getString("storage_key"),
                rs.getString("public_url"),
                rs.getString("original_filename"),
                rs.getString("mime_type"),
                rs.getObject("file_size_bytes", Long.class),
                rs.getString("checksum_sha256")
            ))
            .optional();
    }

    private void upsertMediaRecord(
        ExistingMedia existingMedia,
        long submissionId,
        String certId,
        String sideCode,
        String stage,
        String storageProviderCode,
        StoredMediaFile storedFile
    ) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("submissionId", submissionId);
        params.put("certId", certId);
        params.put("sideCode", sideCode);
        params.put("stage", requireStage(stage));
        params.put("storageProviderCode", storageProviderCode);
        params.put("storageKey", storedFile.storageKey());
        params.put("publicUrl", storedFile.publicUrl());
        params.put("originalFilename", storedFile.originalFilename());
        params.put("mimeType", storedFile.mimeType());
        params.put("fileSizeBytes", storedFile.fileSizeBytes());
        params.put("checksumSha256", storedFile.checksumSha256());

        if (existingMedia == null) {
            jdbcClient.sql(
                    """
                    INSERT INTO submission_media (
                        submission_id,
                        cert_id,
                        media_side_code,
                        media_stage_code,
                        storage_provider_code,
                        storage_key,
                        public_url,
                        sort_order,
                        is_active,
                        original_filename,
                        mime_type,
                        file_size_bytes,
                        checksum_sha256
                    ) VALUES (
                        :submissionId,
                        :certId,
                        :sideCode,
                        :stage,
                        :storageProviderCode,
                        :storageKey,
                        :publicUrl,
                        1,
                        1,
                        :originalFilename,
                        :mimeType,
                        :fileSizeBytes,
                        :checksumSha256
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
                    storage_key = :storageKey,
                    public_url = :publicUrl,
                    sort_order = 1,
                    is_active = 1,
                    original_filename = :originalFilename,
                    mime_type = :mimeType,
                    file_size_bytes = :fileSizeBytes,
                    checksum_sha256 = :checksumSha256,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :mediaId
                """
            )
            .params(params)
            .update();
    }

    private void deleteStoredMediaIfReplaced(ExistingMedia existingMedia, String currentStorageKey) {
        if (existingMedia == null || !isPresent(existingMedia.storageKey()) || existingMedia.storageKey().equals(currentStorageKey)) {
            return;
        }

        String normalizedStage = normalizeStage(existingMedia.mediaStageCode());
        if (normalizedStage == null || !existingMedia.storageProviderCode().startsWith("local-")) {
            return;
        }

        Path oldFile = storageRoot.resolve(normalizedStage).resolve(existingMedia.storageKey()).normalize();
        try {
            if (oldFile.startsWith(storageRoot)) {
                Files.deleteIfExists(oldFile);
            }
        } catch (IOException ignored) {
        }
    }

    private String buildMediaUrl(String stage, String storageKey) {
        return mediaPublicBaseUrl + "/media/" + requireStage(stage) + "/" + storageKey;
    }

    private String normalizeFilter(String value) {
        return isPresent(value) ? value.trim() : null;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalizeStage(String stage) {
        if (!isPresent(stage)) {
            return null;
        }

        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "staged", "published" -> normalized;
            default -> null;
        };
    }

    private String requireStage(String stage) {
        String normalized = normalizeStage(stage);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported media stage.");
        }
        return normalized;
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String extractExtension(String storageKey) {
        int extensionIndex = storageKey.lastIndexOf('.');
        if (extensionIndex <= -1 || extensionIndex == storageKey.length() - 1) {
            return "jpg";
        }
        return storageKey.substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
    }

    private record ParsedImportName(String certId, String sideCode, String extension) {
    }

    private record MediaCandidateKey(String certId, String sideCode) {
    }

    private record MediaCandidate(
        String certId,
        String sideCode,
        String extension,
        String sourceName,
        MultipartFile multipartFile
    ) {
    }

    private record ExistingMedia(
        long id,
        String mediaStageCode,
        String storageProviderCode,
        String storageKey,
        String publicUrl,
        String originalFilename,
        String mimeType,
        Long fileSizeBytes,
        String checksumSha256
    ) {
    }

    private record SubmissionForPublish(long id, String certId, String statusCode) {
    }

    private record StoredMediaFile(
        String storageKey,
        String publicUrl,
        String originalFilename,
        String mimeType,
        long fileSizeBytes,
        String checksumSha256
    ) {
    }

    public record MediaQueueResponse(List<MediaQueueItem> items, MediaQueueSummary summary) {
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

    public record ResolvedMediaAsset(Path path, String contentType) {
    }
}
