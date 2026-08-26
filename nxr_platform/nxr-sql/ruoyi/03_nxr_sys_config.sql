-- ----------------------------------------------------------------------------
-- NXR 若依系统配置：业务字典、角色、菜单权限
-- 在 ry_20260417.sql 与 01/02 业务脚本之后执行。
-- ----------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. 业务字典：运动类型（迁移自 Flask 端 dictionary_groups/sports_type）
-- ---------------------------------------------------------------------------
INSERT INTO sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
VALUES ('运动类型', 'nxr_sports_type', '0', 'admin', sysdate(), 'Sports Card 的 sports_type 可选值');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
VALUES
(10, 'Basketball', 'Basketball', 'nxr_sports_type', '', '', 'N', '0', 'admin', sysdate(), ''),
(20, 'Soccer',     'Soccer',     'nxr_sports_type', '', '', 'N', '0', 'admin', sysdate(), ''),
(30, 'Football',   'Football',   'nxr_sports_type', '', '', 'N', '0', 'admin', sysdate(), ''),
(40, 'Boxing',     'Boxing',     'nxr_sports_type', '', '', 'N', '0', 'admin', sysdate(), ''),
(50, 'F1',         'F1',         'nxr_sports_type', '', '', 'N', '0', 'admin', sysdate(), ''),
(60, 'UFC',        'UFC',        'nxr_sports_type', '', '', 'N', '0', 'admin', sysdate(), '');

-- 卡牌类目字典（录入表单下拉使用，与后端 CARD_CATEGORY 对齐）
INSERT INTO sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
VALUES ('卡牌类目', 'nxr_card_category', '0', 'admin', sysdate(), '录入表单卡牌类目');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
VALUES
(10, 'Trading Card',   'trading_card',   'nxr_card_category', '', '',        'Y', '0', 'admin', sysdate(), ''),
(20, 'Movie Film',     'movie_film',     'nxr_card_category', '', '',        'N', '0', 'admin', sysdate(), ''),
(30, 'Sports Card',    'sports_card',    'nxr_card_category', '', '',        'N', '0', 'admin', sysdate(), ''),
(40, 'Celebrity Card', 'celebrity_card', 'nxr_card_category', '', '',        'N', '0', 'admin', sysdate(), '');

-- 产品类型字典（固定行为值，不与现有 card_category 混用）
INSERT INTO sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
VALUES ('产品类型', 'nxr_product_type', '0', 'admin', sysdate(), '录入流程产品类型；编码由后端固定校验');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
VALUES
(10, 'Graded Card',    'graded_card',    'nxr_product_type', '', '', 'Y', '0', 'admin', sysdate(), ''),
(20, 'Merch Product',  'merch_product',  'nxr_product_type', '', '', 'N', '0', 'admin', sysdate(), ''),
(30, 'Vintage Card',   'vintage_product','nxr_product_type', '', '', 'N', '0', 'admin', sysdate(), '');

INSERT INTO sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
VALUES ('老卡分类', 'nxr_vintage_classification', '0', 'admin', sysdate(), 'Vintage Card 的四分类');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
VALUES
(10, 'Pristine', 'Pristine', 'nxr_vintage_classification', '', '', 'Y', '0', 'admin', sysdate(), ''),
(20, 'Nova',     'Nova',     'nxr_vintage_classification', '', '', 'N', '0', 'admin', sysdate(), ''),
(30, 'Legacy',   'Legacy',   'nxr_vintage_classification', '', '', 'N', '0', 'admin', sysdate(), ''),
(40, 'Helix',    'Helix',    'nxr_vintage_classification', '', '', 'N', '0', 'admin', sysdate(), '');

-- 语言字典
INSERT INTO sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
VALUES ('卡牌语言', 'nxr_language', '0', 'admin', sysdate(), '录入表单语言选项');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
VALUES
(10, 'English',             'EN',    'nxr_language', '', '', 'Y', '0', 'admin', sysdate(), ''),
(20, 'Japanese',            'JP',    'nxr_language', '', '', 'N', '0', 'admin', sysdate(), ''),
(30, 'Traditional Chinese', 'CT',    'nxr_language', '', '', 'N', '0', 'admin', sysdate(), ''),
(40, 'Simplified Chinese',  'CS',    'nxr_language', '', '', 'N', '0', 'admin', sysdate(), ''),
(50, 'Indonesian',          'IN',    'nxr_language', '', '', 'N', '0', 'admin', sysdate(), ''),
(60, 'Korean',              'KO',    'nxr_language', '', '', 'N', '0', 'admin', sysdate(), ''),
(70, 'Thai',                'TH',    'nxr_language', '', '', 'N', '0', 'admin', sysdate(), ''),
(80, 'Other',               'Other', 'nxr_language', '', '', 'N', '0', 'admin', sysdate(), '');

-- ---------------------------------------------------------------------------
-- 2. NXR 业务菜单（menu_id 2000 起）
--
-- 信息架构对齐现有 Python 后台，但复用 Java 页面：录入快捷入口通过
-- sys_menu.query 驱动同一个 entries 组件，不复制 CRUD 页面。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu VALUES('2000', 'NXR后台', '0', '0', 'nxr', null, '', '', 1, 0, 'M', '0', '0', '', 'component', 'admin', sysdate(), '', null, 'NXR 运营后台');

-- 卡牌录入保留分组；上传、订单、客户、候补和导出按业务任务直接展示。
INSERT INTO sys_menu VALUES('2080', '卡牌管理', '2000', '1', 'cards',    null, '', 'NxrCards',    1, 0, 'M', '0', '0', '', 'form',   'admin', sysdate(), '', null, '卡牌录入、审核与状态快捷入口');
INSERT INTO sys_menu VALUES('2092', '系统设置', '2000', '7', 'settings', null, '', 'NxrSettings', 1, 0, 'M', '0', '0', '', 'system', 'admin', sysdate(), '', null, '管理员账号、字典与品牌设置');

-- 固定首页 /index 展示运营总览；该功能权限以按钮记录授予角色。
INSERT INTO sys_menu VALUES('2081', '运营总览查看', '2080', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:dashboard:view', '#', 'admin', sysdate(), '', null, '运营总览 API 权限');

-- 卡牌管理：同一录入组件的独立路由与查询参数。
INSERT INTO sys_menu VALUES('2082', '新建卡牌', '2080', '1', 'new-entry',       'nxr/entries/index', '{"mode":"create"}',   'NxrNewEntry',      1, 1, 'C', '0', '0', 'nxr:entry:add',  'edit',      'admin', sysdate(), '', null, '打开新增卡牌对话框');
INSERT INTO sys_menu VALUES('2001', '卡牌列表', '2080', '2', 'entries',         'nxr/entries/index', '',                    'NxrEntries',       1, 0, 'C', '0', '0', 'nxr:entry:list', 'form',      'admin', sysdate(), '', null, '全部卡牌录入与审批');
INSERT INTO sys_menu VALUES('2083', '待审核',   '2080', '3', 'pending-review',  'nxr/entries/index', '{"status":"pending"}', 'NxrPendingReview', 1, 1, 'C', '0', '0', 'nxr:entry:list', 'time',      'admin', sysdate(), '', null, '待审核录入快捷视图');
INSERT INTO sys_menu VALUES('2084', '已批准',   '2080', '4', 'approved-entries','nxr/entries/index', '{"status":"approved"}','NxrApproved',      1, 1, 'C', '0', '0', 'nxr:entry:list', 'validCode', 'admin', sysdate(), '', null, '已批准录入快捷视图');

-- 独立任务入口：卡图发布不与订单混用，候补和导出也无需进入“工具”分组。
INSERT INTO sys_menu VALUES('2002', '卡图上传', '2000', '2', 'upload',   'nxr/upload/index',   '', 'NxrUpload',   1, 0, 'C', '0', '0', 'nxr:media:list',    'upload', 'admin', sysdate(), '', null, '已审批卡牌的正反面图片导入与证书发布');
INSERT INTO sys_menu VALUES('2005', '候补名单', '2000', '5', 'waitlist', 'nxr/waitlist/index', '', 'NxrWaitlist', 1, 0, 'C', '0', '0', 'nxr:waitlist:list', 'email',  'admin', sysdate(), '', null, '候补提交者名单查看');
INSERT INTO sys_menu VALUES('2003', '数据导出', '2000', '6', 'exports',  'nxr/exports/index',  '', 'NxrExports',  1, 0, 'C', '0', '0', 'nxr:export:list',   'excel',  'admin', sysdate(), '', null, '已审批或已发布数据导出');

-- Administration：品牌页与若依管理员账号/字典页统一归入系统设置。
INSERT INTO sys_menu VALUES('2004', '品牌设置', '2092', '3', 'brands', 'nxr/brands/index', '', 'NxrBrands', 1, 0, 'C', '0', '0', 'nxr:brand:list', 'edit', 'admin', sysdate(), '', null, '品牌与别名管理');
UPDATE sys_menu SET menu_name = '管理员用户', parent_id = 2092, order_num = 1, route_name = 'NxrAdminUsers', update_by = 'admin', update_time = sysdate() WHERE menu_id = 100;
UPDATE sys_menu SET menu_name = '字典设置',   parent_id = 2092, order_num = 2, route_name = 'NxrDictionaries', update_by = 'admin', update_time = sysdate() WHERE menu_id = 105;

-- 若已有非超级管理员角色持有若依用户/字典菜单，补齐新的父级链路。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) SELECT DISTINCT role_id, 2000 FROM sys_role_menu WHERE menu_id IN (100, 105);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) SELECT DISTINCT role_id, 2092 FROM sys_role_menu WHERE menu_id IN (100, 105);

-- 录入管理按钮
INSERT INTO sys_menu VALUES('2011', '录入新增', '2001', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:entry:add',     '#', 'admin', sysdate(), '', null, '');
INSERT INTO sys_menu VALUES('2012', '录入编辑', '2001', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:entry:edit',    '#', 'admin', sysdate(), '', null, '');
INSERT INTO sys_menu VALUES('2013', '录入审批', '2001', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:entry:approve', '#', 'admin', sysdate(), '', null, '');

-- 上传管理按钮
INSERT INTO sys_menu VALUES('2021', '媒体导入', '2002', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:media:import',  '#', 'admin', sysdate(), '', null, '');
INSERT INTO sys_menu VALUES('2022', '媒体发布', '2002', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:media:publish', '#', 'admin', sysdate(), '', null, '');

-- 导出按钮
INSERT INTO sys_menu VALUES('2031', '导出生成', '2003', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:export:generate', '#', 'admin', sysdate(), '', null, '');
INSERT INTO sys_menu VALUES('2032', '导出删除', '2003', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:export:remove',   '#', 'admin', sysdate(), '', null, '');

-- 品牌按钮
INSERT INTO sys_menu VALUES('2041', '品牌新增', '2004', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:brand:add',  '#', 'admin', sysdate(), '', null, '');
INSERT INTO sys_menu VALUES('2042', '品牌编辑', '2004', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'nxr:brand:edit', '#', 'admin', sysdate(), '', null, '');

-- ---------------------------------------------------------------------------
-- 3. 角色（超级管理员使用若依内置 admin 角色，绕过所有权限校验）
-- ---------------------------------------------------------------------------
INSERT INTO sys_role VALUES('100', 'NXR管理员', 'nxr_admin',    3, 1, 1, 1, '0', '0', 'admin', sysdate(), '', null, '对应原 Flask admin 角色：全部 NXR 业务功能');
INSERT INTO sys_role VALUES('101', 'NXR审核员', 'nxr_reviewer', 4, 1, 1, 1, '0', '0', 'admin', sysdate(), '', null, '对应原 Flask reviewer 角色：查看与审批录入');

-- NXR管理员：业务菜单与按钮；系统设置仅由若依超级管理员维护。
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 2000), (100, 2080),
(100, 2081), (100, 2082), (100, 2001), (100, 2083), (100, 2084),
(100, 2002), (100, 2003), (100, 2005),
(100, 2011), (100, 2012), (100, 2013),
(100, 2021), (100, 2022),
(100, 2031), (100, 2032);

-- NXR审核员：录入查看 + 审批
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(101, 2000), (101, 2080),
(101, 2081), (101, 2001), (101, 2083), (101, 2084), (101, 2013);
