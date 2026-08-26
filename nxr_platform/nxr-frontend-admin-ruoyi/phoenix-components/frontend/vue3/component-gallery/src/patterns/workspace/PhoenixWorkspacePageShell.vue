<script setup lang="ts">
export interface PhoenixWorkspaceNavigationItem {
  id: string
  label: string
  badge?: string | number
  disabled?: boolean
}

export interface PhoenixWorkspaceAction {
  id: string
  label: string
  primary?: boolean
  disabled?: boolean
}

withDefaults(defineProps<{
  title: string
  activeNavigation?: string
  navigation?: PhoenixWorkspaceNavigationItem[]
  actions?: PhoenixWorkspaceAction[]
  busy?: boolean
}>(), {
  activeNavigation: '',
  navigation: () => [],
  actions: () => [],
  busy: false,
})

const emit = defineEmits<{
  navigate: [id: string]
  action: [id: string]
}>()
</script>

<template>
  <section class="px-workspace-page" :aria-label="title" :aria-busy="busy">
    <header class="px-workspace-page__topbar">
      <div class="px-workspace-page__brand"><slot name="brand"><strong>Phoenix</strong></slot></div>
      <div class="px-workspace-page__topbar-content"><slot name="topbar" /></div>
    </header>
    <div class="px-workspace-page__layout">
      <nav class="px-workspace-page__navigation" aria-label="工作台导航">
        <slot name="navigation">
          <button
            v-for="item in navigation"
            :key="item.id"
            type="button"
            :class="{ 'is-active': item.id === activeNavigation }"
            :aria-current="item.id === activeNavigation ? 'page' : undefined"
            :disabled="item.disabled || busy"
            @click="emit('navigate', item.id)"
          >
            <span>{{ item.label }}</span><b v-if="item.badge !== undefined">{{ item.badge }}</b>
          </button>
        </slot>
      </nav>
      <main class="px-workspace-page__main">
        <header class="px-workspace-page__header">
          <h1>{{ title }}</h1>
          <div v-if="actions.length" class="px-workspace-page__actions">
            <button
              v-for="actionItem in actions"
              :key="actionItem.id"
              type="button"
              :class="{ 'is-primary': actionItem.primary }"
              :disabled="actionItem.disabled || busy"
              @click="emit('action', actionItem.id)"
            >
              {{ actionItem.label }}
            </button>
          </div>
        </header>
        <div class="px-workspace-page__content"><slot /></div>
      </main>
      <aside v-if="$slots.aside" class="px-workspace-page__aside" aria-label="辅助信息"><slot name="aside" /></aside>
    </div>
    <footer v-if="$slots.footer" class="px-workspace-page__footer"><slot name="footer" /></footer>
  </section>
</template>
