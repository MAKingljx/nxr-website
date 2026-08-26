-- ----------------------------------------------------------------------------
-- NXR Java/RuoYi menu domain separation.
--
-- Card-image publishing and grading-order operations are separate workflows:
-- media publishing acts on approved cards, while orders manage payment,
-- logistics, and optional submission links. This migration keeps publishing as
-- a direct entry and groups order work under a dedicated submission section.
--
-- Menu and role metadata only. No NXR business records are read or written.
-- Rerunnable: UPDATE converges known menu IDs, INSERT IGNORE repairs parent
-- chains, and only obsolete container role edges are removed.
-- Prerequisite: 11_nxr_admin_navigation_alignment.sql.
-- ----------------------------------------------------------------------------

START TRANSACTION;

-- Card intake remains grouped because its four routes are views of one shared
-- entries workspace. Existing child paths stay stable beneath the new group.
UPDATE sys_menu
SET menu_name = '卡牌管理', parent_id = 2000, order_num = 1,
    path = 'cards', component = NULL, query = '', route_name = 'NxrCards',
    is_frame = 1, is_cache = 0, menu_type = 'M', visible = '0', status = '0',
    perms = '', icon = 'form', update_by = 'admin', update_time = sysdate(),
    remark = '卡牌录入、审核与状态快捷入口'
WHERE menu_id = 2080;

UPDATE sys_menu
SET menu_name = '新建卡牌', parent_id = 2080, order_num = 1,
    path = 'new-entry', component = 'nxr/entries/index', query = '{"mode":"create"}',
    route_name = 'NxrNewEntry', is_frame = 1, is_cache = 1, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:entry:add', icon = 'edit',
    update_by = 'admin', update_time = sysdate(), remark = '打开新增卡牌对话框'
WHERE menu_id = 2082;

UPDATE sys_menu
SET menu_name = '卡牌列表', parent_id = 2080, order_num = 2,
    path = 'entries', component = 'nxr/entries/index', query = '',
    route_name = 'NxrEntries', is_frame = 1, is_cache = 0, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:entry:list', icon = 'form',
    update_by = 'admin', update_time = sysdate(), remark = '全部卡牌录入与审批'
WHERE menu_id = 2001;

UPDATE sys_menu
SET menu_name = '待审核', parent_id = 2080, order_num = 3,
    path = 'pending-review', component = 'nxr/entries/index', query = '{"status":"pending"}',
    route_name = 'NxrPendingReview', is_frame = 1, is_cache = 1, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:entry:list', icon = 'time',
    update_by = 'admin', update_time = sysdate(), remark = '待审核录入快捷视图'
WHERE menu_id = 2083;

UPDATE sys_menu
SET menu_name = '已批准', parent_id = 2080, order_num = 4,
    path = 'approved-entries', component = 'nxr/entries/index', query = '{"status":"approved"}',
    route_name = 'NxrApproved', is_frame = 1, is_cache = 1, menu_type = 'C',
    visible = '0', status = '0', perms = 'nxr:entry:list', icon = 'validCode',
    update_by = 'admin', update_time = sysdate(), remark = '已批准录入快捷视图'
WHERE menu_id = 2084;

-- Direct task entries. Moving only menu metadata does not change endpoint or
-- button permissions and therefore does not widen any role's capabilities.
UPDATE sys_menu
SET menu_name = '卡图上传', parent_id = 2000, order_num = 2,
    path = 'upload', component = 'nxr/upload/index', query = '', route_name = 'NxrUpload',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'nxr:media:list', icon = 'upload', update_by = 'admin', update_time = sysdate(),
    remark = '已审批卡牌的正反面图片导入与证书发布'
WHERE menu_id = 2002;

UPDATE sys_menu
SET menu_name = '送审管理', parent_id = 2000, order_num = 3,
    path = 'submissions', component = NULL, query = '', route_name = 'NxrSubmissionManagement',
    is_frame = 1, is_cache = 0, menu_type = 'M', visible = '0', status = '0',
    perms = '', icon = 'shopping', update_by = 'admin', update_time = sysdate(),
    remark = '送评订单、收付款与物流'
WHERE menu_id = 2091;

UPDATE sys_menu
SET menu_name = '订单管理', parent_id = 2091, order_num = 1,
    path = 'orders', component = 'nxr/orders/index', query = '', route_name = 'NxrOrders',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'nxr:order:list', icon = 'shopping', update_by = 'admin', update_time = sysdate(),
    remark = '客户送评订单、收付款与物流'
WHERE menu_id = 2006;

UPDATE sys_menu
SET menu_name = '客户管理', parent_id = 2000, order_num = 4,
    path = 'customers', component = 'nxr/customers/index', query = '', route_name = 'NxrCustomers',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'nxr:customer:list', icon = 'peoples', update_by = 'admin', update_time = sysdate(),
    remark = '客户账号、持卡流转与订单概览'
WHERE menu_id = 2007;

UPDATE sys_menu
SET menu_name = '候补名单', parent_id = 2000, order_num = 5,
    path = 'waitlist', component = 'nxr/waitlist/index', query = '', route_name = 'NxrWaitlist',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'nxr:waitlist:list', icon = 'email', update_by = 'admin', update_time = sysdate(),
    remark = '候补提交者名单查看'
WHERE menu_id = 2005;

UPDATE sys_menu
SET menu_name = '数据导出', parent_id = 2000, order_num = 6,
    path = 'exports', component = 'nxr/exports/index', query = '', route_name = 'NxrExports',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'nxr:export:list', icon = 'excel', update_by = 'admin', update_time = sysdate(),
    remark = '已审批或已发布数据导出'
WHERE menu_id = 2003;

UPDATE sys_menu
SET menu_name = '系统设置', parent_id = 2000, order_num = 7,
    path = 'settings', component = NULL, query = '', route_name = 'NxrSettings',
    is_frame = 1, is_cache = 0, menu_type = 'M', visible = '0', status = '0',
    perms = '', icon = 'system', update_by = 'admin', update_time = sysdate(),
    remark = '管理员账号、字典与品牌设置'
WHERE menu_id = 2092;

-- Keep the former tools container as a disabled migration marker so old
-- database references remain auditable; its functional children live elsewhere.
UPDATE sys_menu
SET visible = '1', status = '1', order_num = 98,
    update_by = 'admin', update_time = sysdate(),
    remark = '已由 12 号迁移停用；功能改为独立任务入口'
WHERE menu_id = 2090;

-- Repair navigation parents only for roles that already own a child. This
-- preserves all existing permissions without granting a new business page.
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2000
FROM sys_role_menu
WHERE menu_id IN (2001, 2002, 2003, 2005, 2006, 2007, 2082, 2083, 2084);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2080
FROM sys_role_menu
WHERE menu_id IN (2001, 2082, 2083, 2084);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2092
FROM sys_role_menu
WHERE menu_id IN (100, 105, 2004);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2000
FROM sys_role_menu
WHERE menu_id = 2092;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2091
FROM sys_role_menu
WHERE menu_id = 2006;

DELETE FROM sys_role_menu WHERE menu_id = 2090;

COMMIT;
