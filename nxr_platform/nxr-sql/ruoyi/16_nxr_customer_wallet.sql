-- Merchant company profiles and prepaid, currency-isolated wallets.
-- Wallet balances are derived from immutable credit/debit transactions. No FX
-- conversion is performed: an order can only debit a wallet in its own currency.

CREATE TABLE IF NOT EXISTS merchant_company_profile (
    customer_id BIGINT PRIMARY KEY,
    company_name VARCHAR(191) NOT NULL,
    contact_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_merchant_company_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_wallet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    balance DECIMAL(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_merchant_wallet_customer_currency (customer_id, currency_code),
    CONSTRAINT fk_merchant_wallet_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_wallet_recharge (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recharge_no VARCHAR(48) NOT NULL,
    customer_id BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    provider_code VARCHAR(32) NOT NULL DEFAULT 'manual_transfer',
    payer_reference VARCHAR(255) NULL,
    proof_reference VARCHAR(512) NULL,
    provider_transaction_id VARCHAR(255) NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'pending',
    reviewed_by_user_id BIGINT NULL,
    reviewed_at TIMESTAMP NULL,
    review_note TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_merchant_wallet_recharge_no (recharge_no),
    UNIQUE KEY uk_merchant_wallet_recharge_provider_tx (provider_code, provider_transaction_id),
    KEY idx_merchant_wallet_recharge_customer (customer_id, status_code, created_at),
    CONSTRAINT fk_merchant_wallet_recharge_customer FOREIGN KEY (customer_id) REFERENCES customer_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_wallet_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    wallet_id BIGINT NOT NULL,
    transaction_no VARCHAR(48) NOT NULL,
    transaction_type_code VARCHAR(32) NOT NULL,
    direction_code VARCHAR(16) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    balance_after DECIMAL(18,2) NOT NULL,
    reference_type_code VARCHAR(32) NOT NULL,
    reference_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    note TEXT NULL,
    actor_type_code VARCHAR(32) NOT NULL,
    actor_customer_id BIGINT NULL,
    actor_admin_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_merchant_wallet_transaction_no (transaction_no),
    UNIQUE KEY uk_merchant_wallet_transaction_idempotency (wallet_id, idempotency_key),
    KEY idx_merchant_wallet_transaction_created (wallet_id, created_at, id),
    CONSTRAINT fk_merchant_wallet_transaction_wallet FOREIGN KEY (wallet_id) REFERENCES merchant_wallet(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_wallet_order_payment (
    order_id BIGINT PRIMARY KEY,
    wallet_id BIGINT NOT NULL,
    debit_transaction_id BIGINT NOT NULL,
    refund_transaction_id BIGINT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    status_code VARCHAR(32) NOT NULL DEFAULT 'paid',
    paid_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    refunded_at TIMESTAMP NULL,
    UNIQUE KEY uk_wallet_order_debit (debit_transaction_id),
    UNIQUE KEY uk_wallet_order_refund (refund_transaction_id),
    CONSTRAINT fk_wallet_order_payment_order FOREIGN KEY (order_id) REFERENCES grading_order(id) ON DELETE CASCADE,
    CONSTRAINT fk_wallet_order_payment_wallet FOREIGN KEY (wallet_id) REFERENCES merchant_wallet(id),
    CONSTRAINT fk_wallet_order_payment_debit FOREIGN KEY (debit_transaction_id) REFERENCES merchant_wallet_transaction(id),
    CONSTRAINT fk_wallet_order_payment_refund FOREIGN KEY (refund_transaction_id) REFERENCES merchant_wallet_transaction(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Allow one active basic-grading price for each supported currency while
-- retaining the existing USD row and API behavior.
DROP PROCEDURE IF EXISTS nxr_wallet_upgrade_price_key;
DELIMITER $$
CREATE PROCEDURE nxr_wallet_upgrade_price_key()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'grading_service_price'
          AND index_name = 'uk_grading_service_price_code'
    ) THEN
        ALTER TABLE grading_service_price DROP INDEX uk_grading_service_price_code;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'grading_service_price'
          AND index_name = 'uk_grading_service_price_code_currency'
    ) THEN
        ALTER TABLE grading_service_price
            ADD UNIQUE INDEX uk_grading_service_price_code_currency (price_code, currency_code);
    END IF;
END$$
DELIMITER ;
CALL nxr_wallet_upgrade_price_key();
DROP PROCEDURE nxr_wallet_upgrade_price_key;

-- Finance staff can review recharge evidence and credit wallets. Existing
-- order-payment permission remains separate from wallet funding.
INSERT IGNORE INTO sys_menu VALUES(
    '2069', '企业钱包财务', '2007', '4', '', '', '', '', 1, 0, 'F', '0', '0',
    'nxr:customer:finance', '#', 'admin', sysdate(), '', null, '企业钱包充值审核、余额与流水查询'
);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (100, 2069), (104, 2069);
