<script setup lang="ts">
import { computed } from 'vue'
import { productCatalog } from './catalog'
import { toSafeProductUrl } from './safety'
import type {
  CatalogItem,
  ProductStageFilter,
  ProductTypeFilter,
} from './types'

const props = withDefaults(defineProps<{
  items?: readonly CatalogItem[]
  title?: string
  subtitle?: string
  query?: string
  type?: ProductTypeFilter
  stage?: ProductStageFilter
  selectedId?: string
}>(), {
  items: () => productCatalog,
  title: 'Phoenix 成品展厅',
  subtitle: '汇总已在当前仓库留下可验证信息的系统与软件，每个成品都标注当前阶段和源码位置。',
  query: '',
  type: '',
  stage: '',
  selectedId: '',
})

const emit = defineEmits<{
  'update:query': [value: string]
  'update:type': [value: ProductTypeFilter]
  'update:stage': [value: ProductStageFilter]
  'update:selectedId': [value: string]
  select: [item: CatalogItem]
  open: [item: CatalogItem, url: string]
  clear: []
}>()

const availableTypes = computed(() => [...new Set(props.items.map((item) => item.type))])
const availableStages = computed(() => [...new Set(props.items.map((item) => item.stage))])
const normalizedQuery = computed(() => props.query.trim().toLocaleLowerCase('zh-CN'))

const filteredItems = computed(() => props.items.filter((item) => {
  if (props.type && item.type !== props.type) return false
  if (props.stage && item.stage !== props.stage) return false
  if (!normalizedQuery.value) return true

  const searchable = [
    item.name,
    item.type,
    item.stage,
    item.version,
    item.summary,
    item.sourcePath,
    ...item.techStack,
    ...item.capabilities,
  ].join(' ').toLocaleLowerCase('zh-CN')

  return searchable.includes(normalizedQuery.value)
}))

const trialCount = computed(() => props.items.filter((item) => item.stage === '试用').length)
const hasFilters = computed(() => Boolean(props.query || props.type || props.stage))

function readInputValue(event: Event): string {
  return (event.target as HTMLInputElement | HTMLSelectElement).value
}

function formatUpdatedAt(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  return match ? `${match[1]}年${match[2]}月${match[3]}日` : value
}

function selectProduct(item: CatalogItem) {
  emit('update:selectedId', item.id)
  emit('select', item)
}

function clearFilters() {
  emit('update:query', '')
  emit('update:type', '')
  emit('update:stage', '')
  emit('clear')
}
</script>

<template>
  <div class="px-products" aria-labelledby="px-products-title">
    <header class="px-products__hero">
      <div class="px-products__hero-copy">
        <span class="px-products__eyebrow">成品系统 · 软件目录</span>
        <h1 id="px-products-title">{{ title }}</h1>
        <p>{{ subtitle }}</p>
      </div>
      <dl class="px-products__stats" aria-label="成品概览">
        <div><dt>已收录</dt><dd>{{ items.length }}</dd></div>
        <div><dt>试用中</dt><dd>{{ trialCount }}</dd></div>
        <div><dt>当前结果</dt><dd>{{ filteredItems.length }}</dd></div>
      </dl>
    </header>

    <section class="px-products__workspace" aria-label="成品检索与列表">
      <form class="px-products__filters" role="search" @submit.prevent>
        <label class="px-products__search">
          <span>搜索成品</span>
          <span class="px-products__search-field">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m21 21-4.35-4.35m2.35-5.65a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z" /></svg>
            <input :value="query" type="search" placeholder="搜索名称、技术栈或能力" @input="emit('update:query', readInputValue($event))">
          </span>
        </label>

        <label>
          <span>成品类型</span>
          <select :value="type" @change="emit('update:type', readInputValue($event) as ProductTypeFilter)">
            <option value="">全部类型</option>
            <option v-for="option in availableTypes" :key="option" :value="option">{{ option }}</option>
          </select>
        </label>

        <label>
          <span>当前阶段</span>
          <select :value="stage" @change="emit('update:stage', readInputValue($event) as ProductStageFilter)">
            <option value="">全部阶段</option>
            <option v-for="option in availableStages" :key="option" :value="option">{{ option }}</option>
          </select>
        </label>

        <button type="button" class="px-products__clear" :disabled="!hasFilters" @click="clearFilters">
          清除筛选
        </button>
      </form>

      <div class="px-products__result-heading">
        <div>
          <span>仓库可验证成品</span>
          <strong>{{ filteredItems.length }} 个结果</strong>
        </div>
        <p>“试用”表示当前仓库中的实验性交付，不代表生产就绪。</p>
      </div>

      <div v-if="filteredItems.length" class="px-products__grid" aria-live="polite">
        <article
          v-for="item in filteredItems"
          :key="item.id"
          class="px-products__card"
          :class="{ 'is-selected': selectedId === item.id }"
          :data-product-id="item.id"
        >
          <div class="px-products__card-topline">
            <span class="px-products__type">{{ item.type }}</span>
            <span class="px-products__stage">{{ item.stage }}</span>
          </div>

          <div class="px-products__identity">
            <span class="px-products__monogram" aria-hidden="true">{{ item.name.slice(0, 1) }}</span>
            <div>
              <h2>{{ item.name }}</h2>
              <span>v{{ item.version }}</span>
            </div>
          </div>

          <p class="px-products__summary">{{ item.summary }}</p>

          <section class="px-products__meta" aria-label="技术栈">
            <h3>技术栈</h3>
            <ul><li v-for="technology in item.techStack" :key="technology">{{ technology }}</li></ul>
          </section>

          <section class="px-products__meta" aria-label="主要能力">
            <h3>主要能力</h3>
            <ul class="px-products__capabilities"><li v-for="capability in item.capabilities" :key="capability">{{ capability }}</li></ul>
          </section>

          <dl class="px-products__source">
            <div><dt>源码位置</dt><dd><code>{{ item.sourcePath }}</code></dd></div>
            <div><dt>信息更新</dt><dd>{{ formatUpdatedAt(item.updatedAt) }}</dd></div>
          </dl>

          <footer class="px-products__actions">
            <button type="button" @click="selectProduct(item)">
              {{ selectedId === item.id ? '已选中' : '查看成品' }}
            </button>
            <a
              v-if="toSafeProductUrl(item.url)"
              :href="toSafeProductUrl(item.url) ?? undefined"
              target="_blank"
              rel="noopener noreferrer"
              @click="emit('open', item, toSafeProductUrl(item.url) as string)"
            >打开安全地址</a>
          </footer>
        </article>
      </div>

      <div v-else class="px-products__empty" role="status">
        <span aria-hidden="true">/⁄</span>
        <strong>没有找到匹配的成品</strong>
        <p>请调整关键词或筛选条件，也可清除条件查看全部内容。</p>
        <button v-if="hasFilters" type="button" @click="clearFilters">查看全部成品</button>
      </div>
    </section>
  </div>
</template>

<style src="../product-showcase.css"></style>
