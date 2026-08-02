<template>
  <div class="app-container">
    <el-row :gutter="16" class="mb8">
      <el-col :span="6">
        <el-card shadow="never"><el-statistic title="跟踪条目" :value="summary.trackedEntries" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never"><el-statistic title="可发布" :value="summary.readyToPublish" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never"><el-statistic title="已发布" :value="summary.livePublished" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never"><el-statistic title="缺图" :value="summary.missingMedia" /></el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mb8" v-hasPermi="['nxr:media:import']">
      <template #header>
        <div class="card-header">
          <span>文件夹导入（文件名约定：证书编号_A 为正面，证书编号_B 为背面，支持 webp/jpg/jpeg/png）</span>
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
        <el-button type="primary" icon="FolderOpened" @click="openFolderPicker">选择文件夹</el-button>
        <el-button :disabled="!selectedFiles.length || importing" @click="clearSelectedFiles">清空</el-button>
        <el-button
          type="success"
          icon="Upload"
          :loading="importing"
          :disabled="!selectedFiles.length"
          @click="submitImport"
        >开始导入</el-button>
        <span class="picker-meta">
          {{ selectedFolderName || (selectedFiles.length ? '已选择文件' : '未选择文件夹') }}
          · {{ selectedFiles.length }} 个图片文件
          <template v-if="skippedFileCount">（已跳过 {{ skippedFileCount }} 个非图片文件）</template>
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
        <div>已保存 {{ lastImport.savedFiles }} 个文件，更新 {{ lastImport.updatedSides }} 个面，匹配 {{ lastImport.matchedEntries }} 条录入。</div>
        <div v-if="lastImport.missingCertIds.length">未找到的证书编号：{{ lastImport.missingCertIds.join(', ') }}</div>
        <div v-if="lastImport.invalidNames.length">命名不合规：{{ lastImport.invalidNames.join(', ') }}</div>
        <div v-if="lastImport.duplicateNames.length">重复文件：{{ lastImport.duplicateNames.join(', ') }}</div>
      </el-alert>
    </el-card>

    <el-form :inline="true" @submit.prevent>
      <el-form-item label="关键词">
        <el-input
          v-model="searchQuery"
          placeholder="证书编号 / 卡名"
          clearable
          style="width: 240px"
          @keyup.enter="loadQueue(true)"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="loadQueue(true)">搜索</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="queue">
      <el-table-column label="证书编号" prop="certId" width="140" />
      <el-table-column label="卡名" prop="cardName" min-width="160" show-overflow-tooltip />
      <el-table-column label="评级" width="150">
        <template #default="scope">{{ scope.row.finalGradeValue }} · {{ scope.row.finalGradeLabel }}</template>
      </el-table-column>
      <el-table-column label="待发布图" width="160" align="center">
        <template #default="scope">
          <div class="thumb-row">
            <el-image v-if="scope.row.stagedFrontUrl" :src="scope.row.stagedFrontUrl" :preview-src-list="[scope.row.stagedFrontUrl]" fit="cover" class="thumb" preview-teleported />
            <el-image v-if="scope.row.stagedBackUrl" :src="scope.row.stagedBackUrl" :preview-src-list="[scope.row.stagedBackUrl]" fit="cover" class="thumb" preview-teleported />
            <span v-if="!scope.row.stagedFrontUrl && !scope.row.stagedBackUrl" class="muted">-</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="已发布图" width="160" align="center">
        <template #default="scope">
          <div class="thumb-row">
            <el-image v-if="scope.row.publishedFrontUrl" :src="scope.row.publishedFrontUrl" :preview-src-list="[scope.row.publishedFrontUrl]" fit="cover" class="thumb" preview-teleported />
            <el-image v-if="scope.row.publishedBackUrl" :src="scope.row.publishedBackUrl" :preview-src-list="[scope.row.publishedBackUrl]" fit="cover" class="thumb" preview-teleported />
            <span v-if="!scope.row.publishedFrontUrl && !scope.row.publishedBackUrl" class="muted">-</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="媒体状态" width="110" align="center">
        <template #default="scope">
          <el-tag :type="mediaStateTag(scope.row)">{{ mediaStateLabel(scope.row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" align="center">
        <template #default="scope">
          <el-button
            link
            type="success"
            icon="Promotion"
            :disabled="!scope.row.readyToPublish"
            :loading="publishLoadingId === scope.row.submissionId"
            v-hasPermi="['nxr:media:publish']"
            @click="publishEntry(scope.row.submissionId)"
          >发布</el-button>
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
  </div>
</template>

<script setup name="NxrUpload">
import { fetchMediaQueue, importMediaFolder, publishSubmissionMedia } from '@/api/nxr/media'

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
  fetchMediaQueue({
    query: searchQuery.value.trim() || undefined,
    page: queuePage.value,
    pageSize: queuePageSize.value
  })
    .then((res) => {
      queue.value = res.data.items
      summary.value = res.data.summary
      queuePage.value = res.data.page
      queuePageSize.value = res.data.pageSize
    })
    .finally(() => {
      loading.value = false
    })
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
    proxy.$modal.msgWarning('请先选择包含图片的文件夹')
    return
  }
  importing.value = true
  uploadStatus.value = 'uploading'
  uploadPercent.value = 0
  uploadLabel.value = '准备上传…'
  try {
    const response = await importMediaFolder(selectedFiles.value, (percent, loaded, total) => {
      uploadPercent.value = percent
      uploadLabel.value = `${percent}% · ${(loaded / 1024 / 1024).toFixed(1)} MB / ${(total / 1024 / 1024).toFixed(1)} MB`
    })
    lastImport.value = response
    uploadStatus.value = 'success'
    uploadPercent.value = 100
    uploadLabel.value = '上传完成'
    clearSelectedFiles()
    proxy.$modal.msgSuccess(`已保存 ${response.savedFiles} 个文件，匹配 ${response.updatedSubmissionIds.length} 条录入`)
    loadQueue()
  } catch (error) {
    uploadStatus.value = 'failed'
    uploadLabel.value = '上传中断于 ' + uploadPercent.value + '%'
    proxy.$modal.msgError(error?.message || '文件夹导入失败')
  } finally {
    importing.value = false
  }
}

function publishEntry(submissionId) {
  publishLoadingId.value = submissionId
  publishSubmissionMedia(submissionId)
    .then((res) => {
      proxy.$modal.msgSuccess('已发布 ' + res.data.certId)
      loadQueue()
    })
    .finally(() => {
      publishLoadingId.value = null
    })
}

function mediaStateLabel(item) {
  if (item.hasPublishedFront && item.hasPublishedBack) {
    return item.readyToPublish ? '可更新' : '已上线'
  }
  if (item.readyToPublish) return '可发布'
  if (item.hasStagedFront || item.hasStagedBack) return '缺一面'
  return '缺图'
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
  color: #909399;
  font-size: 13px;
}

.upload-label {
  margin-top: 6px;
  color: #909399;
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
  color: #c0c4cc;
}
</style>
