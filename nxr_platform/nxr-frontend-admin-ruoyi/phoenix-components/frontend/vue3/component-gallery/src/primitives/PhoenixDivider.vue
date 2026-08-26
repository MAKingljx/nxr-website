<script setup lang="ts">
import { computed, useSlots } from 'vue'

const props = withDefaults(
  defineProps<{
    direction?: 'horizontal' | 'vertical'
    contentPosition?: 'left' | 'center' | 'right'
    dashed?: boolean
    text?: string
  }>(),
  {
    direction: 'horizontal',
    contentPosition: 'center',
    dashed: false,
    text: '',
  },
)

const slots = useSlots()
const hasContent = computed(() => props.direction === 'horizontal' && Boolean(props.text || slots.default))
</script>

<template>
  <div
    class="px-divider"
    :class="[`px-divider--${direction}`, `px-divider--${contentPosition}`, { 'is-dashed': dashed, 'has-content': hasContent }]"
    role="separator"
    :aria-orientation="direction"
  >
    <span v-if="hasContent" class="px-divider__text"><slot>{{ text }}</slot></span>
  </div>
</template>
