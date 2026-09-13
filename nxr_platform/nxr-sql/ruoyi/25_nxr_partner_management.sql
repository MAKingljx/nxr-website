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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

START TRANSACTION;
UPDATE sys_menu SET menu_name='送评工作台',parent_id=0,path='nxr/submission-workbench',
    component='nxr/agent-workbench/index',route_name='NxrSubmissionWorkbench',remark='子代理代收客户卡片、集中送评及客户回寄',update_time=CURRENT_TIMESTAMP
WHERE menu_id=2140 AND perms='nxr:agent:workbench';
UPDATE sys_menu SET menu_name='子代理账号绑定',parent_id=2142,remark='平台管理子代理后台账号绑定',update_time=CURRENT_TIMESTAMP
WHERE menu_id=2141 AND perms='nxr:agent:manage';
UPDATE sys_role SET role_name='子代理操作员',remark='仅可操作绑定子代理的送评工作台',update_time=CURRENT_TIMESTAMP
WHERE role_key='nxr_agent';

INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,
    menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(2142,'子代理管理',0,7,'nxr/partners','nxr/partners/index','','NxrPartners',1,0,'C','0','0','nxr:partner:list','peoples','admin',CURRENT_TIMESTAMP,'',NULL,'平台子代理企业、账号及业务管理'),
(2143,'子代理管理操作',2142,1,'#','','','',1,0,'F','0','0','nxr:partner:manage','#','admin',CURRENT_TIMESTAMP,'',NULL,'开通与管理仍需账号、客户或财务对应权限');
UPDATE sys_menu SET menu_name='子代理管理',parent_id=0,path='nxr/partners',component='nxr/partners/index',perms='nxr:partner:list',update_time=CURRENT_TIMESTAMP WHERE menu_id=2142;
UPDATE sys_menu SET parent_id=2142,perms='nxr:partner:manage',update_time=CURRENT_TIMESTAMP WHERE menu_id=2143;

DELETE FROM sys_role_menu WHERE role_id IN(SELECT role_id FROM sys_role WHERE role_key='nxr_agent') AND menu_id<>2140;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT role_id,2140 FROM sys_role WHERE role_key='nxr_agent';
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id IN(2142,2143) WHERE r.role_key='admin' AND r.del_flag='0';
COMMIT;
