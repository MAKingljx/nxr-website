<script setup lang="ts">
import { computed, ref } from 'vue'

export type PhoenixInputValue = string | number

const props = withDefaults(
  defineProps<{
    modelValue?: PhoenixInputValue
    id?: string
    type?: 'text' | 'password' | 'email' | 'tel' | 'url' | 'search' | 'number'
    placeholder?: string
    ariaLabel?: string
    name?: string
    autocomplete?: string
    disabled?: boolean
    readonly?: boolean
    required?: boolean
    clearable?: boolean
    invalid?: boolean
    maxLength?: number
    showCount?: boolean
    size?: 'small' | 'medium' | 'large'
    describedBy?: string
  }>(),
  {
    modelValue: '',
    id: undefined,
    type: 'text',
    placeholder: '请输入内容',
    ariaLabel: undefined,
    name: undefined,
    autocomplete: undefined,
    disabled: false,
    readonly: false,
    required: false,
    clearable: false,
    invalid: false,
    maxLength: undefined,
    showCount: false,
    size: 'medium',
    describedBy: undefined,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: PhoenixInputValue]
  change: [value: PhoenixInputValue]
  clear: []
  focus: [event: FocusEvent]
  blur: [event: FocusEvent]
}>()

const input = ref<HTMLInputElement>()
const resolvedAriaLabel = computed(() => props.ariaLabel || (props.id ? undefined : props.placeholder))

function readValue(event: Event): PhoenixInputValue {
  const value = (event.target as HTMLInputElement).value
  return props.type === 'number' && value !== '' ? Number(value) : value
}

function onInput(event: Event) {
  emit('update:modelValue', readValue(event))
}

function onChange(event: Event) {
  emit('change', readValue(event))
}

function clear() {
  if (props.disabled || props.readonly) return
  emit('update:modelValue', '')
  emit('change', '')
  emit('clear')
  input.value?.focus()
}

defineExpose({ focus: () => input.value?.focus() })
</script>

<template>
  <div
    class="px-input"
    :class="[`px-input--${size}`, { 'is-disabled': disabled, 'is-invalid': invalid, 'has-prefix': $slots.prefix, 'has-suffix': $slots.suffix }]"
  >
    <span v-if="$slots.prefix" class="px-input__prefix" aria-hidden="true"><slot name="prefix" /></span>
    <input
      :id="id"
      ref="input"
      :type="type"
      :value="modelValue"
      :name="name"
      :placeholder="placeholder"
      :aria-label="resolvedAriaLabel"
      :aria-describedby="describedBy"
      :aria-invalid="invalid || undefined"
      :autocomplete="autocomplete"
      :disabled="disabled"
      :readonly="readonly"
      :required="required"
      :maxlength="maxLength"
      @input="onInput"
      @change="onChange"
      @focus="emit('focus', $event)"
      @blur="emit('blur', $event)"
    />
    <button
      v-if="clearable && String(modelValue).length > 0"
      type="button"
      class="px-input__clear"
      aria-label="清空输入内容"
      :disabled="disabled || readonly"
      @click="clear"
    >
      ×
    </button>
    <span v-if="$slots.suffix" class="px-input__suffix" aria-hidden="true"><slot name="suffix" /></span>
    <span v-if="showCount && maxLength" class="px-input__count" aria-live="polite">
      {{ String(modelValue).length }}/{{ maxLength }}
    </span>
  </div>
</template>
