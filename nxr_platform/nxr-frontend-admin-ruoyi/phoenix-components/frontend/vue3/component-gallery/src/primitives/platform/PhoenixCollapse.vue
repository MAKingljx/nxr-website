<script setup lang="ts">
import { useId } from 'vue'

export type PhoenixCollapseValue = string | number
export interface PhoenixCollapseItem {
  title: string
  value: PhoenixCollapseValue
  content?: string
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  items: PhoenixCollapseItem[]
  modelValue?: PhoenixCollapseValue[]
  accordion?: boolean
  label?: string
}>(), {
  modelValue: () => [],
  accordion: false,
  label: '折叠面板',
})

const emit = defineEmits<{
  'update:modelValue': [values: PhoenixCollapseValue[]]
  change: [values: PhoenixCollapseValue[], item: PhoenixCollapseItem]
}>()

const uid = useId().replaceAll(':', '')

function toggle(item: PhoenixCollapseItem) {
  if (item.disabled) return
  const expanded = props.modelValue.includes(item.value)
  const values = expanded ? props.modelValue.filter((value) => value !== item.value) : props.accordion ? [item.value] : [...props.modelValue, item.value]
  emit('update:modelValue', values)
  emit('change', values, item)
}
</script>

<template>
  <div class="px-collapse" :aria-label="label">
    <section v-for="(item, index) in items" :key="item.value" class="px-collapse__item" :class="{ 'is-open': modelValue.includes(item.value) }">
      <h3>
        <button :id="`px-collapse-${uid}-button-${index}`" type="button" :disabled="item.disabled" :aria-expanded="modelValue.includes(item.value)" :aria-controls="`px-collapse-${uid}-panel-${index}`" @click="toggle(item)">
          <span>{{ item.title }}</span><span class="px-collapse__arrow" aria-hidden="true">›</span>
        </button>
      </h3>
      <div v-if="modelValue.includes(item.value)" :id="`px-collapse-${uid}-panel-${index}`" role="region" :aria-labelledby="`px-collapse-${uid}-button-${index}`" class="px-collapse__panel"><slot :name="`item-${index}`" :item="item">{{ item.content || '暂无内容' }}</slot></div>
    </section>
  </div>
</template>
