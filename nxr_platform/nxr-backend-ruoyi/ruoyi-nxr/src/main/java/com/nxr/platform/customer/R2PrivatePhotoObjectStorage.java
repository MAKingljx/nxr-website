package com.nxr.platform.customer;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** R2-backed original and preview storage for customer and sub-agent uploads. */
@Component
public class R2PrivatePhotoObjectStorage implements PrivatePhotoObjectStorage {
    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String prefix;
    private volatile S3Client client;

    public R2PrivatePhotoObjectStorage(
        @Value("${nxr.media.r2.endpoint:}") String endpoint,
        @Value("${nxr.media.r2.region:auto}") String region,
        @Value("${nxr.media.r2.private-bucket:}") String bucket,
        @Value("${nxr.media.r2.access-key-id:}") String accessKeyId,
        @Value("${nxr.media.r2.secret-access-key:}") String secretAccessKey,
        @Value("${nxr.media.r2.private-photo-prefix:private/customer-uploads}") String prefix
    ) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.region = region == null || region.isBlank() ? "auto" : region.trim();
        this.bucket = bucket == null ? "" : bucket.trim();
        this.accessKeyId = accessKeyId == null ? "" : accessKeyId.trim();
        this.secretAccessKey = secretAccessKey == null ? "" : secretAccessKey.trim();
        this.prefix = normalizePrefix(prefix);
    }

    @Override
    public boolean configured() {
        return endpoint.startsWith("https://") && !bucket.isBlank()
            && !accessKeyId.isBlank() && !secretAccessKey.isBlank();
    }

    @Override
    public void requireConfigured() {
        if (!configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Private R2 photo storage is not configured");
        }
    }

    @Override
    public void store(String id, byte[] original, byte[] preview, String contentType, String checksumSha256) {
        requireConfigured();
        String originalKey = key(id, false);
        String previewKey = key(id, true);
        try {
            put(originalKey, original, contentType, Map.of("sha256", checksumSha256));
            put(previewKey, preview, "image/jpeg", Map.of());
            verify(originalKey, original.length, checksumSha256);
            verify(previewKey, preview.length, null);
            if (!checksumSha256.equals(digest(readBytes(originalKey)))
                || !digest(preview).equals(digest(readBytes(previewKey)))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Private R2 photo readback failed");
            }
        } catch (Exception failure) {
            try { delete(id); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw unavailable("Private R2 photo upload failed", failure);
        }
    }

    @Override
    public Resource read(String id, boolean original) {
        requireConfigured();
        try {
            return new ByteArrayResource(readBytes(key(id, !original)));
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found", failure);
            }
            throw unavailable("Private R2 photo read failed", failure);
        } catch (Exception failure) {
            throw unavailable("Private R2 photo read failed", failure);
        }
    }

    @Override
    public void delete(String id) {
        requireConfigured();
        RuntimeException failure = null;
        for (boolean preview : new boolean[] {false, true}) {
            try {
                client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket).key(key(id, preview)).build());
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private void put(String key, byte[] content, String contentType, Map<String, String> metadata) {
        client().putObject(PutObjectRequest.builder().bucket(bucket).key(key)
            .contentType(contentType).metadata(metadata).build(), RequestBody.fromBytes(content));
    }

    private byte[] readBytes(String key) {
        return client().getObjectAsBytes(GetObjectRequest.builder()
            .bucket(bucket).key(key).build()).asByteArray();
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void verify(String key, int expectedBytes, String expectedChecksum) {
        var head = client().headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        if (head.contentLength() == null || head.contentLength() != expectedBytes
            || (expectedChecksum != null && !expectedChecksum.equals(head.metadata().get("sha256")))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Private R2 photo verification failed");
        }
    }

    private String key(String id, boolean preview) {
        try { UUID.fromString(id); }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found", invalid);
        }
        if (!id.matches("[a-f0-9-]{36}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
        }
        return prefix + "/" + id + (preview ? ".preview.jpg" : ".original");
    }

    private S3Client client() {
        S3Client result = client;
        if (result == null) {
            synchronized (this) {
                result = client;
                if (result == null) {
                    requireConfigured();
                    result = S3Client.builder().endpointOverride(URI.create(endpoint))
                        .region(Region.of(region))
                        .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(45)))
                        .build();
                    client = result;
                }
            }
        }
        return result;
    }

    @PreDestroy
    public void close() {
        S3Client current = client;
        if (current != null) current.close();
    }

    private static String normalizePrefix(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("^/+|/+$", "");
        if (!value.matches("[A-Za-z0-9/_-]{1,100}") || value.contains("//") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid private R2 photo prefix");
        }
        return value;
    }

    private static ResponseStatusException unavailable(String message, Exception failure) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, failure);
    }
}
