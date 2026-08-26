<script setup lang="ts">
import { computed, useId } from 'vue'

export interface PhoenixManagedFile {
  id: string | number
  name: string
  size?: number
  type?: string
  updatedAt?: string
  disabled?: boolean
  downloadable?: boolean
  deletable?: boolean
}

const props = withDefaults(
  defineProps<{
    files: PhoenixManagedFile[]
    selectedIds?: Array<string | number>
    multiple?: boolean
    loading?: boolean
    readonly?: boolean
    name?: string
    ariaLabel?: string
    emptyText?: string
    loadingText?: string
  }>(),
  {
    selectedIds: () => [],
    multiple: true,
    loading: false,
    readonly: false,
    name: undefined,
    ariaLabel: '文件列表',
    emptyText: '暂无文件',
    loadingText: '文件加载中',
  },
)

const emit = defineEmits<{
  'update:selectedIds': [ids: Array<string | number>]
  select: [file: PhoenixManagedFile, selected: boolean]
  delete: [file: PhoenixManagedFile]
  download: [file: PhoenixManagedFile]
}>()

const selectedSet = computed(() => new Set(props.selectedIds))
const uid = useId()
const selectionName = computed(() => props.name || `phoenix-file-manager-${uid}`)

function toggle(file: PhoenixManagedFile) {
  if (file.disabled) return
  const selected = !selectedSet.value.has(file.id)
  const next = props.multiple
    ? selected
      ? [...props.selectedIds, file.id]
      : props.selectedIds.filter((id) => id !== file.id)
    : selected
      ? [file.id]
      : []
  emit('update:selectedIds', next)
  emit('select', file, selected)
}

function formatSize(bytes?: number) {
  if (bytes === undefined || bytes < 0 || !Number.isFinite(bytes)) return '大小未知'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(bytes < 10 * 1024 ** 2 ? 1 : 0)} MB`
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`
}
</script>

<template>
  <section class="px-file-manager" :aria-label="ariaLabel" :aria-busy="loading">
    <div v-if="loading" class="px-advanced-state" role="status">{{ loadingText }}</div>
    <div v-else-if="files.length === 0" class="px-advanced-state" role="status">{{ emptyText }}</div>
    <ul v-else class="px-file-manager__list">
      <li v-for="file in files" :key="file.id" class="px-file-manager__item" :class="{ 'is-selected': selectedSet.has(file.id), 'is-disabled': file.disabled }">
        <label class="px-file-manager__select">
          <input
            :type="multiple ? 'checkbox' : 'radio'"
            :name="multiple ? undefined : selectionName"
            :checked="selectedSet.has(file.id)"
            :disabled="file.disabled"
            :aria-label="`选择文件 ${file.name}`"
            @change="toggle(file)"
          />
        </label>
        <div class="px-file-manager__info">
          <strong :title="file.name">{{ file.name }}</strong>
          <div class="px-file-manager__meta">
            <span>{{ formatSize(file.size) }}</span>
            <span v-if="file.type">{{ file.type }}</span>
            <time v-if="file.updatedAt">{{ file.updatedAt }}</time>
          </div>
        </div>
        <div v-if="!readonly" class="px-file-manager__actions">
          <button
            v-if="file.downloadable !== false"
            type="button"
            :disabled="file.disabled"
            :aria-label="`下载文件 ${file.name}`"
            @click="emit('download', file)"
          >
            下载
          </button>
          <button
            v-if="file.deletable !== false"
            type="button"
            class="is-danger"
            :disabled="file.disabled"
            :aria-label="`删除文件 ${file.name}`"
            @click="emit('delete', file)"
          >
            删除
          </button>
        </div>
      </li>
    </ul>
  </section>
</template>
