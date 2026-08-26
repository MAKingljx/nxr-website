<template>
  <main class="nxr-workspace nxr-exports-workspace">
    <nxr-page-header
      kicker="DATA EXPORTS"
      title="数据导出"
      summary="预览、生成并管理已审批或已发布的数据文件"
    />

    <el-card shadow="never" class="mb8">
      <template #header><span>导出条件（仅导出已审批/已发布数据）</span></template>
      <el-form :inline="true" :model="exportForm" @submit.prevent>
        <el-form-item label="评级筛选">
          <el-input v-model="exportForm.gradeFilter" placeholder="如 10 / 9.5，留空为全部" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item label="证书编号">
          <el-input
            v-model="exportForm.certIds"
            placeholder="逗号分隔，留空为全部"
            clearable
            style="width: 320px"
          />
        </el-form-item>
        <el-form-item>
          <el-button :loading="previewing" icon="View" @click="handlePreview">预 览</el-button>
          <el-button
            type="primary"
            icon="Download"
            :loading="generating"
            :disabled="preview && !preview.canExport"
            v-hasPermi="['nxr:export:generate']"
            @click="handleGenerate"
          >生成 Excel</el-button>
        </el-form-item>
      </el-form>

      <template v-if="preview">
        <el-alert
          :type="preview.canExport ? 'success' : 'warning'"
          :closable="false"
          class="mb8"
        >
          <div>符合条件共 {{ preview.totalCount }} 条（预览前 {{ preview.previewLimit }} 条）。</div>
          <div v-if="preview.invalidCertIds.length">无效证书编号：{{ preview.invalidCertIds.join(', ') }}</div>
          <div v-if="preview.missingCertIds.length">未找到的证书编号：{{ preview.missingCertIds.join(', ') }}</div>
        </el-alert>
        <el-table :data="preview.rows" size="small" max-height="320">
          <el-table-column label="证书编号" prop="certId" width="140" />
          <el-table-column label="产品类型" width="125">
            <template #default="scope">{{ productTypeLabel(scope.row.productType) }}</template>
          </el-table-column>
          <el-table-column label="卡名" prop="cardName" min-width="160" show-overflow-tooltip />
          <el-table-column label="商品描述" prop="merchDescription" min-width="180" show-overflow-tooltip />
          <el-table-column label="品牌" prop="brandName" width="120" />
          <el-table-column label="系列" prop="setName" min-width="140" show-overflow-tooltip />
          <el-table-column label="卡号" prop="cardNumber" width="110" />
          <el-table-column label="语言" prop="languageCode" width="80" align="center" />
          <el-table-column label="POP" prop="populationValue" width="80" align="center" />
          <el-table-column label="状态" prop="statusCode" width="100" align="center" />
          <el-table-column label="结果" width="150">
            <template #default="scope">{{ exportResult(scope.row) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>导出历史</span>
          <el-button icon="Refresh" circle @click="loadExports" />
        </div>
      </template>
      <el-table v-loading="loading" :data="exports">
        <el-table-column label="文件名" prop="filename" min-width="240" show-overflow-tooltip />
        <el-table-column label="筛选条件" prop="filterLabel" min-width="160" show-overflow-tooltip />
        <el-table-column label="记录数" prop="recordCount" width="100" align="center" />
        <el-table-column label="大小" width="110" align="center">
          <template #default="scope">{{ (scope.row.fileSizeBytes / 1024).toFixed(1) }} KB</template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createdAt" width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="170" align="center">
          <template #default="scope">
            <el-button link type="primary" icon="Download" @click="handleDownload(scope.row)">下载</el-button>
            <el-button link type="danger" icon="Delete" v-hasPermi="['nxr:export:remove']" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination
        v-show="total > 0"
        :total="total"
        v-model:page="pageParams.page"
        v-model:limit="pageParams.pageSize"
        @pagination="loadExports"
      />
    </el-card>
  </main>
</template>

<script setup name="NxrExports">
import { saveAs } from 'file-saver'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import { previewExport, generateExport, fetchExports, downloadExportBlob, deleteExport } from '@/api/nxr/exports'

const { proxy } = getCurrentInstance()

const exportForm = reactive({ gradeFilter: '', certIds: '' })
const preview = ref(null)
const previewing = ref(false)
const generating = ref(false)
const exports = ref([])
const total = ref(0)
const loading = ref(false)
const pageParams = reactive({ page: 1, pageSize: 10 })

function productTypeLabel(value) {
  if (value === 'merch_product' || value === 'label_product') return 'Merch Product'
  if (value === 'vintage_product') return 'Vintage Card'
  return 'Graded Card'
}

function exportResult(row) {
  if (row.productType === 'merch_product' || row.productType === 'label_product') return row.merchDescription || '-'
  if (row.productType === 'vintage_product') return row.vintageClassification || '-'
  const values = [row.finalGradeValue, row.finalGradeLabel].filter(
    (value) => value !== null && value !== undefined && String(value).trim() !== ''
  )
  return values.length ? values.join(' · ') : '-'
}

function handlePreview() {
  previewing.value = true
  previewExport({ ...exportForm })
    .then((res) => {
      preview.value = res.data
    })
    .finally(() => {
      previewing.value = false
    })
}

function handleGenerate() {
  generating.value = true
  generateExport({ ...exportForm })
    .then((res) => {
      proxy.$modal.msgSuccess('已生成 ' + res.data.filename + '（' + res.data.recordCount + ' 条）')
      loadExports()
    })
    .finally(() => {
      generating.value = false
    })
}

function loadExports() {
  loading.value = true
  fetchExports({ ...pageParams })
    .then((res) => {
      exports.value = res.data.items
      total.value = res.data.total
    })
    .finally(() => {
      loading.value = false
    })
}

function handleDownload(row) {
  downloadExportBlob(row.filename).then((blob) => {
    saveAs(new Blob([blob]), row.filename)
  })
}

function handleDelete(row) {
  proxy.$modal
    .confirm('确认删除导出文件 "' + row.filename + '" 吗？')
    .then(() => deleteExport(row.filename))
    .then(() => {
      proxy.$modal.msgSuccess('删除成功')
      loadExports()
    })
    .catch(() => {})
}

loadExports()
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
