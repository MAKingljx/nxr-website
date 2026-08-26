<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    ariaLabel?: string
    loading?: boolean
    error?: string
    empty?: boolean
    loadingText?: string
    emptyText?: string
  }>(),
  {
    title: '数据图表',
    loading: false,
    error: '',
    empty: false,
    loadingText: '图表加载中',
    emptyText: '暂无图表数据',
  },
)
</script>

<template>
  <section class="px-chart-container" :aria-label="ariaLabel || title" :aria-busy="loading">
    <header class="px-chart-container__header">
      <slot name="title"><h3>{{ title }}</h3></slot>
      <div v-if="$slots.actions" class="px-chart-container__actions"><slot name="actions"></slot></div>
    </header>
    <div class="px-chart-container__body">
      <div v-if="loading" class="px-advanced-state" role="status">{{ loadingText }}</div>
      <div v-else-if="error" class="px-advanced-state is-error" role="alert">{{ error }}</div>
      <div v-else-if="empty" class="px-advanced-state" role="status">{{ emptyText }}</div>
      <slot v-else></slot>
    </div>
  </section>
</template>
