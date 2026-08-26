<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: string
  id?: string
  name?: string
  label?: string
  disabled?: boolean
  readonly?: boolean
}>(), {
  modelValue: '#635bff', id: undefined, name: undefined, label: '选择颜色', disabled: false, readonly: false,
})
const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [value: string] }>()
const uid = useId()
const fieldName = computed(() => props.name || `phoenix-color-picker-${uid}`)
const normalizedValue = computed(() => /^#[0-9a-f]{6}$/i.test(props.modelValue) ? props.modelValue.toLowerCase() : '#635bff')
function read(event: Event, commit = false) {
  if (props.readonly) return
  const value = (event.target as HTMLInputElement).value.toLowerCase()
  if (!/^#[0-9a-f]{6}$/.test(value)) return
  emit('update:modelValue', value)
  if (commit) emit('change', value)
}
</script>

<template>
  <div class="px-color-picker" :class="{ 'is-disabled': disabled || readonly }">
    <input
      :id="id" type="color" :name="fieldName" :value="normalizedValue" :aria-label="label"
      :disabled="disabled || readonly" @input="read($event)" @change="read($event, true)"
    >
    <output :for="id">{{ normalizedValue }}</output>
  </div>
</template>
