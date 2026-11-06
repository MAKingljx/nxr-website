-- B-end order creation now reserves the platform order number immediately.
ALTER TABLE agent_intake
    ADD COLUMN reserved_order_no VARCHAR(32) NULL AFTER intake_no,
    ADD UNIQUE KEY uk_agent_intake_reserved_order_no (reserved_order_no);
