<script setup lang="ts">
export type PhoenixStepStatus = 'wait' | 'process' | 'finish' | 'error'

export interface PhoenixStepItem {
  title: string
  description?: string
  status?: PhoenixStepStatus
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    modelValue: number
    items: PhoenixStepItem[]
    direction?: 'horizontal' | 'vertical'
    clickable?: boolean
    label?: string
  }>(),
  {
    direction: 'horizontal',
    clickable: false,
    label: '操作步骤',
  },
)

const emit = defineEmits<{
  'update:modelValue': [index: number]
  change: [index: number, item: PhoenixStepItem]
}>()

function statusAt(item: PhoenixStepItem, index: number): PhoenixStepStatus {
  if (item.status) return item.status
  if (index < props.modelValue) return 'finish'
  if (index === props.modelValue) return 'process'
  return 'wait'
}

function select(item: PhoenixStepItem, index: number) {
  if (!props.clickable || item.disabled || index === props.modelValue) return
  emit('update:modelValue', index)
  emit('change', index, item)
}
</script>

<template>
  <ol class="px-steps" :class="`px-steps--${direction}`" :aria-label="label">
    <li
      v-for="(item, index) in items"
      :key="`${item.title}-${index}`"
      class="px-step"
      :class="[`px-step--${statusAt(item, index)}`, { 'is-disabled': item.disabled }]"
      :aria-current="index === modelValue ? 'step' : undefined"
    >
      <button v-if="clickable" type="button" :disabled="item.disabled" @click="select(item, index)">
        <span class="px-step__marker" aria-hidden="true">{{ statusAt(item, index) === 'finish' ? '✓' : index + 1 }}</span>
        <span class="px-step__content"><strong>{{ item.title }}</strong><span v-if="item.description">{{ item.description }}</span></span>
      </button>
      <div v-else class="px-step__static">
        <span class="px-step__marker" aria-hidden="true">{{ statusAt(item, index) === 'finish' ? '✓' : index + 1 }}</span>
        <span class="px-step__content"><strong>{{ item.title }}</strong><span v-if="item.description">{{ item.description }}</span></span>
      </div>
    </li>
  </ol>
</template>
