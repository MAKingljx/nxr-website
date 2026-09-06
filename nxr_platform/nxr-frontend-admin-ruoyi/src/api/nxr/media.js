import request from '@/utils/request'
import {
  MEDIA_IMPORT_MAX_ATTEMPTS,
  createMediaImportProgress,
  enrichMediaImportError,
  executeMediaImportBatches,
  mergeUniqueMediaImportValues
} from './mediaImportRetry'

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
    suppressErrorMessage: true,
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

// 文件夹导入：分批上传并聚合结果。
// onProgress(percent, loadedBytes, totalBytes, metadata) 的第四个参数为可选重试状态，
// 保持前三个参数与现有调用兼容。
export async function importMediaFolder(files, onProgress) {
  const batches = chunkMediaFiles(files)
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0)
  const progress = createMediaImportProgress(totalBytes)
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

  const progressMetadata = (phase, context = {}) => ({
    phase,
    maxAttempts: MEDIA_IMPORT_MAX_ATTEMPTS,
    completedBatches: context.completedBatches ?? 0,
    successfulBatches: context.completedBatches ?? 0,
    successfulCertificates: aggregate.updatedSubmissionIds.length,
    successfulSubmissionIds: [...aggregate.updatedSubmissionIds],
    ...context
  })

  const reportProgress = (snapshot, metadata) => {
    onProgress?.(
      snapshot.percent,
      snapshot.loadedBytes,
      snapshot.totalBytes,
      metadata
    )
  }

  let failedBatchContext = null
  try {
    await executeMediaImportBatches(
      batches,
      (batch, context) => sendMediaBatch(batch, (batchLoaded) => {
        reportProgress(
          progress.update(completedBytes, batchLoaded),
          progressMetadata('uploading', {
            batchIndex: context.batchIndex,
            batchCount: context.batchCount,
            attempt: context.attempt,
            completedBatches: context.batchIndex - 1
          })
        )
      }),
      {
        onRetry: retry => {
          reportProgress(
            progress.update(completedBytes),
            progressMetadata('retrying', {
              batchIndex: retry.batchIndex,
              batchCount: retry.batchCount,
              attempt: retry.nextAttempt,
              retryCount: retry.retryCount,
              retryDelayMs: retry.delayMs,
              completedBatches: retry.batchIndex - 1
            })
          )
        },
        onFailure: failure => {
          failedBatchContext = failure
        },
        onBatchSuccess: (res, context) => {
          const data = res.data
          completedBytes += context.batch.reduce((sum, file) => sum + file.size, 0)
          aggregate.savedFiles += data.savedFiles
          aggregate.updatedSides += data.updatedSides
          aggregate.missingCertIds = mergeUniqueMediaImportValues(aggregate.missingCertIds, data.missingCertIds)
          aggregate.invalidNames = mergeUniqueMediaImportValues(aggregate.invalidNames, data.invalidNames)
          aggregate.duplicateNames = mergeUniqueMediaImportValues(aggregate.duplicateNames, data.duplicateNames)
          aggregate.updatedSubmissionIds = mergeUniqueMediaImportValues(
            aggregate.updatedSubmissionIds,
            data.updatedSubmissionIds
          )
          aggregate.matchedEntries = aggregate.updatedSubmissionIds.length
          reportProgress(
            progress.update(completedBytes),
            progressMetadata('batch-complete', {
              batchIndex: context.batchIndex,
              batchCount: context.batchCount,
              attempt: null,
              completedBatches: context.completedBatches
            })
          )
        }
      }
    )
  } catch (error) {
    throw enrichMediaImportError(error, aggregate, failedBatchContext, batches.length)
  }

  reportProgress(
    progress.finish(),
    progressMetadata('complete', {
      batchIndex: batches.length,
      batchCount: batches.length,
      attempt: null,
      completedBatches: batches.length
    })
  )

  return aggregate
}
