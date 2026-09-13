CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(64),nick_name VARCHAR(64),status CHAR(1),del_flag CHAR(1));
CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_key VARCHAR(100),status CHAR(1),del_flag CHAR(1));
CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,perms VARCHAR(100),status CHAR(1));
CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT,PRIMARY KEY(user_id,role_id));
CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id));
CREATE TABLE customer_account(id BIGINT PRIMARY KEY,display_name VARCHAR(128),email VARCHAR(191),account_type_code VARCHAR(32),is_active TINYINT);
CREATE TABLE merchant_company_profile(customer_id BIGINT PRIMARY KEY,company_name VARCHAR(191));
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
INSERT INTO sys_user VALUES(1,'admin','Administrator','0','0'),(10,'agent-a','Agent A','0','0'),(20,'agent-b','Agent B','0','0'),
 (30,'unbound','Unbound','0','0'),(40,'scoped-manager','Scoped manager','0','0'),(50,'global-staff','Global staff','0','0'),(60,'spare','Spare','0','0');
INSERT INTO sys_role VALUES(1,'admin','0','0'),(10,'nxr_agent','0','0'),(20,'scoped_manager','0','0'),(30,'finance','0','0'),(40,'empty_role','0','0');
INSERT INTO sys_menu VALUES(2140,'nxr:agent:workbench','0'),(2141,'nxr:agent:manage','0'),(2100,'nxr:customer:finance','0'),(2000,'','0');
INSERT INTO sys_role_menu VALUES(1,2140),(1,2141),(1,2100),(10,2140),(10,2000),(20,2141),(30,2100);
INSERT INTO sys_user_role VALUES(1,1),(40,20),(50,30),(60,40);
INSERT INTO customer_account VALUES(101,'Merchant A','a@example.test','merchant',1),(202,'Merchant B','b@example.test','merchant',1),
 (303,'Collector','collector@example.test','customer',1),(404,'Disabled merchant','disabled@example.test','merchant',0);
INSERT INTO merchant_company_profile VALUES(101,'Company A'),(202,'Company B');
