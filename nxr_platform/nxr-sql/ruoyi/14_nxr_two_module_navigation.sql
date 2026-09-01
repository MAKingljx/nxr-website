-- ----------------------------------------------------------------------------
-- NXR Java admin navigation: two business modules aligned with the Python UI.
--
-- Business navigation is reduced to Card Management and Order Management.
-- NXR settings and the built-in RuoYi administration pages are consolidated
-- under one System Settings root; monitoring and tools stay independent.
--
-- Menu and role metadata only. No NXR customer, card, order, media, payment,
-- shipping, or other business record is read or written.
-- Rerunnable: UPDATE converges known menu IDs and INSERT IGNORE repairs only
-- parent edges for roles that already own a child permission.
-- Prerequisite: 13_nxr_order_fulfillment.sql.
-- ----------------------------------------------------------------------------

START TRANSACTION;

-- Business module 1: mirror the Python card entry/review/tool sequence.
UPDATE sys_menu
SET menu_name = '卡片管理', parent_id = 0, order_num = 0,
    path = 'nxr/cards', component = NULL, query = '', route_name = 'NxrCardManagement',
    is_frame = 1, is_cache = 0, menu_type = 'M', visible = '0', status = '0',
    perms = '', icon = 'form', update_by = 'admin', update_time = sysdate(),
    remark = '卡片录入、审核、数据导出、图片上传与提交者名单'
WHERE menu_id = 2000;

UPDATE sys_menu
SET menu_name = '新建录入', parent_id = 2000, order_num = 1,
    path = 'new-entry', component = 'nxr/entries/index', query = '{"mode":"create"}',
    route_name = 'NxrNewEntry', is_frame = 1, is_cache = 1, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:entry:add', icon = 'edit',
    update_by = 'admin', update_time = sysdate(), remark = '打开新增卡片录入对话框'
WHERE menu_id = 2082;

UPDATE sys_menu
SET menu_name = '全部录入', parent_id = 2000, order_num = 2,
    path = 'entries', component = 'nxr/entries/index', query = '',
    route_name = 'NxrEntries', is_frame = 1, is_cache = 0, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:entry:list', icon = 'form',
    update_by = 'admin', update_time = sysdate(), remark = '全部卡片录入与审批'
WHERE menu_id = 2001;

UPDATE sys_menu
SET menu_name = '待审核', parent_id = 2000, order_num = 3,
    path = 'pending-review', component = 'nxr/entries/index', query = '{"status":"pending"}',
    route_name = 'NxrPendingReview', is_frame = 1, is_cache = 1, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:entry:list', icon = 'time',
    update_by = 'admin', update_time = sysdate(), remark = '待审核录入快捷视图'
WHERE menu_id = 2083;

UPDATE sys_menu
SET menu_name = '已批准', parent_id = 2000, order_num = 4,
    path = 'approved-entries', component = 'nxr/entries/index', query = '{"status":"approved"}',
    route_name = 'NxrApproved', is_frame = 1, is_cache = 1, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:entry:list', icon = 'validCode',
    update_by = 'admin', update_time = sysdate(), remark = '已批准录入快捷视图'
WHERE menu_id = 2084;

UPDATE sys_menu
SET menu_name = '数据导出', parent_id = 2000, order_num = 5,
    path = 'exports', component = 'nxr/exports/index', query = '',
    route_name = 'NxrExports', is_frame = 1, is_cache = 0, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:export:list', icon = 'excel',
    update_by = 'admin', update_time = sysdate(), remark = '已审批或已发布数据导出'
WHERE menu_id = 2003;

UPDATE sys_menu
SET menu_name = '卡图上传', parent_id = 2000, order_num = 6,
    path = 'upload', component = 'nxr/upload/index', query = '',
    route_name = 'NxrUpload', is_frame = 1, is_cache = 0, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:media:list', icon = 'upload',
    update_by = 'admin', update_time = sysdate(),
    remark = '已审批卡片的正反面图片导入与证书发布'
WHERE menu_id = 2002;

UPDATE sys_menu
SET menu_name = '提交者名单', parent_id = 2000, order_num = 7,
    path = 'waitlist', component = 'nxr/waitlist/index', query = '',
    route_name = 'NxrWaitlist', is_frame = 1, is_cache = 0, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:waitlist:list', icon = 'email',
    update_by = 'admin', update_time = sysdate(), remark = '候补提交者名单查看'
WHERE menu_id = 2005;

-- Dashboard access remains a button permission; move it off the retired
-- intermediate card container without changing any role capability.
UPDATE sys_menu
SET parent_id = 2000, order_num = 0, update_by = 'admin', update_time = sysdate()
WHERE menu_id = 2081;

-- Business module 2: keep the order workspace intact and place customers next
-- to it. Receiving, payment, grading, QC, shipping, and pricing stay inside the
-- order page instead of becoming repetitive sidebar entries.
UPDATE sys_menu
SET menu_name = '订单管理', parent_id = 0, order_num = 1,
    path = 'nxr/submissions', component = NULL, query = '', route_name = 'NxrOrderManagement',
    is_frame = 1, is_cache = 0, menu_type = 'M', visible = '0', status = '0',
    perms = '', icon = 'shopping', update_by = 'admin', update_time = sysdate(),
    remark = '订单、客户、收付款与邮寄履约'
WHERE menu_id = 2091;

UPDATE sys_menu
SET menu_name = '订单列表', parent_id = 2091, order_num = 1,
    path = 'orders', component = 'nxr/orders/index', query = '', route_name = 'NxrOrders',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'nxr:order:list', icon = 'shopping', update_by = 'admin', update_time = sysdate(),
    remark = '客户送评订单、收付款与物流'
WHERE menu_id = 2006;

UPDATE sys_menu
SET menu_name = '客户管理', parent_id = 2091, order_num = 2,
    path = 'customers', component = 'nxr/customers/index', query = '', route_name = 'NxrCustomers',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'nxr:customer:list', icon = 'peoples', update_by = 'admin', update_time = sysdate(),
    remark = '客户账号、持卡流转与订单概览'
WHERE menu_id = 2007;

-- Consolidate NXR configuration and the built-in administration pages under
-- the existing framework root. Keeping its stable path preserves all native
-- detail routes while removing the duplicate top-level settings entry.
UPDATE sys_menu
SET menu_name = '系统设置', parent_id = 0, order_num = 90,
    path = 'system', component = NULL, query = '', route_name = '',
    is_frame = 1, is_cache = 0, menu_type = 'M', visible = '0', status = '0',
    perms = '', icon = 'system', update_by = 'admin', update_time = sysdate(),
    remark = '管理员、字典、品牌、角色、菜单与平台配置'
WHERE menu_id = 1;

UPDATE sys_menu SET parent_id = 1, order_num = 1, update_by = 'admin', update_time = sysdate() WHERE menu_id = 100;
UPDATE sys_menu SET parent_id = 1, order_num = 2, update_by = 'admin', update_time = sysdate() WHERE menu_id = 105;
UPDATE sys_menu SET parent_id = 1, order_num = 3, update_by = 'admin', update_time = sysdate() WHERE menu_id = 2004;
UPDATE sys_menu SET parent_id = 1, order_num = 4, update_by = 'admin', update_time = sysdate() WHERE menu_id = 101;
UPDATE sys_menu SET parent_id = 1, order_num = 5, update_by = 'admin', update_time = sysdate() WHERE menu_id = 102;
UPDATE sys_menu SET parent_id = 1, order_num = 6, update_by = 'admin', update_time = sysdate() WHERE menu_id = 103;
UPDATE sys_menu SET parent_id = 1, order_num = 7, update_by = 'admin', update_time = sysdate() WHERE menu_id = 104;
UPDATE sys_menu SET parent_id = 1, order_num = 8, update_by = 'admin', update_time = sysdate() WHERE menu_id = 106;
UPDATE sys_menu SET parent_id = 1, order_num = 9, update_by = 'admin', update_time = sysdate() WHERE menu_id = 107;
UPDATE sys_menu SET parent_id = 1, order_num = 10, update_by = 'admin', update_time = sysdate() WHERE menu_id = 108;

-- Keep monitoring and tools below the unified settings root.
UPDATE sys_menu SET order_num = 91, update_by = 'admin', update_time = sysdate() WHERE menu_id = 2 AND parent_id = 0;
UPDATE sys_menu SET order_num = 92, update_by = 'admin', update_time = sysdate() WHERE menu_id = 3 AND parent_id = 0;

-- Retain obsolete containers as disabled migration markers for auditability.
UPDATE sys_menu
SET visible = '1', status = '1', order_num = 98, route_name = '',
    update_by = 'admin', update_time = sysdate(),
    remark = '已由 14 号迁移停用；卡片功能并入卡片管理根菜单'
WHERE menu_id = 2080;

UPDATE sys_menu
SET visible = '1', status = '1', order_num = 99, route_name = '',
    update_by = 'admin', update_time = sysdate(),
    remark = '已由 14 号迁移停用；工具功能并入卡片管理根菜单'
WHERE menu_id = 2090;

UPDATE sys_menu
SET parent_id = 0, visible = '1', status = '1', order_num = 97, route_name = '',
    update_by = 'admin', update_time = sysdate(),
    remark = '已由 14 号迁移停用；设置功能并入统一系统设置菜单'
WHERE menu_id = 2092;

-- Repair only structural parent edges for roles that already own a page.
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2000
FROM sys_role_menu
WHERE menu_id IN (2001, 2002, 2003, 2005, 2081, 2082, 2083, 2084);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2091
FROM sys_role_menu
WHERE menu_id IN (2006, 2007);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 1
FROM sys_role_menu
WHERE menu_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 2004);

-- A role that owned only the former order-parent edge must not receive an
-- empty Card Management menu after the modules become independent roots.
DELETE card_root
FROM sys_role_menu AS card_root
LEFT JOIN sys_role_menu AS card_child
  ON card_child.role_id = card_root.role_id
 AND card_child.menu_id IN (2001, 2002, 2003, 2005, 2081, 2082, 2083, 2084)
WHERE card_root.menu_id = 2000
  AND card_child.role_id IS NULL;

DELETE FROM sys_role_menu WHERE menu_id IN (2080, 2090, 2092);

COMMIT;
