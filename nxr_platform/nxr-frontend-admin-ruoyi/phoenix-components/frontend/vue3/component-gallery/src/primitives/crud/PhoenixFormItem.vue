<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(
  defineProps<{
    label: string
    htmlFor?: string
    required?: boolean
    error?: string
    help?: string
    labelWidth?: string
  }>(),
  {
    htmlFor: undefined,
    required: false,
    error: '',
    help: '',
    labelWidth: undefined,
  },
)

const uid = useId()
const labelId = `px-form-label-${uid}`
const messageId = `px-form-message-${uid}`
const message = computed(() => props.error || props.help)
</script>

<template>
  <div
    class="px-form-item"
    :class="{ 'is-required': required, 'is-invalid': Boolean(error) }"
    role="group"
    :aria-labelledby="labelId"
    :aria-describedby="message ? messageId : undefined"
    :aria-invalid="error ? 'true' : undefined"
  >
    <label :id="labelId" class="px-form-item__label" :for="htmlFor" :style="labelWidth ? { width: labelWidth } : undefined">
      <span v-if="required" class="px-form-item__required" aria-hidden="true">*</span>{{ label }}
    </label>
    <div class="px-form-item__content">
      <slot :described-by="message ? messageId : undefined" :invalid="Boolean(error)" />
      <p v-if="message" :id="messageId" class="px-form-item__message" :class="{ 'is-error': error }" :role="error ? 'alert' : undefined">
        {{ message }}
      </p>
    </div>
  </div>
</template>
