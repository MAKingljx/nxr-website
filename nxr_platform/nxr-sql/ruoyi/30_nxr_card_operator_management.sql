-- Card-business account management and durable per-user session revocation.
-- Existing users, passwords, operator role grants and business records are preserved.
-- Fail closed on reused identifiers instead of silently granting an unrelated menu.
-- Temporary CHECK assertion deliberately aborts mysql batch execution on mismatch.
CREATE TEMPORARY TABLE nxr_card_operator_migration_guard (valid TINYINT NOT NULL CHECK (valid=1));
INSERT INTO nxr_card_operator_migration_guard
SELECT IF(
  (SELECT COUNT(*) FROM sys_menu WHERE menu_id=2000 AND path='nxr/cards' AND menu_type='M' AND status='0')=1
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE
    (menu_id=2170 AND NOT COALESCE((perms='nxr:card-user:list' AND parent_id=2000 AND component='nxr/card-operators/index' AND path='users' AND menu_type='C' AND status='0'),0))
    OR (menu_id=2171 AND NOT COALESCE((perms='nxr:card-user:add' AND parent_id=2170 AND menu_type='F' AND status='0'),0))
    OR (menu_id=2172 AND NOT COALESCE((perms='nxr:card-user:edit' AND parent_id=2170 AND menu_type='F' AND status='0'),0))
    OR (menu_id=2173 AND NOT COALESCE((perms='nxr:card-user:resetPwd' AND parent_id=2170 AND menu_type='F' AND status='0'),0))
    OR (perms='nxr:card-user:list' AND menu_id<>2170)
    OR (perms='nxr:card-user:add' AND menu_id<>2171)
    OR (perms='nxr:card-user:edit' AND menu_id<>2172)
    OR (perms='nxr:card-user:resetPwd' AND menu_id<>2173))
  AND (SELECT COUNT(*) FROM sys_role WHERE role_key='nxr_card_manager')=1
  AND (SELECT COUNT(*) FROM sys_role WHERE role_key='nxr_card_manager' AND status='0' AND del_flag='0')=1
  AND (SELECT COUNT(*) FROM sys_role WHERE role_key='nxr_card_super_manager')<=1
  AND NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key='nxr_card_super_manager' AND (status<>'0' OR del_flag<>'0' OR data_scope<>'1'))
  AND NOT EXISTS (SELECT 1 FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.role_id
    LEFT JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE r.role_key='nxr_card_super_manager'
    AND (m.menu_id IS NULL OR (m.menu_id NOT IN(1,2000) AND COALESCE(m.perms,'') NOT IN(
      'nxr:entry:list','nxr:entry:add','nxr:entry:edit','nxr:entry:approve',
      'nxr:media:list','nxr:media:import','nxr:media:publish',
      'nxr:brand:list','nxr:brand:add','nxr:brand:edit',
      'nxr:export:list','nxr:export:generate','nxr:export:remove','nxr:card:global',
      'nxr:card-user:list','nxr:card-user:add','nxr:card-user:edit','nxr:card-user:resetPwd'))))
  ,1,0);
DROP TEMPORARY TABLE nxr_card_operator_migration_guard;

START TRANSACTION;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,
    menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
SELECT 2170,'卡片人员管理',2000,60,'users','nxr/card-operators/index','','NxrCardOperators',1,0,
    'C','0','0','nxr:card-user:list','user','admin',CURRENT_TIMESTAMP,'',NULL,'仅管理纯卡片上传角色账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=2170 OR perms='nxr:card-user:list');

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,
    menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
SELECT 2171,'卡片人员新增',2170,1,'#','','','',1,0,'F','0','0','nxr:card-user:add','#','admin',CURRENT_TIMESTAMP,'',NULL,'固定分配卡片上传角色'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=2171 OR perms='nxr:card-user:add');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,
    menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
SELECT 2172,'卡片人员状态',2170,2,'#','','','',1,0,'F','0','0','nxr:card-user:edit','#','admin',CURRENT_TIMESTAMP,'',NULL,'启停后原有会话失效'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=2172 OR perms='nxr:card-user:edit');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,
    menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
SELECT 2173,'卡片人员密码重置',2170,3,'#','','','',1,0,'F','0','0','nxr:card-user:resetPwd','#','admin',CURRENT_TIMESTAMP,'',NULL,'重置后原有会话失效'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=2173 OR perms='nxr:card-user:resetPwd');

INSERT INTO sys_role(role_name,role_key,role_sort,data_scope,menu_check_strictly,dept_check_strictly,status,del_flag,create_by,create_time,remark)
SELECT '超级卡片管理员','nxr_card_super_manager',7,'1',1,1,'0','0','admin',CURRENT_TIMESTAMP,
    '管理卡片业务及纯卡片上传人员；不授予平台、B端或财务管理权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key='nxr_card_super_manager');

-- Copy only the card-business grants into the new role. Never widen the existing
-- nxr_card_manager role or grant any system:user/system:role/system:menu permission.
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT manager.role_id,m.menu_id FROM sys_role manager
JOIN sys_role operator ON operator.role_key='nxr_card_manager' AND operator.status='0' AND operator.del_flag='0'
JOIN sys_role_menu rm ON rm.role_id=operator.role_id
JOIN sys_menu m ON m.menu_id=rm.menu_id AND m.status='0'
WHERE manager.role_key='nxr_card_super_manager' AND manager.status='0' AND manager.del_flag='0'
  AND (m.menu_id IN (1,2000) OR m.perms IN (
    'nxr:entry:list','nxr:entry:add','nxr:entry:edit','nxr:entry:approve',
    'nxr:media:list','nxr:media:import','nxr:media:publish',
    'nxr:brand:list','nxr:brand:add','nxr:brand:edit',
    'nxr:export:list','nxr:export:generate','nxr:export:remove','nxr:card:global'));
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.status='0' AND (
  (m.menu_id=2000 AND m.path='nxr/cards' AND m.menu_type='M')
  OR (m.menu_id=2170 AND m.perms='nxr:card-user:list' AND m.parent_id=2000 AND m.component='nxr/card-operators/index')
  OR (m.menu_id=2171 AND m.perms='nxr:card-user:add' AND m.parent_id=2170)
  OR (m.menu_id=2172 AND m.perms='nxr:card-user:edit' AND m.parent_id=2170)
  OR (m.menu_id=2173 AND m.perms='nxr:card-user:resetPwd' AND m.parent_id=2170))
WHERE r.role_key='nxr_card_super_manager' AND r.status='0' AND r.del_flag='0';

COMMIT;
