<script setup lang="ts">
withDefaults(
  defineProps<{
    rows?: number
    title?: boolean
    avatar?: boolean
    animated?: boolean
    ariaLabel?: string
  }>(),
  {
    rows: 3,
    title: true,
    avatar: false,
    animated: true,
    ariaLabel: '正在加载',
  },
)
</script>

<template>
  <div class="px-skeleton" :class="{ 'is-animated': animated, 'has-avatar': avatar }" role="status" aria-busy="true" :aria-label="ariaLabel">
    <span v-if="avatar" class="px-skeleton__avatar" aria-hidden="true"></span>
    <div class="px-skeleton__content" aria-hidden="true">
      <span v-if="title" class="px-skeleton__title"></span>
      <span
        v-for="row in Math.max(0, rows)"
        :key="row"
        class="px-skeleton__row"
        :class="{ 'is-last': row === Math.max(0, rows) }"
      ></span>
    </div>
  </div>
</template>
