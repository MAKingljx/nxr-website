-- ----------------------------------------------------------------------------
-- Order admission, customer quote/terms acceptance and bounded payment window.
-- Existing orders retain NULL admission metadata and their current workflow.
-- New application code explicitly initializes new orders as pending review.
-- ----------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS nxr_admission_add_column;
DELIMITER $$
CREATE PROCEDURE nxr_admission_add_column(
    IN target_table VARCHAR(64), IN target_column VARCHAR(64), IN ddl TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = target_table AND column_name = target_column
    ) THEN
        SET @nxr_admission_ddl = ddl;
        PREPARE nxr_admission_stmt FROM @nxr_admission_ddl;
        EXECUTE nxr_admission_stmt;
        DEALLOCATE PREPARE nxr_admission_stmt;
    END IF;
END$$
DELIMITER ;

CALL nxr_admission_add_column('grading_order', 'admission_status_code',
    'ALTER TABLE grading_order ADD COLUMN admission_status_code VARCHAR(32) NULL AFTER status_code');
CALL nxr_admission_add_column('grading_order', 'admission_revision',
    'ALTER TABLE grading_order ADD COLUMN admission_revision INT NOT NULL DEFAULT 1 AFTER admission_status_code');
CALL nxr_admission_add_column('grading_order', 'admission_submitted_at',
    'ALTER TABLE grading_order ADD COLUMN admission_submitted_at TIMESTAMP NULL AFTER admission_revision');
CALL nxr_admission_add_column('grading_order', 'admission_decided_at',
    'ALTER TABLE grading_order ADD COLUMN admission_decided_at TIMESTAMP NULL AFTER admission_submitted_at');
CALL nxr_admission_add_column('grading_order', 'admission_decision_note',
    'ALTER TABLE grading_order ADD COLUMN admission_decision_note TEXT NULL AFTER admission_decided_at');
CALL nxr_admission_add_column('grading_order', 'admission_reviewed_by_user_id',
    'ALTER TABLE grading_order ADD COLUMN admission_reviewed_by_user_id BIGINT NULL AFTER admission_decision_note');
CALL nxr_admission_add_column('grading_order', 'payment_due_at',
    'ALTER TABLE grading_order ADD COLUMN payment_due_at TIMESTAMP NULL AFTER admission_reviewed_by_user_id');
CALL nxr_admission_add_column('grading_order', 'payment_deadline_status_code',
    'ALTER TABLE grading_order ADD COLUMN payment_deadline_status_code VARCHAR(24) NULL AFTER payment_due_at');
CALL nxr_admission_add_column('grading_order', 'approved_terms_version',
    'ALTER TABLE grading_order ADD COLUMN approved_terms_version VARCHAR(64) NULL AFTER payment_deadline_status_code');
CALL nxr_admission_add_column('grading_order', 'approved_terms_text',
    'ALTER TABLE grading_order ADD COLUMN approved_terms_text TEXT NULL AFTER approved_terms_version');
CALL nxr_admission_add_column('grading_order', 'approved_turnaround_text',
    'ALTER TABLE grading_order ADD COLUMN approved_turnaround_text VARCHAR(1000) NULL AFTER approved_terms_text');
CALL nxr_admission_add_column('grading_order', 'approved_quote_amount',
    'ALTER TABLE grading_order ADD COLUMN approved_quote_amount DECIMAL(12,2) NULL AFTER approved_turnaround_text');
CALL nxr_admission_add_column('grading_order', 'approved_quote_currency',
    'ALTER TABLE grading_order ADD COLUMN approved_quote_currency VARCHAR(8) NULL AFTER approved_quote_amount');
CALL nxr_admission_add_column('grading_order', 'accepted_terms_version',
    'ALTER TABLE grading_order ADD COLUMN accepted_terms_version VARCHAR(64) NULL AFTER approved_quote_currency');
CALL nxr_admission_add_column('grading_order', 'terms_accepted_at',
    'ALTER TABLE grading_order ADD COLUMN terms_accepted_at TIMESTAMP NULL AFTER accepted_terms_version');

CALL nxr_admission_add_column('grading_order_item', 'year_label',
    'ALTER TABLE grading_order_item ADD COLUMN year_label VARCHAR(32) NULL AFTER brand_name');
CALL nxr_admission_add_column('grading_order_item', 'rarity',
    'ALTER TABLE grading_order_item ADD COLUMN rarity VARCHAR(128) NULL AFTER year_label');
CALL nxr_admission_add_column('grading_order_item', 'product_type',
    'ALTER TABLE grading_order_item ADD COLUMN product_type VARCHAR(64) NULL AFTER rarity');
CALL nxr_admission_add_column('grading_order_item', 'category',
    'ALTER TABLE grading_order_item ADD COLUMN category VARCHAR(128) NULL AFTER product_type');
CALL nxr_admission_add_column('grading_order_item', 'front_photo_id',
    'ALTER TABLE grading_order_item ADD COLUMN front_photo_id BIGINT NULL AFTER item_note');
CALL nxr_admission_add_column('grading_order_item', 'back_photo_id',
    'ALTER TABLE grading_order_item ADD COLUMN back_photo_id BIGINT NULL AFTER front_photo_id');

DROP PROCEDURE IF EXISTS nxr_admission_add_column;

CREATE TABLE IF NOT EXISTS order_admission_config (
    config_id TINYINT PRIMARY KEY,
    payment_deadline_hours INT NOT NULL DEFAULT 48,
    max_cards_per_order INT NOT NULL DEFAULT 500,
    terms_version VARCHAR(64) NOT NULL,
    terms_text TEXT NOT NULL,
    turnaround_text VARCHAR(1000) NOT NULL,
    config_version INT NOT NULL DEFAULT 1,
    updated_by_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_admission_config_user
        FOREIGN KEY (updated_by_user_id) REFERENCES sys_user(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO order_admission_config (
    config_id, payment_deadline_hours, max_cards_per_order, terms_version, terms_text, turnaround_text
) VALUES (
    1, 48, 500, 'initial',
    'Please confirm the submitted item list, approved quote, and current NXR service terms before payment.',
    '以受理确认为准'
);

CREATE TABLE IF NOT EXISTS order_admission_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    event_code VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    detail TEXT NULL,
    actor_type_code VARCHAR(32) NOT NULL,
    actor_customer_id BIGINT NULL,
    actor_admin_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_order_admission_event_order (order_id, created_at, id),
    CONSTRAINT fk_order_admission_event_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_admission_event_customer FOREIGN KEY (actor_customer_id) REFERENCES customer_account(id) ON DELETE SET NULL,
    CONSTRAINT fk_order_admission_event_admin FOREIGN KEY (actor_admin_user_id) REFERENCES sys_user(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_terms_acceptance (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    terms_version VARCHAR(64) NOT NULL,
    accepted_quote_amount DECIMAL(12,2) NOT NULL,
    accepted_quote_currency VARCHAR(8) NOT NULL,
    accepted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_terms_acceptance_version (order_id, terms_version),
    KEY idx_order_terms_acceptance_customer (customer_id, accepted_at),
    CONSTRAINT fk_order_terms_acceptance_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_terms_acceptance_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_admission_supplemental_photo (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    photo_id BIGINT NOT NULL,
    submitted_by_customer_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_admission_supplemental_photo (order_id, photo_id),
    KEY idx_order_admission_supplemental_customer (submitted_by_customer_id, created_at),
    CONSTRAINT fk_order_admission_supplemental_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_admission_supplemental_customer FOREIGN KEY (submitted_by_customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS nxr_admission_add_index;
DELIMITER $$
CREATE PROCEDURE nxr_admission_add_index(IN target_index VARCHAR(64), IN ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'grading_order' AND index_name = target_index
    ) THEN
        SET @nxr_admission_index_ddl = ddl;
        PREPARE nxr_admission_index_stmt FROM @nxr_admission_index_ddl;
        EXECUTE nxr_admission_index_stmt;
        DEALLOCATE PREPARE nxr_admission_index_stmt;
    END IF;
END$$
DELIMITER ;
CALL nxr_admission_add_index('idx_grading_order_admission',
    'ALTER TABLE grading_order ADD INDEX idx_grading_order_admission (admission_status_code, admission_submitted_at)');
CALL nxr_admission_add_index('idx_grading_order_payment_due',
    'ALTER TABLE grading_order ADD INDEX idx_grading_order_payment_due (payment_deadline_status_code, payment_due_at)');
DROP PROCEDURE IF EXISTS nxr_admission_add_index;

INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_by, create_time, update_by, update_time, remark
) VALUES (
    2070, '订单受理审核', 2006, 9, '#', '', '', '',
    1, 0, 'F', '0', '0', 'nxr:order:admission', '#',
    'admin', sysdate(), '', NULL, '订单受理、补充资料、拒绝、批准与付款期限配置'
);
UPDATE sys_menu
SET menu_name = '订单受理审核', parent_id = 2006, perms = 'nxr:order:admission', update_time = sysdate()
WHERE menu_id = 2070;
