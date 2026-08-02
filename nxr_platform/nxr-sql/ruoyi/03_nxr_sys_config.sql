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
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu VALUES('2000', 'NXR管理', '0', '0', 'nxr', null, '', '', 1, 0, 'M', '0', '0', '', 'component', 'admin', sysdate(), '', null, 'NXR 卡牌评级业务目录');

INSERT INTO sys_menu VALUES('2001', '录入管理',   '2000', '1', 'entries',  'nxr/entries/index',  '', '', 1, 0, 'C', '0', '0', 'nxr:entry:list',    'form',      'admin', sysdate(), '', null, '卡牌录入与审批');
INSERT INTO sys_menu VALUES('2002', '上传管理',   '2000', '2', 'upload',   'nxr/upload/index',   '', '', 1, 0, 'C', '0', '0', 'nxr:media:list',    'upload',    'admin', sysdate(), '', null, '媒体导入与发布');
INSERT INTO sys_menu VALUES('2003', 'Excel导出',  '2000', '3', 'exports',  'nxr/exports/index',  '', '', 1, 0, 'C', '0', '0', 'nxr:export:list',   'excel',     'admin', sysdate(), '', null, '已审批数据导出');
INSERT INTO sys_menu VALUES('2004', '品牌设置',   '2000', '4', 'brands',   'nxr/brands/index',   '', '', 1, 0, 'C', '0', '0', 'nxr:brand:list',    'edit',      'admin', sysdate(), '', null, '品牌与别名管理');
INSERT INTO sys_menu VALUES('2005', 'Waitlist',   '2000', '5', 'waitlist', 'nxr/waitlist/index', '', '', 1, 0, 'C', '0', '0', 'nxr:waitlist:list', 'email',     'admin', sysdate(), '', null, '候补名单查看');

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

-- NXR管理员：全部 NXR 菜单与按钮
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(100, 2000), (100, 2001), (100, 2002), (100, 2003), (100, 2004), (100, 2005),
(100, 2011), (100, 2012), (100, 2013),
(100, 2021), (100, 2022),
(100, 2031), (100, 2032),
(100, 2041), (100, 2042);

-- NXR审核员：录入查看 + 审批
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(101, 2000), (101, 2001), (101, 2013);
