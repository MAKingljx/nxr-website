<script setup lang="ts">
import { computed } from 'vue'

type PageToken = number | 'ellipsis-start' | 'ellipsis-end'

const props = withDefaults(
  defineProps<{
    modelValue: number
    total: number
    pageSize?: number
    siblingCount?: number
    disabled?: boolean
    showTotal?: boolean
    ariaLabel?: string
    previousLabel?: string
    nextLabel?: string
  }>(),
  {
    pageSize: 10,
    siblingCount: 1,
    disabled: false,
    showTotal: true,
    ariaLabel: '分页导航',
    previousLabel: '上一页',
    nextLabel: '下一页',
  },
)

const emit = defineEmits<{
  'update:modelValue': [page: number]
  change: [page: number]
}>()

const pageCount = computed(() => Math.max(1, Math.ceil(Math.max(0, props.total) / Math.max(1, props.pageSize))))
const currentPage = computed(() => Math.min(pageCount.value, Math.max(1, props.modelValue)))

const pageTokens = computed<PageToken[]>(() => {
  const count = pageCount.value
  const radius = Math.max(0, props.siblingCount)
  if (count <= 5 + radius * 2) return Array.from({ length: count }, (_, index) => index + 1)
  const pages = new Set<number>([1, count])
  for (let page = currentPage.value - radius; page <= currentPage.value + radius; page += 1) {
    if (page > 1 && page < count) pages.add(page)
  }
  const sorted = [...pages].sort((a, b) => a - b)
  const tokens: PageToken[] = []
  sorted.forEach((page, index) => {
    const previous = sorted[index - 1]
    if (previous && page - previous > 1) tokens.push(previous === 1 ? 'ellipsis-start' : 'ellipsis-end')
    tokens.push(page)
  })
  return tokens
})

function goTo(page: number) {
  if (props.disabled) return
  const next = Math.min(pageCount.value, Math.max(1, page))
  if (next === currentPage.value) return
  emit('update:modelValue', next)
  emit('change', next)
}
</script>

<template>
  <nav class="px-pagination" :aria-label="ariaLabel">
    <span v-if="showTotal" class="px-pagination__total">共 {{ total }} 条</span>
    <div class="px-pagination__pages">
      <button
        type="button"
        class="px-pagination__move"
        :disabled="disabled || currentPage === 1"
        :aria-label="previousLabel"
        @click="goTo(currentPage - 1)"
      >
        <span aria-hidden="true">‹</span><span class="px-pagination__move-text">{{ previousLabel }}</span>
      </button>
      <template v-for="token in pageTokens" :key="token">
        <span v-if="typeof token !== 'number'" class="px-pagination__ellipsis" aria-hidden="true">…</span>
        <button
          v-else
          type="button"
          class="px-pagination__page"
          :class="{ 'is-active': token === currentPage }"
          :disabled="disabled"
          :aria-label="`第 ${token} 页`"
          :aria-current="token === currentPage ? 'page' : undefined"
          @click="goTo(token)"
        >
          {{ token }}
        </button>
      </template>
      <button
        type="button"
        class="px-pagination__move"
        :disabled="disabled || currentPage === pageCount"
        :aria-label="nextLabel"
        @click="goTo(currentPage + 1)"
      >
        <span class="px-pagination__move-text">{{ nextLabel }}</span><span aria-hidden="true">›</span>
      </button>
    </div>
  </nav>
</template>
