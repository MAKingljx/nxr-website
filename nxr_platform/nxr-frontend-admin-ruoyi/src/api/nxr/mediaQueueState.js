const STAGED_VERSION = /^[a-f0-9]{64}$/

export function canPublishMediaRow(row, busy = false) {
  return !busy && row?.readyToPublish === true && row.hasStagedFront === true && row.hasStagedBack === true
    && !row.stagedFrontMissing && !row.stagedBackMissing
    && row.uploadStatus !== 'uploading' && row.uploadStatus !== 'client_pushed'
    && STAGED_VERSION.test(row.stagedVersion || '')
}

export function canMarkMediaClientPushed(row, busy = false) {
  return !busy && row?.uploadStatus === 'uploaded'
}

export function mediaUploadPresentation(row) {
  const status = row?.uploadStatus
  // Lease expiry is projected by the server. Browser clocks never release a live upload.
  if (status === 'failed' && row.uploadInterrupted === true) {
    return { label: canPublishMediaRow(row) ? 'Interrupted, retry available' : 'Upload interrupted', type: 'warning' }
  }
  const states = {
    not_started: { label: 'Not Started', type: 'info' },
    uploading: { label: 'Uploading', type: 'warning' },
    uploaded: { label: 'Uploaded', type: 'success' },
    failed: { label: 'Failed', type: 'danger' },
    client_pushed: { label: 'Client Pushed', type: 'success' }
  }
  return states[status] || states.not_started
}

export function createMediaClientPushAction({ rowForId, isBusy, setBusy, confirm, submit, refresh, onSuccess }) {
  let pending = false
  return async submissionId => {
    if (pending || isBusy() || !canMarkMediaClientPushed(rowForId(submissionId))) return false
    pending = true
    setBusy(submissionId)
    try {
      try { await confirm() } catch { return false }
      if (!canMarkMediaClientPushed(rowForId(submissionId))) return false
      try {
        await submit(submissionId)
        onSuccess?.()
        return true
      } finally {
        // A lost response can still mean success. Reload instead of guessing or retrying the POST.
        await refresh()
      }
    } finally {
      pending = false
      setBusy(null)
    }
  }
}
