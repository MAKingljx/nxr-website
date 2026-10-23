-- Freeze the exact terms accepted with a partner Submit. Existing acceptance
-- rows retain their original version, quote and timestamp; no consent is backfilled.
SET SESSION lock_wait_timeout = 5;
DROP PROCEDURE IF EXISTS nxr_partner_terms_upgrade;
DELIMITER $$
CREATE PROCEDURE nxr_partner_terms_upgrade()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='order_terms_acceptance' AND column_name='terms_text') THEN
        ALTER TABLE order_terms_acceptance ADD COLUMN terms_text TEXT NULL, ALGORITHM=INSTANT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='order_terms_acceptance' AND column_name='turnaround_text') THEN
        ALTER TABLE order_terms_acceptance ADD COLUMN turnaround_text VARCHAR(1000) NULL, ALGORITHM=INSTANT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='order_terms_acceptance' AND column_name='item_snapshot_hash') THEN
        ALTER TABLE order_terms_acceptance ADD COLUMN item_snapshot_hash CHAR(64) NOT NULL DEFAULT '', ALGORITHM=INSTANT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='order_terms_acceptance' AND index_name='uk_order_terms_acceptance_snapshot') THEN
        ALTER TABLE order_terms_acceptance ADD UNIQUE KEY uk_order_terms_acceptance_snapshot
            (order_id,terms_version,item_snapshot_hash), ALGORITHM=INPLACE, LOCK=NONE;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='order_terms_acceptance' AND index_name='uk_order_terms_acceptance_version') THEN
        ALTER TABLE order_terms_acceptance DROP INDEX uk_order_terms_acceptance_version, ALGORITHM=INPLACE, LOCK=NONE;
    END IF;
END$$
DELIMITER ;
CALL nxr_partner_terms_upgrade();
DROP PROCEDURE nxr_partner_terms_upgrade;
