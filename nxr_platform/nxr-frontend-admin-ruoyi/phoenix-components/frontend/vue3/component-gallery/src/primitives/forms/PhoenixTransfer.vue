<script setup lang="ts">
import { computed, ref, useId } from 'vue'

export type PhoenixTransferKey = string | number
export interface PhoenixTransferOption { label: string; value: PhoenixTransferKey; disabled?: boolean }
const props = withDefaults(defineProps<{
  modelValue?: PhoenixTransferKey[]
  options?: PhoenixTransferOption[]
  name?: string
  label?: string
  sourceTitle?: string
  targetTitle?: string
  disabled?: boolean
}>(), {
  modelValue: () => [], options: () => [], name: undefined, label: '穿梭选择', sourceTitle: '待选', targetTitle: '已选', disabled: false,
})
const emit = defineEmits<{
  'update:modelValue': [value: PhoenixTransferKey[]]
  change: [value: PhoenixTransferKey[], direction: 'right' | 'left', moved: PhoenixTransferKey[]]
}>()
const uid = useId()
const fieldName = computed(() => props.name || `phoenix-transfer-${uid}`)
const checkedSource = ref<PhoenixTransferKey[]>([])
const checkedTarget = ref<PhoenixTransferKey[]>([])
const enabled = computed(() => new Set(props.options.filter((item) => !item.disabled).map((item) => item.value)))
const sourceOptions = computed(() => props.options.filter((item) => !props.modelValue.includes(item.value)))
const targetOptions = computed(() => props.options.filter((item) => props.modelValue.includes(item.value)))

function toggle(bucket: 'source' | 'target', value: PhoenixTransferKey, checked: boolean) {
  const target = bucket === 'source' ? checkedSource : checkedTarget
  target.value = checked ? [...target.value, value] : target.value.filter((item) => item !== value)
}
function move(direction: 'right' | 'left') {
  if (props.disabled) return
  const moving = (direction === 'right' ? checkedSource.value : checkedTarget.value).filter((value) => enabled.value.has(value))
  if (!moving.length) return
  const next = direction === 'right'
    ? [...props.modelValue, ...moving.filter((value) => !props.modelValue.includes(value))]
    : props.modelValue.filter((value) => !moving.includes(value))
  checkedSource.value = []
  checkedTarget.value = []
  emit('update:modelValue', next)
  emit('change', next, direction, moving)
}
</script>

<template>
  <fieldset class="px-transfer" :disabled="disabled">
    <legend class="px-sr-only">{{ label }}</legend>
    <section :aria-label="sourceTitle">
      <strong>{{ sourceTitle }}</strong>
      <label v-for="option in sourceOptions" :key="String(option.value)" :class="{ 'is-disabled': option.disabled }">
        <input
          type="checkbox" :name="`${fieldName}-source`" :value="String(option.value)"
          :checked="checkedSource.includes(option.value)" :disabled="option.disabled"
          @change="toggle('source', option.value, ($event.target as HTMLInputElement).checked)"
        >
        <span>{{ option.label }}</span>
      </label>
    </section>
    <div class="px-transfer__actions">
      <button type="button" aria-label="移至已选" :disabled="disabled || checkedSource.length === 0" @click="move('right')">›</button>
      <button type="button" aria-label="移回待选" :disabled="disabled || checkedTarget.length === 0" @click="move('left')">‹</button>
    </div>
    <section :aria-label="targetTitle">
      <strong>{{ targetTitle }}</strong>
      <label v-for="option in targetOptions" :key="String(option.value)" :class="{ 'is-disabled': option.disabled }">
        <input
          type="checkbox" :name="`${fieldName}-target`" :value="String(option.value)"
          :checked="checkedTarget.includes(option.value)" :disabled="option.disabled"
          @change="toggle('target', option.value, ($event.target as HTMLInputElement).checked)"
        >
        <span>{{ option.label }}</span>
      </label>
    </section>
  </fieldset>
</template>
