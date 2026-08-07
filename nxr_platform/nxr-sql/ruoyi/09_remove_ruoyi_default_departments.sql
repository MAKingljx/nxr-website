-- Remove only the original RuoYi demo department tree and its references.
-- Existing NXR users, roles, menus, posts, and user-created departments remain intact.

START TRANSACTION;

UPDATE sys_user AS u
JOIN sys_dept AS d ON d.dept_id = u.dept_id
SET u.dept_id = NULL
WHERE (d.dept_id, d.dept_name) IN (
    (100, '若依科技'),
    (101, '深圳总公司'),
    (102, '长沙分公司'),
    (103, '研发部门'),
    (104, '市场部门'),
    (105, '测试部门'),
    (106, '财务部门'),
    (107, '运维部门'),
    (108, '市场部门'),
    (109, '财务部门')
)
AND d.leader = '若依'
AND d.email = 'ry@qq.com';

DELETE rd
FROM sys_role_dept AS rd
JOIN sys_dept AS d ON d.dept_id = rd.dept_id
WHERE (d.dept_id, d.dept_name) IN (
    (100, '若依科技'),
    (101, '深圳总公司'),
    (102, '长沙分公司'),
    (103, '研发部门'),
    (104, '市场部门'),
    (105, '测试部门'),
    (106, '财务部门'),
    (107, '运维部门'),
    (108, '市场部门'),
    (109, '财务部门')
)
AND d.leader = '若依'
AND d.email = 'ry@qq.com';

DELETE FROM sys_dept
WHERE (dept_id, dept_name) IN (
    (100, '若依科技'),
    (101, '深圳总公司'),
    (102, '长沙分公司'),
    (103, '研发部门'),
    (104, '市场部门'),
    (105, '测试部门'),
    (106, '财务部门'),
    (107, '运维部门'),
    (108, '市场部门'),
    (109, '财务部门')
)
AND leader = '若依'
AND email = 'ry@qq.com';

COMMIT;
