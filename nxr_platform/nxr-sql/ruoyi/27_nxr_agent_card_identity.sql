-- Official number captured from the front of an agent card.
-- The legacy inventory_code storage remains the opaque custody/card code;
-- this additive column carries the printed official number without rewriting
-- existing custody records.
DROP PROCEDURE IF EXISTS nxr_agent_official_card_number;
DELIMITER $$
CREATE PROCEDURE nxr_agent_official_card_number()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'agent_card'
          AND column_name = 'official_card_number'
    ) THEN
        ALTER TABLE agent_card ADD COLUMN official_card_number VARCHAR(128) NULL AFTER inventory_code;
    END IF;
END$$
DELIMITER ;
CALL nxr_agent_official_card_number();
DROP PROCEDURE nxr_agent_official_card_number;
