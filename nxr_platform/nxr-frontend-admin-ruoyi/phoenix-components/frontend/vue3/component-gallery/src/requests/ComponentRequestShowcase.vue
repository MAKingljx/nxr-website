<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { createAnonymousRecordClient } from '../feedback'
import type { EditableRequestRecord, FetchClient } from '../feedback'
import requestRegistry from '../data/component-requests.json'
import RequestSubmissionPanel from './RequestSubmissionPanel.vue'
import type {
  ComponentRequestItem,
  ComponentRequestPriority,
  ComponentRequestRegistry,
  ComponentRequestStatus,
} from './types'

const props = withDefaults(defineProps<{
  items?: readonly ComponentRequestItem[]
  fetchClient?: FetchClient
}>(), {
  items: () => (requestRegistry as ComponentRequestRegistry).requests,
})

const query = ref('')
const status = ref<ComponentRequestStatus | ''>('')
const priority = ref<ComponentRequestPriority | ''>('')
const publicItems = ref<EditableRequestRecord[]>([])
const publicLoading = ref(true)
const publicError = ref('')

const statusLabels: Record<ComponentRequestStatus, string> = {
  proposed: '待评估',
  accepted: '已采纳',
  in_progress: '开发中',
  done: '已完成',
  rejected: '不采纳',
}

const filteredItems = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase('zh-CN')
  return props.items.filter((item) => {
    if (status.value && item.status !== status.value) return false
    if (priority.value && item.priority !== priority.value) return false
    if (!needle) return true
    return [
      item.id,
      item.title,
      item.scenario,
      item.stack,
      ...item.capabilities,
      ...item.acceptanceCriteria,
    ].join(' ').toLocaleLowerCase('zh-CN').includes(needle)
  })
})

const openCount = computed(() => props.items.filter((item) => (
  item.status === 'proposed' || item.status === 'accepted' || item.status === 'in_progress'
)).length)

function resetFilters() {
  query.value = ''
  status.value = ''
  priority.value = ''
}

async function loadPublicRequests() {
  publicLoading.value = true
  publicError.value = ''
  try {
    const response = await createAnonymousRecordClient(props.fetchClient).listRequests()
    publicItems.value = Array.isArray(response.records) ? response.records : []
  } catch {
    publicError.value = '暂时无法读取最新需求，下方静态需求清单仍可浏览。'
  } finally {
    publicLoading.value = false
  }
}

onMounted(loadPublicRequests)
</script>

<template>
  <div class="px-requests" aria-labelledby="px-requests-title">
    <header class="px-requests__hero">
      <div>
        <h1 id="px-requests-title">组件需求清单</h1>
      </div>
      <dl aria-label="需求统计">
        <div><dt>全部需求</dt><dd>{{ items.length }}</dd></div>
        <div><dt>待推进</dt><dd>{{ openCount }}</dd></div>
        <div><dt>当前结果</dt><dd>{{ filteredItems.length }}</dd></div>
      </dl>
    </header>

    <form class="px-requests__filters" role="search" @submit.prevent>
      <label>
        <span>搜索需求</span>
        <input v-model="query" type="search" placeholder="名称、场景、技术栈或能力">
      </label>
      <label>
        <span>开发状态</span>
        <select v-model="status">
          <option value="">全部状态</option>
          <option value="proposed">待评估</option>
          <option value="accepted">已采纳</option>
          <option value="in_progress">开发中</option>
          <option value="done">已完成</option>
          <option value="rejected">不采纳</option>
        </select>
      </label>
      <label>
        <span>优先级</span>
        <select v-model="priority">
          <option value="">全部优先级</option>
          <option v-for="value in ['P0', 'P1', 'P2', 'P3']" :key="value" :value="value">{{ value }}</option>
        </select>
      </label>
      <button type="button" :disabled="!query && !status && !priority" @click="resetFilters">清除筛选</button>
    </form>

    <RequestSubmissionPanel :fetch-client="fetchClient" />

    <section class="px-public-records" aria-labelledby="public-request-title">
      <header><h2 id="public-request-title">最新匿名需求</h2><button type="button" :disabled="publicLoading" @click="loadPublicRequests">{{ publicLoading ? '读取中' : '刷新' }}</button></header>
      <p v-if="publicError" class="px-contribution__message is-error" role="alert">{{ publicError }}</p>
      <div v-if="publicItems.length" class="px-public-records__list" aria-live="polite">
        <article v-for="item in publicItems" :key="item.id" :data-submission-id="item.id">
          <div><span>{{ item.status }}</span><code>{{ item.id }}</code></div>
          <h3>{{ item.title }}</h3>
          <dl><div><dt>能力分类</dt><dd>{{ item.capabilityArea }}</dd></div><div><dt>使用场景</dt><dd>{{ item.useCase }}</dd></div><div><dt>预期结果</dt><dd>{{ item.expectedOutcome }}</dd></div><div><dt>技术方向</dt><dd>{{ item.targetStacks?.join('、') || '通用' }}</dd></div></dl>
        </article>
      </div>
      <p v-else-if="!publicLoading && !publicError" class="px-public-records__empty" role="status">暂无匿名需求</p>
    </section>

    <div v-if="filteredItems.length" class="px-requests__list" aria-live="polite">
      <article v-for="item in filteredItems" :key="item.id" :data-request-id="item.id">
        <div class="px-requests__topline">
          <span :data-priority="item.priority">{{ item.priority }}</span>
          <span :data-status="item.status">{{ statusLabels[item.status] }}</span>
          <code>{{ item.id }}</code>
        </div>
        <h2>{{ item.title }}</h2>
        <p>{{ item.scenario }}</p>
        <dl>
          <div><dt>技术方向</dt><dd>{{ item.category }} / {{ item.stack }} / {{ item.kind }}</dd></div>
          <div><dt>提交来源</dt><dd>{{ item.requestedBy }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ item.updatedAt }}</dd></div>
          <div v-if="item.targetComponentId"><dt>交付组件</dt><dd><code>{{ item.targetComponentId }}</code></dd></div>
        </dl>
        <section>
          <h3>需要的能力</h3>
          <ul class="px-requests__tags"><li v-for="capability in item.capabilities" :key="capability">{{ capability }}</li></ul>
        </section>
        <section>
          <h3>验收标准</h3>
          <ol><li v-for="criterion in item.acceptanceCriteria" :key="criterion">{{ criterion }}</li></ol>
        </section>
        <section v-if="item.reuseCandidates.length">
          <h3>优先复用</h3>
          <ul class="px-requests__reuse"><li v-for="candidate in item.reuseCandidates" :key="candidate"><code>{{ candidate }}</code></li></ul>
        </section>
      </article>
    </div>

    <div v-else class="px-requests__empty" role="status">
      <strong>没有匹配的组件需求</strong>
      <button type="button" @click="resetFilters">查看全部需求</button>
    </div>
  </div>
</template>
