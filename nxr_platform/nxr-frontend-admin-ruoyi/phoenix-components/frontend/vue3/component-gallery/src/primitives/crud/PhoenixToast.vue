<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'

export type PhoenixToastCloseReason = 'close-button' | 'timeout' | 'escape'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    message?: string
    type?: 'info' | 'success' | 'warning' | 'error'
    title?: string
    closable?: boolean
    duration?: number
    position?: 'top' | 'top-right' | 'bottom' | 'bottom-right'
  }>(),
  {
    message: '操作已完成',
    type: 'info',
    title: '',
    closable: true,
    duration: 0,
    position: 'top-right',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  close: [reason: PhoenixToastCloseReason]
}>()

let timer: ReturnType<typeof setTimeout> | undefined

function close(reason: PhoenixToastCloseReason) {
  if (timer) clearTimeout(timer)
  timer = undefined
  emit('update:modelValue', false)
  emit('close', reason)
}

watch(
  () => [props.modelValue, props.duration] as const,
  ([visible, duration]) => {
    if (timer) clearTimeout(timer)
    timer = undefined
    if (visible && duration > 0) timer = setTimeout(() => close('timeout'), duration)
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <Transition name="px-toast">
    <section
      v-if="modelValue"
      class="px-toast"
      :class="[`px-toast--${type}`, `px-toast--${position}`]"
      :role="type === 'error' || type === 'warning' ? 'alert' : 'status'"
      :aria-live="type === 'error' || type === 'warning' ? 'assertive' : 'polite'"
      tabindex="0"
      @keydown.esc="close('escape')"
    >
      <span class="px-toast__icon" aria-hidden="true">{{ type === 'success' ? '✓' : type === 'error' ? '!' : type === 'warning' ? '!' : 'i' }}</span>
      <div class="px-toast__content">
        <strong v-if="title">{{ title }}</strong>
        <span>{{ message }}</span>
        <div v-if="$slots.action" class="px-toast__action"><slot name="action" /></div>
      </div>
      <button v-if="closable" type="button" class="px-toast__close" aria-label="关闭消息" @click="close('close-button')">×</button>
    </section>
  </Transition>
</template>
