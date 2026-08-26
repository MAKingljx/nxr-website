<script setup lang="ts">
const props = withDefaults(defineProps<{
  title?: string
  query?: string
  view?: 'table' | 'grid'
  selectedCount?: number
  loading?: boolean
  empty?: boolean
  canCreate?: boolean
}>(), {
  title: '资源管理',
  query: '',
  view: 'table',
  selectedCount: 0,
  loading: false,
  empty: false,
  canCreate: true,
})

const emit = defineEmits<{
  'update:query': [query: string]
  'update:view': [view: 'table' | 'grid']
  search: [query: string]
  create: []
  clearSelection: []
}>()

function setQuery(value: string) {
  emit('update:query', value)
}

function submitSearch() {
  emit('search', props.query.trim())
}

function onSearchSubmit(event: Event) {
  event.preventDefault()
  submitSearch()
}
</script>

<template>
  <main class="px-page-pattern px-resource-page" :aria-label="title" :aria-busy="loading">
    <header class="px-page-pattern__header">
      <h1><slot name="title">{{ title }}</slot></h1>
      <div class="px-page-pattern__actions">
        <slot name="header-actions">
          <button
            type="button"
            class="px-page-pattern__button px-page-pattern__button--primary"
            :disabled="!canCreate"
            @click="emit('create')"
          >
            新建
          </button>
        </slot>
      </div>
    </header>

    <form class="px-page-pattern__toolbar px-resource-page__toolbar" role="search" @submit="onSearchSubmit">
      <slot name="search" :query="query" :set-query="setQuery" :submit="submitSearch">
        <label class="px-page-pattern__search">
          <span class="px-page-pattern__sr-only">搜索资源</span>
          <input
            :value="query"
            type="search"
            placeholder="搜索资源"
            @input="setQuery(($event.target as HTMLInputElement).value)"
          />
        </label>
        <button type="submit" class="px-page-pattern__button">搜索</button>
      </slot>
      <div class="px-resource-page__filters"><slot name="filters" /></div>
      <div class="px-resource-page__view" role="group" aria-label="展示方式">
        <slot name="view-switch" :view="view">
          <button
            type="button"
            class="px-page-pattern__icon-button"
            :class="{ 'is-active': view === 'table' }"
            :aria-pressed="view === 'table'"
            aria-label="表格视图"
            @click="emit('update:view', 'table')"
          >
            ☷
          </button>
          <button
            type="button"
            class="px-page-pattern__icon-button"
            :class="{ 'is-active': view === 'grid' }"
            :aria-pressed="view === 'grid'"
            aria-label="网格视图"
            @click="emit('update:view', 'grid')"
          >
            ▦
          </button>
        </slot>
      </div>
    </form>

    <section v-if="selectedCount > 0" class="px-resource-page__selection" aria-live="polite">
      <span>已选 {{ selectedCount }} 项</span>
      <slot name="bulk-actions" :selected-count="selectedCount">
        <button type="button" class="px-page-pattern__text-button" @click="emit('clearSelection')">取消选择</button>
      </slot>
    </section>

    <section class="px-page-pattern__surface px-resource-page__content" :class="`is-${view}`">
      <div v-if="loading" class="px-page-pattern__state" role="status"><slot name="loading">加载中</slot></div>
      <div v-else-if="empty" class="px-page-pattern__state"><slot name="empty">暂无资源</slot></div>
      <slot v-else />
    </section>
    <footer v-if="$slots.pagination" class="px-page-pattern__pagination"><slot name="pagination" /></footer>
  </main>
</template>
