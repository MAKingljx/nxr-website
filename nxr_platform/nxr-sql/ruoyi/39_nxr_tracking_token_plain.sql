-- Keep private tracking links re-displayable and copyable for staff.
-- Lookup hashing and hint columns stay unchanged; the raw token is stored for display only.
ALTER TABLE merchant_batch_tracking_token
    ADD COLUMN token_value VARCHAR(64) NULL AFTER token_hint;
