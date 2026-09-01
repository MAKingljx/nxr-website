package com.nxr.platform.admin.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only bridge to images staged by the legacy Python administrator.
 *
 * <p>The configured directory is mounted read-only into the Java service. This
 * provider never creates, modifies, or deletes a Python file; publication
 * streams the selected asset into the active Java storage provider.</p>
 */
@Component
public class LegacyPythonMediaStorageProvider implements MediaStorageProvider {

    static final String PROVIDER_CODE = "legacy-python";
    static final String STORAGE_BUCKET = "python-admin-uploads";

    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final Path storageRoot;

    public LegacyPythonMediaStorageProvider(
        @Value("${nxr.media.legacy-python.storage-root:./nxr_admin/uploads}") String storageRoot
    ) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public boolean manages(String storageProviderCode) {
        return storageProviderCode != null
            && PROVIDER_CODE.equals(storageProviderCode.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public StoredMediaObject store(
        String stage,
        String certId,
        String sideCode,
        String extension,
        MediaUpload mediaUpload
    ) {
        throw readOnlyFailure();
    }

    @Override
    public StoredMediaObject copy(
        String targetStage,
        String certId,
        String sideCode,
        StoredMediaSource source
    ) {
        throw readOnlyFailure();
    }

    @Override
    public ResolvedMediaAsset resolve(StoredMediaLocation location) {
        Path assetPath = resolveAssetPath(location);
        if (!Files.isRegularFile(assetPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(assetPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Python staged media was not found.");
        }
        return new ResolvedMediaAsset(
            new FileSystemResource(assetPath),
            contentType(assetPath.getFileName().toString()),
            assetPath.getFileName().toString()
        );
    }

    @Override
    public void deleteIfPresent(StoredMediaLocation location) {
        // Deliberately no-op: Python owns the source queue and its retention.
    }

    private Path resolveAssetPath(StoredMediaLocation location) {
        if (location == null
            || !"staged".equals(normalize(location.stage()))
            || !STORAGE_BUCKET.equals(normalize(location.storageBucket()))
            || !isSafeFilename(location.storageKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Python staged media was not found.");
        }

        Path candidate = storageRoot.resolve(location.storageKey()).normalize();
        if (!candidate.startsWith(storageRoot)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Python staged media was not found.");
        }
        try {
            Path realRoot = storageRoot.toRealPath();
            Path mountedAsset = realRoot.resolve(location.storageKey()).normalize();
            if (!mountedAsset.startsWith(realRoot) || Files.isSymbolicLink(mountedAsset)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Python staged media was not found.");
            }
            Path realAsset = mountedAsset.toRealPath();
            if (!realAsset.startsWith(realRoot)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Python staged media was not found.");
            }
            return realAsset;
        } catch (IOException exc) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Python staged media was not found.", exc);
        }
    }

    private boolean isSafeFilename(String filename) {
        if (filename == null || !SAFE_FILENAME.matcher(filename).matches()) {
            return false;
        }
        int extensionIndex = filename.lastIndexOf('.');
        return extensionIndex > 0
            && extensionIndex < filename.length() - 1
            && SUPPORTED_EXTENSIONS.contains(filename.substring(extensionIndex + 1).toLowerCase(Locale.ROOT));
    }

    private String contentType(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private ResponseStatusException readOnlyFailure() {
        return new ResponseStatusException(
            HttpStatus.METHOD_NOT_ALLOWED,
            "The Python media bridge is read-only."
        );
    }
}
