-- Unify batch row references on the platform order number.
-- The displayed/stored client_reference becomes grading_order.order_no; the
-- original agent intake number is kept separately so intake tracing, tracking
-- links and preserved-photo reuse keep working for existing and new rows.
ALTER TABLE merchant_order_batch_item
    ADD COLUMN intake_no VARCHAR(64) NULL AFTER client_reference,
    ADD KEY ix_merchant_batch_item_intake (intake_no);

-- Existing agent batches stored the intake number in client_reference.
UPDATE merchant_order_batch_item bi
JOIN merchant_order_batch b ON b.id = bi.batch_id
JOIN agent_intake i ON i.merchant_customer_id = b.merchant_customer_id
    AND CAST(i.intake_no AS CHAR(128)) = CAST(bi.client_reference AS CHAR(128))
SET bi.intake_no = i.intake_no
WHERE bi.intake_no IS NULL;

-- Unify the stored reference on the system order number.
UPDATE merchant_order_batch_item bi
JOIN grading_order o ON o.id = bi.order_id
SET bi.client_reference = o.order_no
WHERE bi.client_reference <> o.order_no;
