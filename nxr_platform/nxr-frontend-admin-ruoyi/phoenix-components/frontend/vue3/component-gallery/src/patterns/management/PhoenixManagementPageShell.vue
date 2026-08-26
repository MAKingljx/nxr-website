<script setup lang="ts">
export interface PhoenixManagementStat {
  label: string
  value: string | number
  tone?: 'neutral' | 'primary' | 'success' | 'warning' | 'danger'
}

export interface PhoenixManagementAction {
  id: string
  label: string
  variant?: 'primary' | 'secondary' | 'danger'
  disabled?: boolean
}

withDefaults(defineProps<{
  title: string
  stats?: PhoenixManagementStat[]
  actions?: PhoenixManagementAction[]
  busy?: boolean
  sidebarLabel?: string
  contentLabel?: string
  detailLabel?: string
}>(), {
  stats: () => [],
  actions: () => [],
  busy: false,
  sidebarLabel: '分类导航',
  contentLabel: '数据内容',
  detailLabel: '详情面板',
})

const emit = defineEmits<{
  action: [id: string]
}>()
</script>

<template>
  <section class="px-management-page" :aria-label="title" :aria-busy="busy">
    <header class="px-management-page__header">
      <div><h2>{{ title }}</h2><slot name="header" /></div>
      <nav v-if="actions.length" aria-label="页面操作">
        <button v-for="actionItem in actions" :key="actionItem.id" type="button" :class="`is-${actionItem.variant ?? 'secondary'}`" :disabled="actionItem.disabled || busy" @click="emit('action', actionItem.id)">{{ actionItem.label }}</button>
      </nav>
    </header>
    <div v-if="stats.length" class="px-management-page__stats">
      <article v-for="stat in stats" :key="stat.label" :data-tone="stat.tone ?? 'neutral'"><span>{{ stat.label }}</span><strong>{{ stat.value }}</strong></article>
    </div>
    <div v-if="$slots.filters" class="px-management-page__filters"><slot name="filters" /></div>
    <div class="px-management-page__workspace" :class="{ 'has-sidebar': $slots.sidebar, 'has-detail': $slots.detail }">
      <aside v-if="$slots.sidebar" :aria-label="sidebarLabel"><slot name="sidebar" /></aside>
      <main :aria-label="contentLabel"><slot /></main>
      <aside v-if="$slots.detail" :aria-label="detailLabel"><slot name="detail" /></aside>
    </div>
    <footer v-if="$slots.footer"><slot name="footer" /></footer>
  </section>
</template>
