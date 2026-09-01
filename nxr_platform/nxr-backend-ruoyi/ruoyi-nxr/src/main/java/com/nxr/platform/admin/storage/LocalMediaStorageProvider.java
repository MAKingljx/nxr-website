package com.nxr.platform.admin.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LocalMediaStorageProvider implements MediaStorageProvider {

    private final Path storageRoot;
    private final String mediaPublicBaseUrl;
    private final String storageBucket;

    public LocalMediaStorageProvider(
        @Value("${nxr.media.storage-root:./.local-data/media}") String storageRoot,
        @Value("${nxr.media.public-base-url:http://127.0.0.1:8088}") String mediaPublicBaseUrl,
        @Value("${nxr.media.local-bucket:local-media}") String storageBucket
    ) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.mediaPublicBaseUrl = trimTrailingSlash(mediaPublicBaseUrl);
        this.storageBucket = storageBucket == null || storageBucket.isBlank()
            ? "local-media"
            : storageBucket.trim();
    }

    @Override
    public String providerCode() {
        return "local";
    }

    @Override
    public boolean manages(String storageProviderCode) {
        if (storageProviderCode == null || storageProviderCode.isBlank()) {
            return false;
        }
        String normalized = storageProviderCode.trim().toLowerCase(Locale.ROOT);
        return normalized.equals(providerCode()) || normalized.startsWith(providerCode() + "-");
    }

    @Override
    public StoredMediaObject store(
        String stage,
        String certId,
        String sideCode,
        String extension,
        MediaUpload mediaUpload
    ) {
        String normalizedStage = requireStage(stage);
        String storageKey = generateStorageKey(normalizedStage, certId, sideCode, extension);
        Path outputPath = resolveStagePath(normalizedStage, storageKey);
        Path pendingPath = outputPath.resolveSibling("." + outputPath.getFileName() + ".part");

        try {
            Files.createDirectories(outputPath.getParent());
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            long fileSizeBytes = 0L;
            try (
                InputStream inputStream = mediaUpload.inputStreamSource().openStream();
                DigestInputStream digestInputStream = new DigestInputStream(inputStream, messageDigest);
                OutputStream outputStream = Files.newOutputStream(pendingPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            ) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = digestInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    fileSizeBytes += bytesRead;
                }
            }
            forceFile(pendingPath);
            moveCompleteFile(pendingPath, outputPath);

            return new StoredMediaObject(
                providerCode(),
                storageBucket,
                storageKey,
                buildMediaUrl(normalizedStage, storageKey),
                mediaUpload.originalFilename(),
                mediaUpload.contentType(),
                fileSizeBytes,
                HexFormat.of().formatHex(messageDigest.digest()),
                null,
                mediaUpload.widthPx(),
                mediaUpload.heightPx()
            );
        } catch (IOException exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded media.", exc);
        } catch (NoSuchAlgorithmException exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Missing SHA-256 support.", exc);
        } finally {
            deletePendingFile(pendingPath);
        }
    }

    @Override
    public StoredMediaObject copy(
        String targetStage,
        String certId,
        String sideCode,
        StoredMediaSource source
    ) {
        String normalizedStage = requireStage(targetStage);
        Path sourcePath = resolveStagePath(
            new StoredMediaLocation(
                requireStage(source.stage()),
                source.storageBucket(),
                source.storageKey(),
                source.storageObjectVersion()
            )
        );
        if (!Files.isRegularFile(sourcePath)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staged file is missing on disk.");
        }

        String storageKey = generateStorageKey(
            normalizedStage,
            certId,
            sideCode,
            extractExtension(source.storageKey())
        );
        Path targetPath = resolveStagePath(normalizedStage, storageKey);
        Path pendingPath = targetPath.resolveSibling("." + targetPath.getFileName() + ".part");

        try {
            Files.createDirectories(targetPath.getParent());
            Files.copy(sourcePath, pendingPath);
            forceFile(pendingPath);
            moveCompleteFile(pendingPath, targetPath);
            return new StoredMediaObject(
                providerCode(),
                storageBucket,
                storageKey,
                buildMediaUrl(normalizedStage, storageKey),
                source.originalFilename(),
                source.mimeType(),
                Files.size(targetPath),
                source.checksumSha256(),
                null,
                source.widthPx(),
                source.heightPx()
            );
        } catch (IOException exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to publish staged media.", exc);
        } finally {
            deletePendingFile(pendingPath);
        }
    }

    @Override
    public ResolvedMediaAsset resolve(StoredMediaLocation location) {
        Path assetPath = resolveStagePath(location);
        if (!Files.isRegularFile(assetPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found.");
        }

        try {
            String contentType = Files.probeContentType(assetPath);
            return new ResolvedMediaAsset(
                new FileSystemResource(assetPath),
                contentType == null ? "application/octet-stream" : contentType,
                location.storageKey()
            );
        } catch (IOException exc) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to load media asset.", exc);
        }
    }

    @Override
    public void deleteIfPresent(StoredMediaLocation location) {
        Path assetPath = resolveStagePath(location);
        try {
            Files.deleteIfExists(assetPath);
        } catch (IOException ignored) {
        }
    }

    private Path resolveStagePath(String stage, String storageKey) {
        return resolveStagePath(new StoredMediaLocation(stage, storageBucket, storageKey, null));
    }

    private Path resolveStagePath(StoredMediaLocation location) {
        if (location == null || !isSafeStorageKey(location.storageKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found.");
        }
        if (location.storageBucket() != null
            && !location.storageBucket().isBlank()
            && !storageBucket.equals(location.storageBucket().trim())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found.");
        }

        Path stageRoot = storageRoot.resolve(requireStage(location.stage())).normalize();
        Path assetPath = stageRoot.resolve(location.storageKey()).normalize();
        if (!assetPath.startsWith(storageRoot)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found.");
        }
        return assetPath;
    }

    private String generateStorageKey(String stage, String certId, String sideCode, String extension) {
        String sanitizedCertId = certId == null
            ? "media"
            : certId.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        String sanitizedSideCode = sideCode == null
            ? "asset"
            : sideCode.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        String sanitizedExtension = normalizeExtension(extension);
        return stage
            + "_"
            + sanitizedCertId
            + "_"
            + sanitizedSideCode
            + "_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            + "."
            + sanitizedExtension;
    }

    private String buildMediaUrl(String stage, String storageKey) {
        return mediaPublicBaseUrl + "/media/" + requireStage(stage) + "/" + storageKey;
    }

    private boolean isSafeStorageKey(String storageKey) {
        return storageKey != null
            && !storageKey.isBlank()
            && !storageKey.contains("/")
            && !storageKey.contains("\\");
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

    private String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return "jpg";
        }
        return extension.replace(".", "").trim().toLowerCase(Locale.ROOT);
    }

    private String extractExtension(String storageKey) {
        int extensionIndex = storageKey.lastIndexOf('.');
        if (extensionIndex <= -1 || extensionIndex == storageKey.length() - 1) {
            return "jpg";
        }
        return normalizeExtension(storageKey.substring(extensionIndex + 1));
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void moveCompleteFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private void deletePendingFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A random private .part name is never exposed through a media URL.
        }
    }
}
