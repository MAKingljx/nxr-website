package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.*;
import com.nxr.platform.admin.storage.MediaCapacityService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class CustomerOrderPhotoServiceTest {
    @TempDir Path directory;
    private CustomerOrderPhotoService service;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:photos_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE customer_account(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE grading_order(id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE customer_order_photo(id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL, order_id BIGINT, storage_key VARCHAR(64), original_filename VARCHAR(255), mime_type VARCHAR(64), byte_size BIGINT, width_px INT, height_px INT, checksum_sha256 VARCHAR(64), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, attached_at TIMESTAMP)");
        jdbc.update("INSERT INTO customer_account VALUES(1),(2)");
        jdbc.update("INSERT INTO grading_order VALUES(10,1),(11,1),(20,2)");
        service = new CustomerOrderPhotoService(JdbcClient.create(jdbc), jdbc, new MediaCapacityService(0), directory.toString());
    }

    @Test void privatePhotosPreserveOriginalAndCannotBeReadOrAttachedByAnotherCustomer() throws Exception {
        byte[] bytes = png();
        var photo = transaction.execute(status -> service.upload(1, new MockMultipartFile("file", "card.png", "image/png", bytes)));
        assertThat(service.readOwned(1, photo.id(), true).getContentAsByteArray()).isEqualTo(bytes);
        assertThat(service.readOwned(1, photo.id(), false).getContentAsByteArray()).startsWith((byte) 0xff, (byte) 0xd8);
        assertThatThrownBy(() -> service.readOwned(2, photo.id(), false)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.attachToOrder(2, 20, List.of(photo.id())))).isInstanceOf(ResponseStatusException.class);
        transaction.executeWithoutResult(status -> service.attachToOrder(1, 10, List.of(photo.id())));
        assertThat(service.attachedOrderId(photo.id())).isEqualTo(10L);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.attachToOrder(1, 11, List.of(photo.id())))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.removeUnused(1, photo.id())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("preserved");
    }

    @Test void rejectedFormatsAndRollbackLeaveNoOrphanedFiles() throws Exception {
        assertThatThrownBy(() -> service.upload(1, new MockMultipartFile("file", "fake.png", "image/png", "<svg onload='alert(1)'/>".getBytes())))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("JPEG");
        byte[] image = png();
        transaction.executeWithoutResult(status -> { service.upload(1, new MockMultipartFile("file", "card.png", "image/png", image)); status.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_order_photo", Integer.class)).isZero();
        try (var files = Files.list(directory.resolve("customer-uploads"))) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void unusedDeletionIsOwnerOnlyAndRollbackKeepsOriginal() throws Exception {
        byte[] image = png();
        var photo = transaction.execute(status -> service.upload(1, new MockMultipartFile("file", "card.png", "image/png", image)));
        assertThatThrownBy(() -> service.removeUnused(2, photo.id())).isInstanceOf(ResponseStatusException.class);
        transaction.executeWithoutResult(status -> { service.removeUnused(1, photo.id()); status.setRollbackOnly(); });
        assertThat(service.readOwned(1, photo.id(), true).exists()).isTrue();
        transaction.executeWithoutResult(status -> service.removeUnused(1, photo.id()));
        try (var files = Files.list(directory.resolve("customer-uploads"))) { assertThat(files.toList()).isEmpty(); }
    }

    private byte[] png() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
