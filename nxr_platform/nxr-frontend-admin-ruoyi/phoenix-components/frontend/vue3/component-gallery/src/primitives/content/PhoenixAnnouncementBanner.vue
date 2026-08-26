<script setup lang="ts">
import { computed } from 'vue'
import { normalizeAppearance } from './safety'
import type { PhoenixContentAppearance } from './safety'

export type PhoenixAnnouncementTone = 'info' | 'success' | 'warning' | 'danger'

const props = withDefaults(defineProps<{
  visible?: boolean
  title?: string
  description?: string
  label?: string
  actionLabel?: string
  dismissible?: boolean
  tone?: PhoenixAnnouncementTone
  appearance?: PhoenixContentAppearance
}>(), {
  visible: true,
  title: '重要公告',
  description: '',
  label: '公告',
  actionLabel: '',
  dismissible: true,
  tone: 'info',
  appearance: 'modern',
})

const emit = defineEmits<{
  'update:visible': [visible: boolean]
  action: []
  dismiss: []
}>()

const appearanceValue = computed(() => normalizeAppearance(props.appearance))

function dismiss() {
  emit('update:visible', false)
  emit('dismiss')
}
</script>

<template>
  <aside v-if="visible" class="px-announcement-banner" :class="`is-${tone}`" :data-appearance="appearanceValue" :role="tone === 'danger' ? 'alert' : 'status'">
    <span class="px-announcement-banner__label">{{ label }}</span>
    <div class="px-announcement-banner__copy"><strong>{{ title }}</strong><p v-if="description">{{ description }}</p></div>
    <div class="px-content-actions">
      <button v-if="actionLabel" type="button" @click="emit('action')">{{ actionLabel }}</button>
      <button v-if="dismissible" type="button" aria-label="关闭公告" @click="dismiss">关闭</button>
    </div>
  </aside>
</template>
