CREATE TABLE IF NOT EXISTS merchant_order_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_no VARCHAR(48) NOT NULL UNIQUE,
    merchant_customer_id BIGINT NOT NULL,
    batch_name VARCHAR(191) NOT NULL,
    source_name VARCHAR(255),
    status_code VARCHAR(32) NOT NULL,
    total_rows INT NOT NULL,
    accepted_rows INT NOT NULL DEFAULT 0,
    rejected_rows INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS merchant_order_batch_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL UNIQUE,
    row_no INT NOT NULL,
    client_reference VARCHAR(128) NOT NULL,
    client_display_name VARCHAR(128),
    client_contact_hint VARCHAR(191),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_batch_row UNIQUE (batch_id, row_no),
    CONSTRAINT uk_batch_reference UNIQUE (batch_id, client_reference)
);

CREATE TABLE merchant_batch_tracking_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_item_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    token_hint VARCHAR(12) NOT NULL,
    status_code VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP
);

CREATE TABLE merchant_batch_shipment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    direction_code VARCHAR(16) NOT NULL,
    carrier_name VARCHAR(128) NOT NULL,
    tracking_number VARCHAR(255) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'shipped',
    note TEXT,
    created_by_type VARCHAR(16) NOT NULL,
    created_by_customer_id BIGINT,
    created_by_admin_user_id BIGINT,
    shipped_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO customer_account (
    id, email, password_hash, display_name, account_type_code, is_active
) VALUES (1, 'merchant@example.test', 'x', 'Merchant', 'merchant', 1),
         (2, 'other@example.test', 'x', 'Other Merchant', 'merchant', 1);
