-- Additive enterprise credit support. Existing money wallets and their history
-- are intentionally retained; conversion requires a reviewed administrator command.
CREATE TABLE IF NOT EXISTS enterprise_credit_config (
    id INT PRIMARY KEY,
    version BIGINT NOT NULL,
    points_per_cny DECIMAL(18,8) NOT NULL,
    updated_by_user_id BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO enterprise_credit_config(id,version,points_per_cny) VALUES(1,1,1);
CREATE TABLE IF NOT EXISTS enterprise_credit_rate (
    currency_code VARCHAR(8) PRIMARY KEY,
    cny_per_unit DECIMAL(18,8) NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO enterprise_credit_rate(currency_code,cny_per_unit,enabled) VALUES
('CNY',1,TRUE),('USD',NULL,FALSE),('EUR',NULL,FALSE),('GBP',NULL,FALSE),
('HKD',NULL,FALSE),('JPY',NULL,FALSE),('CAD',NULL,FALSE),('AUD',NULL,FALSE),('SGD',NULL,FALSE);
CREATE TABLE IF NOT EXISTS enterprise_credit_snapshot (
    reference_type_code VARCHAR(32) NOT NULL,
    reference_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    settings_version BIGINT NOT NULL,
    source_currency VARCHAR(8) NOT NULL,
    source_amount DECIMAL(18,2) NOT NULL,
    cny_per_unit DECIMAL(18,8) NOT NULL,
    points_per_cny DECIMAL(18,8) NOT NULL,
    points DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(reference_type_code,reference_id),
    KEY idx_credit_snapshot_customer(customer_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS enterprise_credit_conversion (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    settings_version BIGINT NOT NULL,
    points DECIMAL(18,2) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    note VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_credit_conversion_command(customer_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
