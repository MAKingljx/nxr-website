-- ----------------------------------------------------------------------------
-- NXR Java/RuoYi admin navigation alignment.
--
-- Aligns the Java menu with the current Python admin workflow while preserving
-- Java-only customer and order capabilities. This migration changes menu and
-- role metadata only; it never reads or writes NXR business records.
--
-- Rerunnable: INSERT IGNORE creates missing nodes, UPDATE converges metadata,
-- and role grants are replaced only for the menu IDs owned by this migration.
-- Prerequisite: 03, 04, and 05 have already created their feature menus.
-- ----------------------------------------------------------------------------

START TRANSACTION;

-- Create the NXR root, Python-style sections, dashboard permission, and the
-- three query-driven entry shortcuts when upgrading an existing database.
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_by, create_time, update_by, update_time, remark
) VALUES
(2000, 'NXR后台',   0,    0, 'nxr',              NULL,                '',                       '',                 1, 0, 'M', '0', '0', '',                   'component', 'admin', sysdate(), '', NULL, 'NXR 运营后台'),
(2080, '主要功能',  2000, 1, 'main',             NULL,                '',                       'NxrMain',          1, 0, 'M', '0', '0', '',                   'dashboard', 'admin', sysdate(), '', NULL, '录入、审核与状态快捷入口'),
(2090, '工具',      2000, 2, 'tools',            NULL,                '',                       'NxrTools',         1, 0, 'M', '0', '0', '',                   'tool',      'admin', sysdate(), '', NULL, '发布、导出与提交者工具'),
(2091, '客户运营',  2000, 3, 'customer-ops',     NULL,                '',                       'NxrCustomerOps',   1, 0, 'M', '0', '0', '',                   'peoples',   'admin', sysdate(), '', NULL, 'Java 扩展的客户与订单流程'),
(2092, '系统设置',  2000, 4, 'settings',         NULL,                '',                       'NxrSettings',      1, 0, 'M', '0', '0', '',                   'system',    'admin', sysdate(), '', NULL, '管理员账号、字典与品牌设置'),
(2081, '运营总览查看', 2080, 1, '',               '',                  '',                       '',                 1, 0, 'F', '0', '0', 'nxr:dashboard:view', '#',         'admin', sysdate(), '', NULL, '运营总览 API 权限'),
(2082, '新建录入',  2080, 1, 'new-entry',        'nxr/entries/index', '{"mode":"create"}',       'NxrNewEntry',      1, 1, 'C', '0', '0', 'nxr:entry:add',      'edit',      'admin', sysdate(), '', NULL, '打开新增录入对话框'),
(2083, '待审核',    2080, 3, 'pending-review',   'nxr/entries/index', '{"status":"pending"}',    'NxrPendingReview', 1, 1, 'C', '0', '0', 'nxr:entry:list',     'time',      'admin', sysdate(), '', NULL, '待审核录入快捷视图'),
(2084, '已批准',    2080, 4, 'approved-entries', 'nxr/entries/index', '{"status":"approved"}',   'NxrApproved',      1, 1, 'C', '0', '0', 'nxr:entry:list',     'validCode', 'admin', sysdate(), '', NULL, '已批准录入快捷视图');

-- Converge all navigation records, including rows originally created by
-- 03/04/05, so rerunning this script repairs old names and parent IDs.
UPDATE sys_menu SET menu_name = 'NXR后台', parent_id = 0, order_num = 0, path = 'nxr', component = NULL, query = '', route_name = '', menu_type = 'M', visible = '0', status = '0', perms = '', icon = 'component', update_by = 'admin', update_time = sysdate(), remark = 'NXR 运营后台' WHERE menu_id = 2000;
UPDATE sys_menu SET menu_name = '主要功能', parent_id = 2000, order_num = 1, path = 'main', component = NULL, query = '', route_name = 'NxrMain', is_cache = 0, menu_type = 'M', visible = '0', status = '0', perms = '', icon = 'dashboard', update_by = 'admin', update_time = sysdate() WHERE menu_id = 2080;
UPDATE sys_menu SET menu_name = '工具', parent_id = 2000, order_num = 2, path = 'tools', component = NULL, query = '', route_name = 'NxrTools', is_cache = 0, menu_type = 'M', visible = '0', status = '0', perms = '', icon = 'tool', update_by = 'admin', update_time = sysdate() WHERE menu_id = 2090;
UPDATE sys_menu SET menu_name = '客户运营', parent_id = 2000, order_num = 3, path = 'customer-ops', component = NULL, query = '', route_name = 'NxrCustomerOps', is_cache = 0, menu_type = 'M', visible = '0', status = '0', perms = '', icon = 'peoples', update_by = 'admin', update_time = sysdate() WHERE menu_id = 2091;
UPDATE sys_menu SET menu_name = '系统设置', parent_id = 2000, order_num = 4, path = 'settings', component = NULL, query = '', route_name = 'NxrSettings', is_cache = 0, menu_type = 'M', visible = '0', status = '0', perms = '', icon = 'system', update_by = 'admin', update_time = sysdate(), remark = '管理员账号、字典与品牌设置' WHERE menu_id = 2092;

UPDATE sys_menu SET menu_name = '运营总览查看', parent_id = 2080, order_num = 1, path = '', component = '', query = '', route_name = '', is_cache = 0, menu_type = 'F', visible = '0', status = '0', perms = 'nxr:dashboard:view', icon = '#', update_by = 'admin', update_time = sysdate() WHERE menu_id = 2081;
UPDATE sys_menu SET menu_name = '新建录入', parent_id = 2080, order_num = 1, path = 'new-entry', component = 'nxr/entries/index', query = '{"mode":"create"}', route_name = 'NxrNewEntry', is_cache = 1, menu_type = 'C', visible = '0', status = '0', perms = 'nxr:entry:add', icon = 'edit', update_by = 'admin', update_time = sysdate() WHERE menu_id = 2082;
UPDATE sys_menu SET menu_name = '全部录入', parent_id = 2080, order_num = 2, path = 'entries', component = 'nxr/entries/index', query = '', route_name = 'NxrEntries', is_cache = 0, menu_type = 'C', visible = '0', status = '0', perms = 'nxr:entry:list', icon = 'form', update_by = 'admin', update_time = sysdate(), remark = '全部录入与审批' WHERE menu_id = 2001;
UPDATE sys_menu SET menu_name = '待审核', parent_id = 2080, order_num = 3, path = 'pending-review', component = 'nxr/entries/index', query = '{"status":"pending"}', route_name = 'NxrPendingReview', is_cache = 1, menu_type = 'C', visible = '0', status = '0', perms = 'nxr:entry:list', icon = 'time', update_by = 'admin', update_time = sysdate() WHERE menu_id = 2083;
UPDATE sys_menu SET menu_name = '已批准', parent_id = 2080, order_num = 4, path = 'approved-entries', component = 'nxr/entries/index', query = '{"status":"approved"}', route_name = 'NxrApproved', is_cache = 1, menu_type = 'C', visible = '0', status = '0', perms = 'nxr:entry:list', icon = 'validCode', update_by = 'admin', update_time = sysdate() WHERE menu_id = 2084;

UPDATE sys_menu SET menu_name = '上传与发布', parent_id = 2090, order_num = 1, path = 'upload', component = 'nxr/upload/index', query = '', route_name = 'NxrUpload', menu_type = 'C', visible = '0', status = '0', perms = 'nxr:media:list', icon = 'upload', update_by = 'admin', update_time = sysdate(), remark = '媒体导入与证书发布' WHERE menu_id = 2002;
UPDATE sys_menu SET menu_name = 'Excel 导出', parent_id = 2090, order_num = 2, path = 'exports', component = 'nxr/exports/index', query = '', route_name = 'NxrExports', menu_type = 'C', visible = '0', status = '0', perms = 'nxr:export:list', icon = 'excel', update_by = 'admin', update_time = sysdate() WHERE menu_id = 2003;
UPDATE sys_menu SET menu_name = '提交者名单', parent_id = 2090, order_num = 3, path = 'waitlist', component = 'nxr/waitlist/index', query = '', route_name = 'NxrWaitlist', menu_type = 'C', visible = '0', status = '0', perms = 'nxr:waitlist:list', icon = 'email', update_by = 'admin', update_time = sysdate(), remark = '候补名单查看' WHERE menu_id = 2005;

UPDATE sys_menu SET menu_name = '送评订单', parent_id = 2091, order_num = 1, path = 'orders', component = 'nxr/orders/index', query = '', route_name = 'NxrOrders', menu_type = 'C', visible = '0', status = '0', perms = 'nxr:order:list', icon = 'shopping', update_by = 'admin', update_time = sysdate() WHERE menu_id = 2006;
UPDATE sys_menu SET menu_name = '客户管理', parent_id = 2091, order_num = 2, path = 'customers', component = 'nxr/customers/index', query = '', route_name = 'NxrCustomers', menu_type = 'C', visible = '0', status = '0', perms = 'nxr:customer:list', icon = 'peoples', update_by = 'admin', update_time = sysdate(), remark = '客户账号、持卡流转与订单概览' WHERE menu_id = 2007;

UPDATE sys_menu SET menu_name = '管理员用户', parent_id = 2092, order_num = 1, route_name = 'NxrAdminUsers', update_by = 'admin', update_time = sysdate() WHERE menu_id = 100;
UPDATE sys_menu SET menu_name = '字典设置', parent_id = 2092, order_num = 2, route_name = 'NxrDictionaries', update_by = 'admin', update_time = sysdate() WHERE menu_id = 105;
UPDATE sys_menu SET menu_name = '品牌设置', parent_id = 2092, order_num = 3, path = 'brands', component = 'nxr/brands/index', query = '', route_name = 'NxrBrands', menu_type = 'C', visible = '0', status = '0', perms = 'nxr:brand:list', icon = 'edit', update_by = 'admin', update_time = sysdate() WHERE menu_id = 2004;

-- RuoYi super administrators bypass role-menu checks. Removing every explicit
-- brand edge keeps brand maintenance super-admin-only on upgraded databases.
DELETE FROM sys_role_menu WHERE menu_id IN (2004, 2041, 2042);

-- Preserve navigation for any existing role that already held a moved RuoYi
-- user/dictionary menu, without granting those settings to NXR roles 100/101.
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2000 FROM sys_role_menu WHERE menu_id IN (100, 105);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2092 FROM sys_role_menu WHERE menu_id IN (100, 105);

-- Rebuild only this migration's role edges. Existing action permissions for
-- entries, media, exports, orders, and customers remain untouched.
DELETE FROM sys_role_menu
WHERE role_id IN (100, 101)
  AND menu_id IN (2080, 2081, 2082, 2083, 2084, 2090, 2091, 2092);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(100, 2000), (100, 2080), (100, 2090), (100, 2091),
(100, 2081), (100, 2082), (100, 2001), (100, 2083), (100, 2084),
(100, 2002), (100, 2003), (100, 2005),
(101, 2000), (101, 2080), (101, 2091),
(101, 2081), (101, 2001), (101, 2083), (101, 2084);

COMMIT;
