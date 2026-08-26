<script setup lang="ts">
import { computed } from 'vue'

export type PhoenixSelectValue = string | number

export interface PhoenixSelectOption {
  label: string
  value: PhoenixSelectValue
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    modelValue?: PhoenixSelectValue | null
    id?: string
    options: PhoenixSelectOption[]
    placeholder?: string
    ariaLabel?: string
    name?: string
    disabled?: boolean
    required?: boolean
    clearable?: boolean
    loading?: boolean
    invalid?: boolean
    size?: 'small' | 'medium' | 'large'
    describedBy?: string
  }>(),
  {
    modelValue: undefined,
    id: undefined,
    placeholder: '请选择',
    ariaLabel: undefined,
    name: undefined,
    disabled: false,
    required: false,
    clearable: false,
    loading: false,
    invalid: false,
    size: 'medium',
    describedBy: undefined,
  },
)

const resolvedAriaLabel = computed(() => props.ariaLabel || (props.id ? undefined : props.placeholder))

const emit = defineEmits<{
  'update:modelValue': [value: PhoenixSelectValue | undefined]
  change: [value: PhoenixSelectValue | undefined]
  clear: []
  focus: [event: FocusEvent]
  blur: [event: FocusEvent]
}>()

function change(event: Event) {
  const select = event.target as HTMLSelectElement
  const option = select.selectedOptions[0] as (HTMLOptionElement & { _value?: PhoenixSelectValue }) | undefined
  const value = select.value === '' ? undefined : (option?._value ?? option?.value)
  emit('update:modelValue', value)
  emit('change', value)
}

function clear() {
  emit('update:modelValue', undefined)
  emit('change', undefined)
  emit('clear')
}
</script>

<template>
  <div class="px-select" :class="[`px-select--${size}`, { 'is-disabled': disabled, 'is-invalid': invalid }]">
    <select
      :id="id"
      :value="modelValue ?? ''"
      :name="name"
      :aria-label="resolvedAriaLabel"
      :aria-describedby="describedBy"
      :aria-invalid="invalid || undefined"
      :disabled="disabled || loading"
      :required="required"
      @change="change"
      @focus="emit('focus', $event)"
      @blur="emit('blur', $event)"
    >
      <option value="" :disabled="required">{{ loading ? '正在加载' : placeholder }}</option>
      <option v-for="option in options" :key="String(option.value)" :value="option.value" :disabled="option.disabled">
        {{ option.label }}
      </option>
    </select>
    <button
      v-if="clearable && modelValue !== undefined && modelValue !== null && modelValue !== ''"
      type="button"
      class="px-select__clear"
      aria-label="清空选择"
      :disabled="disabled || loading"
      @click="clear"
    >
      ×
    </button>
    <span v-else class="px-select__arrow" aria-hidden="true"></span>
  </div>
</template>
