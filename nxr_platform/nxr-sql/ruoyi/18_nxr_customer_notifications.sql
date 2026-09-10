-- ----------------------------------------------------------------------------
-- NXR customer account verification, password recovery and durable email outbox.
-- Additive and repeatable. This migration only targets the Java/MySQL database.
-- ----------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS nxr_add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE nxr_add_column_if_missing(
    IN target_table VARCHAR(64),
    IN target_column VARCHAR(64),
    IN alter_statement TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = target_table
          AND column_name = target_column
    ) THEN
        SET @nxr_alter_sql = alter_statement;
        PREPARE nxr_alter_stmt FROM @nxr_alter_sql;
        EXECUTE nxr_alter_stmt;
        DEALLOCATE PREPARE nxr_alter_stmt;
    END IF;
END$$
DELIMITER ;

CALL nxr_add_column_if_missing(
    'customer_account', 'email_verified_at',
    'ALTER TABLE customer_account ADD COLUMN email_verified_at TIMESTAMP NULL AFTER last_login_at'
);

DROP PROCEDURE nxr_add_column_if_missing;

CREATE TABLE IF NOT EXISTS customer_account_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    purpose_code VARCHAR(32) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    request_ip_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_customer_account_token_hash (token_hash),
    KEY idx_customer_account_token_customer_purpose (customer_id, purpose_code, created_at),
    KEY idx_customer_account_token_expiry (purpose_code, expires_at, consumed_at),
    CONSTRAINT fk_customer_account_token_customer
        FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS customer_auth_rate_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    action_code VARCHAR(32) NOT NULL,
    identifier_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_customer_auth_rate_window (action_code, identifier_hash, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS customer_notification_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stable_key CHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    notification_type_code VARCHAR(48) NOT NULL,
    encrypted_payload MEDIUMTEXT NOT NULL,
    status_code VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMP NULL,
    sent_at TIMESTAMP NULL,
    last_error_code VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_customer_notification_stable_key (stable_key),
    KEY idx_customer_notification_dispatch (status_code, next_attempt_at, id),
    KEY idx_customer_notification_customer (customer_id, created_at),
    CONSTRAINT fk_customer_notification_customer
        FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- encrypted_payload is intentionally never exposed by a controller or admin query.
-- Grant application users only the table rights required by the notification service.
