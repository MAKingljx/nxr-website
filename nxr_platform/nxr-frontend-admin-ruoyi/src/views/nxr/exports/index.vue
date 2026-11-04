<template>
  <main class="nxr-workspace nxr-exports-workspace">
    <nxr-page-header
      :kicker="$tx('DATA EXPORTS')"
      :title="$tx('Data Export')"
      :summary="$tx('Preview, generate, and manage data files for approved or published records')"
    >
      <template #actions>
        <el-button icon="Tickets" @click="openHistoryDrawer">
          {{ $tx('Export History') }}<span v-if="total"> ({{ total }})</span>
        </el-button>
      </template>
    </nxr-page-header>

    <el-card shadow="never" class="mb8">
      <template #header><span>{{ $tx('Export Criteria (approved/published records only)') }}</span></template>
      <el-form :model="exportForm" label-position="top" class="export-criteria-form" @submit.prevent>
        <el-form-item :label="$tx('Export Filter')">
          <el-select v-model="exportForm.exportFilter" style="width: 260px">
            <el-option :label="$tx('All Approved Products')" value="all" />
            <el-option-group :label="$tx('Graded Cards')">
              <el-option
                v-for="grade in gradeExportOptions"
                :key="grade"
                :label="grade"
                :value="`grade:${grade}`"
              />
            </el-option-group>
            <el-option-group :label="$tx('Merch Product')">
              <el-option :label="$tx('Merch Product')" value="merch_product" />
            </el-option-group>
            <el-option-group :label="$tx('Vintage Cards')">
              <el-option
                v-for="classification in vintageExportOptions"
                :key="classification"
                :label="classification"
                :value="`vintage_product:${classification}`"
              />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item :label="$tx('Status')">
          <el-select v-model="exportForm.statusCode" style="width: 150px">
            <el-option :label="$tx('All Statuses')" value="all" />
            <el-option :label="$tx('Approved')" value="approved" />
            <el-option :label="$tx('Published')" value="published" />
          </el-select>
        </el-form-item>
        <el-form-item :label="$tx('Period (approved date)')">
          <el-date-picker
            v-model="exportForm.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="-"
            :start-placeholder="$tx('Start date')"
            :end-placeholder="$tx('End date')"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item :label="$tx('Cert IDs')">
          <el-input
            v-model="exportForm.certIds"
            :placeholder="$tx('Comma-separated; blank for all')"
            clearable
            style="width: 320px"
          />
        </el-form-item>
        <el-form-item class="export-form-actions">
          <el-button type="primary" :loading="previewing" icon="Search" @click="handleSearch">{{ $tx('Search') }}</el-button>
          <el-button
            icon="Download"
            :loading="generating"
            :disabled="!preview || !preview.canExport"
            v-hasPermi="['nxr:export:generate']"
            @click="handleGenerate"
          >{{ $tx('Generate Excel') }}</el-button>
        </el-form-item>
      </el-form>

      <template v-if="preview">
        <el-alert
          v-if="preview.invalidCertIds.length || preview.missingCertIds.length"
          type="warning"
          :closable="false"
          class="mb8"
        >
          <div v-if="preview.invalidCertIds.length">{{ $tx('Invalid Cert IDs:') }} {{ preview.invalidCertIds.join(', ') }}</div>
          <div v-if="preview.missingCertIds.length">{{ $tx('Cert IDs not found:') }} {{ preview.missingCertIds.join(', ') }}</div>
        </el-alert>
        <el-table :data="preview.rows" size="small" max-height="320">
          <el-table-column :label="$tx('Cert ID')" prop="certId" width="140" />
          <el-table-column :label="$tx('Product Type')" width="125">
            <template #default="scope">{{ productTypeLabel(scope.row.productType) }}</template>
          </el-table-column>
          <el-table-column :label="$tx('Card Name')" prop="cardName" min-width="160" show-overflow-tooltip />
          <el-table-column :label="$tx('Merch Description')" prop="merchDescription" min-width="180" show-overflow-tooltip />
          <el-table-column :label="$tx('Brand')" prop="brandName" width="120" />
          <el-table-column :label="$tx('Set Name')" prop="setName" min-width="140" show-overflow-tooltip />
          <el-table-column :label="$tx('Card Number')" prop="cardNumber" width="110" />
          <el-table-column :label="$tx('Language')" prop="languageCode" width="80" align="center" />
          <el-table-column :label="$tx('POP')" prop="populationValue" width="80" align="center" />
          <el-table-column :label="$tx('Status')" prop="statusCode" width="100" align="center" />
          <el-table-column :label="$tx('Result')" width="150">
            <template #default="scope">{{ exportResult(scope.row) }}</template>
          </el-table-column>
        </el-table>
        <export-pagination
          :total="preview.totalCount"
          :page="searchParams.page"
          :page-size="searchParams.pageSize"
          :page-sizes="previewPageSizes"
          @change="handleSearchPageChange"
        />
      </template>
    </el-card>

    <el-drawer
      v-model="historyDrawerVisible"
      :title="$tx('Export History')"
      direction="rtl"
      size="80%"
    >
      <div class="history-toolbar">
        <el-input
          v-model="historyForm.keyword"
          clearable
          :placeholder="$tx('Keyword')"
          style="width: 200px"
        />
        <el-button type="primary" icon="Search" @click="applyHistorySearch">{{ $tx('Search') }}</el-button>
        <el-button icon="Refresh" @click="resetHistorySearch">{{ $tx('Reset') }}</el-button>
        <el-button icon="Refresh" circle :aria-label="$tx('Refresh')" @click="loadExports" />
      </div>
      <el-table v-loading="loading" :data="exports">
        <el-table-column :label="$tx('Filename')" prop="filename" min-width="240" show-overflow-tooltip />
        <el-table-column :label="$tx('Filter')" prop="filterLabel" min-width="160" show-overflow-tooltip />
        <el-table-column :label="$tx('Records')" prop="recordCount" width="100" align="center" />
        <el-table-column :label="$tx('Size')" width="110" align="center">
          <template #default="scope">{{ (scope.row.fileSizeBytes / 1024).toFixed(1) }} {{ $tx('KB') }}</template>
        </el-table-column>
        <el-table-column :label="$tx('Created At')" prop="createdAt" width="180" show-overflow-tooltip />
        <el-table-column :label="$tx('Actions')" width="170" align="center">
          <template #default="scope">
            <el-button link type="primary" icon="Download" @click="handleDownload(scope.row)">{{ $tx('Download') }}</el-button>
            <el-button link type="danger" icon="Delete" v-hasPermi="['nxr:export:remove']" @click="handleDelete(scope.row)">{{ $tx('Delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <export-pagination
        :total="total"
        :page="pageParams.page"
        :page-size="pageParams.pageSize"
        @change="handleHistoryPageChange"
      />
    </el-drawer>
  </main>
</template>

<script setup name="NxrExports">
import { saveAs } from 'file-saver'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import ExportPagination from '@/components/ExportPagination/index.vue'
import { previewExport, generateExport, fetchExports, downloadExportBlob, deleteExport } from '@/api/nxr/exports'

const { proxy } = getCurrentInstance()

const gradeExportOptions = ['8', '8.5', '9', '9.5', '10', 'Pristine 10']
const vintageExportOptions = ['Pristine', 'Nova', 'Legacy', 'Helix']
const exportForm = reactive({ exportFilter: 'all', certIds: '', statusCode: 'all', dateRange: [] })
const preview = ref(null)
const previewing = ref(false)
const generating = ref(false)
const exports = ref([])
const total = ref(0)
const loading = ref(false)
const pageParams = reactive({ page: 1, pageSize: 20 })
const searchParams = reactive({ page: 1, pageSize: 20 })
const previewPageSizes = [10, 20, 50, 100]
const historyDrawerVisible = ref(false)
const historyForm = reactive({ keyword: '' })
const historyFilters = reactive({ keyword: '' })
let previewRequestVersion = 0
let historyRequestVersion = 0

watch(
  () => [
    exportForm.exportFilter,
    exportForm.certIds,
    exportForm.statusCode,
    ...(Array.isArray(exportForm.dateRange) ? exportForm.dateRange : [])
  ],
  () => {
    previewRequestVersion += 1
    preview.value = null
  }
)

function productTypeLabel(value) {
  if (value === 'merch_product' || value === 'label_product') return tx('Merch Product')
  if (value === 'vintage_product') return tx('Vintage Card')
  return tx('Graded Card')
}

function exportResult(row) {
  if (row.productType === 'merch_product' || row.productType === 'label_product') return row.merchDescription || '-'
  if (row.productType === 'vintage_product') return row.vintageClassification || '-'
  const values = [row.finalGradeValue, row.finalGradeLabel].filter(
    (value) => value !== null && value !== undefined && String(value).trim() !== ''
  )
  return values.length ? values.join(' · ') : '-'
}

function exportRequestBody(extra = {}) {
  const dateRange = Array.isArray(exportForm.dateRange) ? exportForm.dateRange : []
  return {
    exportFilter: exportForm.exportFilter,
    certIds: exportForm.certIds,
    statusCode: exportForm.statusCode === 'all' ? '' : exportForm.statusCode,
    dateFrom: dateRange[0] || '',
    dateTo: dateRange[1] || '',
    ...extra
  }
}

function handleSearch() {
  searchParams.page = 1
  loadSearchResults()
}

function loadSearchResults() {
  const requestVersion = ++previewRequestVersion
  preview.value = null
  previewing.value = true
  previewExport(exportRequestBody({ page: searchParams.page, pageSize: searchParams.pageSize }))
    .then((res) => {
      if (requestVersion === previewRequestVersion) preview.value = res.data
    })
    .finally(() => {
      if (requestVersion === previewRequestVersion) previewing.value = false
    })
}

function handleSearchPageChange({ page, pageSize }) {
  searchParams.page = page
  searchParams.pageSize = pageSize
  loadSearchResults()
}

function handleGenerate() {
  generating.value = true
  generateExport(exportRequestBody())
    .then((res) => {
      proxy.$modal.msgSuccess(tx('Generated ') + res.data.filename + ' (' + res.data.recordCount + tx(' records)'))
      loadExports()
    })
    .finally(() => {
      generating.value = false
    })
}

function openHistoryDrawer() {
  historyForm.keyword = historyFilters.keyword
  historyDrawerVisible.value = true
  loadExports()
}

function applyHistorySearch() {
  historyFilters.keyword = (historyForm.keyword || '').trim()
  pageParams.page = 1
  loadExports()
}

function resetHistorySearch() {
  historyForm.keyword = ''
  historyFilters.keyword = ''
  pageParams.page = 1
  loadExports()
}

function handleHistoryPageChange({ page, pageSize }) {
  pageParams.page = page
  pageParams.pageSize = pageSize
  loadExports()
}

function loadExports() {
  const requestVersion = ++historyRequestVersion
  loading.value = true
  fetchExports({ ...pageParams, ...historyFilters })
    .then((res) => {
      if (requestVersion === historyRequestVersion) {
        exports.value = res.data.items
        total.value = res.data.total
      }
    })
    .finally(() => {
      if (requestVersion === historyRequestVersion) loading.value = false
    })
}

function handleDownload(row) {
  downloadExportBlob(row.filename).then((blob) => {
    saveAs(new Blob([blob]), row.filename)
  })
}

function handleDelete(row) {
  proxy.$modal
    .confirm(tx('Delete export file "') + row.filename + '"?')
    .then(() => deleteExport(row.filename))
    .then(() => {
      proxy.$modal.msgSuccess(tx('Export file deleted'))
      loadExports()
    })
    .catch(() => {})
}

// Load history count and the first page of export data on entry, so the page opens with records.
loadExports()
handleSearch()
</script>

<style scoped>
.export-criteria-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 0 16px;
}

.export-criteria-form :deep(.el-form-item) {
  margin-right: 0;
  margin-bottom: 12px;
}

.export-criteria-form :deep(.el-form-item.export-form-actions) {
  margin-left: auto;
}

.history-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
</style>
