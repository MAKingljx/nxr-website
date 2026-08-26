<script setup lang="ts">
withDefaults(defineProps<{
  title?: string
  description?: string
  backLabel?: string
  showBack?: boolean
}>(), {
  title: '页面标题',
  description: '',
  backLabel: '返回',
  showBack: false,
})

defineEmits<{ back: [] }>()
</script>

<template>
  <header class="px-page-header">
    <button v-if="showBack" type="button" class="px-page-header__back" :aria-label="backLabel" @click="$emit('back')">←</button>
    <div class="px-page-header__content">
      <div v-if="$slots.breadcrumb" class="px-page-header__breadcrumb"><slot name="breadcrumb" /></div>
      <h1><slot name="title">{{ title }}</slot></h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div class="px-page-header__actions"><slot name="actions" /></div>
  </header>
</template>
