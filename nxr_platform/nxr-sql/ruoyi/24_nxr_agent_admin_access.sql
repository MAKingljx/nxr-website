-- RuoYi agent workspace access. No real operator or merchant account is bound by this migration.
-- Existing agent custody data remains unchanged. Run after migration 23; safe to rerun.
CREATE TABLE IF NOT EXISTS agent_operator_binding (
    sys_user_id BIGINT PRIMARY KEY,
    merchant_customer_id BIGINT NOT NULL,
    active TINYINT NOT NULL DEFAULT 1,
    created_by_user_id BIGINT NOT NULL,
    updated_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_operator_user FOREIGN KEY (sys_user_id) REFERENCES sys_user(user_id),
    CONSTRAINT fk_agent_operator_company FOREIGN KEY (merchant_customer_id) REFERENCES customer_account(id),
    CONSTRAINT ck_agent_operator_active CHECK (active IN (0,1)),
    INDEX ix_agent_operator_company (merchant_customer_id,active,sys_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_operator_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sys_user_id BIGINT NOT NULL,
    merchant_customer_id BIGINT NOT NULL,
    active TINYINT NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_operator_event_user FOREIGN KEY (sys_user_id) REFERENCES sys_user(user_id),
    CONSTRAINT fk_agent_operator_event_company FOREIGN KEY (merchant_customer_id) REFERENCES customer_account(id),
    CONSTRAINT ck_agent_operator_event_active CHECK (active IN (0,1)),
    INDEX ix_agent_operator_event_user (sys_user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

START TRANSACTION;
INSERT IGNORE INTO sys_menu (
    menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,
    menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark
) VALUES
(2140,'代理工作台',0,8,'nxr/agent-workbench','nxr/agent-workbench/index','','NxrAgentWorkbench',1,0,'C','0','0','nxr:agent:workbench','peoples','admin',CURRENT_TIMESTAMP,'',NULL,'绑定企业的客户来件、送评和客户回寄'),
(2141,'代理账号绑定',2140,1,'#','','','',1,0,'F','0','0','nxr:agent:manage','#','admin',CURRENT_TIMESTAMP,'',NULL,'仅无限制平台管理员可管理代理后台账号绑定');

-- Keep this independent workspace out of the existing card-management container.
UPDATE sys_menu SET parent_id=0,path='nxr/agent-workbench'
WHERE menu_id=2140 AND component='nxr/agent-workbench/index' AND perms='nxr:agent:workbench';

INSERT INTO sys_role(role_name,role_key,role_sort,data_scope,menu_check_strictly,dept_check_strictly,status,del_flag,create_by,create_time,remark)
SELECT '代理企业操作员','nxr_agent',20,'5',1,1,'0','0','admin',CURRENT_TIMESTAMP,'仅可进入已绑定企业的代理工作台'
WHERE NOT EXISTS(SELECT 1 FROM sys_role WHERE role_key='nxr_agent');

-- This dedicated role owns only the agent workspace; it never receives global operations.
DELETE FROM sys_role_menu WHERE role_id IN(SELECT role_id FROM sys_role WHERE role_key='nxr_agent') AND menu_id<>2140;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT role_id,2140 FROM sys_role WHERE role_key='nxr_agent';
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id IN(2140,2141) WHERE r.role_key='admin' AND r.del_flag='0';
COMMIT;
