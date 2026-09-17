CREATE TABLE sys_user(user_id BIGINT AUTO_INCREMENT PRIMARY KEY,user_name VARCHAR(30) NOT NULL,nick_name VARCHAR(30) NOT NULL,status CHAR(1),del_flag CHAR(1),password VARCHAR(100),user_type VARCHAR(2),create_by VARCHAR(64),create_time TIMESTAMP,pwd_update_date TIMESTAMP);
CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_key VARCHAR(100),status CHAR(1),del_flag CHAR(1));
CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,perms VARCHAR(100),status CHAR(1));
CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT,PRIMARY KEY(user_id,role_id));
CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id));
CREATE TABLE customer_account(id BIGINT AUTO_INCREMENT PRIMARY KEY,display_name VARCHAR(128),email VARCHAR(191) UNIQUE,account_type_code VARCHAR(32),is_active TINYINT,mobile VARCHAR(64),password_hash VARCHAR(100),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE merchant_company_profile(customer_id BIGINT PRIMARY KEY,company_name VARCHAR(191),contact_name VARCHAR(128) DEFAULT 'Contact',created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE commerce_staff_business_line(user_id BIGINT,business_line_id BIGINT);
CREATE TABLE commerce_business_line(id BIGINT PRIMARY KEY,is_active TINYINT);
CREATE TABLE commerce_staff_work_center(user_id BIGINT,work_center_id BIGINT);
CREATE TABLE commerce_work_center(id BIGINT PRIMARY KEY,is_active TINYINT);
CREATE TABLE agent_operator_binding (
    sys_user_id BIGINT PRIMARY KEY,
    merchant_customer_id BIGINT NOT NULL,
    active TINYINT NOT NULL DEFAULT 1,
    created_by_user_id BIGINT NOT NULL,
    updated_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_operator_user FOREIGN KEY(sys_user_id) REFERENCES sys_user(user_id),
    CONSTRAINT fk_agent_operator_company FOREIGN KEY(merchant_customer_id) REFERENCES customer_account(id),
    CONSTRAINT ck_agent_operator_active CHECK(active IN(0,1))
);
CREATE TABLE agent_operator_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sys_user_id BIGINT NOT NULL,
    merchant_customer_id BIGINT NOT NULL,
    active TINYINT NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_operator_event_user FOREIGN KEY(sys_user_id) REFERENCES sys_user(user_id),
    CONSTRAINT fk_agent_operator_event_company FOREIGN KEY(merchant_customer_id) REFERENCES customer_account(id)
);
INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(1,'admin','Administrator','0','0'),(10,'agent-a','Agent A','0','0'),(20,'agent-b','Agent B','0','0'),
 (30,'unbound','Unbound','0','0'),(40,'scoped-manager','Scoped manager','0','0'),(50,'global-staff','Global staff','0','0'),(60,'spare','Spare','0','0');
INSERT INTO sys_role VALUES(1,'admin','0','0'),(10,'nxr_agent','0','0'),(20,'scoped_manager','0','0'),(30,'finance','0','0'),(40,'empty_role','0','0');
INSERT INTO sys_menu VALUES(2140,'nxr:agent:workbench','0'),(2141,'nxr:agent:manage','0'),(2100,'nxr:customer:finance','0'),(2000,'','0');
INSERT INTO sys_role_menu VALUES(1,2140),(1,2141),(1,2100),(10,2140),(10,2000),(20,2141),(30,2100);
INSERT INTO sys_user_role VALUES(1,1),(40,20),(50,30),(60,40);
INSERT INTO customer_account(id,display_name,email,account_type_code,is_active) VALUES(101,'Merchant A','a@example.test','merchant',1),(202,'Merchant B','b@example.test','merchant',1),
 (303,'Collector','collector@example.test','customer',1),(404,'Disabled merchant','disabled@example.test','merchant',0);
INSERT INTO merchant_company_profile(customer_id,company_name) VALUES(101,'Company A'),(202,'Company B');

CREATE TABLE customer_session(id BIGINT AUTO_INCREMENT PRIMARY KEY,customer_id BIGINT,token_hash VARCHAR(64),expires_at TIMESTAMP);
CREATE TABLE agent_client(id BIGINT PRIMARY KEY,merchant_customer_id BIGINT);
CREATE TABLE agent_card(id BIGINT PRIMARY KEY,merchant_customer_id BIGINT,status_code VARCHAR(32));
CREATE TABLE merchant_order_batch(id BIGINT PRIMARY KEY,merchant_customer_id BIGINT,status_code VARCHAR(32));
INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(70,'read-manager','Read manager','0','0'),(80,'provision-manager','Provision manager','0','0');
INSERT INTO sys_role VALUES(50,'admin','0','0'),(60,'admin','0','0');
INSERT INTO sys_menu VALUES(2142,'nxr:partner:list','0'),(2143,'nxr:partner:manage','0'),(100,'system:user:add','0'),(2101,'nxr:customer:manage','0');
INSERT INTO sys_user_role VALUES(70,50),(80,60);
INSERT INTO sys_role_menu VALUES(50,2142),(60,2142),(60,2143),(60,100),(60,2101);
CREATE TABLE merchant_wallet (id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL, currency_code VARCHAR(8) NOT NULL, balance DECIMAL(18,2) NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(customer_id,currency_code));
CREATE TABLE merchant_wallet_recharge (id BIGINT AUTO_INCREMENT PRIMARY KEY, recharge_no VARCHAR(48) NOT NULL UNIQUE, customer_id BIGINT NOT NULL, currency_code VARCHAR(8) NOT NULL, amount DECIMAL(18,2) NOT NULL, provider_code VARCHAR(32) NOT NULL, payer_reference VARCHAR(255), proof_reference VARCHAR(512), provider_transaction_id VARCHAR(255), status_code VARCHAR(32) NOT NULL, reviewed_by_user_id BIGINT, reviewed_at TIMESTAMP, review_note TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(provider_code,provider_transaction_id));
CREATE TABLE merchant_wallet_transaction (id BIGINT AUTO_INCREMENT PRIMARY KEY, wallet_id BIGINT NOT NULL, transaction_no VARCHAR(48) NOT NULL UNIQUE, transaction_type_code VARCHAR(32) NOT NULL, direction_code VARCHAR(16) NOT NULL, amount DECIMAL(18,2) NOT NULL, balance_after DECIMAL(18,2) NOT NULL, reference_type_code VARCHAR(32) NOT NULL, reference_id BIGINT NOT NULL, idempotency_key VARCHAR(128) NOT NULL, note TEXT, actor_type_code VARCHAR(32) NOT NULL, actor_customer_id BIGINT, actor_admin_user_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(wallet_id,idempotency_key));
CREATE TABLE merchant_wallet_order_payment (order_id BIGINT PRIMARY KEY, wallet_id BIGINT NOT NULL, debit_transaction_id BIGINT NOT NULL UNIQUE, refund_transaction_id BIGINT UNIQUE, idempotency_key VARCHAR(128) NOT NULL, amount DECIMAL(18,2) NOT NULL, currency_code VARCHAR(8) NOT NULL, status_code VARCHAR(32) NOT NULL, paid_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, refunded_at TIMESTAMP);
CREATE TABLE payment_attempt (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, status_code VARCHAR(32) NOT NULL);
-- Platform partner management and a customer-facing submission workspace.
-- Keeps existing merchant/profile/wallet/binding relationships and financial ledgers intact.
CREATE TABLE IF NOT EXISTS partner_management_command (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_user_id BIGINT NOT NULL,
    operation_code VARCHAR(32) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    sys_user_id BIGINT NULL,
    recharge_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_partner_command UNIQUE(actor_user_id,operation_code,request_key),
    CONSTRAINT fk_partner_command_actor FOREIGN KEY(actor_user_id) REFERENCES sys_user(user_id),
    CONSTRAINT fk_partner_command_customer FOREIGN KEY(customer_id) REFERENCES customer_account(id),
    CONSTRAINT fk_partner_command_operator FOREIGN KEY(sys_user_id) REFERENCES sys_user(user_id),
    CONSTRAINT fk_partner_command_recharge FOREIGN KEY(recharge_id) REFERENCES merchant_wallet_recharge(id),
    CONSTRAINT ck_partner_command_operation CHECK(operation_code IN('provision','recharge')),
    INDEX ix_partner_command_customer(customer_id,id)
);
