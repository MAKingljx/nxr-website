<script setup lang="ts">
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    id?: string
    min?: string
    max?: string
    name?: string
    ariaLabel?: string
    disabled?: boolean
    readonly?: boolean
    required?: boolean
    clearable?: boolean
    invalid?: boolean
    size?: 'small' | 'medium' | 'large'
    describedBy?: string
  }>(),
  {
    modelValue: '',
    id: undefined,
    min: undefined,
    max: undefined,
    name: undefined,
    ariaLabel: undefined,
    disabled: false,
    readonly: false,
    required: false,
    clearable: false,
    invalid: false,
    size: 'medium',
    describedBy: undefined,
  },
)

const resolvedAriaLabel = computed(() => props.ariaLabel || (props.id ? undefined : '选择日期'))

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
  clear: []
  focus: [event: FocusEvent]
  blur: [event: FocusEvent]
}>()

const input = ref<HTMLInputElement>()

function update(event: Event) {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}

function change(event: Event) {
  emit('change', (event.target as HTMLInputElement).value)
}

function clear() {
  emit('update:modelValue', '')
  emit('change', '')
  emit('clear')
  input.value?.focus()
}

defineExpose({ focus: () => input.value?.focus() })
</script>

<template>
  <div class="px-date-picker" :class="[`px-date-picker--${size}`, { 'is-disabled': disabled, 'is-invalid': invalid }]">
    <input
      :id="id"
      ref="input"
      type="date"
      :value="modelValue"
      :min="min"
      :max="max"
      :name="name"
      :aria-label="resolvedAriaLabel"
      :aria-describedby="describedBy"
      :aria-invalid="invalid || undefined"
      :disabled="disabled"
      :readonly="readonly"
      :required="required"
      @input="update"
      @change="change"
      @focus="emit('focus', $event)"
      @blur="emit('blur', $event)"
    />
    <button
      v-if="clearable && modelValue"
      type="button"
      class="px-date-picker__clear"
      aria-label="清空日期"
      :disabled="disabled || readonly"
      @click="clear"
    >
      ×
    </button>
  </div>
</template>
