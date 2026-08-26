<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    description?: string
    compact?: boolean
  }>(),
  {
    title: '暂无数据',
    description: '',
    compact: false,
  },
)
</script>

<template>
  <section class="px-empty" :class="{ 'px-empty--compact': compact }" role="status" aria-live="polite">
    <div class="px-empty__visual" aria-hidden="true">
      <slot name="visual"><span></span></slot>
    </div>
    <h3>{{ title }}</h3>
    <p v-if="description">{{ description }}</p>
    <div v-if="$slots.action" class="px-empty__action"><slot name="action" /></div>
  </section>
</template>
