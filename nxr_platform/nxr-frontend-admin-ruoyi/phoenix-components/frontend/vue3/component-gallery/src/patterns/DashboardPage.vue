<script setup lang="ts">
const props = withDefaults(defineProps<{
  title?: string
  sidebarOpen?: boolean
  refreshing?: boolean
  showRefresh?: boolean
  navigationLabel?: string
}>(), {
  title: '管理工作台',
  sidebarOpen: true,
  refreshing: false,
  showRefresh: true,
  navigationLabel: '工作台导航',
})

const emit = defineEmits<{
  'update:sidebarOpen': [open: boolean]
  refresh: []
}>()

function closeNavigation() {
  if (props.sidebarOpen) emit('update:sidebarOpen', false)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeNavigation()
}
</script>

<template>
  <div class="px-page-pattern px-dashboard-page" :class="{ 'is-navigation-open': sidebarOpen }" @keydown="onKeydown">
    <header class="px-page-pattern__topbar">
      <button
        type="button"
        class="px-page-pattern__nav-trigger"
        aria-label="打开导航"
        :aria-expanded="sidebarOpen"
        @click="emit('update:sidebarOpen', !sidebarOpen)"
      >
        ☰
      </button>
      <slot name="topbar">
        <strong>{{ title }}</strong>
      </slot>
      <div class="px-page-pattern__topbar-actions"><slot name="topbar-actions" /></div>
    </header>

    <aside
      class="px-dashboard-page__navigation"
      :class="{ 'is-open': sidebarOpen }"
      :aria-label="navigationLabel"
    >
      <slot name="navigation" />
    </aside>
    <button
      v-if="sidebarOpen"
      type="button"
      class="px-page-pattern__scrim"
      aria-label="关闭导航"
      @click="closeNavigation"
    ></button>

    <main class="px-dashboard-page__main" :aria-label="title">
      <header class="px-page-pattern__header">
        <h1><slot name="title">{{ title }}</slot></h1>
        <div class="px-page-pattern__actions">
          <slot name="actions">
            <button
              v-if="showRefresh"
              type="button"
              class="px-page-pattern__button"
              :disabled="refreshing"
              @click="emit('refresh')"
            >
              {{ refreshing ? '刷新中' : '刷新' }}
            </button>
          </slot>
        </div>
      </header>
      <section v-if="$slots.filters" class="px-page-pattern__toolbar" aria-label="筛选条件">
        <slot name="filters" />
      </section>
      <section v-if="$slots.metrics" class="px-dashboard-page__metrics" aria-label="关键指标">
        <slot name="metrics" />
      </section>
      <div class="px-dashboard-page__content">
        <section class="px-page-pattern__surface" aria-label="主要内容"><slot /></section>
        <aside v-if="$slots.aside" class="px-page-pattern__surface" aria-label="辅助信息"><slot name="aside" /></aside>
      </div>
    </main>
  </div>
</template>
