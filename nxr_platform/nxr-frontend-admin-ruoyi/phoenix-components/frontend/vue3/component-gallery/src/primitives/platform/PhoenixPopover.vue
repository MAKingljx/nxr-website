<script setup lang="ts">
import { ref, useId } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: boolean
  title?: string
  placement?: 'top' | 'right' | 'bottom' | 'left'
  disabled?: boolean
}>(), {
  modelValue: false,
  title: '',
  placement: 'bottom',
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  show: []
  hide: []
}>()

const root = ref<HTMLElement>()
const panelId = `px-popover-${useId().replaceAll(':', '')}`

function setOpen(open: boolean) {
  if (props.disabled || props.modelValue === open) return
  emit('update:modelValue', open)
  if (open) emit('show')
  else emit('hide')
}

function onFocusout(event: FocusEvent) {
  if (!root.value?.contains(event.relatedTarget as Node | null)) setOpen(false)
}
</script>

<template>
  <span ref="root" class="px-popover" @focusout="onFocusout" @keydown.esc.prevent="setOpen(false)">
    <button type="button" class="px-popover__trigger" :disabled="disabled" :aria-expanded="modelValue" :aria-controls="modelValue ? panelId : undefined" aria-haspopup="dialog" @click="setOpen(!modelValue)"><slot name="trigger">查看内容</slot></button>
    <span v-if="modelValue" :id="panelId" role="dialog" class="px-popover__panel" :class="`px-popover__panel--${placement}`" :aria-label="title || '弹出内容'">
      <strong v-if="title">{{ title }}</strong>
      <span class="px-popover__content"><slot>暂无内容</slot></span>
    </span>
  </span>
</template>
