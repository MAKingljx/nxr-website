-- Private tracking links must never repeat, including historical revoked rows.
-- The raw token is stored for display, so it carries its own unique constraint in
-- addition to the existing unique token_hash lookup constraint.
ALTER TABLE merchant_batch_tracking_token
    ADD UNIQUE KEY uk_merchant_batch_tracking_value (token_value);
