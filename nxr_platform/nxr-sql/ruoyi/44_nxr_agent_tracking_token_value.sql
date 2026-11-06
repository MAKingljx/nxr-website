-- Keep the customer tracking link retrievable so staff can copy it again later.
ALTER TABLE agent_order_tracking_token
    ADD COLUMN token_value VARCHAR(64) NULL AFTER token_hash,
    ADD UNIQUE KEY uk_agent_tracking_value (token_value);
