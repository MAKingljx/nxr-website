<script setup lang="ts">
import { computed, ref, useId } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: number | null
  id?: string
  name?: string
  label?: string
  placeholder?: string
  min?: number
  max?: number
  step?: number
  precision?: number
  disabled?: boolean
  readonly?: boolean
  required?: boolean
  invalid?: boolean
}>(), {
  modelValue: null, id: undefined, name: undefined, label: '数字输入', placeholder: '请输入数字',
  min: undefined, max: undefined, step: 1, precision: undefined, disabled: false, readonly: false,
  required: false, invalid: false,
})
const emit = defineEmits<{
  'update:modelValue': [value: number | null]
  change: [value: number | null]
  focus: [event: FocusEvent]
  blur: [event: FocusEvent]
}>()
const input = ref<HTMLInputElement>()
const uid = useId()
const fieldName = computed(() => props.name || `phoenix-number-input-${uid}`)
const safeStep = computed(() => Number.isFinite(props.step) && props.step > 0 ? props.step : 1)
const safePrecision = computed(() => props.precision == null ? undefined : Math.max(0, Math.min(12, Math.trunc(props.precision))))

function normalize(value: number | null) {
  if (value == null || !Number.isFinite(value)) return null
  const min = Number.isFinite(props.min) ? props.min as number : -Infinity
  const max = Number.isFinite(props.max) ? props.max as number : Infinity
  const bounded = Math.min(Math.max(value, Math.min(min, max)), Math.max(min, max))
  return safePrecision.value == null ? bounded : Number(bounded.toFixed(safePrecision.value))
}
function read(event: Event) {
  const raw = (event.target as HTMLInputElement).value
  return normalize(raw === '' ? null : Number(raw))
}
function update(value: number | null, commit = false) {
  const next = normalize(value)
  emit('update:modelValue', next)
  if (commit) emit('change', next)
}
function adjust(direction: 1 | -1) {
  if (props.disabled || props.readonly) return
  update((props.modelValue ?? 0) + direction * safeStep.value, true)
  input.value?.focus()
}
</script>

<template>
  <div class="px-number-input" :class="{ 'is-disabled': disabled, 'is-invalid': invalid }">
    <button type="button" aria-label="减少" :disabled="disabled || readonly || (min != null && modelValue != null && modelValue <= min)" @click="adjust(-1)">−</button>
    <input
      :id="id" ref="input" type="number" inputmode="decimal" :name="fieldName" :value="modelValue ?? ''"
      :placeholder="placeholder" :aria-label="label" :aria-invalid="invalid || undefined"
      :min="min" :max="max" :step="safeStep" :disabled="disabled" :readonly="readonly" :required="required"
      @input="update(read($event))" @change="update(read($event), true)"
      @focus="emit('focus', $event)" @blur="emit('blur', $event)"
    >
    <button type="button" aria-label="增加" :disabled="disabled || readonly || (max != null && modelValue != null && modelValue >= max)" @click="adjust(1)">+</button>
  </div>
</template>
