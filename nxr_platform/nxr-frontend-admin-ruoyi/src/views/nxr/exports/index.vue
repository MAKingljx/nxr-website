<template>
  <main class="nxr-workspace nxr-exports-workspace">
    <nxr-page-header
      :kicker="$tx('DATA EXPORTS')"
      :title="$tx('Data Export')"
      :summary="$tx('Preview, generate, and manage data files for approved or published records')"
    />

    <el-card shadow="never" class="mb8">
      <template #header><span>{{ $tx('Export Criteria (approved/published records only)') }}</span></template>
      <el-form :inline="true" :model="exportForm" @submit.prevent>
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
        <el-form-item :label="$tx('Cert IDs')">
          <el-input
            v-model="exportForm.certIds"
            :placeholder="$tx('Comma-separated; blank for all')"
            clearable
            style="width: 320px"
          />
        </el-form-item>
        <el-form-item>
          <el-button :loading="previewing" icon="View" @click="handlePreview">{{ $tx('Preview') }}</el-button>
          <el-button
            type="primary"
            icon="Download"
            :loading="generating"
            :disabled="preview && !preview.canExport"
            v-hasPermi="['nxr:export:generate']"
            @click="handleGenerate"
          >{{ $tx('Generate Excel') }}</el-button>
        </el-form-item>
      </el-form>

      <template v-if="preview">
        <el-alert
          :type="preview.canExport ? 'success' : 'warning'"
          :closable="false"
          class="mb8"
        >
          <div>{{ preview.totalCount }} {{ $tx('matching records (showing the first') }} {{ preview.previewLimit }}).</div>
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
      </template>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>{{ $tx('Export History') }}</span>
          <el-button icon="Refresh" circle @click="loadExports" />
        </div>
      </template>
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

const gradeExportOptions = ['8', '8.5', '9', '9.5', '10', 'Pristine 10']
const vintageExportOptions = ['Pristine', 'Nova', 'Legacy', 'Helix']
const exportForm = reactive({ exportFilter: 'all', certIds: '' })
const preview = ref(null)
const previewing = ref(false)
const generating = ref(false)
const exports = ref([])
const total = ref(0)
const loading = ref(false)
const pageParams = reactive({ page: 1, pageSize: 10 })

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
      proxy.$modal.msgSuccess(tx('Generated ') + res.data.filename + ' (' + res.data.recordCount + tx(' records)'))
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
    .confirm(tx('Delete export file "') + row.filename + '"?')
    .then(() => deleteExport(row.filename))
    .then(() => {
      proxy.$modal.msgSuccess(tx('Export file deleted'))
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
