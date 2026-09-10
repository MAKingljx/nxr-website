-- ----------------------------------------------------------------------------
-- NXR provider-backed payment channels.
--
-- Additive schema and one super-admin settings route. Channels are deliberately
-- seeded disabled and without credentials; applying this migration never makes
-- checkout available and never touches legacy Flask Data/ databases.
-- ----------------------------------------------------------------------------

START TRANSACTION;

CREATE TABLE IF NOT EXISTS payment_channel_config (
    provider_code VARCHAR(32) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    mode_code VARCHAR(16) NOT NULL,
    is_enabled TINYINT NOT NULL DEFAULT 0,
    supported_currencies VARCHAR(255) NOT NULL,
    api_base_url VARCHAR(255) NOT NULL,
    notify_url VARCHAR(512) NULL,
    return_url VARCHAR(512) NULL,
    credentials_ciphertext LONGTEXT NULL,
    config_version INT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    updated_by_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_payment_channel_enabled_sort (is_enabled, sort_order),
    CONSTRAINT fk_payment_channel_updated_by
        FOREIGN KEY (updated_by_user_id) REFERENCES sys_user(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO payment_channel_config (
    provider_code, display_name, mode_code, is_enabled, supported_currencies,
    api_base_url, notify_url, return_url, credentials_ciphertext, sort_order
) VALUES
('wechat_pay_native', '微信支付', 'live',    0, 'CNY', 'https://api.mch.weixin.qq.com',          NULL, NULL, NULL, 10),
('alipay',            '支付宝',   'sandbox', 0, 'CNY', 'https://openapi.alipaydev.com/gateway.do', NULL, NULL, NULL, 20),
('paypal',            'PayPal',   'sandbox', 0, 'USD,EUR,GBP,CAD,AUD,JPY,HKD,SGD,CNY',
 'https://api-m.sandbox.paypal.com', NULL, NULL, NULL, 30);

CREATE TABLE IF NOT EXISTS payment_attempt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    payment_record_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    active_order_id BIGINT NULL,
    customer_id BIGINT NOT NULL,
    provider_code VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    merchant_order_no VARCHAR(64) NOT NULL,
    provider_order_id VARCHAR(128) NULL,
    provider_transaction_id VARCHAR(255) NULL,
    expected_amount DECIMAL(12,2) NOT NULL,
    expected_currency CHAR(3) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'creating',
    payment_url VARCHAR(1024) NULL,
    qr_payload VARCHAR(1024) NULL,
    verified_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_attempt_customer_idempotency (customer_id, idempotency_key),
    UNIQUE KEY uk_payment_attempt_active_order (active_order_id),
    UNIQUE KEY uk_payment_attempt_provider_order (provider_code, provider_order_id),
    KEY idx_payment_attempt_order_created (order_id, created_at),
    KEY idx_payment_attempt_payment (payment_record_id),
    CONSTRAINT fk_payment_attempt_payment
        FOREIGN KEY (payment_record_id) REFERENCES payment_record(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_attempt_order
        FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_attempt_active_order
        FOREIGN KEY (active_order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_attempt_customer
        FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_finance_exception (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    payment_record_id BIGINT NOT NULL,
    payment_attempt_id BIGINT NOT NULL,
    provider_code VARCHAR(32) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    provider_transaction_id VARCHAR(255) NOT NULL,
    exception_type_code VARCHAR(32) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    resolution_status_code VARCHAR(32) NOT NULL DEFAULT 'open',
    resolved_by_user_id BIGINT NULL,
    resolution_note TEXT NULL,
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_finance_exception_event (provider_code, provider_event_id),
    KEY idx_payment_finance_exception_order (order_id, resolution_status_code, created_at),
    CONSTRAINT fk_payment_finance_exception_order
        FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_finance_exception_payment
        FOREIGN KEY (payment_record_id) REFERENCES payment_record(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_finance_exception_attempt
        FOREIGN KEY (payment_attempt_id) REFERENCES payment_attempt(id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_finance_exception_resolved_by
        FOREIGN KEY (resolved_by_user_id) REFERENCES sys_user(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Keep payment credentials behind one dedicated finance permission. No normal
-- role is granted this edge automatically; RuoYi super administrators retain
-- their built-in bypass and can delegate it explicitly through Role Settings.
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_by, create_time, update_by, update_time, remark
) VALUES (
    2017, '支付渠道', 1, 4, 'payment-settings', 'nxr/payment-settings/index', '', 'NxrPaymentSettings',
    1, 0, 'C', '0', '0', 'nxr:payment:config', 'money',
    'admin', sysdate(), '', NULL, '支付渠道配置；响应脱敏，写入不记录请求或响应参数'
);

UPDATE sys_menu
SET menu_name = '支付渠道', parent_id = 1, order_num = 4,
    path = 'payment-settings', component = 'nxr/payment-settings/index', query = '',
    route_name = 'NxrPaymentSettings', is_frame = 1, is_cache = 0,
    menu_type = 'C', visible = '0', status = '0', perms = 'nxr:payment:config',
    icon = 'money', update_by = 'admin', update_time = sysdate(),
    remark = '支付渠道配置；响应脱敏，写入不记录请求或响应参数'
WHERE menu_id = 2017;

COMMIT;
