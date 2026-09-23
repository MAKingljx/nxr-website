package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class R2PrivatePhotoObjectStorageTest {
    private final Map<String, byte[]> objects = new HashMap<>();
    private final Map<String, Map<String, String>> metadata = new HashMap<>();
    private R2PrivatePhotoObjectStorage storage;
    private S3Client client;
    private boolean corruptReadback;

    @BeforeEach void setup() {
        client = mock(S3Client.class);
        storage = new R2PrivatePhotoObjectStorage("https://example.r2.cloudflarestorage.com",
            "auto", "nxr-private-test", "test-key", "test-secret", "private/customer-uploads");
        ReflectionTestUtils.setField(storage, "client", client);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            RequestBody body = call.getArgument(1);
            assertThat(request.bucket()).isEqualTo("nxr-private-test");
            assertThat(request.key()).startsWith("private/customer-uploads/");
            objects.put(request.key(), body.contentStreamProvider().newStream().readAllBytes());
            metadata.put(request.key(), request.metadata());
            return PutObjectResponse.builder().build();
        });
        when(client.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> {
            HeadObjectRequest request = call.getArgument(0);
            assertThat(request.bucket()).isEqualTo("nxr-private-test");
            return HeadObjectResponse.builder().contentLength((long) objects.get(request.key()).length)
                .metadata(metadata.get(request.key())).build();
        });
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(call -> {
            GetObjectRequest request = call.getArgument(0);
            assertThat(request.bucket()).isEqualTo("nxr-private-test");
            byte[] bytes = objects.get(request.key());
            if (corruptReadback && request.key().endsWith(".original")) bytes = "corrupt".getBytes(StandardCharsets.UTF_8);
            return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes);
        });
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            DeleteObjectRequest request = call.getArgument(0);
            objects.remove(request.key());
            metadata.remove(request.key());
            return DeleteObjectResponse.builder().build();
        });
    }

    @Test void storesAndReadsPrivateOriginalAndPreviewAfterReadbackVerification() throws Exception {
        byte[] original = "original-card-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] preview = "preview-card-bytes".getBytes(StandardCharsets.UTF_8);
        String id = UUID.randomUUID().toString();
        storage.store(id, original, preview, "image/png", sha(original));
        assertThat(objects).hasSize(2);
        assertThat(storage.read(id, true).getContentAsByteArray()).isEqualTo(original);
        assertThat(storage.read(id, false).getContentAsByteArray()).isEqualTo(preview);
        storage.delete(id);
        assertThat(objects).isEmpty();
    }

    @Test void failedReadbackRemovesBothObjectsAndRejectsTheUpload() throws Exception {
        byte[] original = "original-card-bytes".getBytes(StandardCharsets.UTF_8);
        corruptReadback = true;
        assertThatThrownBy(() -> storage.store(UUID.randomUUID().toString(), original,
            "preview".getBytes(StandardCharsets.UTF_8), "image/jpeg", sha(original)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        assertThat(objects).isEmpty();
    }

    @Test void incompleteConfigurationDoesNotClaimR2IsAvailable() {
        var missingBucket = new R2PrivatePhotoObjectStorage("https://example.r2.cloudflarestorage.com",
            "auto", "", "test-key", "test-secret", "private/customer-uploads");
        assertThat(missingBucket.configured()).isFalse();
        assertThatThrownBy(missingBucket::requireConfigured).isInstanceOf(ResponseStatusException.class);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
