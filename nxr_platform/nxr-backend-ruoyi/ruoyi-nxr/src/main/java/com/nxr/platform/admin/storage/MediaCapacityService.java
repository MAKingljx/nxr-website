package com.nxr.platform.admin.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Reserves headroom across concurrent uploads in this JVM before opening a file. */
@Service
public class MediaCapacityService {
    private final long minimumFreeBytes;
    private long reservedBytes;

    public MediaCapacityService(@Value("${nxr.media.minimum-free-bytes:536870912}") long minimumFreeBytes) {
        this.minimumFreeBytes = Math.max(0, minimumFreeBytes);
    }

    public synchronized Reservation reserve(Path root, long bytes) {
        if (bytes < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A known upload size is required");
        long usable = usableSpace(root);
        if (bytes > Math.max(0, usable - minimumFreeBytes - reservedBytes)) {
            throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
                "Image storage is nearly full. Please contact NXR before uploading more images.");
        }
        reservedBytes += bytes;
        return new Reservation(bytes);
    }

    public synchronized Map<String, Object> status(Path root) {
        long usable = usableSpace(root);
        try {
            long total = Files.getFileStore(existingParent(root)).getTotalSpace();
            return Map.of("totalBytes", total, "usableBytes", usable, "reservedBytes", reservedBytes,
                "minimumFreeBytes", minimumFreeBytes, "uploadsAllowed", usable > minimumFreeBytes + reservedBytes,
                "checkedAt", java.time.Instant.now().toString());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot inspect image storage", e);
        }
    }

    protected long usableSpace(Path root) {
        try { return Files.getFileStore(existingParent(root)).getUsableSpace(); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot inspect image storage", e); }
    }

    private Path existingParent(Path root) {
        Path path = root.toAbsolutePath().normalize();
        while (path != null && !Files.exists(path)) path = path.getParent();
        if (path == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is unavailable");
        return path;
    }

    public final class Reservation implements AutoCloseable {
        private final long bytes;
        private boolean closed;
        private Reservation(long bytes) { this.bytes = bytes; }
        @Override public void close() {
            synchronized (MediaCapacityService.this) {
                if (!closed) { reservedBytes -= bytes; closed = true; }
            }
        }
    }
}
