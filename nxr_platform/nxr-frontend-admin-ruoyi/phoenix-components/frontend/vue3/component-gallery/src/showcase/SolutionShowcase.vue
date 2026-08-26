<script setup lang="ts">
import {
  PhoenixAdminWorkspacePage,
  PhoenixAccountSecurityPage,
  PhoenixAnalyticsDashboardPage,
  PhoenixContentWorkspacePage,
} from '../patterns/solutions'

const users = [
  { id: 1, name: '李明', department: '产品中心', status: '正常' },
  { id: 2, name: '王芳', department: '技术中心', status: '正常' },
  { id: 3, name: '陈晓', department: '运营中心', status: '待审核' },
]
const columns = [
  { key: 'name', label: '姓名', sortable: true, filterable: true, editable: true, fixed: 'left' as const },
  { key: 'department', label: '部门', filterable: true },
  { key: 'status', label: '状态' },
]
const notifications = [
  { id: 1, title: '权限申请已通过', description: '你现在可以访问组件项目。', read: false, tone: 'success' as const },
  { id: 2, title: '库存预警', description: '有 3 个商品库存偏低。', read: false, tone: 'warning' as const },
]
const threads = [
  { id: 1, sender: '王芳', subject: '项目发布确认', preview: '测试已全部通过。', unread: true, starred: true },
  { id: 2, sender: '系统通知', subject: '登录策略更新', preview: '新策略将在今晚生效。' },
]
const activities = [
  { id: 1, actor: '李明', action: '更新了', target: '商品资料', tone: 'info' as const },
  { id: 2, actor: '王芳', action: '完成了', target: '订单审核', tone: 'success' as const },
]
const trend = [{ key: 'may', label: '五月', value: 860 }, { key: 'june', label: '六月', value: 1120 }, { key: 'july', label: '七月', value: 1286 }]
const categories = [{ key: 'mall', label: '商城', value: 1560 }, { key: 'booking', label: '预约', value: 980 }, { key: 'learning', label: '教学', value: 760 }]
const sessions = [
  { id: 'mobile', clientName: '手机', createdAt: '2026-08-10T08:00:00Z', lastSeenAt: '10 分钟前', expiresAt: '2026-08-12T08:00:00Z', current: false },
  { id: 'office', clientName: '办公电脑', createdAt: '2026-08-11T08:00:00Z', lastSeenAt: '刚刚', expiresAt: '2026-08-12T08:00:00Z', current: true },
]
</script>

<template>
  <div class="cg-solution-showcase">
    <article><h3>后台数据管理成品页</h3><PhoenixAdminWorkspacePage title="用户管理" :stats="[{ label: '全部用户', value: 1286 }, { label: '今日新增', value: 24, tone: 'success' }]" :rows="users" :columns="columns" :query-fields="[{ key: 'name', label: '姓名', type: 'search' }, { key: 'department', label: '部门', type: 'select', options: [{ label: '产品中心', value: 'product' }, { label: '技术中心', value: 'tech' }] }]" :batch-actions="[{ key: 'enable', label: '批量启用' }, { key: 'disable', label: '批量停用', danger: true }]" /></article>
    <article><h3>经营数据看板成品页</h3><PhoenixAnalyticsDashboardPage :metrics="[{ key: 'visitors', label: '访问人数', value: 1286, trend: 12.6 }, { key: 'revenue', label: '成交金额', value: 26890, kind: 'currency', trend: 8.2 }, { key: 'rate', label: '转化率', value: 6.8, kind: 'percent', trend: -1.4 }]" :trend="trend" :categories="categories" :ranking="categories" :filters="[{ key: 'period', label: '周期', options: [{ label: '本月', value: 'month' }, { label: '本季度', value: 'quarter' }] }]" /></article>
    <article><h3>内容消息工作台成品页</h3><PhoenixContentWorkspacePage :notifications="notifications" :threads="threads" :activities="activities" :announcement="{ title: '系统维护通知', description: '今晚 23:00 进行例行维护。', actionLabel: '查看安排' }" /></article>
    <article><h3>账户安全成品页</h3><PhoenixAccountSecurityPage username="admin" :sessions="sessions" /></article>
  </div>
</template>
