-- Configurable customer pricing, weight-based return shipping, and staff order scope.
-- No example prices, provider credentials, or inferred geographic work centres are seeded.

DROP PROCEDURE IF EXISTS nxr_commerce_add_column;
DELIMITER $$
CREATE PROCEDURE nxr_commerce_add_column(
    IN target_table VARCHAR(64), IN target_column VARCHAR(64), IN alter_statement TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = target_table AND column_name = target_column
    ) THEN
        SET @nxr_commerce_sql = alter_statement;
        PREPARE nxr_commerce_stmt FROM @nxr_commerce_sql;
        EXECUTE nxr_commerce_stmt;
        DEALLOCATE PREPARE nxr_commerce_stmt;
    END IF;
END$$
DELIMITER ;

CREATE TABLE IF NOT EXISTS commerce_business_line (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    line_code VARCHAR(48) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    order_origin_code VARCHAR(32) NOT NULL,
    is_default TINYINT NOT NULL DEFAULT 0,
    is_active TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_commerce_business_line_code (line_code),
    KEY idx_commerce_business_line_origin (order_origin_code, is_active, is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_work_center (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    center_code VARCHAR(48) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    is_default TINYINT NOT NULL DEFAULT 0,
    is_active TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_commerce_work_center_code (center_code),
    KEY idx_commerce_work_center_active_default (is_active, is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_customer_routing (
    customer_id BIGINT PRIMARY KEY,
    business_line_id BIGINT NOT NULL,
    work_center_id BIGINT NOT NULL,
    order_origin_code VARCHAR(32) NOT NULL DEFAULT 'customer_submission',
    updated_by_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_commerce_customer_routing_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_commerce_customer_routing_line FOREIGN KEY (business_line_id) REFERENCES commerce_business_line(id),
    CONSTRAINT fk_commerce_customer_routing_center FOREIGN KEY (work_center_id) REFERENCES commerce_work_center(id),
    CONSTRAINT fk_commerce_customer_routing_user FOREIGN KEY (updated_by_user_id) REFERENCES sys_user(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_price_policy (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    policy_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    customer_segment_code VARCHAR(16) NOT NULL,
    customer_id BIGINT NULL,
    currency_code CHAR(3) NOT NULL,
    minimum_quantity INT NOT NULL DEFAULT 1,
    maximum_quantity INT NULL,
    unit_price DECIMAL(18,2) NOT NULL,
    priority_no INT NOT NULL DEFAULT 0,
    is_active TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_commerce_price_policy_code (policy_code),
    KEY idx_commerce_price_match (currency_code, is_active, customer_id, customer_segment_code, minimum_quantity, priority_no),
    CONSTRAINT fk_commerce_price_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_shipping_policy (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    policy_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    destination_country VARCHAR(128) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    per_card_weight_grams INT NOT NULL,
    packaging_weight_grams INT NOT NULL,
    first_weight_grams INT NOT NULL,
    first_weight_price DECIMAL(18,2) NOT NULL,
    additional_weight_grams INT NOT NULL,
    additional_weight_price DECIMAL(18,2) NOT NULL,
    discount_quantity_threshold INT NULL,
    discount_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    free_shipping_quantity_threshold INT NULL,
    priority_no INT NOT NULL DEFAULT 0,
    is_active TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_commerce_shipping_policy_code (policy_code),
    KEY idx_commerce_shipping_match (destination_country, currency_code, is_active, priority_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Safe on reruns of an earlier local draft that used a short country-code-only column.
ALTER TABLE commerce_shipping_policy MODIFY COLUMN destination_country VARCHAR(128) NOT NULL;

CREATE TABLE IF NOT EXISTS commerce_staff_business_line (
    user_id BIGINT NOT NULL,
    business_line_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, business_line_id),
    CONSTRAINT fk_commerce_staff_line_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_commerce_staff_line_line FOREIGN KEY (business_line_id) REFERENCES commerce_business_line(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_staff_work_center (
    user_id BIGINT NOT NULL,
    work_center_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, work_center_id),
    CONSTRAINT fk_commerce_staff_center_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_commerce_staff_center_center FOREIGN KEY (work_center_id) REFERENCES commerce_work_center(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CALL nxr_commerce_add_column('grading_order', 'order_origin_code',
    'ALTER TABLE grading_order ADD COLUMN order_origin_code VARCHAR(32) NULL AFTER customer_id');
CALL nxr_commerce_add_column('grading_order', 'business_line_id',
    'ALTER TABLE grading_order ADD COLUMN business_line_id BIGINT NULL AFTER order_origin_code');
CALL nxr_commerce_add_column('grading_order', 'work_center_id',
    'ALTER TABLE grading_order ADD COLUMN work_center_id BIGINT NULL AFTER business_line_id');
CALL nxr_commerce_add_column('grading_order', 'commerce_price_policy_id',
    'ALTER TABLE grading_order ADD COLUMN commerce_price_policy_id BIGINT NULL AFTER currency_code');
CALL nxr_commerce_add_column('grading_order', 'commerce_shipping_policy_id',
    'ALTER TABLE grading_order ADD COLUMN commerce_shipping_policy_id BIGINT NULL AFTER commerce_price_policy_id');
CALL nxr_commerce_add_column('grading_order', 'quoted_unit_price',
    'ALTER TABLE grading_order ADD COLUMN quoted_unit_price DECIMAL(18,2) NULL AFTER commerce_shipping_policy_id');
CALL nxr_commerce_add_column('grading_order', 'quoted_card_weight_grams',
    'ALTER TABLE grading_order ADD COLUMN quoted_card_weight_grams INT NULL AFTER quoted_unit_price');
CALL nxr_commerce_add_column('grading_order', 'quoted_packaging_weight_grams',
    'ALTER TABLE grading_order ADD COLUMN quoted_packaging_weight_grams INT NULL AFTER quoted_card_weight_grams');
CALL nxr_commerce_add_column('grading_order', 'quoted_chargeable_weight_grams',
    'ALTER TABLE grading_order ADD COLUMN quoted_chargeable_weight_grams INT NULL AFTER quoted_packaging_weight_grams');
CALL nxr_commerce_add_column('grading_order', 'pricing_source_code',
    'ALTER TABLE grading_order ADD COLUMN pricing_source_code VARCHAR(32) NULL AFTER quoted_chargeable_weight_grams');
CALL nxr_commerce_add_column('grading_order', 'shipping_source_code',
    'ALTER TABLE grading_order ADD COLUMN shipping_source_code VARCHAR(32) NULL AFTER pricing_source_code');

-- Unbound submissions are visible to restricted staff only after an explicit owned-inventory assignment.
-- Existing records remain NULL and are not guessed into a business line or geographic center.
CALL nxr_commerce_add_column('grading_submission', 'order_origin_code',
    'ALTER TABLE grading_submission ADD COLUMN order_origin_code VARCHAR(32) NULL AFTER id');
CALL nxr_commerce_add_column('grading_submission', 'business_line_id',
    'ALTER TABLE grading_submission ADD COLUMN business_line_id BIGINT NULL AFTER order_origin_code');
CALL nxr_commerce_add_column('grading_submission', 'work_center_id',
    'ALTER TABLE grading_submission ADD COLUMN work_center_id BIGINT NULL AFTER business_line_id');

DROP PROCEDURE nxr_commerce_add_column;

DROP PROCEDURE IF EXISTS nxr_commerce_add_index;
DELIMITER $$
CREATE PROCEDURE nxr_commerce_add_index(IN target_table VARCHAR(64), IN target_index VARCHAR(64), IN index_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name=target_table AND index_name=target_index
    ) THEN
        SET @nxr_commerce_index_sql=index_ddl;
        PREPARE nxr_commerce_index_stmt FROM @nxr_commerce_index_sql;
        EXECUTE nxr_commerce_index_stmt;
        DEALLOCATE PREPARE nxr_commerce_index_stmt;
    END IF;
END$$
DELIMITER ;
CALL nxr_commerce_add_index('grading_order', 'idx_grading_order_commerce_scope',
    'ALTER TABLE grading_order ADD INDEX idx_grading_order_commerce_scope (business_line_id, work_center_id, id)');
CALL nxr_commerce_add_index('grading_submission', 'idx_grading_submission_commerce_scope',
    'ALTER TABLE grading_submission ADD INDEX idx_grading_submission_commerce_scope (order_origin_code, business_line_id, work_center_id, id)');
DROP PROCEDURE nxr_commerce_add_index;

INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_by, create_time, update_by, update_time, remark
) VALUES
(2131, '商业策略配置', 2006, 91, '#', '', '', '', 1, 0, 'F', '0', '0', 'nxr:commerce:config', '#', 'admin', sysdate(), '', NULL, '分层价格、重量运费、业务线和作业中心配置'),
(2132, '订单行级范围', 2006, 92, '#', '', '', '', 1, 0, 'F', '0', '0', 'nxr:commerce:scope', '#', 'admin', sysdate(), '', NULL, '员工业务线和作业中心映射');

UPDATE sys_menu SET menu_name='商业策略配置', parent_id=2006, perms='nxr:commerce:config', update_time=sysdate() WHERE menu_id=2131;
UPDATE sys_menu SET menu_name='订单行级范围', parent_id=2006, perms='nxr:commerce:scope', update_time=sysdate() WHERE menu_id=2132;
