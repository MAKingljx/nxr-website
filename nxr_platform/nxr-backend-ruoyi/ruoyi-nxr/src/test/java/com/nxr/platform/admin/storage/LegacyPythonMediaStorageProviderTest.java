package com.nxr.platform.admin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class LegacyPythonMediaStorageProviderTest {

    @TempDir
    Path uploadRoot;

    @Test
    void resolvesOnlySafeStagedFilesAndNeverDeletesThem() throws Exception {
        Path image = uploadRoot.resolve("front_CERT001_deadbeef.webp");
        Files.write(image, new byte[] {'R', 'I', 'F', 'F'});
        LegacyPythonMediaStorageProvider provider = new LegacyPythonMediaStorageProvider(uploadRoot.toString());
        MediaStorageProvider.StoredMediaLocation location = new MediaStorageProvider.StoredMediaLocation(
            "staged",
            "python-admin-uploads",
            image.getFileName().toString(),
            null
        );

        MediaStorageProvider.ResolvedMediaAsset resolved = provider.resolve(location);

        assertThat(resolved.contentType()).isEqualTo("image/webp");
        assertThat(resolved.resource().getInputStream().readAllBytes()).containsExactly('R', 'I', 'F', 'F');
        provider.deleteIfPresent(location);
        assertThat(image).exists();
    }

    @Test
    void rejectsTraversalWrongBucketsAndWriteOperations() {
        LegacyPythonMediaStorageProvider provider = new LegacyPythonMediaStorageProvider(uploadRoot.toString());

        assertNotFound(provider, "staged", "python-admin-uploads", "../secret.webp");
        assertNotFound(provider, "published", "python-admin-uploads", "safe.webp");
        assertNotFound(provider, "staged", "another-bucket", "safe.webp");
        assertThatThrownBy(() -> provider.store("staged", "CERT001", "front", "webp", null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED));
    }

    @Test
    void fastExistenceCheckRejectsSymlinkAndInvalidReference() throws Exception {
        Path image = uploadRoot.resolve("front.webp");
        Files.writeString(image, "image");
        Files.createSymbolicLink(uploadRoot.resolve("linked.webp"), image);
        LegacyPythonMediaStorageProvider provider = new LegacyPythonMediaStorageProvider(uploadRoot.toString());

        assertThat(provider.referenceExists(location("front.webp"))).isTrue();
        assertThat(provider.referenceExists(location("linked.webp"))).isFalse();
        assertThat(provider.referenceExists(location("../front.webp"))).isFalse();
        assertThat(provider.referenceExists(location("missing.webp"))).isFalse();
        assertThat(provider.referenceExists(new MediaStorageProvider.StoredMediaLocation(
            "published", "python-admin-uploads", "front.webp", null))).isFalse();
    }

    private MediaStorageProvider.StoredMediaLocation location(String key) {
        return new MediaStorageProvider.StoredMediaLocation("staged", "python-admin-uploads", key, null);
    }

    private void assertNotFound(
        LegacyPythonMediaStorageProvider provider,
        String stage,
        String bucket,
        String key
    ) {
        assertThatThrownBy(() -> provider.resolve(
            new MediaStorageProvider.StoredMediaLocation(stage, bucket, key, null)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
