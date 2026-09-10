CREATE TABLE IF NOT EXISTS customer_order_photo (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    storage_key VARCHAR(64) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    width_px INT NOT NULL,
    height_px INT NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attached_at DATETIME NULL,
    UNIQUE KEY uk_customer_order_photo_storage (storage_key),
    KEY ix_customer_photo_owner (customer_id, order_id),
    CONSTRAINT fk_customer_photo_owner FOREIGN KEY (customer_id) REFERENCES customer_account(id),
    CONSTRAINT fk_customer_photo_order FOREIGN KEY (order_id) REFERENCES grading_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
