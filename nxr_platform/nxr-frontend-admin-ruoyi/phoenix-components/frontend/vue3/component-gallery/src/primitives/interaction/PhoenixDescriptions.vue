<script setup lang="ts">
export interface PhoenixDescriptionItem {
  label: string
  value?: string | number
  span?: number
}

withDefaults(
  defineProps<{
    items: PhoenixDescriptionItem[]
    title?: string
    columns?: 1 | 2 | 3 | 4
    bordered?: boolean
    direction?: 'horizontal' | 'vertical'
    emptyText?: string
  }>(),
  {
    title: '详细信息',
    columns: 3,
    bordered: false,
    direction: 'horizontal',
    emptyText: '暂无',
  },
)
</script>

<template>
  <section class="px-descriptions" :class="[{ 'is-bordered': bordered }, `px-descriptions--${direction}`]" :aria-label="title">
    <h3>{{ title }}</h3>
    <dl :style="{ '--px-descriptions-columns': columns }">
      <div v-for="(item, index) in items" :key="`${item.label}-${index}`" :style="{ '--px-description-span': Math.min(columns, Math.max(1, item.span ?? 1)) }">
        <dt>{{ item.label }}</dt>
        <dd><slot :name="`item-${index}`" :item="item">{{ item.value ?? emptyText }}</slot></dd>
      </div>
    </dl>
  </section>
</template>
