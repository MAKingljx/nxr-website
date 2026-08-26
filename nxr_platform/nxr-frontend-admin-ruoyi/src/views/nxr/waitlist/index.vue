<template>
  <main class="nxr-workspace nxr-waitlist-workspace">
    <nxr-page-header
      kicker="CUSTOMER INTAKE"
      title="候补名单"
      :summary="`共 ${total} 条候补记录，可按邮箱查询确认状态`"
    />

    <nxr-server-data-workbench
      :loading="loading"
      :error="loadError"
      :empty="!loading && !loadError && rows.length === 0"
      :total="total"
      :page="pageParams.page"
      :page-size="pageParams.pageSize"
      :show-reset="Boolean(query)"
      empty-title="没有候补记录"
      empty-description="请调整邮箱搜索条件后重试。"
      aria-label="候补名单数据列表"
      @query="handleQuery"
      @reset="resetQuery"
      @retry="getList"
      @page-change="handlePageChange"
    >
      <template #filters>
        <div class="waitlist-filter">
          <label for="waitlist-email-query">邮箱</label>
        <el-input
          id="waitlist-email-query"
          v-model="query"
          placeholder="搜索邮箱"
          clearable
          @clear="handleQuery"
        />
        </div>
      </template>
      <template #filter-actions>
        <el-button type="primary" icon="Search" :loading="loading" native-type="submit">搜索</el-button>
        <el-button v-if="query" icon="Refresh" :disabled="loading" @click="resetQuery">重置</el-button>
      </template>

      <el-table :data="rows">
      <el-table-column label="ID" prop="id" width="90" align="center" />
      <el-table-column label="邮箱" prop="email" min-width="240" show-overflow-tooltip />
      <el-table-column label="来源" prop="sourceCode" width="120" align="center" />
      <el-table-column label="状态" width="120" align="center">
        <template #default="scope">
          <nxr-status-tag :code="scope.row.statusCode" domain="waitlist" />
        </template>
      </el-table-column>
      <el-table-column label="加入时间" prop="createdAt" width="200" show-overflow-tooltip />
      </el-table>
    </nxr-server-data-workbench>
  </main>
</template>

<script setup name="NxrWaitlist">
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import NxrServerDataWorkbench from '@/components/NxrWorkspace/ServerDataWorkbench.vue'
import NxrStatusTag from '@/components/NxrWorkspace/StatusTag.vue'
import { fetchWaitlist } from '@/api/nxr/waitlist'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
const query = ref('')
const pageParams = reactive({ page: 1, pageSize: 20 })

function getList() {
  loading.value = true
  loadError.value = ''
  fetchWaitlist({ query: query.value || undefined, ...pageParams })
    .then((res) => {
      rows.value = res.data.items
      total.value = res.data.total
      pageParams.page = res.data.page
      pageParams.pageSize = res.data.pageSize
    })
    .catch(() => {
      loadError.value = '暂时无法读取候补名单，请稍后重试。'
    })
    .finally(() => {
      loading.value = false
    })
}

function handleQuery() {
  pageParams.page = 1
  getList()
}

function resetQuery() {
  query.value = ''
  handleQuery()
}

function handlePageChange(page, pageSize) {
  pageParams.page = page
  pageParams.pageSize = pageSize
  getList()
}

getList()
</script>

<style scoped>
.waitlist-filter {
  display: grid;
  width: min(100%, 280px);
  gap: 6px;
}

.waitlist-filter label {
  color: var(--nxr-text-muted);
  font-size: 12px;
}
</style>
