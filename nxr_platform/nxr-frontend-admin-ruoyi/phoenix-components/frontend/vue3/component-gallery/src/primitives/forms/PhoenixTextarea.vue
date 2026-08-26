<script setup lang="ts">
import { computed, ref, useId } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: string
  id?: string
  name?: string
  label?: string
  placeholder?: string
  rows?: number
  minLength?: number
  maxLength?: number
  disabled?: boolean
  readonly?: boolean
  required?: boolean
  invalid?: boolean
  describedBy?: string
  showCount?: boolean
}>(), {
  modelValue: '', id: undefined, name: undefined, label: '多行文本', placeholder: '请输入内容', rows: 4,
  minLength: undefined, maxLength: undefined, disabled: false, readonly: false, required: false,
  invalid: false, describedBy: undefined, showCount: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
  focus: [event: FocusEvent]
  blur: [event: FocusEvent]
}>()
const textarea = ref<HTMLTextAreaElement>()
const uid = useId()
const fieldName = computed(() => props.name || `phoenix-textarea-${uid}`)
const safeRows = computed(() => Math.max(1, Math.min(30, Math.trunc(Number(props.rows) || 4))))
const safeMaxLength = computed(() => props.maxLength == null ? undefined : Math.max(0, Math.trunc(Number(props.maxLength) || 0)))

function valueOf(event: Event) {
  const value = (event.target as HTMLTextAreaElement).value
  return safeMaxLength.value == null ? value : value.slice(0, safeMaxLength.value)
}
</script>

<template>
  <div class="px-textarea" :class="{ 'is-disabled': disabled, 'is-invalid': invalid }">
    <textarea
      :id="id" ref="textarea" :name="fieldName" :value="modelValue" :rows="safeRows"
      :placeholder="placeholder" :aria-label="label" :aria-describedby="describedBy"
      :aria-invalid="invalid || undefined" :disabled="disabled" :readonly="readonly"
      :required="required" :minlength="minLength" :maxlength="safeMaxLength"
      @input="emit('update:modelValue', valueOf($event))"
      @change="emit('change', valueOf($event))"
      @focus="emit('focus', $event)" @blur="emit('blur', $event)"
    ></textarea>
    <span v-if="showCount && safeMaxLength != null" class="px-textarea__count" aria-live="polite">
      {{ Math.min(modelValue.length, safeMaxLength) }}/{{ safeMaxLength }}
    </span>
  </div>
</template>
