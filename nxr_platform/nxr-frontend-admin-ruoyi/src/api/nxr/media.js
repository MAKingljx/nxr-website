import request from '@/utils/request'

// 媒体队列
export function fetchMediaQueue(query) {
  return request({
    url: '/api/admin/media/queue',
    method: 'get',
    params: query
  })
}

// 发布单条媒体
export function publishSubmissionMedia(submissionId) {
  return request({
    url: '/api/admin/media/submissions/' + submissionId + '/publish',
    method: 'post',
    timeout: 1000 * 60 * 5
  })
}

// 批量发布媒体；服务端逐条提交并返回每条结果，单条失败不会隐藏其他成功项。
export function publishSubmissionMediaBatch(submissionIds) {
  return request({
    url: '/api/admin/media/batch-publish',
    method: 'post',
    data: { submissionIds },
    timeout: 1000 * 60 * 30
  })
}

export function markSubmissionClientPushed(submissionId) {
  return request({
    url: '/api/admin/media/submissions/' + submissionId + '/client-pushed',
    method: 'post'
  })
}

// 分批上传图片文件（文件名约定 {certId}_A / {certId}_B）
const MAX_BATCH_FILES = 12
const MAX_BATCH_BYTES = 24 * 1024 * 1024

function chunkMediaFiles(files) {
  const batches = []
  let currentBatch = []
  let currentBytes = 0

  for (const file of files) {
    const wouldExceedFileCount = currentBatch.length >= MAX_BATCH_FILES
    const wouldExceedBytes = currentBatch.length > 0 && currentBytes + file.size > MAX_BATCH_BYTES
    if (wouldExceedFileCount || wouldExceedBytes) {
      batches.push(currentBatch)
      currentBatch = []
      currentBytes = 0
    }
    currentBatch.push(file)
    currentBytes += file.size
  }

  if (currentBatch.length) {
    batches.push(currentBatch)
  }

  return batches
}

function mergeUnique(existing, incoming) {
  return Array.from(new Set([...existing, ...(incoming || [])]))
}

function sendMediaBatch(files, onBatchProgress) {
  const formData = new FormData()
  for (const file of files) {
    formData.append('image_files', file)
  }

  return request({
    url: '/api/admin/media/import-folder',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false },
    timeout: 1000 * 60 * 30,
    onUploadProgress: (event) => {
      if (event.total) {
        onBatchProgress?.(event.loaded, event.total)
      }
    }
  })
}

export function importSubmissionMedia(submissionId, files) {
  const formData = new FormData()
  for (const file of files) {
    formData.append('image_files', file)
  }

  return request({
    url: `/api/admin/media/submissions/${submissionId}/staged`,
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false },
    timeout: 1000 * 60 * 10
  })
}

// 文件夹导入：分批上传并聚合结果，onProgress(percent, loadedBytes, totalBytes)
export async function importMediaFolder(files, onProgress) {
  const batches = chunkMediaFiles(files)
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0)
  let completedBytes = 0
  const aggregate = {
    matchedEntries: 0,
    savedFiles: 0,
    updatedSides: 0,
    missingCertIds: [],
    invalidNames: [],
    duplicateNames: [],
    updatedSubmissionIds: []
  }

  for (const batch of batches) {
    const res = await sendMediaBatch(batch, (batchLoaded) => {
      const loaded = Math.min(totalBytes, completedBytes + batchLoaded)
      const percent = totalBytes ? Math.min(100, Math.round((loaded / totalBytes) * 100)) : 0
      onProgress?.(percent, loaded, totalBytes)
    })
    const data = res.data
    completedBytes += batch.reduce((sum, file) => sum + file.size, 0)
    aggregate.matchedEntries += data.matchedEntries
    aggregate.savedFiles += data.savedFiles
    aggregate.updatedSides += data.updatedSides
    aggregate.missingCertIds = mergeUnique(aggregate.missingCertIds, data.missingCertIds)
    aggregate.invalidNames = mergeUnique(aggregate.invalidNames, data.invalidNames)
    aggregate.duplicateNames = mergeUnique(aggregate.duplicateNames, data.duplicateNames)
    aggregate.updatedSubmissionIds = mergeUnique(aggregate.updatedSubmissionIds, data.updatedSubmissionIds)
    onProgress?.(
      Math.min(100, Math.round((completedBytes / Math.max(totalBytes, 1)) * 100)),
      completedBytes,
      totalBytes
    )
  }

  return aggregate
}
