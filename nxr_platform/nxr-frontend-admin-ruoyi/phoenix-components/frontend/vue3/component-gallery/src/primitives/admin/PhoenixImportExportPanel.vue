<script setup lang="ts">
import { computed, useId } from 'vue'

export interface PhoenixDataFormat { value: string; label: string; accept?: string; disabled?: boolean }
const props = withDefaults(defineProps<{
  formats?: PhoenixDataFormat[]
  modelValue?: string
  importing?: boolean
  exporting?: boolean
  disabled?: boolean
  title?: string
}>(), {
  formats: () => [{ value: 'xlsx', label: 'Excel', accept: '.xlsx' }, { value: 'csv', label: 'CSV', accept: '.csv,text/csv' }],
  modelValue: 'xlsx', importing: false, exporting: false, disabled: false, title: '数据导入导出',
})
const emit = defineEmits<{
  'update:modelValue': [value: string]
  importRequest: [file: File, format: string]
  exportRequest: [format: string]
}>()
const uid = useId()
const selected = computed(() => props.formats.find((item) => item.value === props.modelValue && !item.disabled) ?? props.formats.find((item) => !item.disabled))
const accept = computed(() => selected.value?.accept || '')
function importFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file && selected.value && !props.disabled && !props.importing) emit('importRequest', file, selected.value.value)
  input.value = ''
}
</script>

<template>
  <section class="px-import-export-panel" :aria-label="title" :aria-busy="importing || exporting">
    <header><h3>{{ title }}</h3></header>
    <label :for="`px-format-${uid}`"><span>文件格式</span>
      <select :id="`px-format-${uid}`" :value="selected?.value" :disabled="disabled || importing || exporting" @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
        <option v-for="format in formats" :key="format.value" :value="format.value" :disabled="format.disabled">{{ format.label }}</option>
      </select>
    </label>
    <div>
      <label class="px-import-export-panel__file" :class="{ 'is-disabled': disabled || importing || !selected }">
        <input type="file" :accept="accept" :disabled="disabled || importing || !selected" @change="importFile">
        <span>{{ importing ? '导入中' : '选择文件导入' }}</span>
      </label>
      <button type="button" :disabled="disabled || exporting || !selected" @click="selected && emit('exportRequest', selected.value)">{{ exporting ? '导出中' : '导出数据' }}</button>
    </div>
  </section>
</template>
