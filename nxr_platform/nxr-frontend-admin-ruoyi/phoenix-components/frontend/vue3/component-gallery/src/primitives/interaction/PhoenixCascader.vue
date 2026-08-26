<script setup lang="ts">
import { computed, ref, watch } from 'vue'

export type PhoenixCascaderValue = string | number

export interface PhoenixCascaderOption {
  label: string
  value: PhoenixCascaderValue
  disabled?: boolean
  children?: PhoenixCascaderOption[]
}

const props = withDefaults(
  defineProps<{
    modelValue: PhoenixCascaderValue[]
    options: PhoenixCascaderOption[]
    label?: string
    placeholder?: string
    disabled?: boolean
    clearable?: boolean
  }>(),
  {
    label: '级联选择',
    placeholder: '请选择',
    disabled: false,
    clearable: true,
  },
)

const emit = defineEmits<{
  'update:modelValue': [path: PhoenixCascaderValue[]]
  change: [path: PhoenixCascaderValue[], option: PhoenixCascaderOption | null]
  expand: [path: PhoenixCascaderValue[], option: PhoenixCascaderOption]
  clear: []
}>()

const activePath = ref<PhoenixCascaderValue[]>([...props.modelValue])

watch(
  () => props.modelValue,
  (value) => {
    activePath.value = [...value]
  },
  { deep: true },
)

const columns = computed(() => {
  const result: PhoenixCascaderOption[][] = [props.options]
  let options = props.options
  for (const value of activePath.value) {
    const selected = options.find((option) => option.value === value)
    if (!selected?.children?.length) break
    options = selected.children
    result.push(options)
  }
  return result
})

const selectedLabel = computed(() => {
  const labels: string[] = []
  let options = props.options
  for (const value of props.modelValue) {
    const selected = options.find((option) => option.value === value)
    if (!selected) break
    labels.push(selected.label)
    options = selected.children ?? []
  }
  return labels.join(' / ')
})

function choose(option: PhoenixCascaderOption, level: number) {
  if (props.disabled || option.disabled) return
  const path = [...activePath.value.slice(0, level), option.value]
  activePath.value = path
  if (option.children?.length) emit('expand', path, option)
  else {
    emit('update:modelValue', path)
    emit('change', path, option)
  }
}

function clear() {
  if (props.disabled || !props.clearable || !props.modelValue.length) return
  activePath.value = []
  emit('update:modelValue', [])
  emit('change', [], null)
  emit('clear')
}

function focusSibling(event: KeyboardEvent, delta: number) {
  const current = event.currentTarget as HTMLElement
  const options = Array.from(current.closest('[role="listbox"]')?.querySelectorAll<HTMLElement>('[role="option"]:not([aria-disabled="true"])') ?? [])
  const index = options.indexOf(current)
  options[Math.min(options.length - 1, Math.max(0, index + delta))]?.focus()
}

function onKeydown(event: KeyboardEvent, option: PhoenixCascaderOption, level: number) {
  if (event.key === 'ArrowDown') focusSibling(event, 1)
  else if (event.key === 'ArrowUp') focusSibling(event, -1)
  else if (event.key === 'Home') focusSibling(event, -Number.MAX_SAFE_INTEGER)
  else if (event.key === 'End') focusSibling(event, Number.MAX_SAFE_INTEGER)
  else if (event.key === 'ArrowRight' || event.key === 'Enter' || event.key === ' ') choose(option, level)
  else if (event.key === 'ArrowLeft' && level > 0) {
    const container = (event.currentTarget as HTMLElement).closest('.px-cascader__panels')
    container?.querySelectorAll<HTMLElement>('[role="listbox"]')[level - 1]?.querySelector<HTMLElement>('[aria-selected="true"]')?.focus()
  } else return
  event.preventDefault()
}
</script>

<template>
  <div class="px-cascader" :class="{ 'is-disabled': disabled }">
    <div class="px-cascader__summary">
      <span class="px-cascader__value" :class="{ 'is-placeholder': !selectedLabel }">{{ selectedLabel || placeholder }}</span>
      <button v-if="clearable && modelValue.length" type="button" :disabled="disabled" aria-label="清空级联选择" @click="clear">×</button>
    </div>
    <div class="px-cascader__panels" :aria-label="label">
      <ul v-for="(column, level) in columns" :key="level" role="listbox" :aria-label="`第 ${level + 1} 级选项`">
        <li v-for="option in column" :key="option.value">
          <button
            type="button"
            role="option"
            :class="{ 'is-active': activePath[level] === option.value }"
            :aria-selected="activePath[level] === option.value"
            :aria-disabled="disabled || option.disabled"
            :disabled="disabled || option.disabled"
            @click="choose(option, level)"
            @keydown="onKeydown($event, option, level)"
          >
            <span>{{ option.label }}</span><span v-if="option.children?.length" aria-hidden="true">›</span>
          </button>
        </li>
      </ul>
    </div>
  </div>
</template>
