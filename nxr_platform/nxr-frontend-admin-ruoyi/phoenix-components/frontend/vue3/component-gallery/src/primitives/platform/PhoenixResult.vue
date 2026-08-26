<script setup lang="ts">
withDefaults(defineProps<{
  status?: 'success' | 'info' | 'warning' | 'error'
  title?: string
  description?: string
}>(), {
  status: 'success',
  title: '操作成功',
  description: '',
})
</script>

<template>
  <section class="px-result" :class="`px-result--${status}`" role="status">
    <div class="px-result__icon" aria-hidden="true"><slot name="icon">{{ status === 'success' ? '✓' : status === 'warning' ? '!' : status === 'error' ? '×' : 'i' }}</slot></div>
    <h2>{{ title }}</h2>
    <p v-if="description">{{ description }}</p>
    <div v-if="$slots.default" class="px-result__extra"><slot /></div>
    <div v-if="$slots.actions" class="px-result__actions"><slot name="actions" /></div>
  </section>
</template>
