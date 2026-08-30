<template>
  <main class="nxr-workspace nxr-upload-workspace">
    <nxr-page-header
      :kicker="$tx('MEDIA OPERATIONS')"
      :title="$tx('Card Image Upload & Publication')"
      :summary="$tx('Import front/back images for approved cards and review publication status')"
    />

    <el-row :gutter="16" class="mb8">
      <el-col :span="6">
        <el-card shadow="never"><el-statistic :title="$tx('Tracked Entries')" :value="summary.trackedEntries" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never"><el-statistic :title="$tx('Ready to Publish')" :value="summary.readyToPublish" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never"><el-statistic :title="$tx('Published')" :value="summary.livePublished" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never"><el-statistic :title="$tx('Missing Images')" :value="summary.missingMedia" /></el-card>
      </el-col>
    </el-row>

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

      <el-alert v-if="lastImport" type="success" :closable="false" class="mt8">
        <div>{{ $tx('Saved') }} {{ lastImport.savedFiles }} {{ $tx('files, updated') }} {{ lastImport.updatedSides }} {{ $tx('sides, and matched') }} {{ lastImport.matchedEntries }} {{ $tx('entries.') }}</div>
        <div v-if="lastImport.missingCertIds.length">{{ $tx('Cert IDs not found:') }} {{ lastImport.missingCertIds.join(', ') }}</div>
        <div v-if="lastImport.invalidNames.length">{{ $tx('Invalid filenames:') }} {{ lastImport.invalidNames.join(', ') }}</div>
        <div v-if="lastImport.duplicateNames.length">{{ $tx('Duplicate files:') }} {{ lastImport.duplicateNames.join(', ') }}</div>
      </el-alert>
    </el-card>

    <el-form :inline="true" @submit.prevent>
      <el-form-item :label="$tx('Keyword')">
        <el-input
          v-model="searchQuery"
          :placeholder="$tx('Cert ID / Card Name')"
          clearable
          style="width: 240px"
          @keyup.enter="loadQueue(true)"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="loadQueue(true)">{{ $tx('Search') }}</el-button>
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
            <el-image v-if="scope.row.stagedFrontUrl" :src="scope.row.stagedFrontUrl" :preview-src-list="[scope.row.stagedFrontUrl]" fit="cover" class="thumb" preview-teleported />
            <el-image v-if="scope.row.stagedBackUrl" :src="scope.row.stagedBackUrl" :preview-src-list="[scope.row.stagedBackUrl]" fit="cover" class="thumb" preview-teleported />
            <span v-if="!scope.row.stagedFrontUrl && !scope.row.stagedBackUrl" class="muted">-</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Published Images')" width="160" align="center">
        <template #default="scope">
          <div class="thumb-row">
            <el-image v-if="scope.row.publishedFrontUrl" :src="scope.row.publishedFrontUrl" :preview-src-list="[scope.row.publishedFrontUrl]" fit="cover" class="thumb" preview-teleported />
            <el-image v-if="scope.row.publishedBackUrl" :src="scope.row.publishedBackUrl" :preview-src-list="[scope.row.publishedBackUrl]" fit="cover" class="thumb" preview-teleported />
            <span v-if="!scope.row.publishedFrontUrl && !scope.row.publishedBackUrl" class="muted">-</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Media Status')" width="120" align="center">
        <template #default="scope">
          <el-tag :type="mediaStateTag(scope.row)">{{ mediaStateLabel(scope.row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Actions')" width="130" align="center">
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
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="summary.trackedEntries > 0"
      :total="summary.trackedEntries"
      v-model:page="queuePage"
      v-model:limit="queuePageSize"
      @pagination="loadQueue()"
    />
  </main>
</template>

<script setup name="NxrUpload">
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import { fetchMediaQueue, importMediaFolder, publishSubmissionMedia, publishSubmissionMediaBatch } from '@/api/nxr/media'

const { proxy } = getCurrentInstance()
const allowedImagePattern = /\.(webp|png|jpe?g)$/i

const queue = ref([])
const summary = ref({ trackedEntries: 0, readyToPublish: 0, livePublished: 0, missingMedia: 0 })
const queuePage = ref(1)
const queuePageSize = ref(12)
const searchQuery = ref('')
const loading = ref(false)
const importing = ref(false)
const publishLoadingId = ref(null)
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

function loadQueue(resetPage = false) {
  if (resetPage) queuePage.value = 1
  loading.value = true
  return fetchMediaQueue({
    query: searchQuery.value.trim() || undefined,
    page: queuePage.value,
    pageSize: queuePageSize.value
  })
    .then((res) => {
      queue.value = res.data.items
      selectedReadyIds.value = []
      summary.value = res.data.summary
      queuePage.value = res.data.page
      queuePageSize.value = res.data.pageSize
    })
    .finally(() => {
      loading.value = false
    })
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
    const response = await importMediaFolder(selectedFiles.value, (percent, loaded, total) => {
      uploadPercent.value = percent
      uploadLabel.value = `${percent}% · ${(loaded / 1024 / 1024).toFixed(1)} MB / ${(total / 1024 / 1024).toFixed(1)} MB`
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
    uploadLabel.value = tx('Upload stopped at ') + uploadPercent.value + '%'
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

.thumb {
  width: 56px;
  height: 56px;
  border-radius: 6px;
}

.muted {
  color: var(--nxr-text-placeholder);
}
</style>
