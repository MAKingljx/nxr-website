<script setup lang="ts">
import { ref } from 'vue'
import PhoenixButton from '../primitives/PhoenixButton.vue'
import PhoenixCard from '../primitives/PhoenixCard.vue'
import {
  PhoenixAppShell,
  PhoenixCalendar,
  PhoenixCollapse,
  PhoenixDropdown,
  PhoenixKanban,
  PhoenixPageHeader,
  PhoenixPopover,
  PhoenixProgress,
  PhoenixResult,
  PhoenixSideMenu,
  PhoenixTimeline,
  PhoenixTopBar,
} from '../primitives/platform'

const sidebarOpen = ref(true)
const menuValue = ref<string | number | null>('overview')
const dropdownOpen = ref(false)
const popoverOpen = ref(false)
const collapseValue = ref<Array<string | number>>(['scope'])
const calendarValue = ref('2026-08-10')
const calendarView = ref('2026-08-01')
const selectedTask = ref<string | number | null>('task-2')

const menuItems = [
  { label: '工作台', value: 'overview', icon: '⌂' },
  { label: '资源管理', value: 'resources', icon: '▦', badge: 12 },
  { label: '订单中心', value: 'orders', icon: '◇' },
]
const dropdownItems = [
  { label: '编辑资料', value: 'edit' },
  { label: '复制记录', value: 'copy' },
  { label: '删除记录', value: 'delete', danger: true },
]
const collapseItems = [
  { title: '适用范围', value: 'scope', content: '教学、图书、旅游和商城管理项目。' },
  { title: '权限要求', value: 'permission', content: '由业务系统传入已授权操作。' },
  { title: '使用限制', value: 'limits', content: '组件不直接访问网络或持久化数据。' },
]
const timelineItems = [
  { title: '创建记录', time: '09:30', status: 'success' as const },
  { title: '提交审核', time: '10:15', status: 'default' as const },
  { title: '等待发布', time: '11:00', status: 'warning' as const },
]
const columns = [
  { id: 'todo', title: '待处理', cards: [{ id: 'task-1', title: '整理课程资料', tag: '教学' }] },
  { id: 'doing', title: '进行中', cards: [{ id: 'task-2', title: '核对馆藏目录', tag: '图书' }], limit: 3 },
  { id: 'done', title: '已完成', cards: [{ id: 'task-3', title: '发布旅游指南', tag: '旅游' }] },
]
</script>

<template>
  <div class="cg-platform-showcase">
    <PhoenixCard title="应用框架" padding="none" class="cg-demo-card cg-demo-card--wide cg-shell-preview">
      <PhoenixAppShell v-model:sidebar-open="sidebarOpen" label="示例后台">
        <template #topbar>
          <PhoenixTopBar title="资源中心" @menu="sidebarOpen = !sidebarOpen">
            <template #actions><PhoenixButton size="small">新建资源</PhoenixButton></template>
          </PhoenixTopBar>
        </template>
        <template #sidebar><PhoenixSideMenu v-model="menuValue" :items="menuItems" /></template>
        <PhoenixPageHeader title="资源管理" />
        <div class="cg-shell-preview__content">当前栏目：{{ menuItems.find((item) => item.value === menuValue)?.label }}</div>
      </PhoenixAppShell>
    </PhoenixCard>

    <PhoenixCard title="菜单与气泡" padding="large" class="cg-demo-card">
      <div class="cg-component-row">
        <PhoenixDropdown v-model="dropdownOpen" :items="dropdownItems" label="更多操作" />
        <PhoenixPopover v-model="popoverOpen" title="快捷信息">
          <template #trigger>查看状态</template>
          当前任务运行正常
        </PhoenixPopover>
      </div>
    </PhoenixCard>

    <PhoenixCard title="折叠面板" padding="large" class="cg-demo-card">
      <PhoenixCollapse v-model="collapseValue" :items="collapseItems" accordion />
    </PhoenixCard>

    <PhoenixCard title="进度与结果" padding="large" class="cg-demo-card">
      <div class="cg-control-stack">
        <PhoenixProgress :percentage="76" status="success" label="数据导入进度" />
        <PhoenixProgress :percentage="42" status="warning" label="内容审核进度" />
        <PhoenixResult title="保存成功" status="success">
          <template #actions><PhoenixButton size="small" variant="outline">返回列表</PhoenixButton></template>
        </PhoenixResult>
      </div>
    </PhoenixCard>

    <PhoenixCard title="操作记录" padding="large" class="cg-demo-card">
      <PhoenixTimeline :items="timelineItems" />
    </PhoenixCard>

    <PhoenixCard title="日历" padding="large" class="cg-demo-card">
      <PhoenixCalendar v-model="calendarValue" v-model:view-date="calendarView" />
    </PhoenixCard>

    <PhoenixCard title="任务看板" padding="large" class="cg-demo-card cg-demo-card--wide">
      <PhoenixKanban v-model:selected-id="selectedTask" :columns="columns" />
    </PhoenixCard>
  </div>
</template>
