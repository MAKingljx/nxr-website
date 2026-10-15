package com.nxr.platform.admin.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Provider compatibility contracts use a mocked SDK client and never contact R2. */
class R2MediaStorageProviderContractTest {
    private R2MediaStorageProvider provider(S3Client client) {
        var provider = new R2MediaStorageProvider("https://r2.example.invalid", "auto", "qa-bucket",
            "unused-local-test", "unused-local-test", "https://images.example.invalid/", "/cards/", "/staging/");
        ReflectionTestUtils.setField(provider, "client", client);
        return provider;
    }

    @Test void localStartupAndLegacyProviderNamesRequireNoCredentialsOrNetwork() {
        var provider = new R2MediaStorageProvider("", "auto", "", "", "", "", "cards", "staging");
        assertThat(provider.providerCode()).isEqualTo("r2");
        assertThat(provider.manages(" R2 ")).isTrue();
        assertThat(provider.manages("r2-published")).isTrue();
        assertThat(provider.manages("local")).isFalse();
        assertThat(provider.manages(null)).isFalse();
        assertThat(provider.referenceExists(new MediaStorageProvider.StoredMediaLocation("published", "", "cards/a.png", null)))
            .isTrue();
        assertThat(ReflectionTestUtils.getField(provider, "client")).isNull();
    }

    @Test void stagedUploadPreservesChecksumSizeVersionAndSafePrefix() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().versionId("local-version").build());
        byte[] bytes = "synthetic-local-image".getBytes(StandardCharsets.UTF_8);
        var stored = provider(client).store("staged", "CERT 123", "front", ".PNG",
            new MediaStorageProvider.MediaUpload("front.png", "image/png", bytes.length, 240, 336,
                () -> new ByteArrayInputStream(bytes)));
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("qa-bucket");
        assertThat(stored.storageKey()).matches("staging/CERT-123/CERT-123_front_[a-f0-9]{12}\\.png");
        assertThat(request.getValue().key()).isEqualTo(stored.storageKey());
        assertThat(stored.storageProviderCode()).isEqualTo("r2");
        assertThat(stored.fileSizeBytes()).isEqualTo(bytes.length);
        assertThat(stored.storageObjectVersion()).isEqualTo("local-version");
        assertThat(stored.checksumSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(stored.publicUrl()).isEqualTo("https://images.example.invalid/" + stored.storageKey());
        verifyNoMoreInteractions(client);
    }

    @Test void publishingCopiesAcrossBucketsAndRetainsImageMetadata() {
        S3Client client = mock(S3Client.class);
        when(client.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().versionId("published-version").build());
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(77L).build());
        var stored = provider(client).copy("published", "CERT-1", "back",
            new MediaStorageProvider.StoredMediaSource("staged", "old bucket", "staging/CERT 1/front.png", "old-version",
                "front.png", "image/png", "prior-checksum", 240, 336));
        var request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(request.capture());
        assertThat(request.getValue().copySource()).isEqualTo("old%20bucket/staging/CERT%201/front.png");
        assertThat(request.getValue().destinationBucket()).isEqualTo("qa-bucket");
        assertThat(stored.storageKey()).matches("cards/CERT-1/CERT-1_back_[a-f0-9]{12}\\.png");
        assertThat(stored.fileSizeBytes()).isEqualTo(77L);
        assertThat(stored.checksumSha256()).isEqualTo("prior-checksum");
        assertThat(stored.storageObjectVersion()).isEqualTo("published-version");
        assertThat(stored.widthPx()).isEqualTo(240);
        verify(client).headObject(any(HeadObjectRequest.class));
        verifyNoMoreInteractions(client);
    }

    @Test void readOnlyResolutionEncodesPathsWithoutAnySdkRequest() throws Exception {
        S3Client client = mock(S3Client.class);
        var resolved = provider(client).resolve(new MediaStorageProvider.StoredMediaLocation("published", "qa-bucket", "cards/CERT 1/back.png", null));
        assertThat(resolved.resource().getURL().toString()).isEqualTo("https://images.example.invalid/cards/CERT%201/back.png");
        assertThat(resolved.contentType()).isEqualTo("image/png");
        assertThat(resolved.filename()).isEqualTo("back.png");
        verifyNoInteractions(client);
    }

    @Test void lengthMismatchFailsBeforeRemoteUploadAndEmptyCleanupIsInert() {
        S3Client client = mock(S3Client.class);
        var provider = provider(client);
        assertThatThrownBy(() -> provider.store("staged", "CERT", "front", "png",
            new MediaStorageProvider.MediaUpload("front.png", "image/png", 10, null, null,
                () -> new ByteArrayInputStream(new byte[]{1, 2}))))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Failed to upload media");
        provider.deleteIfPresent(null);
        provider.deleteIfPresent(new MediaStorageProvider.StoredMediaLocation("staged", "qa-bucket", "", null));
        verifyNoInteractions(client);
    }
}
