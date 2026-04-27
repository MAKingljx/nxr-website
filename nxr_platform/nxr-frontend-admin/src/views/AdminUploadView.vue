<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AdminLayoutShell from '../components/AdminLayoutShell.vue'
import {
  fetchMediaQueue,
  importMediaFolder,
  publishSubmissionMedia,
  type MediaImportResponse,
  type MediaQueueItem,
  type MediaQueueSummary,
} from '../lib/api'

const searchQuery = ref('')
const queue = ref<MediaQueueItem[]>([])
const summary = ref<MediaQueueSummary>({
  trackedEntries: 0,
  readyToPublish: 0,
  livePublished: 0,
  missingMedia: 0,
})
const selectedFiles = ref<File[]>([])
const selectedFolderName = ref('No folder selected')
const isLoading = ref(false)
const isImporting = ref(false)
const publishLoadingId = ref<number | null>(null)
const uploadPercent = ref(0)
const uploadTransferredLabel = ref('Waiting to upload')
const uploadStatus = ref<'idle' | 'uploading' | 'success' | 'failed'>('idle')
const uploadFailureReason = ref('')
const lastImport = ref<MediaImportResponse | null>(null)
const folderInput = ref<HTMLInputElement | null>(null)

const fileCountLabel = computed(() => {
  if (!selectedFiles.value.length) {
    return 'No image files selected'
  }

  return `${selectedFiles.value.length} file${selectedFiles.value.length === 1 ? '' : 's'} ready`
})

async function loadQueue() {
  isLoading.value = true

  try {
    const response = await fetchMediaQueue(searchQuery.value || undefined)
    queue.value = response.items
    summary.value = response.summary
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Failed to load media queue')
  } finally {
    isLoading.value = false
  }
}

function openFolderPicker() {
  folderInput.value?.click()
}

function handleFolderChange(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  selectedFiles.value = files

  if (!files.length) {
    selectedFolderName.value = 'No folder selected'
    return
  }

  const firstRelativePath = (files[0] as File & { webkitRelativePath?: string }).webkitRelativePath ?? files[0].name
  selectedFolderName.value = firstRelativePath.includes('/') ? firstRelativePath.split('/')[0] : 'Selected folder'
}

async function submitImport() {
  if (!selectedFiles.value.length) {
    uploadStatus.value = 'failed'
    uploadFailureReason.value = 'Choose a folder first.'
    uploadTransferredLabel.value = 'No folder selected'
    ElMessage.warning('Choose a folder first')
    return
  }

  isImporting.value = true
  uploadPercent.value = 0
  uploadStatus.value = 'uploading'
  uploadFailureReason.value = ''
  uploadTransferredLabel.value = 'Preparing upload'

  try {
    const response = await importMediaFolder(selectedFiles.value, (percent, loaded, total) => {
      uploadPercent.value = percent
      uploadTransferredLabel.value = `${percent}% · ${(loaded / 1024 / 1024).toFixed(1)} MB / ${(total / 1024 / 1024).toFixed(1)} MB`
    })

    lastImport.value = response
    uploadPercent.value = 100
    uploadStatus.value = 'success'
    uploadTransferredLabel.value = 'Folder import completed'
    ElMessage.success(`Imported ${response.savedFiles} files across ${response.updatedSubmissionIds.length} entries`)
    await loadQueue()
  } catch (error) {
    uploadStatus.value = 'failed'
    uploadFailureReason.value = error instanceof Error ? error.message : 'Folder import failed'
    uploadTransferredLabel.value = `Upload stopped at ${uploadPercent.value}%`
    ElMessage.error(uploadFailureReason.value)
  } finally {
    isImporting.value = false
  }
}

async function publishEntry(submissionId: number) {
  publishLoadingId.value = submissionId

  try {
    const response = await publishSubmissionMedia(submissionId)
    ElMessage.success(`Published ${response.certId}`)
    await loadQueue()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Publish failed')
  } finally {
    publishLoadingId.value = null
  }
}

function mediaStateLabel(item: MediaQueueItem) {
  if (item.hasPublishedFront && item.hasPublishedBack) {
    return item.readyToPublish ? 'Live + staged update ready' : 'Live'
  }

  if (item.readyToPublish) {
    return 'Ready to publish'
  }

  if (item.hasStagedFront || item.hasStagedBack) {
    return 'Partial staged'
  }

  return 'Missing media'
}

const statusTitle = computed(() => {
  switch (uploadStatus.value) {
    case 'uploading':
      return 'Uploading folder'
    case 'success':
      return 'Last upload completed'
    case 'failed':
      return 'Last upload failed'
    default:
      return 'Upload status'
  }
})

onMounted(() => {
  void loadQueue()
})
</script>

<template>
  <AdminLayoutShell
    section-label="Upload Module"
    title="Folder import and media publish"
    description="This workspace mirrors the legacy cert-ID image import flow, but keeps staging and publish separated so live media is only replaced after explicit confirmation."
  >
    <section class="summary-grid">
      <article>
        <span>Tracked entries</span>
        <strong>{{ summary.trackedEntries }}</strong>
      </article>
      <article>
        <span>Ready to publish</span>
        <strong>{{ summary.readyToPublish }}</strong>
      </article>
      <article>
        <span>Live published</span>
        <strong>{{ summary.livePublished }}</strong>
      </article>
      <article>
        <span>Missing media</span>
        <strong>{{ summary.missingMedia }}</strong>
      </article>
    </section>

    <section class="workspace-grid">
      <article class="import-panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">Folder Import</p>
            <h3>Choose a directory of images</h3>
          </div>
          <button type="button" class="ghost-button" @click="loadQueue">Refresh queue</button>
        </div>

        <p class="panel-copy">
          Filenames must follow exact cert-ID matching with side markers, such as
          <code>NXR2026042602_A.jpg</code> or <code>VRA003_B_1.webp</code>. `A` maps to front and `B` maps to back.
          Unmatched files are skipped. Re-import replaces staged media for the same side and does not touch live published images until publish runs.
        </p>

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
          <button type="button" class="primary-button" @click="openFolderPicker">Choose Folder</button>
          <div class="picker-meta">
            <strong>{{ selectedFolderName }}</strong>
            <span>{{ fileCountLabel }}</span>
          </div>
        </div>

        <div class="progress-shell">
          <div class="progress-copy">
            <span>Upload progress</span>
            <strong>{{ uploadTransferredLabel }}</strong>
          </div>
          <div class="progress-track">
            <div class="progress-bar" :class="`status-${uploadStatus}`" :style="{ width: `${uploadPercent}%` }"></div>
          </div>
        </div>

        <div class="status-shell" :class="`status-${uploadStatus}`">
          <span>{{ statusTitle }}</span>
          <strong v-if="uploadStatus === 'failed'">{{ uploadFailureReason }}</strong>
          <strong v-else-if="uploadStatus === 'success'">Imported {{ lastImport?.savedFiles ?? 0 }} files successfully.</strong>
          <strong v-else-if="uploadStatus === 'uploading'">The progress bar remains visible until the server returns a success or failure result.</strong>
          <strong v-else>Waiting for the next folder import.</strong>
        </div>

        <button type="button" class="primary-button submit-button" :disabled="isImporting" @click="submitImport">
          {{ isImporting ? 'Importing folder...' : 'Import Folder to Staging' }}
        </button>

        <div v-if="lastImport" class="import-result">
          <h4>Last import result</h4>
          <div class="result-grid">
            <article>
              <span>Matched entries</span>
              <strong>{{ lastImport.matchedEntries }}</strong>
            </article>
            <article>
              <span>Saved files</span>
              <strong>{{ lastImport.savedFiles }}</strong>
            </article>
            <article>
              <span>Updated sides</span>
              <strong>{{ lastImport.updatedSides }}</strong>
            </article>
          </div>
          <p class="result-copy">
            Missing exact matches: {{ lastImport.missingCertIds.length }} · Invalid names: {{ lastImport.invalidNames.length }} ·
            Duplicate names: {{ lastImport.duplicateNames.length }}
          </p>
        </div>
      </article>

      <article class="queue-panel">
        <div class="panel-head">
          <div>
            <p class="eyebrow">Publish Queue</p>
            <h3>Approved and published entries</h3>
          </div>
        </div>

        <div class="search-row">
          <input v-model="searchQuery" type="text" placeholder="Search cert ID, card name, set" @keyup.enter="loadQueue" />
          <button type="button" class="ghost-button" @click="loadQueue">Search</button>
        </div>

        <div v-if="queue.length" class="queue-list">
          <article v-for="item in queue" :key="item.submissionId" class="queue-card">
            <div class="queue-head">
              <div>
                <span class="status-kicker">{{ item.statusCode }}</span>
                <h4>{{ item.certId }}</h4>
                <p>{{ item.cardName }}</p>
              </div>
              <div class="grade-pill">{{ item.finalGradeValue }} · {{ item.finalGradeLabel }}</div>
            </div>

            <div class="state-row">
              <strong>{{ mediaStateLabel(item) }}</strong>
              <span>
                staged {{ item.hasStagedFront ? 'F' : '-' }}/{{ item.hasStagedBack ? 'B' : '-' }}
                · live {{ item.hasPublishedFront ? 'F' : '-' }}/{{ item.hasPublishedBack ? 'B' : '-' }}
              </span>
            </div>

            <div class="preview-grid">
              <div>
                <span>Staged Front</span>
                <img v-if="item.stagedFrontUrl" :src="item.stagedFrontUrl" alt="Staged front" />
                <div v-else class="preview-empty">No image</div>
              </div>
              <div>
                <span>Staged Back</span>
                <img v-if="item.stagedBackUrl" :src="item.stagedBackUrl" alt="Staged back" />
                <div v-else class="preview-empty">No image</div>
              </div>
              <div>
                <span>Live Front</span>
                <img v-if="item.publishedFrontUrl" :src="item.publishedFrontUrl" alt="Published front" />
                <div v-else class="preview-empty">No image</div>
              </div>
              <div>
                <span>Live Back</span>
                <img v-if="item.publishedBackUrl" :src="item.publishedBackUrl" alt="Published back" />
                <div v-else class="preview-empty">No image</div>
              </div>
            </div>

            <div class="queue-actions">
              <button
                type="button"
                class="primary-button"
                :disabled="!item.readyToPublish || publishLoadingId === item.submissionId"
                @click="publishEntry(item.submissionId)"
              >
                {{
                  publishLoadingId === item.submissionId
                    ? 'Publishing...'
                    : item.hasPublishedFront && item.hasPublishedBack
                      ? 'Republish Live Media'
                      : 'Publish Media'
                }}
              </button>
            </div>
          </article>
        </div>

        <p v-else-if="!isLoading" class="empty-state">No approved or published entries matched the current filter.</p>
      </article>
    </section>
  </AdminLayoutShell>
</template>

<style scoped>
.summary-grid,
.workspace-grid {
  display: grid;
  gap: 18px;
}

.summary-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 18px;
}

.summary-grid article,
.import-panel,
.queue-panel,
.queue-card,
.result-grid article {
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 18px 42px rgba(20, 32, 51, 0.08);
}

.summary-grid article {
  padding: 20px 22px;
}

.summary-grid span,
.state-row span,
.preview-grid span,
.result-copy,
.panel-copy,
.picker-meta span {
  color: #607089;
}

.summary-grid strong {
  display: block;
  margin-top: 10px;
  font-size: 1.5rem;
}

.workspace-grid {
  grid-template-columns: minmax(360px, 420px) 1fr;
  align-items: start;
}

.import-panel,
.queue-panel {
  padding: 22px;
}

.panel-head,
.picker-row,
.progress-copy,
.search-row,
.queue-head,
.state-row,
.queue-actions {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
}

.panel-head h3,
.queue-head h4,
.import-result h4 {
  margin: 0;
}

.panel-copy {
  margin: 16px 0 0;
  line-height: 1.6;
}

.hidden-input {
  display: none;
}

.picker-row {
  margin-top: 20px;
  padding: 18px;
  border-radius: 20px;
  background: #eef4ff;
}

.picker-meta strong,
.picker-meta span {
  display: block;
}

.progress-shell {
  margin-top: 18px;
  padding: 18px;
  border-radius: 20px;
  background: #f6f8fc;
}

.progress-copy strong {
  color: #102443;
}

.progress-track {
  margin-top: 14px;
  height: 12px;
  border-radius: 999px;
  overflow: hidden;
  background: rgba(16, 36, 67, 0.08);
}

.progress-bar {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #1a56db 0%, #0c8f6d 100%);
  transition: width 0.2s ease;
}

.progress-bar.status-failed {
  background: linear-gradient(90deg, #b42318 0%, #f04438 100%);
}

.progress-bar.status-idle {
  background: linear-gradient(90deg, #9aa7b8 0%, #c2ccd7 100%);
}

.status-shell {
  display: grid;
  gap: 8px;
  margin-top: 14px;
  padding: 16px 18px;
  border-radius: 18px;
  border: 1px solid rgba(16, 36, 67, 0.08);
  background: #f6f8fc;
}

.status-shell span {
  color: #607089;
  font-size: 0.82rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.status-shell strong {
  color: #102443;
  line-height: 1.5;
}

.status-shell.status-failed {
  border-color: rgba(176, 35, 24, 0.24);
  background: #fff2f0;
}

.status-shell.status-failed strong {
  color: #912018;
}

.status-shell.status-success {
  border-color: rgba(12, 143, 109, 0.2);
  background: #effcf6;
}

.submit-button {
  width: 100%;
  margin-top: 16px;
}

.import-result {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid rgba(16, 36, 67, 0.08);
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 14px;
}

.result-grid article {
  padding: 16px;
}

.result-grid span,
.result-grid strong {
  display: block;
}

.result-grid strong {
  margin-top: 8px;
}

.search-row {
  margin-top: 14px;
}

input,
button,
code {
  font: inherit;
}

input,
.primary-button,
.ghost-button {
  height: 46px;
  border-radius: 14px;
}

input {
  flex: 1;
  min-width: 0;
  padding: 0 14px;
  border: 1px solid rgba(20, 32, 51, 0.12);
}

.primary-button,
.ghost-button {
  padding: 0 18px;
  border: 1px solid rgba(20, 32, 51, 0.12);
  cursor: pointer;
}

.primary-button {
  background: #143f9f;
  color: #fff;
}

.primary-button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.ghost-button {
  background: #fff;
  color: #102443;
}

.queue-list {
  display: grid;
  gap: 16px;
  margin-top: 16px;
}

.queue-card {
  padding: 18px;
}

.queue-head h4 {
  font-size: 1.1rem;
}

.queue-head p,
.status-kicker,
.state-row,
.preview-empty,
.empty-state {
  margin: 0;
}

.status-kicker {
  display: inline-block;
  margin-bottom: 8px;
  font-size: 0.74rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: #607089;
}

.grade-pill {
  padding: 10px 14px;
  border-radius: 999px;
  background: #edf4ff;
  color: #143f9f;
  font-weight: 700;
}

.state-row {
  margin-top: 16px;
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.preview-grid > div {
  display: grid;
  gap: 8px;
}

.preview-grid img,
.preview-empty {
  width: 100%;
  aspect-ratio: 0.74;
  border-radius: 16px;
}

.preview-grid img {
  object-fit: cover;
  background: #edf4ff;
}

.preview-empty {
  display: grid;
  place-items: center;
  background: #f6f8fc;
}

.queue-actions {
  margin-top: 16px;
}

.empty-state {
  margin-top: 20px;
  color: #607089;
}

code {
  padding: 2px 6px;
  border-radius: 8px;
  background: #edf4ff;
}

@media (max-width: 1180px) {
  .workspace-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 960px) {
  .summary-grid,
  .result-grid,
  .preview-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 640px) {
  .summary-grid,
  .result-grid,
  .preview-grid {
    grid-template-columns: 1fr;
  }

  .panel-head,
  .picker-row,
  .progress-copy,
  .search-row,
  .queue-head,
  .state-row,
  .queue-actions {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
