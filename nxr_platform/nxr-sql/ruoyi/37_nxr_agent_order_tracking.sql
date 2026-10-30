-- Private links for an agent order exist before submission and remain bound to that order.
CREATE TABLE IF NOT EXISTS agent_order_tracking_token (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    intake_id BIGINT NOT NULL,
    merchant_customer_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    status_code VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL,
    UNIQUE KEY uk_agent_order_tracking_hash (token_hash),
    KEY ix_agent_order_tracking_order (intake_id, merchant_customer_id, status_code, id),
    CONSTRAINT fk_agent_order_tracking_intake FOREIGN KEY (intake_id, merchant_customer_id)
        REFERENCES agent_intake(id, merchant_customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
