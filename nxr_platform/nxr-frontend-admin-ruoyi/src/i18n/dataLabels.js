import { activeLocale } from '@/i18n'

const ROLE_LABELS = {
  '超级管理员': 'Super Administrator',
  '普通角色': 'General Role',
  'NXR管理员': 'NXR Administrator',
  'NXR审核员': 'NXR Reviewer'
}

const POST_LABELS = {
  '董事长': 'Chairperson',
  '项目经理': 'Project Manager',
  '人力资源': 'Human Resources',
  '普通员工': 'General Employee'
}

const MENU_LABELS = {
  'NXR后台': 'NXR Admin',
  '卡片管理': 'Card Management',
  '卡牌管理': 'Card Management',
  '系统管理': 'System Management',
  '系统监控': 'System Monitoring',
  '系统工具': 'System Tools',
  '主要功能': 'Main Operations',
  '工具': 'Tools',
  '客户运营': 'Customer Operations',
  '系统设置': 'System Settings',
  '管理员用户': 'Admin Users',
  '用户管理': 'Admin Users',
  '角色管理': 'Role Management',
  '菜单管理': 'Menu Management',
  '部门管理': 'Department Management',
  '岗位管理': 'Position Management',
  '字典设置': 'Dictionary Settings',
  '字典管理': 'Dictionary Settings',
  '参数设置': 'Configuration',
  '通知公告': 'Notices',
  '日志管理': 'Log Management',
  '操作日志': 'Operation Logs',
  '登录日志': 'Login Logs',
  '在线用户': 'Online Users',
  '定时任务': 'Scheduled Jobs',
  '数据监控': 'Data Monitoring',
  '服务监控': 'Server Monitoring',
  '缓存监控': 'Cache Monitoring',
  '缓存列表': 'Cache List',
  '系统接口': 'API Documentation',
  '表单构建': 'Form Builder',
  '代码生成': 'Code Generation',
  '上传与发布': 'Upload & Publish',
  '订单管理': 'Order Management',
  '订单列表': 'Orders',
  '送评订单': 'Grading Orders',
  '运营总览': 'Operations Dashboard',
  '运营总览查看': 'View Operations Dashboard',
  '新建录入': 'New Entry',
  '全部录入': 'All Entries',
  '待审核': 'Pending Review',
  '已批准': 'Approved',
  '卡图上传': 'Card Image Upload',
  '数据导出': 'Data Export',
  'Excel 导出': 'Excel Export',
  '品牌设置': 'Brand Settings',
  '提交者名单': 'Submitters',
  '客户管理': 'Customer Management',
  '用户查询': 'View Users',
  '用户新增': 'Add Users',
  '用户修改': 'Edit Users',
  '用户删除': 'Delete Users',
  '用户导出': 'Export Users',
  '用户导入': 'Import Users',
  '重置密码': 'Reset Passwords',
  '角色查询': 'View Roles',
  '角色新增': 'Add Roles',
  '角色修改': 'Edit Roles',
  '角色删除': 'Delete Roles',
  '角色导出': 'Export Roles',
  '菜单查询': 'View Menus',
  '菜单新增': 'Add Menus',
  '菜单修改': 'Edit Menus',
  '菜单删除': 'Delete Menus',
  '部门查询': 'View Departments',
  '部门新增': 'Add Departments',
  '部门修改': 'Edit Departments',
  '部门删除': 'Delete Departments',
  '岗位查询': 'View Positions',
  '岗位新增': 'Add Positions',
  '岗位修改': 'Edit Positions',
  '岗位删除': 'Delete Positions',
  '岗位导出': 'Export Positions',
  '字典查询': 'View Dictionaries',
  '字典新增': 'Add Dictionaries',
  '字典修改': 'Edit Dictionaries',
  '字典删除': 'Delete Dictionaries',
  '字典导出': 'Export Dictionaries',
  '参数查询': 'View Configuration',
  '参数新增': 'Add Configuration',
  '参数修改': 'Edit Configuration',
  '参数删除': 'Delete Configuration',
  '参数导出': 'Export Configuration',
  '公告查询': 'View Notices',
  '公告新增': 'Add Notices',
  '公告修改': 'Edit Notices',
  '公告删除': 'Delete Notices',
  '操作查询': 'View Operations',
  '操作删除': 'Delete Operations',
  '日志导出': 'Export Logs',
  '登录查询': 'View Login Logs',
  '登录删除': 'Delete Login Logs',
  '账户解锁': 'Unlock Accounts',
  '在线查询': 'View Online Users',
  '批量强退': 'Force Sign Out (Batch)',
  '单条强退': 'Force Sign Out',
  '任务查询': 'View Jobs',
  '任务新增': 'Add Jobs',
  '任务修改': 'Edit Jobs',
  '任务删除': 'Delete Jobs',
  '状态修改': 'Change Job Status',
  '任务导出': 'Export Jobs',
  '生成查询': 'View Generated Code',
  '生成修改': 'Edit Generated Code',
  '生成删除': 'Delete Generated Code',
  '导入代码': 'Import Code',
  '预览代码': 'Preview Code',
  '生成代码': 'Generate Code',
  '录入新增': 'Add Entries',
  '录入编辑': 'Edit Entries',
  '录入审批': 'Approve Entries',
  '媒体导入': 'Import Media',
  '媒体发布': 'Publish Media',
  '导出生成': 'Generate Exports',
  '导出删除': 'Delete Exports',
  '品牌新增': 'Add Brands',
  '品牌编辑': 'Edit Brands',
  '订单查看': 'View Orders',
  '订单处理': 'Process Orders',
  '客户查看': 'View Customers'
}

function localizeKnownLabel(value, labels) {
  if (value === null || value === undefined || activeLocale() === 'zh-CN') return value
  return labels[String(value)] || value
}

export function localizeRoleName(value) {
  return localizeKnownLabel(value, ROLE_LABELS)
}

export function localizePostName(value) {
  return localizeKnownLabel(value, POST_LABELS)
}

export function localizeMenuName(value) {
  return localizeKnownLabel(value, MENU_LABELS)
}

export function localizeMenuTree(nodes = [], labelField = 'label', displayField = labelField) {
  return (nodes || []).map((node) => ({
    ...node,
    [displayField]: localizeMenuName(node[labelField]),
    children: localizeMenuTree(node.children || [], labelField, displayField)
  }))
}
