-- Selected-card and partial returns do not create artificial per-card check records.
-- Existing checks and foreign-key references remain intact.
ALTER TABLE partner_return_item MODIFY COLUMN check_id BIGINT NULL;
