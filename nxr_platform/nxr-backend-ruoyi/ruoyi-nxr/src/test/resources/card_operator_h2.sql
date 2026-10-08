CREATE TABLE sys_user(user_id BIGINT AUTO_INCREMENT PRIMARY KEY,dept_id BIGINT,user_name VARCHAR(30) NOT NULL,nick_name VARCHAR(30) NOT NULL,
 password VARCHAR(100),user_type VARCHAR(2) DEFAULT '00',status CHAR(1) DEFAULT '0',del_flag CHAR(1) DEFAULT '0',
 create_by VARCHAR(64),create_time TIMESTAMP,pwd_update_date TIMESTAMP,update_by VARCHAR(64),update_time TIMESTAMP);
ALTER TABLE sys_user ALTER COLUMN user_id RESTART WITH 1000;
CREATE TABLE sys_role(role_id BIGINT PRIMARY KEY,role_key VARCHAR(100),status CHAR(1) DEFAULT '0',del_flag CHAR(1) DEFAULT '0');
CREATE TABLE sys_user_role(user_id BIGINT,role_id BIGINT,PRIMARY KEY(user_id,role_id));
CREATE TABLE sys_menu(menu_id BIGINT PRIMARY KEY,perms VARCHAR(100),status CHAR(1) DEFAULT '0');
CREATE TABLE sys_role_menu(role_id BIGINT,menu_id BIGINT,PRIMARY KEY(role_id,menu_id));
CREATE TABLE agent_operator_binding(sys_user_id BIGINT PRIMARY KEY,merchant_customer_id BIGINT,active BOOLEAN);
CREATE TABLE commerce_staff_business_line(user_id BIGINT,business_line_id BIGINT,PRIMARY KEY(user_id,business_line_id));
CREATE TABLE commerce_staff_work_center(user_id BIGINT,work_center_id BIGINT,PRIMARY KEY(user_id,work_center_id));
CREATE TABLE customer_account(id BIGINT PRIMARY KEY,email VARCHAR(191));
INSERT INTO sys_role VALUES(1,'admin','0','0'),(107,'nxr_card_manager','0','0'),(108,'nxr_card_super_manager','0','0'),(109,'finance','0','0'),(110,'nxr_agent','0','0');
INSERT INTO sys_menu VALUES(1,'nxr:card-user:list','0'),(2,'nxr:card-user:add','0'),(3,'nxr:card-user:edit','0'),(4,'nxr:card-user:resetPwd','0'),
 (5,'nxr:entry:list','0'),(6,'nxr:entry:add','0'),(7,'nxr:brand:list','0'),(8,'nxr:card:global','0'),(9,'nxr:customer:finance','0');
INSERT INTO sys_role_menu VALUES(108,1),(108,2),(108,3),(108,4),(108,5),(108,6),(108,7),(108,8),(107,5),(107,6),(107,7),(107,8),(109,9);
INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag,create_by,create_time) VALUES
 (1,'admin','Platform admin','old','0','0','admin',CURRENT_TIMESTAMP),
 (50,'nxr_card_super_01','Card manager','old','0','0','admin',CURRENT_TIMESTAMP),
 (51,'nxr_card_super_02','Card manager 2','old','0','0','admin',CURRENT_TIMESTAMP),
 (101,'nxr_card_admin_01','Uploader one','old','0','0','admin',CURRENT_TIMESTAMP),
 (102,'nxr_card_admin_02','Uploader two','old','1','0','admin',CURRENT_TIMESTAMP),
 (201,'finance_staff','Finance','old','0','0','admin',CURRENT_TIMESTAMP),
 (202,'agent_staff','Agent','old','0','0','admin',CURRENT_TIMESTAMP),
 (203,'mixed_staff','Mixed','old','0','0','admin',CURRENT_TIMESTAMP),
 (204,'deleted_staff','Deleted','old','0','2','admin',CURRENT_TIMESTAMP),
 (205,'customer_identity','Customer identity','old','0','0','admin',CURRENT_TIMESTAMP),
 (206,'line_staff','Line','old','0','0','admin',CURRENT_TIMESTAMP),
 (207,'center_staff','Center','old','0','0','admin',CURRENT_TIMESTAMP),
 (208,'other_card_super','Other manager','old','0','0','admin',CURRENT_TIMESTAMP),
 (209,'orphan_role','Orphan','old','0','0','admin',CURRENT_TIMESTAMP),
 (210,'unassigned','Unassigned','old','0','0','admin',CURRENT_TIMESTAMP),
 (211,'disabled_agent_binding','Inactive binding','old','0','0','admin',CURRENT_TIMESTAMP);
UPDATE sys_user SET user_type='01' WHERE user_id=205;
INSERT INTO sys_user_role VALUES(1,1),(50,108),(51,108),(101,107),(102,107),(201,109),(202,110),(203,107),(203,109),
 (204,107),(205,107),(206,107),(207,107),(208,108),(209,107),(209,999),(211,107);
INSERT INTO agent_operator_binding VALUES(202,900,FALSE),(211,900,FALSE);
INSERT INTO commerce_staff_business_line VALUES(206,1);
INSERT INTO commerce_staff_work_center VALUES(207,1);
-- The same numeric ID in the independent customer identity namespace is not a backend binding.
INSERT INTO customer_account VALUES(101,'separate@example.test');

-- UTC production-style DATETIME values; response tests assert epoch semantics.
UPDATE sys_user SET create_time=TIMESTAMP '2026-10-08 10:30:00',update_time=TIMESTAMP '2026-10-08 10:45:30' WHERE user_id=101;
