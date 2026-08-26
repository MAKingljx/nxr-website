-- ----------------------------------------------------------------------------
-- NXR customer-account administration.
-- Additive and rerunnable: this script adds only menu and permission metadata.
-- It does not update or delete customer, ownership, order, or session records.
-- ----------------------------------------------------------------------------

INSERT IGNORE INTO sys_menu VALUES(
    '2007', '客户管理', '2000', '4', 'customers', 'nxr/customers/index', '', 'NxrCustomers',
    1, 0, 'C', '0', '0', 'nxr:customer:list', 'peoples',
    'admin', sysdate(), '', null, '客户账号、持卡流转与订单概览'
);
INSERT IGNORE INTO sys_menu VALUES(
    '2071', '客户查看', '2007', '1', '', '', '', '',
    1, 0, 'F', '0', '0', 'nxr:customer:list', '#',
    'admin', sysdate(), '', null, ''
);
INSERT IGNORE INTO sys_menu VALUES(
    '2072', '客户管理', '2007', '2', '', '', '', '',
    1, 0, 'F', '0', '0', 'nxr:customer:manage', '#',
    'admin', sysdate(), '', null, '启停账号与使登录会话失效，不删除业务数据'
);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
(100, 2000), (100, 2007), (100, 2071), (100, 2072),
(101, 2000), (101, 2007), (101, 2071);
