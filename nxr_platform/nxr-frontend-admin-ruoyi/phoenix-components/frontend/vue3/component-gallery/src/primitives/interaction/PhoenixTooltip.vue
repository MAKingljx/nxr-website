<script setup lang="ts">
import { ref, useId, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    content?: string
    placement?: 'top' | 'right' | 'bottom' | 'left'
    disabled?: boolean
  }>(),
  {
    modelValue: false,
    content: '提示信息',
    placement: 'top',
    disabled: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [visible: boolean]
  show: []
  hide: []
}>()

const tooltipId = `px-tooltip-${useId().replaceAll(':', '')}`
const visible = ref(props.modelValue)

watch(() => props.modelValue, (value) => { visible.value = value })

function setVisible(value: boolean) {
  if (props.disabled || visible.value === value) return
  visible.value = value
  emit('update:modelValue', value)
  if (value) emit('show')
  else emit('hide')
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    setVisible(false)
    event.preventDefault()
  }
}
</script>

<template>
  <span
    class="px-tooltip"
    tabindex="0"
    :aria-describedby="visible && !disabled ? tooltipId : undefined"
    @mouseenter="setVisible(true)"
    @mouseleave="setVisible(false)"
    @focus="setVisible(true)"
    @blur="setVisible(false)"
    @keydown="onKeydown"
  >
    <span class="px-tooltip__trigger"><slot>查看提示</slot></span>
    <span v-if="visible && !disabled" :id="tooltipId" role="tooltip" class="px-tooltip__content" :class="`px-tooltip__content--${placement}`">{{ content }}</span>
  </span>
</template>
