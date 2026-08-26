<script setup lang="ts">
withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    message?: string
    variant?: 'info' | 'success' | 'warning' | 'error'
    closable?: boolean
    actionText?: string
  }>(),
  {
    title: '通知',
    message: '',
    variant: 'info',
    closable: true,
    actionText: '',
  },
)

const emit = defineEmits<{
  'update:modelValue': [visible: boolean]
  close: [reason: 'close' | 'escape']
  action: []
}>()

function close(reason: 'close' | 'escape') {
  emit('update:modelValue', false)
  emit('close', reason)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    close('escape')
    event.preventDefault()
  }
}
</script>

<template>
  <section
    v-if="modelValue"
    class="px-notification"
    :class="`px-notification--${variant}`"
    :role="variant === 'error' ? 'alert' : 'status'"
    :aria-live="variant === 'error' ? 'assertive' : 'polite'"
    tabindex="-1"
    @keydown="onKeydown"
  >
    <span class="px-notification__icon" aria-hidden="true">{{ variant === 'success' ? '✓' : variant === 'error' ? '!' : variant === 'warning' ? '!' : 'i' }}</span>
    <div class="px-notification__content">
      <strong>{{ title }}</strong>
      <p v-if="message">{{ message }}</p>
      <button v-if="actionText || $slots.action" type="button" class="px-notification__action" @click="emit('action')"><slot name="action">{{ actionText }}</slot></button>
    </div>
    <button v-if="closable" type="button" class="px-notification__close" aria-label="关闭通知" @click="close('close')">×</button>
  </section>
</template>
