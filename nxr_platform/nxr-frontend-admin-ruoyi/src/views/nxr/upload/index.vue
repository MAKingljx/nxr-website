<template>
  <main class="nxr-workspace nxr-upload-workspace">
    <nxr-page-header
      :kicker="$tx('MEDIA OPERATIONS')"
      :title="$tx('Card Image Upload & Publication')"
      :summary="$tx('Import front/back images for approved cards and review publication status')"
    />

    <el-row :gutter="16" class="mb8">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card" @click="applyStatFilter('total')"><el-statistic :title="$tx('Total Approved')" :value="summary.totalApproved" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card" @click="applyStatFilter('ready')"><el-statistic :title="$tx('Ready to Publish')" :value="summary.readyToPublish" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card" @click="applyStatFilter('waiting')"><el-statistic :title="$tx('Waiting for Images')" :value="summary.waitingForUpload" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card" @click="applyStatFilter('uploaded')"><el-statistic :title="$tx('Uploaded to Server')" :value="summary.uploadedToServer" /></el-card>
      </el-col>
    </el-row>
    <div class="summary-strip mb8">
      <el-tag type="warning" effect="plain" class="summary-action" @click="applyStatFilter('remaining')">
        {{ $tx('Remaining Uploads') }}: {{ summary.remainingUploadCount }}
      </el-tag>
      <el-tag type="success" effect="plain" class="summary-action" @click="applyUploadStatusFilter('client_pushed')">
        {{ $tx('Client Pushed') }}: {{ summary.clientPushed }}
      </el-tag>
      <el-tag effect="plain">{{ $tx('Front Images') }}: {{ summary.hasFrontImage }}</el-tag>
      <el-tag effect="plain">{{ $tx('Back Images') }}: {{ summary.hasBackImage }}</el-tag>
      <template v-for="(count, status) in summary.statusCounts" :key="status">
        <el-tag v-if="status !== 'client_pushed'" effect="plain" class="summary-action" @click="applyUploadStatusFilter(status)">
          {{ uploadStateLabel(status) }}: {{ count }}
        </el-tag>
      </template>
    </div>

    <el-card shadow="never" class="mb8" v-hasPermi="['nxr:media:import']">
      <template #header>
        <div class="card-header">
          <span>{{ $tx('Folder Import (CertID_A = front, CertID_B = back; webp/jpg/jpeg/png)') }}</span>
          <el-button icon="Refresh" circle @click="loadQueue()" />
        </div>
      </template>

      <input
        ref="folderInput"
        class="hidden-input"
        type="file"
        accept=".webp,.jpg,.jpeg,.png"
        webkitdirectory
        directory
        multiple
        @change="handleFolderChange"
      />

      <div class="picker-row">
        <el-button type="primary" icon="FolderOpened" @click="openFolderPicker">{{ $tx('Select Folder') }}</el-button>
        <el-button :disabled="!selectedFiles.length || importing" @click="clearSelectedFiles">{{ $tx('Clear') }}</el-button>
        <el-button
          type="success"
          icon="Upload"
          :loading="importing"
          :disabled="!selectedFiles.length"
          @click="submitImport"
        >{{ $tx('Start Import') }}</el-button>
        <span class="picker-meta">
          {{ selectedFolderName || (selectedFiles.length ? $tx('Files selected') : $tx('No folder selected')) }}
          · {{ selectedFiles.length }} {{ $tx('image files') }} <template v-if="skippedFileCount">({{ skippedFileCount }} {{ $tx('non-image files skipped)') }}</template>
        </span>
      </div>

      <el-progress
        v-if="uploadStatus !== 'idle'"
        :percentage="uploadPercent"
        :status="uploadStatus === 'failed' ? 'exception' : uploadStatus === 'success' ? 'success' : undefined"
        class="mt8"
      />
      <div v-if="uploadLabel" class="upload-label">{{ uploadLabel }}</div>

      <el-alert v-if="lastImport" :type="uploadStatus === 'failed' ? 'warning' : 'success'" :closable="false" class="mt8">
        <div>{{ $tx('Saved') }} {{ lastImport.savedFiles }} {{ $tx('files, updated') }} {{ lastImport.updatedSides }} {{ $tx('sides, and matched') }} {{ lastImport.matchedEntries }} {{ $tx('entries.') }}</div>
        <div v-if="lastImport.missingCertIds.length">{{ $tx('Cert IDs not found:') }} {{ lastImport.missingCertIds.join(', ') }}</div>
        <div v-if="lastImport.invalidNames.length">{{ $tx('Invalid filenames:') }} {{ lastImport.invalidNames.join(', ') }}</div>
        <div v-if="lastImport.duplicateNames.length">{{ $tx('Duplicate files:') }} {{ lastImport.duplicateNames.join(', ') }}</div>
      </el-alert>
    </el-card>

    <el-form :inline="true" class="queue-filters" @submit.prevent>
      <el-form-item :label="$tx('Keyword')">
        <el-input
          v-model="searchQuery"
          :placeholder="$tx('Cert ID / Card Name')"
          clearable
          style="width: 240px"
          @keyup.enter="loadQueue(true)"
        />
      </el-form-item>
      <el-form-item :label="$tx('Cert ID')">
        <el-input v-model="certIdFilter" clearable style="width: 150px" @keyup.enter="loadQueue(true)" />
      </el-form-item>
      <el-form-item :label="$tx('Card Name')">
        <el-input v-model="cardNameFilter" clearable style="width: 180px" @keyup.enter="loadQueue(true)" />
      </el-form-item>
      <el-form-item :label="$tx('Category')">
        <el-select v-model="cardCategoryFilter" clearable style="width: 160px" @change="loadQueue(true)">
          <el-option :label="$tx('Trading Card')" value="trading_card" />
          <el-option :label="$tx('Movie Film')" value="movie_film" />
          <el-option :label="$tx('Sports Card')" value="sports_card" />
          <el-option :label="$tx('Celebrity Card')" value="celebrity_card" />
        </el-select>
      </el-form-item>
      <el-form-item :label="$tx('Product Type')">
        <el-select v-model="productTypeFilter" clearable style="width: 160px" @change="loadQueue(true)">
          <el-option :label="$tx('Graded Card')" value="graded_card" />
          <el-option :label="$tx('Merch Product')" value="merch_product" />
          <el-option :label="$tx('Vintage Card')" value="vintage_product" />
        </el-select>
      </el-form-item>
      <el-form-item :label="$tx('Brand')">
        <el-input v-model="brandFilter" clearable style="width: 150px" @keyup.enter="loadQueue(true)" />
      </el-form-item>
      <el-form-item :label="$tx('Language')">
        <el-select v-model="languageFilter" clearable style="width: 130px" @change="loadQueue(true)">
          <el-option v-for="language in languageOptions" :key="language" :label="language" :value="language" />
        </el-select>
      </el-form-item>
      <el-form-item :label="$tx('Final Grade')">
        <el-input v-model="finalGradeFilter" clearable style="width: 130px" @keyup.enter="loadQueue(true)" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="loadQueue(true)">{{ $tx('Search') }}</el-button>
        <el-button icon="RefreshLeft" @click="resetQueueFilters">{{ $tx('Clear Filters') }}</el-button>
      </el-form-item>
      <el-form-item :label="$tx('Upload Status')">
        <el-select v-model="uploadStatusFilter" clearable style="width: 160px" @change="loadQueue(true)">
          <el-option :label="$tx('Remaining Uploads')" value="remaining_uploads" />
          <el-option :label="$tx('Uploaded to Server')" value="uploaded_to_server" />
          <el-option :label="$tx('Not Started')" value="not_started" />
          <el-option :label="$tx('Uploading')" value="uploading" />
          <el-option :label="$tx('Uploaded')" value="uploaded" />
          <el-option :label="$tx('Failed')" value="failed" />
          <el-option :label="$tx('Client Pushed')" value="client_pushed" />
        </el-select>
      </el-form-item>
      <el-form-item :label="$tx('Image Status')">
        <el-select v-model="imageStatusFilter" clearable style="width: 150px" @change="loadQueue(true)">
          <el-option :label="$tx('Ready')" value="ready" />
          <el-option :label="$tx('Waiting')" value="waiting" />
          <el-option :label="$tx('Published')" value="published" />
          <el-option :label="$tx('Missing Any Image')" value="missing_any" />
          <el-option :label="$tx('Missing Front Image')" value="missing_front" />
          <el-option :label="$tx('Missing Back Image')" value="missing_back" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-checkbox v-model="showClientPushed" @change="loadQueue(true)">{{ $tx('Show Client Pushed') }}</el-checkbox>
      </el-form-item>
      <el-form-item v-hasPermi="['nxr:media:publish']">
        <el-button
          type="success"
          icon="Promotion"
          :loading="batchPublishing"
          :disabled="!selectedReadyIds.length"
          @click="publishSelected"
        >{{ $tx('Publish Selected (') }}{{ selectedReadyIds.length }})</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="queue" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="48" :selectable="isReadyToSelect" />
      <el-table-column :label="$tx('Cert ID')" prop="certId" width="140" />
      <el-table-column :label="$tx('Card Name')" prop="cardName" min-width="160" show-overflow-tooltip />
      <el-table-column :label="$tx('Result')" width="180" show-overflow-tooltip>
        <template #default="scope">{{ queueResult(scope.row) }}</template>
      </el-table-column>
      <el-table-column :label="$tx('Staged Images')" width="160" align="center">
        <template #default="scope">
          <div class="thumb-row">
            <el-image v-if="scope.row.stagedFrontUrl && !scope.row.stagedFrontMissing" :src="mediaDisplayUrl(scope.row.stagedFrontUrl)" :preview-src-list="[mediaDisplayUrl(scope.row.stagedFrontUrl)]" fit="cover" class="thumb" preview-teleported />
            <el-image v-if="scope.row.stagedBackUrl && !scope.row.stagedBackMissing" :src="mediaDisplayUrl(scope.row.stagedBackUrl)" :preview-src-list="[mediaDisplayUrl(scope.row.stagedBackUrl)]" fit="cover" class="thumb" preview-teleported />
            <span v-if="scope.row.stagedFrontMissing" class="missing-ref">{{ $tx('Front file missing') }}</span>
            <span v-if="scope.row.stagedBackMissing" class="missing-ref">{{ $tx('Back file missing') }}</span>
            <span v-if="!scope.row.stagedFrontUrl && !scope.row.stagedBackUrl" class="muted">-</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Published Images')" width="160" align="center">
        <template #default="scope">
          <div class="thumb-row">
            <el-image v-if="scope.row.publishedFrontUrl && !scope.row.publishedFrontMissing" :src="scope.row.publishedFrontUrl" :preview-src-list="[scope.row.publishedFrontUrl]" fit="cover" class="thumb" preview-teleported />
            <el-image v-if="scope.row.publishedBackUrl && !scope.row.publishedBackMissing" :src="scope.row.publishedBackUrl" :preview-src-list="[scope.row.publishedBackUrl]" fit="cover" class="thumb" preview-teleported />
            <span v-if="scope.row.publishedFrontMissing" class="missing-ref">{{ $tx('Published front missing') }}</span>
            <span v-if="scope.row.publishedBackMissing" class="missing-ref">{{ $tx('Published back missing') }}</span>
            <span v-if="!scope.row.publishedFrontUrl && !scope.row.publishedBackUrl" class="muted">-</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Upload Status')" width="135" align="center">
        <template #default="scope">
          <el-tooltip v-if="scope.row.uploadError" :content="scope.row.uploadError" placement="top">
            <el-tag :type="uploadStateTag(scope.row.uploadStatus)">{{ uploadStateLabel(scope.row.uploadStatus) }}</el-tag>
          </el-tooltip>
          <el-tag v-else :type="uploadStateTag(scope.row.uploadStatus)">{{ uploadStateLabel(scope.row.uploadStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Image Status')" width="130" align="center">
        <template #default="scope">
          <el-tag :type="mediaStateTag(scope.row)">{{ mediaStateLabel(scope.row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Actions')" width="220" align="center">
        <template #default="scope">
          <el-button
            link
            type="success"
            icon="Promotion"
            :disabled="!scope.row.readyToPublish"
            :loading="publishLoadingId === scope.row.submissionId"
            v-hasPermi="['nxr:media:publish']"
            @click="publishEntry(scope.row.submissionId)"
          >{{ $tx('Publish') }}</el-button>
          <el-button
            link
            type="primary"
            :loading="clientPushLoadingId === scope.row.submissionId"
            :disabled="scope.row.uploadStatus === 'uploading' || scope.row.uploadStatus === 'client_pushed'"
            v-hasPermi="['nxr:media:publish']"
            @click="markClientPushed(scope.row)"
          >{{ $tx('Client Pushed') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="queueTotal > 0"
      :total="queueTotal"
      v-model:page="queuePage"
      v-model:limit="queuePageSize"
      @pagination="loadQueue()"
    />
  </main>
</template>

<script setup name="NxrUpload">
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import { fetchMediaQueue, importMediaFolder, publishSubmissionMedia, publishSubmissionMediaBatch, markSubmissionClientPushed } from '@/api/nxr/media'

const { proxy } = getCurrentInstance()
const allowedImagePattern = /\.(webp|png|jpe?g)$/i

const queue = ref([])
const summary = ref({
  trackedEntries: 0,
  readyToPublish: 0,
  livePublished: 0,
  missingMedia: 0,
  totalApproved: 0,
  waitingForUpload: 0,
  uploadedToServer: 0,
  remainingUploadCount: 0
})
const queueTotal = ref(0)
const queuePage = ref(1)
const queuePageSize = ref(12)
const searchQuery = ref('')
const certIdFilter = ref('')
const cardNameFilter = ref('')
const cardCategoryFilter = ref('')
const productTypeFilter = ref('')
const brandFilter = ref('')
const languageFilter = ref('')
const finalGradeFilter = ref('')
const uploadStatusFilter = ref('')
const imageStatusFilter = ref('')
const showClientPushed = ref(false)
const languageOptions = ['EN', 'JP', 'CT', 'CS', 'IN', 'KO', 'TH', 'Other']
const loading = ref(false)
const importing = ref(false)
const publishLoadingId = ref(null)
const clientPushLoadingId = ref(null)
const batchPublishing = ref(false)
const selectedReadyIds = ref([])
const selectedFiles = ref([])
const skippedFileCount = ref(0)
const selectedFolderName = ref('')
const uploadStatus = ref('idle')
const uploadPercent = ref(0)
const uploadLabel = ref('')
const lastImport = ref(null)
const folderInput = ref(null)

function mediaDisplayUrl(value) {
  if (typeof value === 'string' && value.startsWith('/media/')) {
    return `${import.meta.env.VITE_APP_BASE_API}${value}`
  }
  return value
}

function loadQueue(resetPage = false) {
  if (resetPage) queuePage.value = 1
  loading.value = true
  return fetchMediaQueue({
    query: searchQuery.value.trim() || undefined,
    certId: certIdFilter.value.trim() || undefined,
    cardName: cardNameFilter.value.trim() || undefined,
    cardCategory: cardCategoryFilter.value || undefined,
    productType: productTypeFilter.value || undefined,
    brand: brandFilter.value.trim() || undefined,
    language: languageFilter.value || undefined,
    finalGrade: finalGradeFilter.value.trim() || undefined,
    uploadStatus: uploadStatusFilter.value || undefined,
    imageStatus: imageStatusFilter.value || undefined,
    showClientPushed: showClientPushed.value,
    page: queuePage.value,
    pageSize: queuePageSize.value
  })
    .then((res) => {
      queue.value = res.data.items
      selectedReadyIds.value = []
      summary.value = res.data.summary
      queueTotal.value = res.data.total
      queuePage.value = res.data.page
      queuePageSize.value = res.data.pageSize
    })
    .finally(() => {
      loading.value = false
    })
}

function applyStatFilter(kind) {
  clearBusinessFilters()
  uploadStatusFilter.value = ''
  imageStatusFilter.value = ''
  showClientPushed.value = kind === 'total' || kind === 'uploaded'
  if (kind === 'ready') imageStatusFilter.value = 'ready'
  if (kind === 'waiting') imageStatusFilter.value = 'waiting'
  if (kind === 'uploaded') uploadStatusFilter.value = 'uploaded_to_server'
  if (kind === 'remaining') uploadStatusFilter.value = 'remaining_uploads'
  loadQueue(true)
}

function applyUploadStatusFilter(status) {
  clearBusinessFilters()
  imageStatusFilter.value = ''
  uploadStatusFilter.value = status
  showClientPushed.value = status === 'client_pushed'
  loadQueue(true)
}

function clearBusinessFilters() {
  searchQuery.value = ''
  certIdFilter.value = ''
  cardNameFilter.value = ''
  cardCategoryFilter.value = ''
  productTypeFilter.value = ''
  brandFilter.value = ''
  languageFilter.value = ''
  finalGradeFilter.value = ''
}

function resetQueueFilters() {
  clearBusinessFilters()
  uploadStatusFilter.value = ''
  imageStatusFilter.value = ''
  loadQueue(true)
}

function isReadyToSelect(row) {
  return row.readyToPublish
}

function handleSelectionChange(rows) {
  selectedReadyIds.value = rows.filter(isReadyToSelect).map((row) => row.submissionId)
}

function openFolderPicker() {
  folderInput.value?.click()
}

function clearSelectedFiles() {
  selectedFiles.value = []
  skippedFileCount.value = 0
  selectedFolderName.value = ''
  if (folderInput.value) folderInput.value.value = ''
}

function handleFolderChange(event) {
  const files = Array.from(event.target.files ?? [])
  const imageFiles = files.filter((file) => allowedImagePattern.test(file.name))
  selectedFiles.value = imageFiles
  skippedFileCount.value = files.length - imageFiles.length
  if (!imageFiles.length) {
    selectedFolderName.value = ''
    return
  }
  const firstRelativePath = imageFiles[0].webkitRelativePath ?? imageFiles[0].name
  selectedFolderName.value = firstRelativePath.includes('/') ? firstRelativePath.split('/')[0] : ''
}

async function submitImport() {
  if (!selectedFiles.value.length) {
    proxy.$modal.msgWarning(tx('Select a folder containing card images first'))
    return
  }
  importing.value = true
  uploadStatus.value = 'uploading'
  uploadPercent.value = 0
  uploadLabel.value = tx('Preparing upload…')
  try {
    const response = await importMediaFolder(selectedFiles.value, (percent, loaded, total, meta = {}) => {
      uploadPercent.value = meta.phase === 'complete' ? 100 : Math.min(percent, 99)
      if (meta.phase === 'retrying') {
        const retries = Math.max((meta.maxAttempts || 1) - 1, 1)
        uploadLabel.value = `Batch ${meta.batchIndex}/${meta.batchCount} · retry ${meta.retryCount || meta.attempt - 1}/${retries}`
      } else if (meta.phase === 'batch-complete') {
        uploadLabel.value = `${meta.completedBatches}/${meta.batchCount} batches complete · ${meta.successfulCertificates || 0} certificates`
      } else if (meta.phase === 'complete') {
        uploadLabel.value = tx('Upload complete')
      } else {
        const batchLabel = meta.batchCount ? `Batch ${meta.batchIndex}/${meta.batchCount} · ` : ''
        uploadLabel.value = `${batchLabel}${uploadPercent.value}% · ${(loaded / 1024 / 1024).toFixed(1)} MB / ${(total / 1024 / 1024).toFixed(1)} MB`
      }
    })
    lastImport.value = response
    uploadStatus.value = 'success'
    uploadPercent.value = 100
    uploadLabel.value = tx('Upload complete')
    clearSelectedFiles()
    proxy.$modal.msgSuccess(`Saved ${response.savedFiles} files and matched ${response.updatedSubmissionIds.length} entries`)
    loadQueue()
  } catch (error) {
    uploadStatus.value = 'failed'
    if (error?.importSummary) lastImport.value = error.importSummary
    uploadLabel.value = error?.failedBatch
      ? `Batch ${error.failedBatch}/${error.totalBatches} failed after ${error.failedAttempt}/${error.maxAttempts}; ${error.completedBatches} completed`
      : tx('Upload stopped at ') + uploadPercent.value + '%'
    proxy.$modal.msgError(error?.message || tx('Folder import failed'))
  } finally {
    importing.value = false
  }
}

function publishEntry(submissionId) {
  publishLoadingId.value = submissionId
  publishSubmissionMedia(submissionId)
    .then((res) => {
      proxy.$modal.msgSuccess(tx('Published ') + res.data.certId)
      loadQueue()
    })
    .finally(() => {
      publishLoadingId.value = null
    })
}

async function publishSelected() {
  if (!selectedReadyIds.value.length) return
  try {
    await proxy.$modal.confirm(`Publish the ${selectedReadyIds.value.length} selected cards?`)
  } catch {
    return
  }

  batchPublishing.value = true
  try {
    const response = await publishSubmissionMediaBatch(selectedReadyIds.value)
    const result = response.data
    if (result.failedCount) {
      const details = result.failures.slice(0, 5).map((item) => `${item.submissionId}: ${item.message}`).join('; ')
      const suffix = result.failedCount > 5 ? tx('; refresh and retry the remaining failures') : ''
      proxy.$modal.msgWarning(`${result.publishedCount} published, ${result.failedCount} failed. ${details}${suffix}`)
    } else {
      proxy.$modal.msgSuccess(`${result.publishedCount} cards published`)
    }
    await loadQueue()
  } catch (error) {
    proxy.$modal.msgError(error?.message || tx('Batch publication failed'))
  } finally {
    batchPublishing.value = false
  }
}

function mediaStateLabel(item) {
  if (item.hasPublishedFront && item.hasPublishedBack) {
    return item.readyToPublish ? tx('Update Ready') : tx('Live')
  }
  if (item.readyToPublish) return tx('Ready')
  if (item.hasStagedFront || item.hasStagedBack) return tx('One Side Missing')
  return tx('Images Missing')
}

function uploadStateLabel(status) {
  const labels = {
    not_started: 'Not Started',
    uploading: 'Uploading',
    uploaded: 'Uploaded',
    failed: 'Failed',
    client_pushed: 'Client Pushed'
  }
  return tx(labels[status] || 'Not Started')
}

function uploadStateTag(status) {
  if (status === 'uploaded' || status === 'client_pushed') return 'success'
  if (status === 'uploading') return 'warning'
  if (status === 'failed') return 'danger'
  return 'info'
}

async function markClientPushed(row) {
  try {
    await proxy.$modal.confirm(tx('Mark this card as pushed by the client?'))
  } catch {
    return
  }
  clientPushLoadingId.value = row.submissionId
  try {
    await markSubmissionClientPushed(row.submissionId)
    proxy.$modal.msgSuccess(tx('Marked as Client Pushed'))
    await loadQueue()
  } finally {
    clientPushLoadingId.value = null
  }
}

function queueResult(item) {
  if (item.productType === 'merch_product' || item.productType === 'label_product') {
    return item.merchDescription || tx('Merch Product')
  }
  if (item.productType === 'vintage_product') {
    return item.vintageClassification || tx('Vintage Card')
  }
  const values = [item.finalGradeValue, item.finalGradeLabel].filter(
    (value) => value !== null && value !== undefined && String(value).trim() !== ''
  )
  return values.length ? values.join(' · ') : '-'
}

function mediaStateTag(item) {
  if (item.hasPublishedFront && item.hasPublishedBack) return 'success'
  if (item.readyToPublish) return 'warning'
  return 'info'
}

loadQueue()
</script>

<style scoped>
.hidden-input {
  display: none;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.picker-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.picker-meta {
  color: var(--nxr-text-faint);
  font-size: 13px;
}

.upload-label {
  margin-top: 6px;
  color: var(--nxr-text-faint);
  font-size: 13px;
}

.mt8 {
  margin-top: 8px;
}

.thumb-row {
  display: flex;
  gap: 6px;
  justify-content: center;
}

.stat-card {
  cursor: pointer;
}

.summary-strip {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.summary-action {
  cursor: pointer;
}

.queue-filters {
  align-items: flex-end;
}

.missing-ref {
  max-width: 72px;
  color: var(--el-color-danger);
  font-size: 11px;
  line-height: 1.2;
}

.thumb {
  width: 56px;
  height: 56px;
  border-radius: 6px;
}

.muted {
  color: var(--nxr-text-placeholder);
}
</style>
