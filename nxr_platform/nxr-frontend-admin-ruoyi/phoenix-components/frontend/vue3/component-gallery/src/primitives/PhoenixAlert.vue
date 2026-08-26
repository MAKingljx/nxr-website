<script setup lang="ts">
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    variant?: 'info' | 'success' | 'warning' | 'error'
    title?: string
    description?: string
    closable?: boolean
    showIcon?: boolean
    closeLabel?: string
  }>(),
  {
    variant: 'info',
    title: '',
    description: '',
    closable: false,
    showIcon: true,
    closeLabel: '关闭提示',
  },
)

const emit = defineEmits<{
  close: []
}>()

const visible = ref(true)
const resolvedTitle = computed(() => props.title || ({
  info: '信息提示',
  success: '操作成功',
  warning: '请注意',
  error: '出现错误',
} as const)[props.variant])
const icon = computed(() => ({ info: 'i', success: '✓', warning: '!', error: '×' })[props.variant])

function close() {
  visible.value = false
  emit('close')
}
</script>

<template>
  <div v-if="visible" class="px-alert" :class="`px-alert--${variant}`" :role="variant === 'error' ? 'alert' : 'status'">
    <span v-if="showIcon" class="px-alert__icon" aria-hidden="true">{{ icon }}</span>
    <div class="px-alert__content">
      <strong><slot name="title">{{ resolvedTitle }}</slot></strong>
      <p v-if="description || $slots.default"><slot>{{ description }}</slot></p>
    </div>
    <button v-if="closable" type="button" class="px-alert__close" :aria-label="closeLabel" @click="close">×</button>
  </div>
</template>
