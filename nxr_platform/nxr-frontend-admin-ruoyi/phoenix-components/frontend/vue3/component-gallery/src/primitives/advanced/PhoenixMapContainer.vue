<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    ariaLabel?: string
    loading?: boolean
    locating?: boolean
    error?: string
    empty?: boolean
    loadingText?: string
    emptyText?: string
    showLocate?: boolean
    locateLabel?: string
    height?: number
  }>(),
  {
    title: '地图',
    ariaLabel: '地图区域',
    loading: false,
    locating: false,
    error: '',
    empty: false,
    loadingText: '地图加载中',
    emptyText: '暂无地图内容',
    showLocate: true,
    locateLabel: '定位当前位置',
    height: 360,
  },
)

const emit = defineEmits<{
  locate: []
}>()
</script>

<template>
  <section class="px-map-container" :aria-label="ariaLabel" :aria-busy="loading || locating">
    <header class="px-map-container__header">
      <slot name="title"><h3>{{ title }}</h3></slot>
      <button v-if="showLocate" type="button" :disabled="loading || locating" :aria-label="locateLabel" @click="emit('locate')">
        {{ locating ? '定位中' : locateLabel }}
      </button>
    </header>
    <div class="px-map-container__body" :style="{ minHeight: `${Math.max(160, height)}px` }">
      <div v-if="loading" class="px-advanced-state" role="status">{{ loadingText }}</div>
      <div v-else-if="error" class="px-advanced-state is-error" role="alert">{{ error }}</div>
      <div v-else-if="empty" class="px-advanced-state" role="status"><slot name="empty">{{ emptyText }}</slot></div>
      <slot v-else></slot>
      <div v-if="$slots.overlay" class="px-map-container__overlay"><slot name="overlay"></slot></div>
    </div>
  </section>
</template>
