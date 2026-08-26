<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { focusOverlay, trapOverlayFocus, type PhoenixOverlayCloseReason } from './overlay'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    ariaLabel?: string
    placement?: 'left' | 'right'
    width?: string
    closeOnEscape?: boolean
    closeOnOverlay?: boolean
    showClose?: boolean
    initialFocus?: string
  }>(),
  {
    title: '详情',
    ariaLabel: '抽屉',
    placement: 'right',
    width: '420px',
    closeOnEscape: true,
    closeOnOverlay: true,
    showClose: true,
    initialFocus: undefined,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  close: [reason: PhoenixOverlayCloseReason]
  opened: []
}>()

const uid = useId()
const titleId = `px-drawer-title-${uid}`
const panel = ref<HTMLElement>()
let previousFocus: HTMLElement | null = null

function requestClose(reason: PhoenixOverlayCloseReason) {
  emit('update:modelValue', false)
  emit('close', reason)
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (!panel.value) return
  if (event.key === 'Escape' && props.closeOnEscape) {
    event.preventDefault()
    event.stopPropagation()
    requestClose('escape')
    return
  }
  trapOverlayFocus(event, panel.value)
}

function stopListening(restoreFocus = true) {
  document.removeEventListener('keydown', onDocumentKeydown)
  if (restoreFocus) previousFocus?.focus()
  previousFocus = null
}

watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) {
      stopListening()
      return
    }
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    document.addEventListener('keydown', onDocumentKeydown)
    await nextTick()
    if (!panel.value) return
    focusOverlay(panel.value, props.initialFocus)
    emit('opened')
  },
  { immediate: true, flush: 'post' },
)

onBeforeUnmount(() => stopListening())
</script>

<template>
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="px-drawer__overlay"
      @click.self="closeOnOverlay && requestClose('overlay')"
    >
      <aside
        ref="panel"
        class="px-drawer"
        :class="`px-drawer--${placement}`"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="title ? titleId : undefined"
        :aria-label="title ? undefined : ariaLabel"
        :style="{ width }"
        tabindex="-1"
      >
        <header class="px-drawer__header">
          <h2 v-if="title" :id="titleId">{{ title }}</h2>
          <button v-if="showClose" type="button" class="px-overlay__close" aria-label="关闭抽屉" @click="requestClose('close-button')">×</button>
        </header>
        <div class="px-drawer__body"><slot /></div>
        <footer v-if="$slots.footer" class="px-drawer__footer"><slot name="footer" /></footer>
      </aside>
    </div>
  </Teleport>
</template>
