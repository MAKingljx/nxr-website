package com.nxr.platform.admin.storage;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.Resource;

public interface MediaStorageProvider {

    String providerCode();

    boolean manages(String storageProviderCode);

    StoredMediaObject store(
        String stage,
        String certId,
        String sideCode,
        String extension,
        MediaUpload mediaUpload
    );

    StoredMediaObject copy(
        String targetStage,
        String certId,
        String sideCode,
        StoredMediaSource source
    );

    ResolvedMediaAsset resolve(StoredMediaLocation location);

    void deleteIfPresent(StoredMediaLocation location);

    record MediaUpload(
        String originalFilename,
        String contentType,
        long contentLength,
        Integer widthPx,
        Integer heightPx,
        InputStreamSource inputStreamSource
    ) {
    }

    @FunctionalInterface
    interface InputStreamSource {
        InputStream openStream() throws IOException;
    }

    record StoredMediaSource(
        String stage,
        String storageBucket,
        String storageKey,
        String storageObjectVersion,
        String originalFilename,
        String mimeType,
        String checksumSha256,
        Integer widthPx,
        Integer heightPx
    ) {
    }

    record StoredMediaObject(
        String storageProviderCode,
        String storageBucket,
        String storageKey,
        String publicUrl,
        String originalFilename,
        String mimeType,
        long fileSizeBytes,
        String checksumSha256,
        String storageObjectVersion,
        Integer widthPx,
        Integer heightPx
    ) {
    }

    record StoredMediaLocation(
        String stage,
        String storageBucket,
        String storageKey,
        String storageObjectVersion
    ) {
    }

    record ResolvedMediaAsset(
        Resource resource,
        String contentType,
        String filename
    ) {
    }
}
