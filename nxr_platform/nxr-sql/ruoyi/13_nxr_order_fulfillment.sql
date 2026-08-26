-- ----------------------------------------------------------------------------
-- NXR grading-service sales, intake, fulfillment, billing and support workflow.
-- Additive only: this migration never reads or mutates the legacy Flask Data/ DBs.
-- It is safe to run repeatedly against the local Java/MySQL development database.
-- ----------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS nxr_add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE nxr_add_column_if_missing(
    IN target_table VARCHAR(64),
    IN target_column VARCHAR(64),
    IN alter_statement TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = target_table
          AND column_name = target_column
    ) THEN
        SET @nxr_alter_sql = alter_statement;
        PREPARE nxr_alter_stmt FROM @nxr_alter_sql;
        EXECUTE nxr_alter_stmt;
        DEALLOCATE PREPARE nxr_alter_stmt;
    END IF;
END$$
DELIMITER ;

CALL nxr_add_column_if_missing(
    'customer_account', 'account_type_code',
    'ALTER TABLE customer_account ADD COLUMN account_type_code VARCHAR(32) NOT NULL DEFAULT ''customer'' AFTER mobile'
);

CALL nxr_add_column_if_missing(
    'grading_order', 'return_shipping_option_code',
    'ALTER TABLE grading_order ADD COLUMN return_shipping_option_code VARCHAR(32) NULL AFTER service_level_code'
);
CALL nxr_add_column_if_missing(
    'grading_order', 'return_shipping_option_name',
    'ALTER TABLE grading_order ADD COLUMN return_shipping_option_name VARCHAR(128) NULL AFTER return_shipping_option_code'
);
CALL nxr_add_column_if_missing(
    'grading_order', 'intake_code',
    'ALTER TABLE grading_order ADD COLUMN intake_code VARCHAR(64) NULL AFTER internal_note'
);
CALL nxr_add_column_if_missing(
    'grading_order', 'packing_slip_code',
    'ALTER TABLE grading_order ADD COLUMN packing_slip_code VARCHAR(64) NULL AFTER intake_code'
);
CALL nxr_add_column_if_missing(
    'grading_order', 'shipping_label_created_at',
    'ALTER TABLE grading_order ADD COLUMN shipping_label_created_at TIMESTAMP NULL AFTER packing_slip_code'
);

CALL nxr_add_column_if_missing(
    'payment_record', 'payment_no',
    'ALTER TABLE payment_record ADD COLUMN payment_no VARCHAR(48) NULL AFTER payment_type_code'
);
CALL nxr_add_column_if_missing(
    'payment_record', 'payment_url',
    'ALTER TABLE payment_record ADD COLUMN payment_url VARCHAR(512) NULL AFTER proof_reference'
);
CALL nxr_add_column_if_missing(
    'payment_record', 'qr_payload',
    'ALTER TABLE payment_record ADD COLUMN qr_payload VARCHAR(512) NULL AFTER payment_url'
);
CALL nxr_add_column_if_missing(
    'payment_record', 'related_payment_id',
    'ALTER TABLE payment_record ADD COLUMN related_payment_id BIGINT NULL AFTER qr_payload'
);

CALL nxr_add_column_if_missing(
    'order_shipment', 'shipping_option_code',
    'ALTER TABLE order_shipment ADD COLUMN shipping_option_code VARCHAR(32) NULL AFTER direction_code'
);
CALL nxr_add_column_if_missing(
    'order_shipment', 'shipping_option_name',
    'ALTER TABLE order_shipment ADD COLUMN shipping_option_name VARCHAR(128) NULL AFTER shipping_option_code'
);

DROP PROCEDURE nxr_add_column_if_missing;

CREATE TABLE IF NOT EXISTS grading_service_price (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    price_code VARCHAR(32) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    currency_code VARCHAR(8) NOT NULL DEFAULT 'USD',
    is_active TINYINT NOT NULL DEFAULT 1,
    version_no INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_grading_service_price_code (price_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS return_shipping_option (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    option_code VARCHAR(32) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    country_scope VARCHAR(1000) NOT NULL DEFAULT '*',
    currency_code VARCHAR(8) NOT NULL DEFAULT 'USD',
    price_amount DECIMAL(12,2) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_active TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_return_shipping_option_code (option_code),
    KEY idx_return_shipping_option_active_sort (is_active, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS customer_address (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    label VARCHAR(64) NOT NULL DEFAULT 'Return address',
    contact_name VARCHAR(128) NOT NULL,
    contact_phone VARCHAR(64) NOT NULL,
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255) NULL,
    city VARCHAR(128) NOT NULL,
    region VARCHAR(128) NULL,
    postal_code VARCHAR(64) NOT NULL,
    country VARCHAR(128) NOT NULL,
    is_default TINYINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_customer_address_customer_default (customer_id, is_default, updated_at),
    CONSTRAINT fk_customer_address_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_intake_receipt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    package_no VARCHAR(128) NULL,
    expected_count INT NOT NULL,
    received_count INT NOT NULL,
    condition_note VARCHAR(1000) NULL,
    received_by_user_id BIGINT NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_order_intake_receipt_order (order_id, received_at),
    CONSTRAINT fk_order_intake_receipt_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_exception (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    exception_type_code VARCHAR(32) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'open',
    title VARCHAR(255) NOT NULL,
    detail TEXT NULL,
    resolution_note TEXT NULL,
    visible_to_customer TINYINT NOT NULL DEFAULT 1,
    created_by_user_id BIGINT NULL,
    resolved_by_user_id BIGINT NULL,
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_order_exception_order_status (order_id, status_code, created_at),
    CONSTRAINT fk_order_exception_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_work_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NULL,
    task_type_code VARCHAR(32) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'pending',
    attempt_count INT NOT NULL DEFAULT 0,
    result_summary TEXT NULL,
    failure_reason TEXT NULL,
    assigned_user_id BIGINT NULL,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_order_work_task_order_type (order_id, task_type_code, status_code),
    KEY idx_order_work_task_item (order_item_id, task_type_code),
    CONSTRAINT fk_order_work_task_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_work_task_item FOREIGN KEY (order_item_id) REFERENCES grading_order_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS shipment_tracking_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shipment_id BIGINT NOT NULL,
    event_code VARCHAR(32) NOT NULL,
    event_title VARCHAR(255) NOT NULL,
    location_label VARCHAR(255) NULL,
    event_detail TEXT NULL,
    event_time TIMESTAMP NOT NULL,
    created_by_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_shipment_tracking_event_shipment_time (shipment_id, event_time),
    CONSTRAINT fk_shipment_tracking_event_shipment FOREIGN KEY (shipment_id) REFERENCES order_shipment(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS support_ticket (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_no VARCHAR(40) NOT NULL,
    order_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    category_code VARCHAR(32) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'open',
    subject VARCHAR(255) NOT NULL,
    assigned_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    closed_at TIMESTAMP NULL,
    UNIQUE KEY uk_support_ticket_no (ticket_no),
    KEY idx_support_ticket_order_status (order_id, status_code, created_at),
    KEY idx_support_ticket_customer_created (customer_id, created_at),
    CONSTRAINT fk_support_ticket_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_support_ticket_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS support_ticket_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    actor_type_code VARCHAR(32) NOT NULL,
    actor_customer_id BIGINT NULL,
    actor_admin_user_id BIGINT NULL,
    message_text TEXT NOT NULL,
    attachment_reference VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_support_ticket_message_ticket (ticket_id, created_at),
    CONSTRAINT fk_support_ticket_message_ticket FOREIGN KEY (ticket_id) REFERENCES support_ticket(id) ON DELETE CASCADE,
    CONSTRAINT fk_support_ticket_message_customer FOREIGN KEY (actor_customer_id) REFERENCES customer_account(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS shipping_change_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    ticket_id BIGINT NULL,
    old_option_code VARCHAR(32) NOT NULL,
    old_option_name VARCHAR(128) NOT NULL,
    old_price_amount DECIMAL(12,2) NOT NULL,
    new_option_code VARCHAR(32) NOT NULL,
    new_option_name VARCHAR(128) NOT NULL,
    new_price_amount DECIMAL(12,2) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    difference_amount DECIMAL(12,2) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'requested',
    reason TEXT NULL,
    payment_id BIGINT NULL,
    reviewed_by_user_id BIGINT NULL,
    reviewed_at TIMESTAMP NULL,
    settled_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_shipping_change_order_status (order_id, status_code, created_at),
    CONSTRAINT fk_shipping_change_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_shipping_change_ticket FOREIGN KEY (ticket_id) REFERENCES support_ticket(id) ON DELETE SET NULL,
    CONSTRAINT fk_shipping_change_payment FOREIGN KEY (payment_id) REFERENCES payment_record(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_import_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    source_name VARCHAR(255) NULL,
    total_rows INT NOT NULL,
    accepted_rows INT NOT NULL DEFAULT 0,
    rejected_rows INT NOT NULL DEFAULT 0,
    status_code VARCHAR(32) NOT NULL DEFAULT 'processing',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    KEY idx_merchant_import_job_customer (customer_id, created_at),
    CONSTRAINT fk_merchant_import_job_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_import_row (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_job_id BIGINT NOT NULL,
    row_no INT NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    order_id BIGINT NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_merchant_import_row_number (import_job_id, row_no),
    CONSTRAINT fk_merchant_import_row_job FOREIGN KEY (import_job_id) REFERENCES merchant_import_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_merchant_import_row_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO grading_service_price (price_code, display_name, unit_price, currency_code, is_active, version_no)
VALUES ('basic_grading', 'Basic grading', 20.00, 'USD', 1, 1)
ON DUPLICATE KEY UPDATE price_code = price_code;

INSERT INTO return_shipping_option
    (option_code, display_name, description, country_scope, currency_code, price_amount, sort_order, is_active)
VALUES
    ('economy_line', 'Economy Line', 'Lower-cost tracked return shipping.', '*', 'USD', 12.00, 10, 1),
    ('standard_express', 'Standard Express', 'Tracked standard express return shipping.', '*', 'USD', 25.00, 20, 1),
    ('dhl_international', 'DHL International Express', 'Priority international return shipping.', '*', 'USD', 60.00, 30, 1)
ON DUPLICATE KEY UPDATE option_code = option_code;

DROP PROCEDURE IF EXISTS nxr_add_index_if_missing;
DELIMITER $$
CREATE PROCEDURE nxr_add_index_if_missing(
    IN target_table VARCHAR(64),
    IN target_index VARCHAR(64),
    IN alter_statement TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = target_table
          AND index_name = target_index
    ) THEN
        SET @nxr_index_sql = alter_statement;
        PREPARE nxr_index_stmt FROM @nxr_index_sql;
        EXECUTE nxr_index_stmt;
        DEALLOCATE PREPARE nxr_index_stmt;
    END IF;
END$$
DELIMITER ;

CALL nxr_add_index_if_missing('grading_order', 'uk_grading_order_intake_code', 'CREATE UNIQUE INDEX uk_grading_order_intake_code ON grading_order (intake_code)');
CALL nxr_add_index_if_missing('payment_record', 'uk_payment_record_payment_no', 'CREATE UNIQUE INDEX uk_payment_record_payment_no ON payment_record (payment_no)');
DROP PROCEDURE nxr_add_index_if_missing;

-- Granular workflow permissions. The legacy manage permission remains valid for
-- administrators while operational roles receive only their domain actions.
INSERT IGNORE INTO sys_menu VALUES('2063', '订单财务', '2006', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:order:payment', '#', 'admin', sysdate(), '', null, '收款、调整和退款');
INSERT IGNORE INTO sys_menu VALUES('2064', '订单仓库', '2006', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:order:warehouse', '#', 'admin', sysdate(), '', null, '扫码、清点和入库异常');
INSERT IGNORE INTO sys_menu VALUES('2065', '订单评级', '2006', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:order:grading', '#', 'admin', sysdate(), '', null, '预处理、评分、封装和质检');
INSERT IGNORE INTO sys_menu VALUES('2066', '订单物流', '2006', '6', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:order:shipping', '#', 'admin', sysdate(), '', null, '回寄方案、出库和物流轨迹');
INSERT IGNORE INTO sys_menu VALUES('2067', '订单客服', '2006', '7', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:order:support', '#', 'admin', sysdate(), '', null, '工单与物流变更');
INSERT IGNORE INTO sys_menu VALUES('2068', '订单配置', '2006', '8', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:order:config', '#', 'admin', sysdate(), '', null, '回寄价格与启停配置');

INSERT IGNORE INTO sys_role VALUES('102', 'NXR仓库', 'nxr_warehouse', 5, 1, 1, 1, '0', '0', 'admin', sysdate(), '', null, '送评订单扫码、收件、清点与异常处理');
INSERT IGNORE INTO sys_role VALUES('103', 'NXR评级', 'nxr_grader', 6, 1, 1, 1, '0', '0', 'admin', sysdate(), '', null, '评级、复审、封装与质检');
INSERT IGNORE INTO sys_role VALUES('104', 'NXR财务', 'nxr_finance', 7, 1, 1, 1, '0', '0', 'admin', sysdate(), '', null, '收款、账务调整与退款');
INSERT IGNORE INTO sys_role VALUES('105', 'NXR客服', 'nxr_support', 8, 1, 1, 1, '0', '0', 'admin', sysdate(), '', null, '客户工单与物流变更');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
    (100, 2063), (100, 2064), (100, 2065), (100, 2066), (100, 2067), (100, 2068),
    (102, 2000), (102, 2006), (102, 2061), (102, 2064),
    (103, 2000), (103, 2006), (103, 2061), (103, 2065),
    (104, 2000), (104, 2006), (104, 2061), (104, 2063),
    (105, 2000), (105, 2006), (105, 2061), (105, 2067);
