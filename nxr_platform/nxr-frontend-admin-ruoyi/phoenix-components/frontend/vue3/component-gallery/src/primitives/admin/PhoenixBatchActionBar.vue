<script setup lang="ts">
import { computed } from 'vue'

export interface PhoenixBatchAction { key: string; label: string; danger?: boolean; disabled?: boolean }
const props = withDefaults(defineProps<{
  selectedCount?: number
  actions?: PhoenixBatchAction[]
  disabled?: boolean
  clearText?: string
}>(), { selectedCount: 0, actions: () => [], disabled: false, clearText: '取消选择' })
const emit = defineEmits<{ action: [action: PhoenixBatchAction]; clear: [] }>()
const count = computed(() => Number.isFinite(props.selectedCount) ? Math.max(0, Math.floor(props.selectedCount)) : 0)
function choose(action: PhoenixBatchAction) {
  if (!props.disabled && !action.disabled && count.value > 0) emit('action', action)
}
</script>

<template>
  <aside class="px-batch-action-bar" aria-label="批量操作" :aria-disabled="disabled || count === 0">
    <strong aria-live="polite">已选择 {{ count }} 项</strong>
    <div>
      <button v-for="action in actions" :key="action.key" type="button" :class="{ 'is-danger': action.danger }" :disabled="disabled || action.disabled || count === 0" @click="choose(action)">{{ action.label }}</button>
      <button type="button" class="is-quiet" :disabled="disabled || count === 0" @click="emit('clear')">{{ clearText }}</button>
    </div>
  </aside>
</template>
