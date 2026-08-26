<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { finiteInteger, formatFileSize, normalizeAppearance } from './safety'
import type { PhoenixContentAppearance } from './safety'

export type PhoenixFileRejectReason = 'type' | 'size' | 'count'

export interface PhoenixRejectedFile {
  file: File
  reason: PhoenixFileRejectReason
}

const props = withDefaults(defineProps<{
  appearance?: PhoenixContentAppearance
  accept?: string
  multiple?: boolean
  disabled?: boolean
  maxFiles?: number
  maxSize?: number
  title?: string
  hint?: string
}>(), {
  appearance: 'modern',
  accept: '',
  multiple: true,
  disabled: false,
  maxFiles: 10,
  maxSize: 10 * 1024 * 1024,
  title: '拖放文件到这里',
  hint: '或点击选择本地文件；组件不会自动上传或读取文件内容',
})

const emit = defineEmits<{
  'files-selected': [files: File[]]
  rejected: [rejected: PhoenixRejectedFile[]]
  'drag-state': [dragging: boolean]
}>()

const uid = useId()
const inputId = `px-file-dropzone-${uid}`
const dragging = ref(false)
const appearanceValue = computed(() => normalizeAppearance(props.appearance))
const fileLimit = computed(() => finiteInteger(props.maxFiles, 10, 1))
const sizeLimit = computed(() => finiteInteger(props.maxSize, 10 * 1024 * 1024, 1))

function matchesAccept(file: File) {
  const tokens = props.accept.split(',').map((value) => value.trim().toLowerCase()).filter(Boolean)
  if (!tokens.length) return true
  const name = file.name.toLowerCase()
  const type = file.type.toLowerCase()
  return tokens.some((token) => {
    if (token.startsWith('.')) return name.endsWith(token)
    if (token.endsWith('/*')) return type.startsWith(token.slice(0, -1))
    return type === token
  })
}

function processFiles(source: FileList | File[]) {
  if (props.disabled) return
  const files = Array.from(source)
  const accepted: File[] = []
  const rejected: PhoenixRejectedFile[] = []
  files.forEach((file) => {
    if (accepted.length >= fileLimit.value || (!props.multiple && accepted.length >= 1)) rejected.push({ file, reason: 'count' })
    else if (!matchesAccept(file)) rejected.push({ file, reason: 'type' })
    else if (file.size > sizeLimit.value) rejected.push({ file, reason: 'size' })
    else accepted.push(file)
  })
  if (accepted.length) emit('files-selected', accepted)
  if (rejected.length) emit('rejected', rejected)
}

function onChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files) processFiles(input.files)
  input.value = ''
}

function setDragging(value: boolean) {
  if (props.disabled || dragging.value === value) return
  dragging.value = value
  emit('drag-state', value)
}

function onDrop(event: DragEvent) {
  setDragging(false)
  if (event.dataTransfer?.files) processFiles(event.dataTransfer.files)
}
</script>

<template>
  <section class="px-file-dropzone" :class="{ 'is-dragging': dragging, 'is-disabled': disabled }" :data-appearance="appearanceValue" @dragenter.prevent="setDragging(true)" @dragover.prevent="setDragging(true)" @dragleave.prevent="setDragging(false)" @drop.prevent="onDrop">
    <input :id="inputId" type="file" :accept="accept || undefined" :multiple="multiple" :disabled="disabled" @change="onChange" />
    <label :for="inputId" :aria-disabled="disabled">
      <span class="px-file-dropzone__icon" aria-hidden="true">＋</span>
      <strong>{{ title }}</strong>
      <small>{{ hint }}</small>
      <span>{{ accept ? `支持 ${accept}` : '支持任意文件类型' }} · 单个不超过 {{ formatFileSize(sizeLimit) }} · 最多 {{ multiple ? fileLimit : 1 }} 个</span>
    </label>
  </section>
</template>
