<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'
    size?: 'small' | 'medium' | 'large'
    nativeType?: 'button' | 'submit' | 'reset'
    loading?: boolean
    disabled?: boolean
    block?: boolean
    loadingText?: string
  }>(),
  {
    variant: 'primary',
    size: 'medium',
    nativeType: 'button',
    loading: false,
    disabled: false,
    block: false,
    loadingText: '加载中',
  },
)
</script>

<template>
  <button
    :type="nativeType"
    class="px-button"
    :class="[`px-button--${variant}`, `px-button--${size}`, { 'px-button--block': block, 'is-loading': loading }]"
    :disabled="disabled || loading"
    :aria-busy="loading"
  >
    <span v-if="loading" class="px-button__spinner" aria-hidden="true"></span>
    <span v-else-if="$slots.icon" class="px-button__icon" aria-hidden="true"><slot name="icon" /></span>
    <span class="px-button__content"><template v-if="loading">{{ loadingText }}</template><slot v-else>按钮</slot></span>
  </button>
</template>
