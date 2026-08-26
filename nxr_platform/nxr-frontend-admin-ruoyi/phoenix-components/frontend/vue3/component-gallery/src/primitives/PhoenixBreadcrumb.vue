<script setup lang="ts">
export interface PhoenixBreadcrumbItem {
  label: string
  href?: string
  disabled?: boolean
}

withDefaults(
  defineProps<{
    items: PhoenixBreadcrumbItem[]
    separator?: string
    ariaLabel?: string
  }>(),
  {
    separator: '›',
    ariaLabel: '面包屑导航',
  },
)

const emit = defineEmits<{
  select: [item: PhoenixBreadcrumbItem, index: number]
}>()

function select(event: MouseEvent, item: PhoenixBreadcrumbItem, index: number, isCurrent: boolean) {
  if (item.disabled || isCurrent) {
    event.preventDefault()
    return
  }
  emit('select', item, index)
}
</script>

<template>
  <nav class="px-breadcrumb" :aria-label="ariaLabel">
    <ol>
      <li v-for="(item, index) in items" :key="`${item.label}-${index}`">
        <span v-if="index > 0" class="px-breadcrumb__separator" aria-hidden="true">{{ separator }}</span>
        <a
          v-if="item.href && index !== items.length - 1"
          :href="item.href"
          :aria-disabled="item.disabled || undefined"
          :tabindex="item.disabled ? -1 : undefined"
          @click="select($event, item, index, false)"
        >{{ item.label }}</a>
        <span v-else :aria-current="index === items.length - 1 ? 'page' : undefined">{{ item.label }}</span>
      </li>
    </ol>
  </nav>
</template>
