<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { normalizeAppearance, safeImageUrl } from './safety'
import type { PhoenixContentAppearance } from './safety'

const props = withDefaults(defineProps<{
  open?: boolean
  src?: string
  alt?: string
  title?: string
  caption?: string
  scale?: number
  appearance?: PhoenixContentAppearance
  hasPrevious?: boolean
  hasNext?: boolean
  downloadable?: boolean
}>(), {
  open: false,
  src: '',
  alt: '图片预览',
  title: '图片预览',
  caption: '',
  scale: 1,
  appearance: 'modern',
  hasPrevious: false,
  hasNext: false,
  downloadable: false,
})

const emit = defineEmits<{
  'update:open': [open: boolean]
  'update:scale': [scale: number]
  close: []
  previous: []
  next: []
  download: [safeUrl: string]
}>()

const appearanceValue = computed(() => normalizeAppearance(props.appearance))
const safeSource = computed(() => safeImageUrl(props.src))
const scaleValue = computed(() => Math.min(4, Math.max(0.25, Number.isFinite(props.scale) ? props.scale : 1)))
const dialog = ref<HTMLElement>()
let focusBeforeOpen: HTMLElement | null = null

function close() {
  emit('update:open', false)
  emit('close')
}

function changeScale(delta: number) {
  emit('update:scale', Math.min(4, Math.max(0.25, Number((scaleValue.value + delta).toFixed(2)))))
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
  } else if (event.key === 'Tab') {
    trapFocus(event)
  }
}

function trapFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), [href], [tabindex]:not([tabindex="-1"])')]
  if (!controls.length) {
    event.preventDefault()
    dialog.value.focus()
    return
  }
  const first = controls[0]
  const last = controls.at(-1)!
  const active = document.activeElement
  if (!dialog.value.contains(active) || active === dialog.value) {
    event.preventDefault()
    const target = event.shiftKey ? last : first
    target.focus()
  } else if (event.shiftKey && active === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

watch(() => props.open, async (open, wasOpen) => {
  if (open) {
    focusBeforeOpen = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    dialog.value?.querySelector<HTMLElement>('button:not(:disabled)')?.focus()
  } else if (wasOpen) {
    await nextTick()
    if (focusBeforeOpen?.isConnected) focusBeforeOpen.focus()
    focusBeforeOpen = null
  }
}, { immediate: true, flush: 'post' })

onMounted(() => document.addEventListener('keydown', onDocumentKeydown))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onDocumentKeydown)
  if (focusBeforeOpen?.isConnected) focusBeforeOpen.focus()
})
</script>

<template>
  <section v-if="open" ref="dialog" class="px-image-preview" :data-appearance="appearanceValue" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1">
    <header><div><h3>{{ title }}</h3><p v-if="caption">{{ caption }}</p></div><button type="button" aria-label="关闭图片预览" @click="close">关闭</button></header>
    <div class="px-image-preview__stage">
      <img v-if="safeSource" :src="safeSource" :alt="alt" :style="{ transform: `scale(${scaleValue})` }" />
      <p v-else class="px-content-empty" role="status">图片地址不可用</p>
    </div>
    <footer>
      <div><button type="button" :disabled="!hasPrevious" @click="emit('previous')">上一张</button><button type="button" :disabled="!hasNext" @click="emit('next')">下一张</button></div>
      <div><button type="button" :disabled="scaleValue <= 0.25" aria-label="缩小图片" @click="changeScale(-0.25)">−</button><output aria-label="当前缩放比例">{{ Math.round(scaleValue * 100) }}%</output><button type="button" :disabled="scaleValue >= 4" aria-label="放大图片" @click="changeScale(0.25)">＋</button><button v-if="downloadable" type="button" :disabled="!safeSource" @click="emit('download', safeSource)">下载</button></div>
    </footer>
  </section>
</template>
