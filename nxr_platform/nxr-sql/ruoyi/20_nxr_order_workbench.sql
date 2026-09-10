-- Merchant master batches, private end-customer tracking and order-scoped workbench controls.
-- Additive and repeatable for the Java/MySQL application. No legacy Flask data is read.

DROP PROCEDURE IF EXISTS nxr_workbench_add_column;
DELIMITER $$
CREATE PROCEDURE nxr_workbench_add_column(
    IN target_table VARCHAR(64),
    IN target_column VARCHAR(64),
    IN alter_statement TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = target_table AND column_name = target_column
    ) THEN
        SET @nxr_workbench_sql = alter_statement;
        PREPARE nxr_workbench_stmt FROM @nxr_workbench_sql;
        EXECUTE nxr_workbench_stmt;
        DEALLOCATE PREPARE nxr_workbench_stmt;
    END IF;
END$$
DELIMITER ;

-- Existing orders remain operable. Orders created after this migration must pass
-- the workbench packing gate before they can enter return_shipped.
CALL nxr_workbench_add_column(
    'grading_order', 'workbench_required',
    'ALTER TABLE grading_order ADD COLUMN workbench_required TINYINT NOT NULL DEFAULT 0 AFTER shipping_label_created_at'
);
ALTER TABLE grading_order ALTER COLUMN workbench_required SET DEFAULT 1;
DROP PROCEDURE nxr_workbench_add_column;

CREATE TABLE IF NOT EXISTS merchant_order_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_no VARCHAR(48) NOT NULL,
    merchant_customer_id BIGINT NOT NULL,
    batch_name VARCHAR(191) NOT NULL,
    source_name VARCHAR(255) NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'open',
    total_rows INT NOT NULL DEFAULT 0,
    accepted_rows INT NOT NULL DEFAULT 0,
    rejected_rows INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_merchant_order_batch_no (batch_no),
    KEY idx_merchant_order_batch_customer_created (merchant_customer_id, created_at),
    CONSTRAINT fk_merchant_order_batch_customer
        FOREIGN KEY (merchant_customer_id) REFERENCES customer_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_order_batch_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    row_no INT NOT NULL,
    client_reference VARCHAR(128) NOT NULL,
    client_display_name VARCHAR(128) NULL,
    client_contact_hint VARCHAR(191) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_merchant_batch_item_order (order_id),
    UNIQUE KEY uk_merchant_batch_item_row (batch_id, row_no),
    UNIQUE KEY uk_merchant_batch_item_reference (batch_id, client_reference),
    CONSTRAINT fk_merchant_batch_item_batch
        FOREIGN KEY (batch_id) REFERENCES merchant_order_batch(id) ON DELETE CASCADE,
    CONSTRAINT fk_merchant_batch_item_order
        FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_batch_tracking_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_item_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    token_hint VARCHAR(12) NOT NULL,
    status_code VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL,
    UNIQUE KEY uk_merchant_batch_tracking_hash (token_hash),
    KEY idx_merchant_batch_tracking_item (batch_item_id, status_code, created_at),
    CONSTRAINT fk_merchant_batch_tracking_item
        FOREIGN KEY (batch_item_id) REFERENCES merchant_order_batch_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_batch_shipment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    direction_code VARCHAR(16) NOT NULL,
    carrier_name VARCHAR(128) NOT NULL,
    tracking_number VARCHAR(255) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'shipped',
    note TEXT NULL,
    created_by_type VARCHAR(16) NOT NULL,
    created_by_customer_id BIGINT NULL,
    created_by_admin_user_id BIGINT NULL,
    shipped_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_merchant_batch_shipment_batch (batch_id, direction_code, shipped_at),
    KEY idx_merchant_batch_shipment_tracking (tracking_number),
    CONSTRAINT fk_merchant_batch_shipment_batch
        FOREIGN KEY (batch_id) REFERENCES merchant_order_batch(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_physical_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    barcode VARCHAR(48) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_physical_item_order_item (order_item_id),
    UNIQUE KEY uk_order_physical_item_barcode (barcode),
    KEY idx_order_physical_item_order (order_id, id),
    CONSTRAINT fk_order_physical_item_order
        FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_physical_item_item
        FOREIGN KEY (order_item_id) REFERENCES grading_order_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_workbench_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    active_order_id BIGINT NULL,
    status_code VARCHAR(16) NOT NULL DEFAULT 'active',
    locked_by_user_id BIGINT NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP NULL,
    UNIQUE KEY uk_order_workbench_active_order (active_order_id),
    KEY idx_order_workbench_order_started (order_id, started_at),
    CONSTRAINT fk_order_workbench_session_order
        FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_workbench_active_order
        FOREIGN KEY (active_order_id) REFERENCES grading_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_workbench_scan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    physical_item_id BIGINT NOT NULL,
    active_physical_item_id BIGINT NULL,
    scan_stage_code VARCHAR(16) NOT NULL,
    status_code VARCHAR(16) NOT NULL DEFAULT 'active',
    label_fingerprint CHAR(64) NULL,
    scanned_by_user_id BIGINT NOT NULL,
    scanned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    invalidated_at TIMESTAMP NULL,
    UNIQUE KEY uk_order_workbench_scan_active_stage (order_id, active_physical_item_id, scan_stage_code),
    KEY idx_order_workbench_scan_session (session_id, scan_stage_code, scanned_at),
    CONSTRAINT fk_order_workbench_scan_session
        FOREIGN KEY (session_id) REFERENCES order_workbench_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_workbench_scan_order
        FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_workbench_scan_item
        FOREIGN KEY (physical_item_id) REFERENCES order_physical_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Upgrade an already-applied early development copy without deleting its scan history.
DROP PROCEDURE IF EXISTS nxr_workbench_upgrade_scan_audit;
DELIMITER $$
CREATE PROCEDURE nxr_workbench_upgrade_scan_audit()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order_workbench_scan' AND column_name='active_physical_item_id') THEN
        ALTER TABLE order_workbench_scan ADD COLUMN active_physical_item_id BIGINT NULL AFTER physical_item_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order_workbench_scan' AND column_name='status_code') THEN
        ALTER TABLE order_workbench_scan ADD COLUMN status_code VARCHAR(16) NOT NULL DEFAULT 'active' AFTER scan_stage_code;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order_workbench_scan' AND column_name='label_fingerprint') THEN
        ALTER TABLE order_workbench_scan ADD COLUMN label_fingerprint CHAR(64) NULL AFTER status_code;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order_workbench_scan' AND column_name='invalidated_at') THEN
        ALTER TABLE order_workbench_scan ADD COLUMN invalidated_at TIMESTAMP NULL AFTER scanned_at;
    END IF;
    UPDATE order_workbench_scan
    SET active_physical_item_id=physical_item_id
    WHERE status_code='active' AND active_physical_item_id IS NULL;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='order_workbench_scan' AND index_name='uk_order_workbench_scan_active_stage') THEN
        ALTER TABLE order_workbench_scan ADD UNIQUE INDEX uk_order_workbench_scan_active_stage
            (order_id, active_physical_item_id, scan_stage_code);
    END IF;
    -- Keep an order_id-leading index available while replacing the old unique key;
    -- InnoDB may use that old key to support fk_order_workbench_scan_order.
    IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='order_workbench_scan' AND index_name='uk_order_workbench_scan_stage') THEN
        ALTER TABLE order_workbench_scan DROP INDEX uk_order_workbench_scan_stage;
    END IF;
END$$
DELIMITER ;
CALL nxr_workbench_upgrade_scan_audit();
DROP PROCEDURE nxr_workbench_upgrade_scan_audit;

CREATE TABLE IF NOT EXISTS order_print_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    export_type_code VARCHAR(32) NOT NULL,
    print_sequence INT NOT NULL,
    row_count INT NOT NULL,
    content_fingerprint CHAR(64) NULL,
    reprint_reason VARCHAR(1000) NULL,
    generated_by_user_id BIGINT NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_print_job_sequence (order_id, export_type_code, print_sequence),
    KEY idx_order_print_job_order (order_id, generated_at),
    CONSTRAINT fk_order_print_job_order
        FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- An early development copy may already have the print audit table.
DROP PROCEDURE IF EXISTS nxr_workbench_upgrade_print_audit;
DELIMITER $$
CREATE PROCEDURE nxr_workbench_upgrade_print_audit()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order_print_job' AND column_name='content_fingerprint') THEN
        ALTER TABLE order_print_job ADD COLUMN content_fingerprint CHAR(64) NULL AFTER row_count;
    END IF;
END$$
DELIMITER ;
CALL nxr_workbench_upgrade_print_audit();
DROP PROCEDURE nxr_workbench_upgrade_print_audit;

CREATE TABLE IF NOT EXISTS order_packing_check (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    status_code VARCHAR(16) NOT NULL,
    expected_count INT NOT NULL,
    scanned_count INT NOT NULL,
    checked_by_user_id BIGINT NOT NULL,
    checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    invalidated_at TIMESTAMP NULL,
    KEY idx_order_packing_check_order (order_id, status_code, checked_at),
    CONSTRAINT fk_order_packing_check_order
        FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_packing_check_session
        FOREIGN KEY (session_id) REFERENCES order_workbench_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS nxr_workbench_add_index;
DELIMITER $$
CREATE PROCEDURE nxr_workbench_add_index(
    IN target_table VARCHAR(64),
    IN target_index VARCHAR(64),
    IN alter_statement TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = target_table AND index_name = target_index
    ) THEN
        SET @nxr_workbench_index_sql = alter_statement;
        PREPARE nxr_workbench_index_stmt FROM @nxr_workbench_index_sql;
        EXECUTE nxr_workbench_index_stmt;
        DEALLOCATE PREPARE nxr_workbench_index_stmt;
    END IF;
END$$
DELIMITER ;
-- A grading submission can represent only one physical card in one order.
CALL nxr_workbench_add_index(
    'grading_order_item', 'uk_grading_order_item_submission_unique',
    'CREATE UNIQUE INDEX uk_grading_order_item_submission_unique ON grading_order_item (grading_submission_id)'
);
DROP PROCEDURE nxr_workbench_add_index;

INSERT IGNORE INTO sys_menu VALUES(
    '2072', '订单工位', '2006', '10', '', '', '', '', 1, 0, 'F', '0', '0',
    'nxr:order:workbench', '#', 'admin', sysdate(), '', NULL, '订单锁定、逐卡扫码、标签导出和打包复核'
);
INSERT IGNORE INTO sys_menu VALUES(
    '2073', '代理批次', '2006', '11', '', '', '', '', 1, 0, 'F', '0', '0',
    'nxr:order:batch', '#', 'admin', sysdate(), '', NULL, '代理母包裹与子订单批次管理'
);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
    (100, 2072), (100, 2073), (102, 2072), (102, 2073), (103, 2072), (103, 2073);
