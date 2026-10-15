-- Additive partner Order metadata. Existing intake numbers, customer records,
-- cards, submissions and fulfillment states are not rewritten.
-- Fail promptly on busy metadata; never fall back to a table-copy ALTER.
SET SESSION lock_wait_timeout = 5;
DROP PROCEDURE IF EXISTS nxr_partner_order_add_column;
DELIMITER $$
CREATE PROCEDURE nxr_partner_order_add_column(IN target_table VARCHAR(64),IN target_column VARCHAR(64),IN definition_sql TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name=target_table AND column_name=target_column) THEN
        SET @nxr_partner_order_sql=definition_sql;
        PREPARE nxr_partner_order_stmt FROM @nxr_partner_order_sql;
        EXECUTE nxr_partner_order_stmt;
        DEALLOCATE PREPARE nxr_partner_order_stmt;
    END IF;
END$$
DELIMITER ;
CALL nxr_partner_order_add_column('agent_intake','partner_order_version','ALTER TABLE agent_intake ADD COLUMN partner_order_version TINYINT NOT NULL DEFAULT 0, ALGORITHM=INSTANT');
CALL nxr_partner_order_add_column('agent_intake','client_name_snapshot','ALTER TABLE agent_intake ADD COLUMN client_name_snapshot VARCHAR(128) NULL, ALGORITHM=INSTANT');
CALL nxr_partner_order_add_column('agent_intake','client_phone_snapshot','ALTER TABLE agent_intake ADD COLUMN client_phone_snapshot VARCHAR(64) NULL, ALGORITHM=INSTANT');
CALL nxr_partner_order_add_column('agent_intake','client_email_snapshot','ALTER TABLE agent_intake ADD COLUMN client_email_snapshot VARCHAR(191) NULL, ALGORITHM=INSTANT');
CALL nxr_partner_order_add_column('agent_card','brand_name','ALTER TABLE agent_card ADD COLUMN brand_name VARCHAR(128) NULL, ALGORITHM=INSTANT');
CALL nxr_partner_order_add_column('agent_card','set_name','ALTER TABLE agent_card ADD COLUMN set_name VARCHAR(255) NULL, ALGORITHM=INSTANT');
CALL nxr_partner_order_add_column('agent_card','product_type','ALTER TABLE agent_card ADD COLUMN product_type VARCHAR(64) NULL, ALGORITHM=INSTANT');
CALL nxr_partner_order_add_column('agent_card','category','ALTER TABLE agent_card ADD COLUMN category VARCHAR(128) NULL, ALGORITHM=INSTANT');
DROP PROCEDURE nxr_partner_order_add_column;
