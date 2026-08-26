<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: string
  id?: string
  name?: string
  label?: string
  length?: number
  disabled?: boolean
  readonly?: boolean
  required?: boolean
  digitsOnly?: boolean
  autocomplete?: string
}>(), {
  modelValue: '', id: undefined, name: undefined, label: '验证码', length: 6, disabled: false,
  readonly: false, required: false, digitsOnly: true, autocomplete: 'one-time-code',
})
const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [value: string]; complete: [value: string] }>()
const uid = useId()
const inputs = ref<HTMLInputElement[]>([])
const safeLength = computed(() => Math.max(1, Math.min(12, Math.trunc(Number(props.length) || 6))))
const fieldName = computed(() => props.name || `phoenix-otp-${uid}`)
const characters = computed(() => props.modelValue.slice(0, safeLength.value).split(''))
function accepted(value: string) {
  return (props.digitsOnly ? value.replace(/\D/g, '') : value).slice(0, safeLength.value)
}
function publish(value: string, commit = false) {
  const next = accepted(value)
  emit('update:modelValue', next)
  if (commit) emit('change', next)
  if (next.length === safeLength.value) emit('complete', next)
}
async function onInput(index: number, event: Event) {
  const incoming = accepted((event.target as HTMLInputElement).value)
  const current = characters.value
  current[index] = incoming.slice(-1)
  publish(current.join(''))
  if (incoming && index < safeLength.value - 1) await nextTick(() => inputs.value[index + 1]?.focus())
}
function onKeydown(index: number, event: KeyboardEvent) {
  if (event.key === 'ArrowLeft' && index > 0) { event.preventDefault(); inputs.value[index - 1]?.focus() }
  if (event.key === 'ArrowRight' && index < safeLength.value - 1) { event.preventDefault(); inputs.value[index + 1]?.focus() }
  if (event.key === 'Backspace' && !characters.value[index] && index > 0) { event.preventDefault(); inputs.value[index - 1]?.focus() }
  if (event.key === 'Enter') publish(props.modelValue, true)
}
</script>

<template>
  <fieldset class="px-otp" :disabled="disabled">
    <legend class="px-sr-only">{{ label }}</legend>
    <input
      v-for="index in safeLength" :id="id ? `${id}-${index}` : undefined" :key="index"
      :ref="(element) => { if (element) inputs[index - 1] = element as HTMLInputElement }"
      :name="`${fieldName}-${index}`" :value="characters[index - 1] || ''" type="text" maxlength="1"
      :inputmode="digitsOnly ? 'numeric' : 'text'" :pattern="digitsOnly ? '[0-9]*' : undefined"
      :aria-label="`${label}第 ${index} 位`" :autocomplete="index === 1 ? autocomplete : 'off'"
      :disabled="disabled" :readonly="readonly" :required="required"
      @input="onInput(index - 1, $event)" @change="publish(modelValue, true)" @keydown="onKeydown(index - 1, $event)"
    >
  </fieldset>
</template>
