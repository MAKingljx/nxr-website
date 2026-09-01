-- Java/Python workflow parity. Apply only after a verified MySQL backup.
-- Idempotent for environments that may already contain part of the schema.

SET @nxr_schema = DATABASE();

SET @sql = IF(
    (SELECT COALESCE(numeric_scale, 0) FROM information_schema.columns
     WHERE table_schema=@nxr_schema AND table_name='grading_score'
       AND column_name='final_grade_value') < 2,
    'ALTER TABLE grading_score MODIFY COLUMN final_grade_value DECIMAL(4,2) NOT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=@nxr_schema AND table_name='grading_submission' AND column_name='entry_by_label') = 0,
    'ALTER TABLE grading_submission ADD COLUMN entry_by_label VARCHAR(128) NULL AFTER entry_by_user_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=@nxr_schema AND table_name='grading_score' AND column_name='ai_centering_score') = 0,
    'ALTER TABLE grading_score ADD COLUMN ai_centering_score DECIMAL(4,1) NULL AFTER ai_grade_value',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=@nxr_schema AND table_name='grading_score' AND column_name='ai_edges_score') = 0,
    'ALTER TABLE grading_score ADD COLUMN ai_edges_score DECIMAL(4,1) NULL AFTER ai_centering_score',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=@nxr_schema AND table_name='grading_score' AND column_name='ai_corners_score') = 0,
    'ALTER TABLE grading_score ADD COLUMN ai_corners_score DECIMAL(4,1) NULL AFTER ai_edges_score',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=@nxr_schema AND table_name='grading_score' AND column_name='ai_surface_score') = 0,
    'ALTER TABLE grading_score ADD COLUMN ai_surface_score DECIMAL(4,1) NULL AFTER ai_corners_score',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS submission_upload_state (
    submission_id BIGINT PRIMARY KEY,
    status_code VARCHAR(32) NOT NULL DEFAULT 'not_started',
    claim_token CHAR(36) NULL,
    claimed_front_media_id BIGINT NULL,
    claimed_back_media_id BIGINT NULL,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    error_message TEXT NULL,
    response_payload_json LONGTEXT NULL,
    triggered_by_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_submission_upload_state_status (status_code, updated_at),
    CONSTRAINT fk_submission_upload_state_submission
        FOREIGN KEY (submission_id) REFERENCES grading_submission(id) ON DELETE CASCADE,
    CONSTRAINT fk_submission_upload_state_front_media
        FOREIGN KEY (claimed_front_media_id) REFERENCES submission_media(id) ON DELETE SET NULL,
    CONSTRAINT fk_submission_upload_state_back_media
        FOREIGN KEY (claimed_back_media_id) REFERENCES submission_media(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO submission_upload_state (submission_id, status_code, completed_at)
SELECT id,
       CASE WHEN status_code='published' THEN 'uploaded' ELSE 'not_started' END,
       CASE WHEN status_code='published' THEN published_at ELSE NULL END
FROM grading_submission
ON DUPLICATE KEY UPDATE submission_id=VALUES(submission_id);

CREATE TABLE IF NOT EXISTS nxr_python_match_projection (
    source_code VARCHAR(16) NOT NULL,
    cert_id VARCHAR(32) NOT NULL,
    product_type_code VARCHAR(32) NOT NULL DEFAULT 'graded_card',
    card_category_code VARCHAR(32) NOT NULL DEFAULT 'trading_card',
    set_name VARCHAR(255) NOT NULL,
    card_number VARCHAR(64) NOT NULL,
    card_name VARCHAR(255) NOT NULL,
    brand_name VARCHAR(64) NOT NULL,
    year_label VARCHAR(16) NULL,
    variety_name VARCHAR(255) NULL,
    language_code VARCHAR(16) NOT NULL DEFAULT 'EN',
    sports_type VARCHAR(64) NULL,
    group_name VARCHAR(128) NULL,
    merch_description TEXT NULL,
    status_code VARCHAR(32) NULL,
    source_updated_at TIMESTAMP NULL,
    PRIMARY KEY (source_code, cert_id),
    KEY idx_nxr_python_match_lookup (
        product_type_code, card_category_code, set_name, card_number, source_code, source_updated_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Normalize only labels that are known aliases; unknown historical values are preserved.
UPDATE grading_score
SET final_grade_label = CASE
    WHEN UPPER(TRIM(final_grade_label)) IN ('8', '8.0', '8.00', 'NEAR MINT-MINT 8') THEN '8'
    WHEN UPPER(TRIM(final_grade_label)) IN ('8.5', '8.50', 'NEAR MINT-MINT+ 8.5') THEN '8.5'
    WHEN UPPER(TRIM(final_grade_label)) IN ('9', '9.0', '9.00', 'MINT 9') THEN '9'
    WHEN UPPER(TRIM(final_grade_label)) IN ('9.5', '9.50', 'GEM MINT 9.5') THEN '9.5'
    WHEN UPPER(TRIM(final_grade_label)) IN ('10', '10.0', '10.00') THEN '10'
    WHEN REPLACE(REPLACE(UPPER(TRIM(final_grade_label)), ' ', ''), '-', '') LIKE '%STINE10%' THEN 'Pristine 10'
    ELSE final_grade_label
END;
