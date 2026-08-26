<script setup lang="ts">
import { computed } from 'vue'
import { finiteInteger, formatFileSize, normalizeAppearance } from './safety'
import type { PhoenixContentAppearance } from './safety'

export interface PhoenixCommentAttachment {
  id: string | number
  name: string
  size?: number
}

const props = withDefaults(defineProps<{
  modelValue?: string
  attachments?: PhoenixCommentAttachment[]
  appearance?: PhoenixContentAppearance
  placeholder?: string
  submitLabel?: string
  replyTo?: string
  maxLength?: number
  disabled?: boolean
  submitting?: boolean
  autofocus?: boolean
}>(), {
  modelValue: '',
  attachments: () => [],
  appearance: 'modern',
  placeholder: '写下你的评论…',
  submitLabel: '发布评论',
  replyTo: '',
  maxLength: 500,
  disabled: false,
  submitting: false,
  autofocus: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: [content: string]
  cancel: []
  'remove-attachment': [attachment: PhoenixCommentAttachment]
  'request-attachment': []
}>()

const appearanceValue = computed(() => normalizeAppearance(props.appearance))
const limit = computed(() => finiteInteger(props.maxLength, 500, 1))
const value = computed(() => props.modelValue.slice(0, limit.value))
const canSubmit = computed(() => !props.disabled && !props.submitting && value.value.trim().length > 0)

function update(event: Event) {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value.slice(0, limit.value))
}

function submit() {
  if (canSubmit.value) emit('submit', value.value.trim())
}
</script>

<template>
  <section class="px-comment-composer px-content-card" :data-appearance="appearanceValue" :aria-busy="submitting">
    <header v-if="replyTo" class="px-comment-composer__reply"><span>正在回复 {{ replyTo }}</span><button type="button" :disabled="disabled" @click="emit('cancel')">取消回复</button></header>
    <textarea :value="value" :maxlength="limit" :placeholder="placeholder" :disabled="disabled || submitting" :autofocus="autofocus" aria-label="评论内容" @input="update" @keydown.ctrl.enter.prevent="submit" @keydown.meta.enter.prevent="submit"></textarea>
    <ul v-if="attachments.length" class="px-comment-composer__attachments" aria-label="评论附件">
      <li v-for="attachment in attachments" :key="attachment.id"><span><strong>{{ attachment.name }}</strong><small>{{ formatFileSize(attachment.size) }}</small></span><button type="button" :disabled="disabled || submitting" :aria-label="`移除附件：${attachment.name}`" @click="emit('remove-attachment', attachment)">移除</button></li>
    </ul>
    <footer>
      <div><button type="button" :disabled="disabled || submitting" @click="emit('request-attachment')">添加附件</button><span>{{ value.length }}/{{ limit }}</span></div>
      <button class="px-content-primary" type="button" :disabled="!canSubmit" @click="submit">{{ submitting ? '发布中' : submitLabel }}</button>
    </footer>
  </section>
</template>
