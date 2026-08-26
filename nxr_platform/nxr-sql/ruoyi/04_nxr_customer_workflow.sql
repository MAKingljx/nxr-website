-- ----------------------------------------------------------------------------
-- NXR customer portal and grading-order workflow.
-- This migration is additive and safe to run against an existing local NXR
-- development database. It deliberately does not touch legacy Flask data.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS customer_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(191) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    mobile VARCHAR(64) NULL,
    is_active TINYINT NOT NULL DEFAULT 1,
    last_login_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_customer_account_email (email),
    KEY idx_customer_account_active (is_active, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS customer_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_customer_session_token_hash (token_hash),
    KEY idx_customer_session_customer_expiry (customer_id, expires_at),
    CONSTRAINT fk_customer_session_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS certificate_ownership (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cert_id VARCHAR(32) NOT NULL,
    active_cert_id VARCHAR(32) NULL,
    customer_id BIGINT NOT NULL,
    ownership_status_code VARCHAR(32) NOT NULL DEFAULT 'active',
    visibility_code VARCHAR(32) NOT NULL DEFAULT 'public',
    note TEXT NULL,
    bound_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_certificate_ownership_active_cert (active_cert_id),
    KEY idx_certificate_ownership_customer_active (customer_id, ownership_status_code, bound_at),
    KEY idx_certificate_ownership_cert_history (cert_id, bound_at),
    CONSTRAINT fk_certificate_ownership_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS certificate_ownership_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cert_id VARCHAR(32) NOT NULL,
    from_customer_id BIGINT NULL,
    to_customer_id BIGINT NULL,
    event_type_code VARCHAR(32) NOT NULL,
    visibility_code VARCHAR(32) NOT NULL DEFAULT 'public',
    message TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_certificate_ownership_event_cert (cert_id, created_at),
    CONSTRAINT fk_certificate_ownership_event_from FOREIGN KEY (from_customer_id) REFERENCES customer_account(id) ON DELETE SET NULL,
    CONSTRAINT fk_certificate_ownership_event_to FOREIGN KEY (to_customer_id) REFERENCES customer_account(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS grading_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(40) NOT NULL,
    customer_id BIGINT NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'awaiting_payment',
    service_level_code VARCHAR(32) NOT NULL DEFAULT 'standard',
    total_card_count INT NOT NULL,
    service_fee DECIMAL(12,2) NOT NULL,
    return_shipping_fee DECIMAL(12,2) NOT NULL DEFAULT 0,
    total_amount DECIMAL(12,2) NOT NULL,
    currency_code VARCHAR(8) NOT NULL DEFAULT 'USD',
    contact_name VARCHAR(128) NOT NULL,
    contact_phone VARCHAR(64) NOT NULL,
    return_address_line1 VARCHAR(255) NOT NULL,
    return_address_line2 VARCHAR(255) NULL,
    return_city VARCHAR(128) NOT NULL,
    return_region VARCHAR(128) NULL,
    return_postal_code VARCHAR(64) NOT NULL,
    return_country VARCHAR(128) NOT NULL,
    customer_note TEXT NULL,
    internal_note TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_grading_order_order_no (order_no),
    KEY idx_grading_order_customer_created (customer_id, created_at),
    KEY idx_grading_order_status_created (status_code, created_at),
    CONSTRAINT fk_grading_order_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS grading_order_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    item_no INT NOT NULL,
    card_name VARCHAR(255) NOT NULL,
    brand_name VARCHAR(128) NULL,
    set_name VARCHAR(255) NULL,
    card_number VARCHAR(128) NULL,
    language_code VARCHAR(32) NULL,
    declared_value DECIMAL(12,2) NULL,
    item_note TEXT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'awaiting_inbound',
    grading_submission_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_grading_order_item_number (order_id, item_no),
    KEY idx_grading_order_item_submission (grading_submission_id),
    CONSTRAINT fk_grading_order_item_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_grading_order_item_submission FOREIGN KEY (grading_submission_id) REFERENCES grading_submission(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    direction_code VARCHAR(16) NOT NULL DEFAULT 'receivable',
    payment_type_code VARCHAR(32) NOT NULL DEFAULT 'grading_fee',
    provider_code VARCHAR(32) NOT NULL DEFAULT 'manual_transfer',
    method_label VARCHAR(128) NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'pending',
    amount DECIMAL(12,2) NOT NULL,
    currency_code VARCHAR(8) NOT NULL DEFAULT 'USD',
    payer_reference VARCHAR(255) NULL,
    proof_reference VARCHAR(512) NULL,
    provider_transaction_id VARCHAR(255) NULL,
    confirmed_by_user_id BIGINT NULL,
    submitted_at TIMESTAMP NULL,
    confirmed_at TIMESTAMP NULL,
    callback_received_at TIMESTAMP NULL,
    callback_payload LONGTEXT NULL,
    note TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_record_provider_transaction (provider_code, provider_transaction_id),
    KEY idx_payment_record_order_status (order_id, status_code, created_at),
    CONSTRAINT fk_payment_record_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_callback_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_code VARCHAR(32) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    payment_id BIGINT NULL,
    payload LONGTEXT NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_callback_event_provider_event (provider_code, provider_event_id),
    KEY idx_payment_callback_event_payment (payment_id),
    CONSTRAINT fk_payment_callback_event_payment FOREIGN KEY (payment_id) REFERENCES payment_record(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_shipment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    direction_code VARCHAR(16) NOT NULL,
    carrier_name VARCHAR(128) NOT NULL,
    tracking_number VARCHAR(255) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'shipped',
    shipped_by_user_id BIGINT NULL,
    shipped_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP NULL,
    note TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_order_shipment_order_direction (order_id, direction_code, shipped_at),
    KEY idx_order_shipment_tracking (tracking_number),
    CONSTRAINT fk_order_shipment_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_timeline_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    event_code VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    detail TEXT NULL,
    status_code VARCHAR(32) NULL,
    visible_to_customer TINYINT NOT NULL DEFAULT 1,
    actor_type_code VARCHAR(32) NOT NULL DEFAULT 'system',
    actor_customer_id BIGINT NULL,
    actor_admin_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_order_timeline_event_order (order_id, created_at),
    CONSTRAINT fk_order_timeline_event_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_timeline_event_customer FOREIGN KEY (actor_customer_id) REFERENCES customer_account(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- NXR operational menu and permissions. INSERT IGNORE keeps the script rerunnable.
INSERT IGNORE INTO sys_menu VALUES('2006', '订单管理', '2000', '3', 'orders', 'nxr/orders/index', '', 'NxrOrders', 1, 0, 'C', '0', '0', 'nxr:order:list', 'shopping', 'admin', sysdate(), '', null, '客户送评订单、收付款与物流');
INSERT IGNORE INTO sys_menu VALUES('2061', '订单查看', '2006', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:order:list', '#', 'admin', sysdate(), '', null, '');
INSERT IGNORE INTO sys_menu VALUES('2062', '订单处理', '2006', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:order:manage', '#', 'admin', sysdate(), '', null, '');
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(100, 2000), (100, 2006), (100, 2061), (100, 2062),
(101, 2000), (101, 2006), (101, 2061);
