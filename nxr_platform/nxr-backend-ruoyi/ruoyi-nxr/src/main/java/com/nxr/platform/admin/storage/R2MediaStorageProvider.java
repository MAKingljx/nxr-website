package com.nxr.platform.admin.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Cloudflare R2 adapter. It is initialized lazily so local-only development needs no R2 credentials. */
@Component
public class R2MediaStorageProvider implements MediaStorageProvider {

    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String publicBaseUrl;
    private final String objectPrefix;
    private final String stagingPrefix;
    private volatile S3Client client;

    public R2MediaStorageProvider(
        @Value("${nxr.media.r2.endpoint:}") String endpoint,
        @Value("${nxr.media.r2.region:auto}") String region,
        @Value("${nxr.media.r2.bucket:}") String bucket,
        @Value("${nxr.media.r2.access-key-id:}") String accessKeyId,
        @Value("${nxr.media.r2.secret-access-key:}") String secretAccessKey,
        @Value("${nxr.media.r2.public-base-url:}") String publicBaseUrl,
        @Value("${nxr.media.r2.object-prefix:cards}") String objectPrefix,
        @Value("${nxr.media.r2.staging-prefix:staging}") String stagingPrefix
    ) {
        this.endpoint = trimSlash(endpoint);
        this.region = isPresent(region) ? region.trim() : "auto";
        this.bucket = bucket == null ? "" : bucket.trim();
        this.accessKeyId = accessKeyId == null ? "" : accessKeyId.trim();
        this.secretAccessKey = secretAccessKey == null ? "" : secretAccessKey.trim();
        this.publicBaseUrl = trimSlash(publicBaseUrl);
        this.objectPrefix = trimPath(objectPrefix, "cards");
        this.stagingPrefix = trimPath(stagingPrefix, "staging");
    }

    @Override
    public String providerCode() {
        return "r2";
    }

    @Override
    public boolean manages(String storageProviderCode) {
        if (!isPresent(storageProviderCode)) {
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
        String key = generateKey(stage, certId, sideCode, extension);
        Path pending = null;
        try {
            pending = Files.createTempFile("nxr-r2-media-", ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (
                InputStream input = mediaUpload.inputStreamSource().openStream();
                DigestInputStream digestInput = new DigestInputStream(input, digest);
                OutputStream output = Files.newOutputStream(pending)
            ) {
                copied = digestInput.transferTo(output);
            }
            if (mediaUpload.contentLength() >= 0 && copied != mediaUpload.contentLength()) {
                throw new IOException("Media stream length changed during upload");
            }

            PutObjectResponse response = client().putObject(
                PutObjectRequest.builder()
                    .bucket(require(bucket, "R2_BUCKET"))
                    .key(key)
                    .contentType(mediaUpload.contentType())
                    .build(),
                RequestBody.fromFile(pending)
            );
            return new StoredMediaObject(
                providerCode(),
                bucket,
                key,
                publicUrl(key),
                mediaUpload.originalFilename(),
                mediaUpload.contentType(),
                copied,
                HexFormat.of().formatHex(digest.digest()),
                response.versionId(),
                mediaUpload.widthPx(),
                mediaUpload.heightPx()
            );
        } catch (IOException | NoSuchAlgorithmException | S3Exception exc) {
            throw storageFailure("Failed to upload media to R2.", exc);
        } finally {
            deleteTemp(pending);
        }
    }

    @Override
    public StoredMediaObject copy(
        String targetStage,
        String certId,
        String sideCode,
        StoredMediaSource source
    ) {
        String sourceBucket = isPresent(source.storageBucket()) ? source.storageBucket().trim() : bucket;
        String targetKey = generateKey(targetStage, certId, sideCode, extension(source.storageKey()));
        try {
            CopyObjectResponse response = client().copyObject(
                CopyObjectRequest.builder()
                    .copySource(encodeCopySource(sourceBucket, source.storageKey()))
                    .destinationBucket(require(bucket, "R2_BUCKET"))
                    .destinationKey(targetKey)
                    .build()
            );
            HeadObjectResponse head = client().headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(targetKey)
                .build());
            return new StoredMediaObject(
                providerCode(),
                bucket,
                targetKey,
                publicUrl(targetKey),
                source.originalFilename(),
                source.mimeType(),
                head.contentLength() == null ? 0L : head.contentLength(),
                source.checksumSha256(),
                response.versionId(),
                source.widthPx(),
                source.heightPx()
            );
        } catch (S3Exception exc) {
            throw storageFailure("Failed to publish staged media to R2.", exc);
        }
    }

    @Override
    public ResolvedMediaAsset resolve(StoredMediaLocation location) {
        try {
            return new ResolvedMediaAsset(
                new UrlResource(publicUrl(location.storageKey())),
                contentType(location.storageKey()),
                filename(location.storageKey())
            );
        } catch (Exception exc) {
            throw storageFailure("Failed to resolve R2 media.", exc);
        }
    }

    @Override
    public void deleteIfPresent(StoredMediaLocation location) {
        if (location == null || !isPresent(location.storageKey())) {
            return;
        }
        try {
            client().deleteObject(DeleteObjectRequest.builder()
                .bucket(isPresent(location.storageBucket()) ? location.storageBucket() : bucket)
                .key(location.storageKey())
                .build());
        } catch (S3Exception ignored) {
            // Cleanup is best effort; the database never points at a partially published object.
        }
    }

    private S3Client client() {
        S3Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                client = S3Client.builder()
                    .endpointOverride(URI.create(require(endpoint, "R2_ENDPOINT")))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        require(accessKeyId, "R2_ACCESS_KEY_ID"),
                        require(secretAccessKey, "R2_SECRET_ACCESS_KEY")
                    )))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .build();
            }
            return client;
        }
    }

    private String generateKey(String stage, String certId, String sideCode, String extension) {
        String prefix = "published".equalsIgnoreCase(stage) ? objectPrefix : stagingPrefix;
        String cert = safePart(certId, "card");
        String side = safePart(sideCode, "image");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return prefix + "/" + cert + "/" + cert + "_" + side + "_" + suffix + "." + safeExtension(extension);
    }

    private String publicUrl(String key) {
        return require(publicBaseUrl, "R2_PUBLIC_BASE_URL") + "/" + encodePath(key);
    }

    private String encodeCopySource(String sourceBucket, String sourceKey) {
        return encodePath(sourceBucket + "/" + sourceKey);
    }

    private String encodePath(String value) {
        String[] parts = value.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (String part : parts) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    private String safePart(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        normalized = normalized.replaceAll("^[._-]+|[._-]+$", "");
        return normalized.isBlank() ? fallback : normalized;
    }

    private String safeExtension(String value) {
        String normalized = value == null ? "jpg" : value.replace(".", "").trim().toLowerCase(Locale.ROOT);
        return normalized.matches("webp|jpe?g|png") ? normalized : "jpg";
    }

    private String extension(String key) {
        int index = key == null ? -1 : key.lastIndexOf('.');
        return index < 0 ? "jpg" : key.substring(index + 1);
    }

    private String filename(String key) {
        int index = key == null ? -1 : key.lastIndexOf('/');
        return index < 0 ? key : key.substring(index + 1);
    }

    private String contentType(String key) {
        return switch (safeExtension(extension(key))) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    private String require(String value, String environmentName) {
        if (!isPresent(value)) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                environmentName + " is required when NXR_STORAGE_DRIVER=r2"
            );
        }
        return value.trim();
    }

    private ResponseStatusException storageFailure(String message, Exception cause) {
        if (cause instanceof ResponseStatusException responseStatusException) {
            return responseStatusException;
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, cause);
    }

    private void deleteTemp(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private String trimSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String trimPath(String value, String fallback) {
        String normalized = trimSlash(value);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? fallback : normalized;
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
