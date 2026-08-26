<script setup lang="ts">
import { ref } from 'vue'
import {
  PhoenixAdvancedTable,
  PhoenixApprovalPanel,
  PhoenixAttachmentPreviewer,
  PhoenixAuditLogViewer,
  PhoenixBatchActionBar,
  PhoenixImportExportPanel,
  PhoenixOrganizationTree,
  PhoenixPasswordChange,
  PhoenixQueryPanel,
  PhoenixSessionManager,
  PhoenixTreeSelect,
  PhoenixUserProfile,
} from '../primitives/admin'

const selectedRows = ref<Array<string | number>>([1])
const sort = ref<{ key: string; direction: 'asc' | 'desc' | null } | null>({ key: 'updatedAt', direction: 'desc' })
const filters = ref<Record<string, string>>({})
const editValue = ref('')
const query = ref({ keyword: '', status: null as string | null })
const treeValues = ref<Array<string | number>>(['vue'])
const treeExpanded = ref<Array<string | number>>(['frontend'])
const organizationSelected = ref<string | number | null>('product')
const organizationExpanded = ref<Array<string | number>>(['company'])
const selectedLog = ref<string | number | null>(1)
const approvalComment = ref('')
const attachmentId = ref<string | number | null>('guide')
const userProfile = ref({ name: '李明', email: 'liming@example.com', phone: '13800000000', department: '产品中心', position: '产品经理', bio: '负责通用产品能力建设。' })
const profileEditing = ref(false)
const passwordValue = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })

const columns = [
  { key: 'name', label: '名称', fixed: 'left' as const, sortable: true, filterable: true, editable: true, width: 180 },
  { key: 'department', label: '部门', filterable: true, width: 130 },
  { key: 'status', label: '状态', sortable: true, width: 100 },
  { key: 'updatedAt', label: '更新时间', fixed: 'right' as const, sortable: true, width: 150 },
]
const rows = [
  { id: 1, name: '城市图书馆', department: '公共服务部', status: '启用', updatedAt: '08-10 10:20' },
  { id: 2, name: '旅游推荐', department: '内容运营部', status: '审核中', updatedAt: '08-10 09:30' },
]
const treeNodes = [{ value: 'frontend', label: '前端组件', children: [{ value: 'vue', label: 'Vue 3' }, { value: 'uni', label: 'uni-app' }] }, { value: 'backend', label: '后端组件' }]
const organization = [{ id: 'company', name: '凤凰科技', type: 'company' as const, memberCount: 128, children: [{ id: 'product', name: '产品中心', type: 'department' as const, memberCount: 26 }, { id: 'technology', name: '技术中心', type: 'department' as const, memberCount: 54 }] }]
const logs = [{ id: 1, action: '更新用户权限', operator: '系统管理员', time: '10:28', target: '李明', ip: '10.0.0.8', detail: '新增商品审核权限', severity: 'warning' as const }, { id: 2, action: '导出订单', operator: '运营人员', time: '09:45', target: '订单列表', severity: 'info' as const }]
</script>

<template>
  <div class="cg-admin-showcase">
    <article class="is-wide"><h3>高级表格</h3><PhoenixAdvancedTable v-model:selected-keys="selectedRows" v-model:edit-value="editValue" :sort="sort" :filters="filters" :columns="columns" :rows="rows" @sort-change="sort = $event" @filter-change="filters = $event" /></article>
    <article class="is-wide"><h3>查询条件与批量操作</h3><PhoenixQueryPanel v-model="query" :fields="[{ key: 'keyword', label: '关键词', type: 'search', placeholder: '搜索名称' }, { key: 'status', label: '状态', type: 'select', options: [{ label: '启用', value: 'active' }, { label: '停用', value: 'disabled' }] }]" /><PhoenixBatchActionBar :selected-count="selectedRows.length" :actions="[{ key: 'enable', label: '批量启用' }, { key: 'delete', label: '批量删除', danger: true }]" /></article>
    <article><h3>导入导出</h3><PhoenixImportExportPanel /></article>
    <article><h3>树形选择器</h3><PhoenixTreeSelect v-model="treeValues" v-model:expanded-keys="treeExpanded" :nodes="treeNodes" multiple /></article>
    <article><h3>部门组织架构</h3><PhoenixOrganizationTree v-model:selected-id="organizationSelected" v-model:expanded-ids="organizationExpanded" :nodes="organization" /></article>
    <article><h3>审计日志</h3><PhoenixAuditLogViewer v-model:selected-id="selectedLog" :logs="logs" has-more /></article>
    <article><h3>审批操作</h3><PhoenixApprovalPanel v-model:comment="approvalComment" title="商品上架申请" applicant="运营人员" submitted-at="今天 09:20" :details="[{ label: '商品', value: '城市旅行套装' }, { label: '分类', value: '出行用品' }]" :steps="[{ id: 1, name: '提交申请', approver: '运营人员', status: 'approved', time: '09:20' }, { id: 2, name: '商品审核', approver: '张经理', status: 'pending' }]" /></article>
    <article><h3>附件预览</h3><PhoenixAttachmentPreviewer v-model:selected-id="attachmentId" :attachments="[{ id: 'guide', name: '商品说明.pdf', url: 'https://example.com/guide.pdf', mimeType: 'application/pdf', size: 268000, downloadable: true }, { id: 'cover', name: '商品图片.jpg', url: 'https://example.com/cover.jpg', mimeType: 'image/jpeg', size: 128000 }]" :allowed-hosts="['example.com']" /></article>
    <article><h3>用户资料</h3><PhoenixUserProfile v-model="userProfile" v-model:editing="profileEditing" /></article>
    <article><h3>修改密码</h3><PhoenixPasswordChange v-model="passwordValue" username="liming" /></article>
    <article class="is-wide"><h3>登录会话管理</h3><PhoenixSessionManager current-session-id="current" :sessions="[{ id: 'current', device: 'MacBook Pro', browser: 'Chrome', location: '天津', ip: '10.0.0.8', lastActive: '刚刚', current: true }, { id: 'mobile', device: 'iPhone', browser: 'Safari', location: '北京', ip: '10.0.0.9', lastActive: '2 小时前' }]" /></article>
  </div>
</template>
