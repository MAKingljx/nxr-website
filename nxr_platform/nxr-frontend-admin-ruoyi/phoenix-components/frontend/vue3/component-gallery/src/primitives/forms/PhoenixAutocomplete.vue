<script setup lang="ts">
import { computed, useId } from 'vue'

export interface PhoenixAutocompleteOption { label: string; value: string; disabled?: boolean }
const props = withDefaults(defineProps<{
  modelValue?: string
  options?: PhoenixAutocompleteOption[]
  id?: string
  name?: string
  label?: string
  placeholder?: string
  autocomplete?: string
  disabled?: boolean
  readonly?: boolean
  required?: boolean
  invalid?: boolean
  maxLength?: number
}>(), {
  modelValue: '', options: () => [], id: undefined, name: undefined, label: '自动补全', placeholder: '请输入关键词',
  autocomplete: 'off', disabled: false, readonly: false, required: false, invalid: false, maxLength: undefined,
})
const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
  select: [option: PhoenixAutocompleteOption]
}>()
const uid = useId()
const inputId = computed(() => props.id || `phoenix-autocomplete-${uid}`)
const fieldName = computed(() => props.name || `phoenix-autocomplete-${uid}`)
const listId = computed(() => `${inputId.value}-list`)
const usableOptions = computed(() => props.options.filter((option) => !option.disabled))
function read(event: Event, commit = false) {
  const raw = (event.target as HTMLInputElement).value
  const value = props.maxLength == null ? raw : raw.slice(0, Math.max(0, Math.trunc(props.maxLength)))
  emit('update:modelValue', value)
  if (!commit) return
  emit('change', value)
  const option = usableOptions.value.find((item) => item.value === value || item.label === value)
  if (option) emit('select', option)
}
</script>

<template>
  <div class="px-autocomplete" :class="{ 'is-disabled': disabled, 'is-invalid': invalid }">
    <input
      :id="inputId" type="text" :name="fieldName" :value="modelValue" :list="listId"
      :placeholder="placeholder" :aria-label="label" :aria-invalid="invalid || undefined"
      :autocomplete="autocomplete" :disabled="disabled" :readonly="readonly" :required="required"
      :maxlength="maxLength" @input="read($event)" @change="read($event, true)"
    >
    <datalist :id="listId">
      <option v-for="option in usableOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
    </datalist>
  </div>
</template>
