<script setup lang="ts">
import { nextTick, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    ariaLabel?: string
    disabled?: boolean
    readonly?: boolean
    rows?: number
    maxLength?: number
    showToolbar?: boolean
  }>(),
  {
    placeholder: '请输入正文，支持 Markdown 风格标记',
    ariaLabel: '正文编辑器',
    disabled: false,
    readonly: false,
    rows: 10,
    showToolbar: true,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
}>()

const textarea = ref<HTMLTextAreaElement>()

function update(value: string) {
  emit('update:modelValue', value)
  emit('change', value)
}

async function wrapSelection(prefix: string, suffix = prefix) {
  if (props.disabled || props.readonly) return
  const field = textarea.value
  if (!field) return
  const start = field.selectionStart
  const end = field.selectionEnd
  const selected = props.modelValue.slice(start, end)
  const next = `${props.modelValue.slice(0, start)}${prefix}${selected}${suffix}${props.modelValue.slice(end)}`
  update(props.maxLength === undefined ? next : next.slice(0, props.maxLength))
  await nextTick()
  const selectionStart = start + prefix.length
  field.focus()
  field.setSelectionRange(selectionStart, selectionStart + selected.length)
}

async function prefixLine(prefix: string) {
  if (props.disabled || props.readonly) return
  const field = textarea.value
  if (!field) return
  const position = field.selectionStart
  const lineStart = props.modelValue.lastIndexOf('\n', Math.max(0, position - 1)) + 1
  const next = `${props.modelValue.slice(0, lineStart)}${prefix}${props.modelValue.slice(lineStart)}`
  update(props.maxLength === undefined ? next : next.slice(0, props.maxLength))
  await nextTick()
  field.focus()
  field.setSelectionRange(position + prefix.length, position + prefix.length)
}

function onInput(event: Event) {
  update((event.target as HTMLTextAreaElement).value)
}

function onKeydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey)) return
  const key = event.key.toLowerCase()
  if (!['b', 'i'].includes(key)) return
  event.preventDefault()
  void wrapSelection(key === 'b' ? '**' : '_')
}
</script>

<template>
  <div class="px-rich-editor" :class="{ 'is-disabled': disabled }">
    <div v-if="showToolbar" class="px-rich-editor__toolbar" role="toolbar" aria-label="文本格式">
      <button type="button" :disabled="disabled || readonly" aria-label="加粗" @click="wrapSelection('**')"><strong>B</strong></button>
      <button type="button" :disabled="disabled || readonly" aria-label="斜体" @click="wrapSelection('_')"><em>I</em></button>
      <button type="button" :disabled="disabled || readonly" aria-label="一级标题" @click="prefixLine('# ')">H1</button>
      <button type="button" :disabled="disabled || readonly" aria-label="无序列表" @click="prefixLine('- ')">•</button>
      <button type="button" :disabled="disabled || readonly" aria-label="插入链接" @click="wrapSelection('[', '](https://)')">↗</button>
    </div>
    <textarea
      ref="textarea"
      class="px-rich-editor__input"
      :value="modelValue"
      :placeholder="placeholder"
      :aria-label="ariaLabel"
      aria-multiline="true"
      :disabled="disabled"
      :readonly="readonly"
      :rows="rows"
      :maxlength="maxLength"
      @input="onInput"
      @keydown="onKeydown"
    ></textarea>
    <div v-if="maxLength !== undefined" class="px-rich-editor__count" aria-live="polite">{{ modelValue.length }} / {{ maxLength }}</div>
  </div>
</template>
