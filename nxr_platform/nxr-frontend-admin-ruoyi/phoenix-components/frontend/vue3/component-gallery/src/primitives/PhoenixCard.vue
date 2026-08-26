<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    subtitle?: string
    elevated?: boolean
    padding?: 'none' | 'small' | 'medium' | 'large'
    interactive?: boolean
  }>(),
  {
    title: '',
    subtitle: '',
    elevated: false,
    padding: 'medium',
    interactive: false,
  },
)
</script>

<template>
  <article class="px-card" :class="[`px-card--${padding}`, { 'is-elevated': elevated, 'is-interactive': interactive }]">
    <header v-if="title || subtitle || $slots.header" class="px-card__header">
      <slot name="header">
        <div>
          <h3 v-if="title">{{ title }}</h3>
          <p v-if="subtitle">{{ subtitle }}</p>
        </div>
      </slot>
      <slot name="extra" />
    </header>
    <div class="px-card__body"><slot /></div>
    <footer v-if="$slots.footer" class="px-card__footer"><slot name="footer" /></footer>
  </article>
</template>
