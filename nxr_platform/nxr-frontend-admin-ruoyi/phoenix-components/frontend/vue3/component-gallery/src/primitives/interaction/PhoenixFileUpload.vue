<script setup lang="ts">
import { ref } from 'vue'

export type PhoenixUploadRejectReason = 'type' | 'size' | 'limit'

export interface PhoenixUploadRejection {
  file: File
  reason: PhoenixUploadRejectReason
  message: string
}

const props = withDefaults(
  defineProps<{
    modelValue: File[]
    accept?: string
    maxSizeMb?: number
    limit?: number
    multiple?: boolean
    disabled?: boolean
    label?: string
    emptyText?: string
  }>(),
  {
    accept: '',
    maxSizeMb: 10,
    limit: 1,
    multiple: false,
    disabled: false,
    label: '选择文件',
    emptyText: '尚未选择文件',
  },
)

const emit = defineEmits<{
  'update:modelValue': [files: File[]]
  change: [files: File[]]
  reject: [rejection: PhoenixUploadRejection]
  remove: [file: File, index: number]
}>()

const input = ref<HTMLInputElement | null>(null)
const rejectionMessage = ref('')

function matchesAccept(file: File) {
  if (!props.accept.trim()) return true
  return props.accept.split(',').some((rawRule) => {
    const rule = rawRule.trim().toLowerCase()
    if (!rule) return false
    if (rule.startsWith('.')) return file.name.toLowerCase().endsWith(rule)
    if (rule.endsWith('/*')) return file.type.toLowerCase().startsWith(rule.slice(0, -1))
    return file.type.toLowerCase() === rule
  })
}

function reject(file: File, reason: PhoenixUploadRejectReason, message: string) {
  rejectionMessage.value = message
  emit('reject', { file, reason, message })
}

function choose() {
  if (!props.disabled) input.value?.click()
}

function onSelect(event: Event) {
  const element = event.target as HTMLInputElement
  const selected = Array.from(element.files ?? [])
  const next = [...props.modelValue]
  rejectionMessage.value = ''

  selected.forEach((file) => {
    if (!matchesAccept(file)) {
      reject(file, 'type', `${file.name} 的文件类型不符合要求`)
      return
    }
    if (file.size > Math.max(0, props.maxSizeMb) * 1024 * 1024) {
      reject(file, 'size', `${file.name} 超过 ${props.maxSizeMb} MB`)
      return
    }
    if (next.length >= Math.max(0, props.limit)) {
      reject(file, 'limit', `最多选择 ${props.limit} 个文件`)
      return
    }
    next.push(file)
  })

  if (next.length !== props.modelValue.length) {
    emit('update:modelValue', next)
    emit('change', next)
  }
  element.value = ''
}

function removeFile(index: number) {
  if (props.disabled) return
  const file = props.modelValue[index]
  if (!file) return
  const next = props.modelValue.filter((_, fileIndex) => fileIndex !== index)
  emit('update:modelValue', next)
  emit('change', next)
  emit('remove', file, index)
}
</script>

<template>
  <div class="px-file-upload" :class="{ 'is-disabled': disabled }">
    <input
      ref="input"
      class="px-file-upload__input"
      type="file"
      :accept="accept || undefined"
      :multiple="multiple"
      :disabled="disabled"
      :aria-label="label"
      @change="onSelect"
    >
    <button type="button" class="px-file-upload__button" :disabled="disabled" @click="choose">
      <span aria-hidden="true">＋</span>{{ label }}
    </button>
    <span class="px-file-upload__hint">最多 {{ limit }} 个，每个不超过 {{ maxSizeMb }} MB</span>
    <ul v-if="modelValue.length" class="px-file-upload__list" aria-label="已选择文件">
      <li v-for="(file, index) in modelValue" :key="`${file.name}-${file.size}-${index}`">
        <span class="px-file-upload__name">{{ file.name }}</span>
        <button type="button" :disabled="disabled" :aria-label="`移除 ${file.name}`" @click="removeFile(index)">×</button>
      </li>
    </ul>
    <p v-else class="px-file-upload__empty">{{ emptyText }}</p>
    <p class="px-file-upload__error" aria-live="polite">{{ rejectionMessage }}</p>
  </div>
</template>
