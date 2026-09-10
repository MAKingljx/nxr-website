package com.nxr.platform.customer;

import com.nxr.platform.admin.storage.MediaCapacityService;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Private application photos are served only after an owner or staff scope check. */
@Service
public class CustomerOrderPhotoService {
    private static final long MAX_BYTES = 15L * 1024 * 1024;
    private static final long MAX_PIXELS = 40_000_000;
    private final JdbcClient jdbc;
    private final SimpleJdbcInsert insert;
    private final Path root;
    private final MediaCapacityService capacity;

    public CustomerOrderPhotoService(JdbcClient jdbc, JdbcTemplate template, MediaCapacityService capacity,
        @Value("${nxr.media.storage-root:./.local-data/media}") String root) {
        this.jdbc = jdbc;
        this.capacity = capacity;
        this.root = Path.of(root).toAbsolutePath().normalize().resolve("customer-uploads");
        this.insert = new SimpleJdbcInsert(template).withTableName("customer_order_photo")
            .usingColumns("customer_id", "storage_key", "original_filename", "mime_type", "byte_size",
                "width_px", "height_px", "checksum_sha256").usingGeneratedKeyColumns("id");
    }

    @Transactional
    public Photo upload(long customerId, MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES)
            throw bad("Upload a JPEG or PNG image up to 15 MB");
        // A customer row lock makes pending limits effective for simultaneous uploads.
        jdbc.sql("SELECT id FROM customer_account WHERE id = :id FOR UPDATE").param("id", customerId).query(Long.class).single();
        Map<String, Object> usage = jdbc.sql("SELECT COUNT(*) AS photo_count, COALESCE(SUM(byte_size), 0) AS total_bytes FROM customer_order_photo WHERE customer_id = :id AND order_id IS NULL")
            .param("id", customerId).query().singleRow();
        if (((Number) usage.get("photo_count")).longValue() >= 1000 ||
            ((Number) usage.get("total_bytes")).longValue() + file.getSize() > 1024L * 1024 * 1024)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many unsubmitted images. Submit an application or remove unused images first.");
        String key = UUID.randomUUID().toString();
        Path original = path(key, false);
        Path thumbnail = path(key, true);
        try (MediaCapacityService.Reservation ignored = capacity.reserve(root, file.getSize() + 2 * 1024 * 1024)) {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_BYTES) throw bad("Image exceeds 15 MB");
            Decoded decoded = decode(bytes);
            byte[] preview = thumbnail(decoded.image());
            Files.createDirectories(root);
            writeAtomic(original, bytes);
            writeAtomic(thumbnail, preview);
            String filename = file.getOriginalFilename() == null ? "card-image" : Path.of(file.getOriginalFilename().replace('\\', '/')).getFileName().toString();
            filename = filename.replaceAll("[\\p{Cntrl}]", "");
            if (filename.length() > 255) filename = filename.substring(filename.length() - 255);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customer_id", customerId); row.put("storage_key", key); row.put("original_filename", filename);
            row.put("mime_type", decoded.mime()); row.put("byte_size", bytes.length);
            row.put("width_px", decoded.width()); row.put("height_px", decoded.height());
            row.put("checksum_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            long id = insert.executeAndReturnKey(row).longValue();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) { deleteQuietly(original); deleteQuietly(thumbnail); }
                    }
                });
            }
            return new Photo(id, filename, decoded.mime(), bytes.length, decoded.width(), decoded.height());
        } catch (ResponseStatusException e) {
            deleteQuietly(original); deleteQuietly(thumbnail); throw e;
        } catch (Exception e) {
            deleteQuietly(original); deleteQuietly(thumbnail);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The image could not be saved. Please try again.", e);
        }
    }

    public void requireOwnedPhoto(long customerId, Long photoId) {
        if (photoId == null) return;
        Map<String, Object> photo = owned(customerId, photoId, false);
        if (photo.get("order_id") != null) throw bad("This image is already attached to an application. Upload another copy for a different order.");
    }

    @Transactional
    public void attachToOrder(long customerId, long orderId, Collection<Long> photoIds) {
        if (photoIds == null) return;
        long matches = jdbc.sql("SELECT COUNT(*) FROM grading_order WHERE id = :orderId AND customer_id = :customerId")
            .param("orderId", orderId).param("customerId", customerId).query(Long.class).single();
        if (matches != 1) throw missing();
        for (Long photoId : photoIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList()) {
            Map<String, Object> photo = owned(customerId, photoId, true);
            if (photo.get("order_id") != null && ((Number) photo.get("order_id")).longValue() != orderId)
                throw bad("This image belongs to another application");
            jdbc.sql("UPDATE customer_order_photo SET order_id = :orderId, attached_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("orderId", orderId).param("id", photoId).update();
        }
    }

    public FileSystemResource readOwned(long customerId, long photoId, boolean original) {
        return resource(owned(customerId, photoId, false), original);
    }

    public Long attachedOrderId(long photoId) {
        Map<String, Object> photo = jdbc.sql("SELECT order_id FROM customer_order_photo WHERE id = :id")
            .param("id", photoId).query().listOfRows().stream().findFirst().orElseThrow(CustomerOrderPhotoService::missing);
        return photo.get("order_id") == null ? null : ((Number) photo.get("order_id")).longValue();
    }

    public FileSystemResource readAttached(long orderId, long photoId) {
        Map<String, Object> photo = jdbc.sql("SELECT * FROM customer_order_photo WHERE id = :id AND order_id = :orderId")
            .param("id", photoId).param("orderId", orderId).query().listOfRows().stream().findFirst().orElseThrow(CustomerOrderPhotoService::missing);
        return resource(photo, false);
    }

    @Transactional
    public void removeUnused(long customerId, long photoId) {
        Map<String, Object> photo = owned(customerId, photoId, true);
        if (photo.get("order_id") != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Submitted images must be preserved");
        jdbc.sql("DELETE FROM customer_order_photo WHERE id = :id AND order_id IS NULL").param("id", photoId).update();
        Runnable cleanup = () -> { deleteQuietly(path((String) photo.get("storage_key"), false)); deleteQuietly(path((String) photo.get("storage_key"), true)); };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup.run(); }
            });
        } else cleanup.run();
    }

    public List<Map<String, Object>> unused(long customerId) {
        return jdbc.sql("SELECT id, original_filename AS originalFilename, byte_size AS byteSize, created_at AS createdAt FROM customer_order_photo WHERE customer_id = :id AND order_id IS NULL ORDER BY id DESC LIMIT 1000")
            .param("id", customerId).query().listOfRows();
    }

    public Map<String, Object> capacityStatus() { return capacity.status(root); }

    private Map<String, Object> owned(long customerId, long photoId, boolean lock) {
        return jdbc.sql("SELECT * FROM customer_order_photo WHERE id = :id AND customer_id = :customerId" + (lock ? " FOR UPDATE" : ""))
            .param("id", photoId).param("customerId", customerId).query().listOfRows().stream().findFirst().orElseThrow(CustomerOrderPhotoService::missing);
    }

    private FileSystemResource resource(Map<String, Object> photo, boolean original) {
        Path file = path((String) photo.get("storage_key"), !original);
        if (!Files.isRegularFile(file)) throw missing();
        return new FileSystemResource(file);
    }

    private Path path(String key, boolean thumbnail) {
        if (key == null || !key.matches("[a-f0-9-]{36}")) throw missing();
        return root.resolve(key + (thumbnail ? ".preview.jpg" : ".original"));
    }

    private Decoded decode(byte[] bytes) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw bad("Only valid JPEG and PNG images are supported");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("jpg") && !format.equals("png")) throw bad("Only JPEG and PNG images are supported");
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) throw bad("Image must contain fewer than 40 million pixels");
                // Decode at preview resolution; the exact uploaded bytes remain the original.
                var param = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / 1600);
                param.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage decoded = reader.read(0, param);
                if (decoded == null) throw bad("The image is damaged");
                return new Decoded(JpegOrientation.orient(decoded, JpegOrientation.read(bytes)), width, height, format.equals("png") ? "image/png" : "image/jpeg");
            } finally { reader.dispose(); }
        } catch (IOException e) { throw bad("The image is damaged or unsupported"); }
    }

    private byte[] thumbnail(BufferedImage image) throws IOException {
        double scale = Math.min(1.0, 1200.0 / Math.max(image.getWidth(), image.getHeight()));
        BufferedImage output = new BufferedImage(Math.max(1, (int) (image.getWidth() * scale)), Math.max(1, (int) (image.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
        var graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, output.getWidth(), output.getHeight(), null);
        } finally { graphics.dispose(); }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(output, "jpeg", buffer);
        return buffer.toByteArray();
    }

    private void writeAtomic(Path destination, byte[] bytes) throws IOException {
        Path pending = destination.resolveSibling(destination.getFileName() + ".part");
        try {
            Files.write(pending, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
            try { Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(pending, destination); }
        } finally { deleteQuietly(pending); }
    }
    private static void deleteQuietly(Path path) { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"); }
    private record Decoded(BufferedImage image, int width, int height, String mime) { }
    public record Photo(long id, String originalFilename, String mimeType, long byteSize, int widthPx, int heightPx) { }
}
