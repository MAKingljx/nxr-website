<script setup lang="ts">
const props = withDefaults(defineProps<{
  sidebarOpen?: boolean
  sidebarWidth?: string
  label?: string
}>(), {
  sidebarOpen: true,
  sidebarWidth: '248px',
  label: '应用界面',
})

const emit = defineEmits<{
  'update:sidebarOpen': [open: boolean]
}>()

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.sidebarOpen) {
    emit('update:sidebarOpen', false)
  }
}
</script>

<template>
  <div class="px-app-shell" :style="{ '--px-shell-sidebar': sidebarWidth }" @keydown="onKeydown">
    <header class="px-app-shell__top"><slot name="topbar" /></header>
    <aside class="px-app-shell__side" :class="{ 'is-open': sidebarOpen }" :aria-label="`${label}导航`">
      <slot name="sidebar" />
    </aside>
    <button v-if="sidebarOpen" type="button" class="px-app-shell__scrim" aria-label="关闭导航" @click="emit('update:sidebarOpen', false)"></button>
    <main class="px-app-shell__main" :aria-label="label"><slot /></main>
  </div>
</template>
