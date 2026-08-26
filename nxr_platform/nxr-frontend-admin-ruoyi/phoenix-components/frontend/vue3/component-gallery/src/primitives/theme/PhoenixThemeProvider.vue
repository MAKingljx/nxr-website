<script setup lang="ts">
import { computed, provide } from 'vue'
import { PHOENIX_THEME_KEY } from './context'
import type { PhoenixTheme } from './context'

const PHOENIX_THEMES: readonly PhoenixTheme[] = ['modern', 'business', 'minimal', 'festive']

const props = withDefaults(defineProps<{
  theme?: PhoenixTheme
  label?: string
}>(), {
  theme: 'modern',
  label: '',
})

const themeValue = computed<PhoenixTheme>(() => PHOENIX_THEMES.includes(props.theme) ? props.theme : 'modern')

provide(PHOENIX_THEME_KEY, { theme: themeValue })
</script>

<template>
  <div class="px-theme-provider" :data-theme="themeValue" :aria-label="label || undefined">
    <slot />
  </div>
</template>
