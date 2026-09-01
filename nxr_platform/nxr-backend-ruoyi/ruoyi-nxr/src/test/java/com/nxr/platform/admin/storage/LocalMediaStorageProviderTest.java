package com.nxr.platform.admin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class LocalMediaStorageProviderTest {

    @TempDir
    Path storageRoot;

    @Test
    void storeAndCopyUseCompleteFilesAndLeaveNoPartArtifacts() throws Exception {
        LocalMediaStorageProvider provider = new LocalMediaStorageProvider(
            storageRoot.toString(),
            "http://127.0.0.1:8088/",
            "test-media"
        );
        byte[] content = "nxr-media".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        MediaStorageProvider.StoredMediaObject staged = provider.store(
            "staged",
            "CERT001",
            "front",
            "webp",
            new MediaStorageProvider.MediaUpload(
                "CERT001_A.webp",
                "image/webp",
                content.length,
                100,
                200,
                () -> new ByteArrayInputStream(content)
            )
        );
        assertThat(staged.storageProviderCode()).isEqualTo("local");
        assertThat(staged.fileSizeBytes()).isEqualTo(content.length);
        assertThat(staged.publicUrl()).startsWith("http://127.0.0.1:8088/media/staged/");
        assertThat(provider.resolve(new MediaStorageProvider.StoredMediaLocation(
            "staged", staged.storageBucket(), staged.storageKey(), null
        )).resource().getInputStream().readAllBytes()).isEqualTo(content);

        MediaStorageProvider.StoredMediaObject published = provider.copy(
            "published",
            "CERT001",
            "front",
            new MediaStorageProvider.StoredMediaSource(
                "staged",
                staged.storageBucket(),
                staged.storageKey(),
                null,
                staged.originalFilename(),
                staged.mimeType(),
                staged.checksumSha256(),
                staged.widthPx(),
                staged.heightPx()
            )
        );
        assertThat(provider.resolve(new MediaStorageProvider.StoredMediaLocation(
            "published", published.storageBucket(), published.storageKey(), null
        )).resource().getInputStream().readAllBytes()).isEqualTo(content);
        try (var paths = Files.walk(storageRoot)) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".part"))).isEmpty();
        }

        provider.deleteIfPresent(new MediaStorageProvider.StoredMediaLocation(
            "staged", staged.storageBucket(), staged.storageKey(), null
        ));
        provider.deleteIfPresent(new MediaStorageProvider.StoredMediaLocation(
            "published", published.storageBucket(), published.storageKey(), null
        ));
        try (var paths = Files.walk(storageRoot)) {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void interruptedStoreRemovesPrivatePartFile() throws Exception {
        LocalMediaStorageProvider provider = new LocalMediaStorageProvider(
            storageRoot.toString(),
            "http://127.0.0.1:8088",
            "test-media"
        );

        assertThatThrownBy(() -> provider.store(
            "staged",
            "CERT002",
            "back",
            "webp",
            new MediaStorageProvider.MediaUpload(
                "CERT002_B.webp",
                "image/webp",
                10,
                100,
                200,
                FailingInputStream::new
            )
        )).isInstanceOf(ResponseStatusException.class);

        try (var paths = Files.walk(storageRoot)) {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }

    private static final class FailingInputStream extends InputStream {
        private int reads;

        @Override
        public int read() throws IOException {
            if (reads++ > 1) {
                throw new IOException("simulated interrupted upload");
            }
            return 'x';
        }
    }
}
