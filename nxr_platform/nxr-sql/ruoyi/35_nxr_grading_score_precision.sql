-- Preserve Python SQLite REAL / binary64 sub-score precision. This changes only
-- the four input column types; final grades, labels and certificates are not
-- recalculated. Run after a verified backup in the approved maintenance window.
-- MySQL 8.0 changes DECIMAL to DOUBLE by copying the table. LOCK=SHARED permits
-- reads while blocking writes; INSTANT, INPLACE and LOCK=NONE are not supported.
-- Keep DOUBLE on code rollback: reverting to DECIMAL(4,1) would lose new input
-- precision. A schema/data rollback requires the separately verified backup.
SET SESSION lock_wait_timeout = 5;
DROP PROCEDURE IF EXISTS nxr_grading_score_precision_upgrade;
DELIMITER $$
CREATE PROCEDURE nxr_grading_score_precision_upgrade()
BEGIN
    -- Fail on unexpected definitions instead of dropping defaults, comments or
    -- nullability when MODIFY restates the column definition.
    IF (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='grading_score'
          AND column_name IN ('centering_score','edges_score','corners_score','surface_score')
          AND (column_type='decimal(4,1)' OR column_type='double')
          AND is_nullable='NO' AND column_default IS NULL
          AND extra='' AND column_comment='') <> 4 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected grading_score input column definitions; inspect before migration';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='grading_score'
          AND column_name IN ('centering_score','edges_score','corners_score','surface_score')
          AND data_type<>'double') THEN
        ALTER TABLE grading_score
            MODIFY COLUMN centering_score DOUBLE NOT NULL,
            MODIFY COLUMN edges_score DOUBLE NOT NULL,
            MODIFY COLUMN corners_score DOUBLE NOT NULL,
            MODIFY COLUMN surface_score DOUBLE NOT NULL,
            ALGORITHM=COPY, LOCK=SHARED;
    END IF;
END$$
DELIMITER ;
CALL nxr_grading_score_precision_upgrade();
DROP PROCEDURE nxr_grading_score_precision_upgrade;
