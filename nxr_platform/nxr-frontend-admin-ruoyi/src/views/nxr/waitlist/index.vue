<template>
  <div class="app-container">
    <el-form :inline="true" @submit.prevent>
      <el-form-item label="邮箱">
        <el-input
          v-model="query"
          placeholder="搜索邮箱"
          clearable
          style="width: 240px"
          @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="ID" prop="id" width="90" align="center" />
      <el-table-column label="邮箱" prop="email" min-width="240" show-overflow-tooltip />
      <el-table-column label="来源" prop="sourceCode" width="120" align="center" />
      <el-table-column label="状态" width="120" align="center">
        <template #default="scope">
          <el-tag :type="scope.row.statusCode === 'confirmed' ? 'success' : 'info'">{{ scope.row.statusCode }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="加入时间" prop="createdAt" width="200" show-overflow-tooltip />
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      v-model:page="pageParams.page"
      v-model:limit="pageParams.pageSize"
      @pagination="getList"
    />
  </div>
</template>

<script setup name="NxrWaitlist">
import { fetchWaitlist } from '@/api/nxr/waitlist'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = ref('')
const pageParams = reactive({ page: 1, pageSize: 20 })

function getList() {
  loading.value = true
  fetchWaitlist({ query: query.value || undefined, ...pageParams })
    .then((res) => {
      rows.value = res.data.items
      total.value = res.data.total
    })
    .finally(() => {
      loading.value = false
    })
}

function handleQuery() {
  pageParams.page = 1
  getList()
}

getList()
</script>
