import type { RenameRequest } from './types'

const JOURNAL_VERSION = 2
const MAX_JOURNAL_BYTES = 2 * 1024 * 1024
const MAX_JOURNAL_ENTRIES = 10_000
const JOURNAL_PATTERN = /^\.nxr-rename-([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.json$/i
const IMAGE_PATTERN = /\.(?:jpe?g|png|webp)$/i
const TARGET_PATTERN = /^[A-Za-z0-9]{1,64}_[AB]\.(?:[jJ][pP](?:[eE])?[gG]|[pP][nN][gG]|[wW][eE][bB][pP])$/
const HASH_PATTERN = /^[0-9a-f]{64}$/i

export type JournalState =
  | 'planned'
  | 'copying'
  | 'deleting'
  | 'complete'
  | 'failed'
  | 'restoring'
  | 'restored'

export type JournalEntryState = 'planned' | 'copied' | 'deleted' | 'restored'

export interface OperationJournalEntry extends RenameRequest {
  sourceHash: string
  targetHash?: string
  targetSize?: number
  backupName?: string
  state: JournalEntryState
  updatedAt: string
  error?: string
}

export interface OperationJournal {
  schemaVersion: 1 | typeof JOURNAL_VERSION
  backupDirectory?: string
  name: string
  state: JournalState
  createdAt: string
  updatedAt: string
  entries: OperationJournalEntry[]
  error?: string
}

export interface JournalSummary {
  name: string
  state: string
  createdAt: string
  count: number
}

export class RenameOperationError extends Error {
  readonly journalName?: string

  constructor(message: string, journalName?: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'RenameOperationError'
    this.journalName = journalName
  }
}

type Progress = (message: string) => void

export interface RenameOptions {
  signal?: AbortSignal
  conversionConcurrency?: number
}

function checkCancelled(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('已取消本次改名。', 'AbortError')
}

interface DirectorySnapshot {
  exact: Map<string, FileSystemHandle>
  folded: Map<string, string[]>
}

interface FileSnapshot {
  file: File
  hash: string
}

function now(): string {
  return new Date().toISOString()
}

function progress(callback: Progress | undefined, message: string): void {
  callback?.(message)
}

function chineseError(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function assertSafeName(name: unknown, label: string): asserts name is string {
  if (
    typeof name !== 'string' ||
    !name ||
    name === '.' ||
    name === '..' ||
    name.includes('/') ||
    name.includes('\\') ||
    name.includes('\0') ||
    /[\u0000-\u001f\u007f]/.test(name) ||
    name !== name.trim()
  ) {
    throw new RenameOperationError(`${label}不是安全的当前目录文件名：${String(name)}`)
  }
}

function extension(name: string): string {
  const index = name.lastIndexOf('.')
  return index < 0 ? '' : name.slice(index).toLowerCase()
}

function validateRequestShape(request: RenameRequest, index: number): void {
  const prefix = `第 ${index + 1} 项`
  assertSafeName(request.sourceName, `${prefix}源文件`)
  assertSafeName(request.targetName, `${prefix}目标文件`)
  if (!IMAGE_PATTERN.test(request.sourceName) || !TARGET_PATTERN.test(request.targetName)) {
    throw new RenameOperationError(`${prefix}仅允许图片文件，目标名必须为“证书号_A/B.扩展名”`)
  }
  if (request.outputFormat !== undefined && request.outputFormat !== 'webp-lossless') {
    throw new RenameOperationError(`${prefix}输出格式无效`)
  }
  if (request.outputFormat === 'webp-lossless' && extension(request.targetName) !== '.webp') {
    throw new RenameOperationError(`${prefix}转换目标必须是 WebP 文件`)
  }
  if (!request.outputFormat && extension(request.sourceName) !== extension(request.targetName)) {
    throw new RenameOperationError(`${prefix}目标文件必须保留源图片扩展名`)
  }
  if (!Number.isSafeInteger(request.expectedSize) || request.expectedSize < 0) {
    throw new RenameOperationError(`${prefix}预期文件大小无效`)
  }
  if (!Number.isFinite(request.expectedLastModified) || request.expectedLastModified < 0) {
    throw new RenameOperationError(`${prefix}预期修改时间无效`)
  }
  if (
    request.expectedHash !== undefined &&
    (typeof request.expectedHash !== 'string' || !HASH_PATTERN.test(request.expectedHash))
  ) {
    throw new RenameOperationError(`${prefix}预期 SHA-256 无效`)
  }
}

async function snapshotDirectory(directory: FileSystemDirectoryHandle): Promise<DirectorySnapshot> {
  const exact = new Map<string, FileSystemHandle>()
  const folded = new Map<string, string[]>()
  for await (const [name, handle] of directory.entries()) {
    exact.set(name, handle)
    const key = name.toLocaleLowerCase('en-US')
    const names = folded.get(key) ?? []
    names.push(name)
    folded.set(key, names)
  }
  return { exact, folded }
}

function singleName(snapshot: DirectorySnapshot, name: string): string | undefined {
  const matches = snapshot.folded.get(name.toLocaleLowerCase('en-US')) ?? []
  if (matches.length > 1) {
    throw new RenameOperationError(`目录中存在仅大小写不同的重名文件：${matches.join('、')}`)
  }
  return matches[0]
}

async function getExistingFileHandle(
  directory: FileSystemDirectoryHandle,
  name: string,
): Promise<FileSystemFileHandle | undefined> {
  try {
    return await directory.getFileHandle(name)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotFoundError') return undefined
    throw error
  }
}

async function readFileSnapshot(handle: FileSystemFileHandle): Promise<FileSnapshot> {
  const file = await handle.getFile()
  return { file, hash: await hashFile(file) }
}

function ensureExpectedSource(request: RenameRequest, snapshot: FileSnapshot): void {
  if (
    snapshot.file.size !== request.expectedSize ||
    snapshot.file.lastModified !== request.expectedLastModified
  ) {
    throw new RenameOperationError(`源文件在预览后发生变化：${request.sourceName}`)
  }
  if (request.expectedHash && snapshot.hash !== request.expectedHash.toLowerCase()) {
    throw new RenameOperationError(`源文件哈希与预览不一致：${request.sourceName}`)
  }
}

async function writeBlob(handle: FileSystemFileHandle, blob: Blob): Promise<void> {
  const writable = await handle.createWritable({
    keepExistingData: false,
    mode: 'exclusive',
  } as FileSystemCreateWritableOptions & { mode: 'exclusive' })
  try {
    await writable.write(blob)
    await writable.close()
  } catch (error) {
    try {
      await writable.abort(error)
    } catch {
      // The original error is more useful; abort is best effort.
    }
    throw error
  }
}

async function writeNewFile(
  directory: FileSystemDirectoryHandle,
  name: string,
  blob: Blob,
): Promise<FileSystemFileHandle> {
  const handle = await directory.getFileHandle(name, { create: true })
  const created = await handle.getFile()
  if (created.size !== 0) {
    throw new RenameOperationError(`目标文件已被其他程序写入，拒绝覆盖：${name}`)
  }

  // `exclusive` prevents another File System Access writer, but the API has no
  // atomic exclusive-create primitive. A native process can still race this
  // check, so keepExistingData stays true and we re-check the same empty entry
  // after obtaining the writer, immediately before the first byte is written.
  let writable: FileSystemWritableFileStream | undefined
  try {
    writable = await handle.createWritable({
      keepExistingData: true,
      mode: 'exclusive',
    } as FileSystemCreateWritableOptions & { mode: 'exclusive' })
    const currentHandle = await directory.getFileHandle(name)
    const [sameEntry, current] = await Promise.all([
      handle.isSameEntry(currentHandle),
      handle.getFile(),
    ])
    if (
      !sameEntry ||
      current.size !== 0 ||
      current.lastModified !== created.lastModified
    ) {
      throw new RenameOperationError(`目标文件在写入前发生变化，拒绝覆盖：${name}`)
    }
    await writable.write(blob)
    await writable.close()
    return handle
  } catch (error) {
    try {
      await writable?.abort(error)
    } catch {
      // The original error is more useful; abort is best effort.
    }
    try {
      const currentHandle = await directory.getFileHandle(name)
      const current = await currentHandle.getFile()
      // A failed write can leave our empty placeholder. Remove only that exact,
      // still-untouched entry; keep any later external changes for inspection.
      if (await handle.isSameEntry(currentHandle)
        && current.size === 0 && current.lastModified === created.lastModified) {
        await directory.removeEntry(name)
      }
    } catch {
      // Preserve the original failure if a concurrent change prevents cleanup.
    }
    throw error
  }
}

async function writeJournalToHandle(
  handle: FileSystemFileHandle,
  journal: OperationJournal,
): Promise<void> {
  const serialized = `${JSON.stringify(journal, null, 2)}\n`
  if (new Blob([serialized]).size > MAX_JOURNAL_BYTES) {
    throw new RenameOperationError(`操作日志超过 2MB 限制：${journal.name}`, journal.name)
  }
  await writeBlob(handle, new Blob([serialized], { type: 'application/json' }))
  const writtenFile = await handle.getFile()
  if (writtenFile.size > MAX_JOURNAL_BYTES) {
    throw new RenameOperationError(`操作日志写入后超过 2MB 限制：${journal.name}`, journal.name)
  }
  const written = await writtenFile.text()
  if (written !== serialized) {
    throw new RenameOperationError(`操作日志写入后全文校验失败：${journal.name}`, journal.name)
  }
  parseJournal(written, journal.name)
}

async function writeJournal(
  directory: FileSystemDirectoryHandle,
  journal: OperationJournal,
): Promise<void> {
  journal.updatedAt = now()
  const handle = await directory.getFileHandle(journal.name, { create: true })
  await writeJournalToHandle(handle, journal)
}

async function writeInitialJournal(
  directory: FileSystemDirectoryHandle,
  journal: OperationJournal,
): Promise<void> {
  const before = await snapshotDirectory(directory)
  if (singleName(before, journal.name) !== undefined) {
    throw new RenameOperationError(`随机操作日志名称已存在，拒绝覆盖：${journal.name}`, journal.name)
  }
  const handle = await directory.getFileHandle(journal.name, { create: true })
  const created = await handle.getFile()
  if (created.size !== 0) {
    throw new RenameOperationError(`随机操作日志名称被占用，拒绝覆盖：${journal.name}`, journal.name)
  }
  await writeJournalToHandle(handle, journal)
}

function createJournal(entries: OperationJournalEntry[]): OperationJournal {
  const createdAt = now()
  const name = `.nxr-rename-${crypto.randomUUID()}.json`
  return {
    schemaVersion: entries.some((entry) => entry.outputFormat) ? JOURNAL_VERSION : 1,
    ...(entries.some((entry) => entry.outputFormat)
      ? { backupDirectory: backupDirectoryName(name) } : {}),
    name,
    state: 'planned',
    createdAt,
    updatedAt: createdAt,
    entries,
  }
}

function backupDirectoryName(journalName: string): string {
  return journalName.replace('.nxr-rename-', '.nxr-originals-').replace(/\.json$/, '')
}

function expectedTarget(entry: OperationJournalEntry): { hash: string; size: number } {
  if (!entry.outputFormat) return { hash: entry.sourceHash, size: entry.expectedSize }
  if (!entry.targetHash || entry.targetSize === undefined) {
    throw new RenameOperationError(`该项尚未生成可核验的新图片：${entry.targetName}`)
  }
  return { hash: entry.targetHash, size: entry.targetSize }
}

function matches(snapshot: FileSnapshot, expected: { hash: string; size: number }): boolean {
  return snapshot.hash === expected.hash && snapshot.file.size === expected.size
}

async function originalBackup(
  directory: FileSystemDirectoryHandle,
  journal: OperationJournal,
  entry: OperationJournalEntry,
): Promise<FileSnapshot> {
  const backup = await directory.getDirectoryHandle(journal.backupDirectory!)
  const snapshot = await readFileSnapshot(await backup.getFileHandle(entry.backupName!))
  if (!matches(snapshot, { hash: entry.sourceHash, size: entry.expectedSize })) {
    throw new RenameOperationError(`原图备份校验失败：${entry.sourceName}`)
  }
  return snapshot
}

function isJournalState(value: unknown): value is JournalState {
  return ['planned', 'copying', 'deleting', 'complete', 'failed', 'restoring', 'restored'].includes(
    String(value),
  )
}

function isEntryState(value: unknown): value is JournalEntryState {
  return ['planned', 'copied', 'deleted', 'restored'].includes(String(value))
}

function parseJournal(text: string, expectedName: string): OperationJournal {
  if (!JOURNAL_PATTERN.test(expectedName)) {
    throw new RenameOperationError(`操作日志文件名无效：${expectedName}`)
  }
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch (error) {
    throw new RenameOperationError(`操作日志不是有效 JSON：${expectedName}`, expectedName, {
      cause: error,
    })
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new RenameOperationError(`操作日志结构无效：${expectedName}`, expectedName)
  }
  const candidate = value as Partial<OperationJournal>
  if (
    ![1, JOURNAL_VERSION].includes(candidate.schemaVersion as number) ||
    candidate.name !== expectedName ||
    !isJournalState(candidate.state) ||
    typeof candidate.createdAt !== 'string' ||
    !Number.isFinite(Date.parse(candidate.createdAt)) ||
    typeof candidate.updatedAt !== 'string' ||
    !Number.isFinite(Date.parse(candidate.updatedAt)) ||
    !Array.isArray(candidate.entries) ||
    candidate.entries.length === 0 ||
    candidate.entries.length > MAX_JOURNAL_ENTRIES
  ) {
    throw new RenameOperationError(`操作日志结构或版本无效：${expectedName}`, expectedName)
  }

  const sourceNames = new Set<string>()
  const targetNames = new Set<string>()
  if (candidate.schemaVersion === 1 && candidate.backupDirectory !== undefined) {
    throw new RenameOperationError('旧版操作记录不能指定备份目录', expectedName)
  }
  if (candidate.schemaVersion === JOURNAL_VERSION
    && (candidate.backupDirectory !== backupDirectoryName(expectedName)
      || !candidate.entries.some((entry) => entry?.outputFormat === 'webp-lossless'))) {
    throw new RenameOperationError('原图备份目录与操作记录不一致', expectedName)
  }
  for (const [index, raw] of candidate.entries.entries()) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
      throw new RenameOperationError(`操作日志第 ${index + 1} 项无效`, expectedName)
    }
    const entry = raw as OperationJournalEntry
    validateRequestShape(entry, index)
    if (entry.outputFormat) {
      if (candidate.schemaVersion !== JOURNAL_VERSION
        || entry.backupName !== `${index}.nxr-source`
        || ((entry.targetHash !== undefined || entry.targetSize !== undefined)
          && (typeof entry.targetHash !== 'string' || !HASH_PATTERN.test(entry.targetHash)
            || !Number.isSafeInteger(entry.targetSize) || entry.targetSize! <= 0))
        || (entry.state !== 'planned' && entry.state !== 'restored' && !entry.targetHash)) {
        throw new RenameOperationError(`操作日志第 ${index + 1} 项转换记录无效`, expectedName)
      }
    } else if (entry.targetHash !== undefined || entry.targetSize !== undefined || entry.backupName !== undefined) {
      throw new RenameOperationError(`操作日志第 ${index + 1} 项存在不匹配的转换字段`, expectedName)
    }
    if (
      typeof entry.sourceHash !== 'string' ||
      !HASH_PATTERN.test(entry.sourceHash) ||
      !isEntryState(entry.state) ||
      typeof entry.updatedAt !== 'string' ||
      !Number.isFinite(Date.parse(entry.updatedAt))
    ) {
      throw new RenameOperationError(`操作日志第 ${index + 1} 项状态或哈希无效`, expectedName)
    }
    const sourceKey = entry.sourceName.toLocaleLowerCase('en-US')
    const targetKey = entry.targetName.toLocaleLowerCase('en-US')
    if (sourceNames.has(sourceKey) || targetNames.has(targetKey) || sourceKey === targetKey) {
      throw new RenameOperationError(`操作日志包含重复或冲突文件名`, expectedName)
    }
    sourceNames.add(sourceKey)
    targetNames.add(targetKey)
  }
  for (const targetName of targetNames) {
    if (sourceNames.has(targetName)) {
      throw new RenameOperationError(`操作日志包含源文件与目标文件交叉覆盖`, expectedName)
    }
  }
  return candidate as OperationJournal
}

async function failWithJournal(
  directory: FileSystemDirectoryHandle,
  journal: OperationJournal,
  error: unknown,
): Promise<never> {
  const message = chineseError(error)
  journal.state = 'failed'
  journal.error = message
  try {
    await writeJournal(directory, journal)
  } catch (journalError) {
    throw new RenameOperationError(
      `操作失败，且无法更新恢复日志；请保留目录现状。原错误：${message}；日志错误：${chineseError(journalError)}`,
      journal.name,
      { cause: error },
    )
  }
  throw new RenameOperationError(`操作已停止：${message}`, journal.name, { cause: error })
}

export async function hashFile(file: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export async function renameFiles(
  directory: FileSystemDirectoryHandle,
  requests: RenameRequest[],
  onProgress?: Progress,
  // Dependency injection keeps filesystem fault tests independent of the codec.
  convert?: (file: File, options?: { signal?: AbortSignal }) => Promise<Blob>,
  options: RenameOptions = {},
): Promise<OperationJournal> {
  checkCancelled(options.signal)
  if (!requests.length) throw new RenameOperationError('没有可执行的重命名项目')

  const effective = requests.filter(
    (request) => request.sourceName !== request.targetName,
  )
  if (!effective.length) throw new RenameOperationError('所有项目均为同名，无需重命名')
  if (effective.length > MAX_JOURNAL_ENTRIES) {
    throw new RenameOperationError(`单次最多处理 ${MAX_JOURNAL_ENTRIES} 个文件`)
  }

  const initial = await snapshotDirectory(directory)
  const sourceKeys = new Set<string>()
  const targetKeys = new Set<string>()
  const prepared: OperationJournalEntry[] = []

  progress(onProgress, '正在预检文件与名称冲突…')
  for (const [index, request] of effective.entries()) {
    checkCancelled(options.signal)
    validateRequestShape(request, index)
    const sourceKey = request.sourceName.toLocaleLowerCase('en-US')
    const targetKey = request.targetName.toLocaleLowerCase('en-US')
    if (sourceKeys.has(sourceKey)) throw new RenameOperationError(`源文件重复：${request.sourceName}`)
    if (targetKeys.has(targetKey)) throw new RenameOperationError(`目标文件重复：${request.targetName}`)
    if (sourceKey === targetKey) {
      throw new RenameOperationError(`不支持仅改变文件名大小写：${request.sourceName}`)
    }
    sourceKeys.add(sourceKey)
    targetKeys.add(targetKey)

    const actualSourceName = singleName(initial, request.sourceName)
    if (actualSourceName !== request.sourceName) {
      throw new RenameOperationError(`找不到精确匹配的源文件：${request.sourceName}`)
    }
    if (singleName(initial, request.targetName) !== undefined) {
      throw new RenameOperationError(`目标文件已存在（含大小写冲突）：${request.targetName}`)
    }
    const handle = initial.exact.get(request.sourceName)
    if (!handle || handle.kind !== 'file') {
      throw new RenameOperationError(`源项目不是文件：${request.sourceName}`)
    }
    const source = await readFileSnapshot(handle as FileSystemFileHandle)
    ensureExpectedSource(request, source)
    prepared.push({
      ...request,
      expectedHash: request.expectedHash?.toLowerCase(),
      sourceHash: source.hash,
      ...(request.outputFormat ? { backupName: `${index}.nxr-source` } : {}),
      state: 'planned',
      updatedAt: now(),
    })
  }

  const journal = createJournal(prepared)
  checkCancelled(options.signal)
  try {
    await writeInitialJournal(directory, journal)
  } catch (error) {
    throw new RenameOperationError(
      `无法先写入恢复日志，未修改任何图片：${chineseError(error)}`,
      journal.name,
      { cause: error },
    )
  }

  try {
    checkCancelled(options.signal)
    let backupDirectory: FileSystemDirectoryHandle | undefined
    if (journal.backupDirectory) {
      const beforeBackup = await snapshotDirectory(directory)
      if (singleName(beforeBackup, journal.backupDirectory) !== undefined) {
        throw new RenameOperationError('原图备份目录已存在，拒绝覆盖')
      }
      backupDirectory = await directory.getDirectoryHandle(journal.backupDirectory, { create: true })
      if ((await snapshotDirectory(backupDirectory)).exact.size) {
        throw new RenameOperationError('原图备份目录被占用，拒绝覆盖')
      }
    }
    journal.state = 'copying'
    await writeJournal(directory, journal)
    // Only encoding runs concurrently. Backups, output writes and the shared
    // recovery journal stay ordered; at most one small batch retains outputs.
    const requested = options.conversionConcurrency ?? 1
    const batchSize = Number.isFinite(requested) ? Math.max(1, Math.min(2, Math.floor(requested))) : 1
    for (let offset = 0; offset < journal.entries.length; offset += batchSize) {
      const batch: { entry: OperationJournalEntry; source: FileSnapshot }[] = []
      for (const entry of journal.entries.slice(offset, offset + batchSize)) {
        checkCancelled(options.signal)
        progress(onProgress, `正在安全复制：${entry.sourceName} → ${entry.targetName}`)
        const beforeCopy = await snapshotDirectory(directory)
        if (singleName(beforeCopy, entry.targetName) !== undefined) {
          throw new RenameOperationError(`复制前发现目标文件已存在：${entry.targetName}`)
        }
        const sourceHandle = await directory.getFileHandle(entry.sourceName)
        const source = await readFileSnapshot(sourceHandle)
        ensureExpectedSource(entry, source)
        if (source.hash !== entry.sourceHash) {
          throw new RenameOperationError(`复制前源文件内容已变化：${entry.sourceName}`)
        }

        if (entry.outputFormat) {
          progress(onProgress, `正在保留原图：${entry.sourceName}`)
          const backupHandle = await writeNewFile(backupDirectory!, entry.backupName!, source.file)
          if (!matches(await readFileSnapshot(backupHandle), { hash: entry.sourceHash, size: entry.expectedSize })) {
            throw new RenameOperationError(`原图备份写入后校验失败：${entry.sourceName}`)
          }
          if (!convert) throw new RenameOperationError('未提供无损 WebP 编码器')
        }
        batch.push({ entry, source })
      }

      checkCancelled(options.signal)
      progress(onProgress, `正在转换 ${batch.length} 张 · 已完成 ${offset} / ${journal.entries.length} 张`)
      const batchController = new AbortController()
      const cancelBatch = () => batchController.abort()
      options.signal?.addEventListener('abort', cancelBatch, { once: true })
      if (options.signal?.aborted) cancelBatch()
      let failure: unknown
      let failed = false
      let results: PromiseSettledResult<Blob>[]
      try {
        results = await Promise.allSettled(batch.map(async ({ entry, source }) => {
          try {
            checkCancelled(batchController.signal)
            return entry.outputFormat ? await convert!(source.file, { signal: batchController.signal }) : source.file
          } catch (error) {
            if (!failed) {
              failed = true
              failure = error
              batchController.abort()
            }
            throw error
          }
        }))
      } finally {
        options.signal?.removeEventListener('abort', cancelBatch)
      }
      // Wait for every in-flight worker to settle before writing a failed
      // journal, so nothing can mutate this operation after failure returns.
      if (failed) throw failure
      checkCancelled(options.signal)
      for (const [index, { entry }] of batch.entries()) {
        checkCancelled(options.signal)
        const result = results[index]!
        if (result.status === 'rejected') throw result.reason
        const output = result.value
        progress(onProgress, `正在校验 ${offset + index + 1} / ${journal.entries.length} 张：${entry.sourceName}`)
        if (entry.outputFormat) {
          entry.targetHash = await hashFile(output)
          entry.targetSize = output.size
          // Persist the expected encoded bytes before creating a target, so an
          // interrupted write can be distinguished from a later external edit.
          await writeJournal(directory, journal)
        }
        // Conversion can take time; refresh conflicts immediately before writing.
        if (singleName(await snapshotDirectory(directory), entry.targetName) !== undefined) {
          throw new RenameOperationError(`写入前发现目标文件已存在：${entry.targetName}`)
        }
        const targetHandle = await writeNewFile(directory, entry.targetName, output)
        const target = await readFileSnapshot(targetHandle)
        if (!matches(target, expectedTarget(entry))) {
          throw new RenameOperationError(`目标副本校验失败：${entry.targetName}`)
        }
        entry.state = 'copied'
        entry.updatedAt = now()
        delete entry.error
        await writeJournal(directory, journal)
      }
    }

    checkCancelled(options.signal)
    journal.state = 'deleting'
    await writeJournal(directory, journal)
    for (const entry of journal.entries) {
      checkCancelled(options.signal)
      progress(onProgress, `正在复核并移除旧文件：${entry.sourceName}`)
      const sourceHandle = await directory.getFileHandle(entry.sourceName)
      const targetHandle = await directory.getFileHandle(entry.targetName)
      const [source, target] = await Promise.all([
        readFileSnapshot(sourceHandle),
        readFileSnapshot(targetHandle),
      ])
      ensureExpectedSource(entry, source)
      if (source.hash !== entry.sourceHash || !matches(target, expectedTarget(entry))) {
        throw new RenameOperationError(`删除前内容复核失败：${entry.sourceName}`)
      }
      if (entry.outputFormat) await originalBackup(directory, journal, entry)
      checkCancelled(options.signal)
      await directory.removeEntry(entry.sourceName)
      entry.state = 'deleted'
      entry.updatedAt = now()
      await writeJournal(directory, journal)
    }

    journal.state = 'complete'
    delete journal.error
    await writeJournal(directory, journal)
    progress(onProgress, `完成 ${journal.entries.length} 个文件的安全重命名`)
    return journal
  } catch (error) {
    return failWithJournal(directory, journal, error)
  }
}

async function loadJournal(
  directory: FileSystemDirectoryHandle,
  journalName: string,
): Promise<OperationJournal> {
  if (!JOURNAL_PATTERN.test(journalName)) {
    throw new RenameOperationError(`操作日志文件名无效：${journalName}`)
  }
  const handle = await directory.getFileHandle(journalName)
  const file = await handle.getFile()
  if (file.size > MAX_JOURNAL_BYTES) {
    throw new RenameOperationError(`操作日志超过 2MB 限制：${journalName}`, journalName)
  }
  return parseJournal(await file.text(), journalName)
}

export async function restoreJournal(
  directory: FileSystemDirectoryHandle,
  journalName: string,
  onProgress?: Progress,
): Promise<OperationJournal> {
  const journal = await loadJournal(directory, journalName)
  try {
    journal.state = 'restoring'
    delete journal.error
    await writeJournal(directory, journal)

    // Restore every missing original before removing any verified target copy.
    for (const entry of journal.entries) {
      progress(onProgress, `正在检查恢复项：${entry.sourceName}`)
      const directoryState = await snapshotDirectory(directory)
      const sourceActual = singleName(directoryState, entry.sourceName)
      const targetActual = singleName(directoryState, entry.targetName)

      if (sourceActual !== undefined && sourceActual !== entry.sourceName) {
        throw new RenameOperationError(`原文件名存在大小写冲突，拒绝覆盖：${entry.sourceName}`)
      }
      if (targetActual !== undefined && targetActual !== entry.targetName) {
        throw new RenameOperationError(`新文件名存在大小写冲突，拒绝操作：${entry.targetName}`)
      }

      const sourceHandle = sourceActual
        ? await directory.getFileHandle(entry.sourceName)
        : undefined
      const targetHandle = targetActual
        ? await directory.getFileHandle(entry.targetName)
        : undefined

      if (sourceHandle) {
        const source = await readFileSnapshot(sourceHandle)
        if (source.file.size !== entry.expectedSize || source.hash !== entry.sourceHash) {
          throw new RenameOperationError(`原文件名已被不同内容占用，拒绝覆盖：${entry.sourceName}`)
        }
      } else {
        if (!entry.outputFormat && !targetHandle) {
          throw new RenameOperationError(`原文件和安全副本都不存在：${entry.sourceName}`)
        }
        const target = entry.outputFormat
          ? await originalBackup(directory, journal, entry)
          : await readFileSnapshot(targetHandle!)
        if (target.file.size !== entry.expectedSize || target.hash !== entry.sourceHash) {
          throw new RenameOperationError(`恢复副本内容不匹配：${entry.targetName}`)
        }
        const createdSource = await writeNewFile(directory, entry.sourceName, target.file)
        const restored = await readFileSnapshot(createdSource)
        if (restored.file.size !== entry.expectedSize || restored.hash !== entry.sourceHash) {
          throw new RenameOperationError(`恢复后的原文件校验失败：${entry.sourceName}`)
        }
      }
      entry.state = 'restored'
      entry.updatedAt = now()
      await writeJournal(directory, journal)
    }

    // All originals are now safe. Remove only target files whose bytes still match.
    for (const entry of journal.entries) {
      const sourceHandle = await directory.getFileHandle(entry.sourceName)
      const targetHandle = await getExistingFileHandle(directory, entry.targetName)
      const source = await readFileSnapshot(sourceHandle)
      if (source.file.size !== entry.expectedSize || source.hash !== entry.sourceHash) {
        throw new RenameOperationError(`清理新文件前原文件校验失败：${entry.sourceName}`)
      }
      if (targetHandle) {
        const target = await readFileSnapshot(targetHandle)
        if (!matches(target, expectedTarget(entry))) {
          throw new RenameOperationError(`新文件已被修改，拒绝删除：${entry.targetName}`)
        }
        await directory.removeEntry(entry.targetName)
      }
    }

    // Remove only this operation's verified backups after every original is
    // restored. Never recursively remove a directory with unknown contents.
    if (journal.backupDirectory) {
      let backup: FileSystemDirectoryHandle | undefined
      try { backup = await directory.getDirectoryHandle(journal.backupDirectory) }
      catch (error) {
        if (!(error instanceof DOMException && error.name === 'NotFoundError')) throw error
      }
      if (backup) {
        for (const entry of journal.entries.filter((item) => item.outputFormat)) {
          const handle = await getExistingFileHandle(backup, entry.backupName!)
          if (!handle) continue
          const original = await readFileSnapshot(await directory.getFileHandle(entry.sourceName))
          const saved = await readFileSnapshot(handle)
          const expected = { hash: entry.sourceHash, size: entry.expectedSize }
          if (!matches(original, expected) || !matches(saved, expected)) {
            throw new RenameOperationError(`清理备份前原图校验失败：${entry.sourceName}`)
          }
          await backup.removeEntry(entry.backupName!)
        }
        if (!(await snapshotDirectory(backup)).exact.size) await directory.removeEntry(journal.backupDirectory)
      }
    }

    journal.state = 'restored'
    delete journal.error
    await writeJournal(directory, journal)
    progress(onProgress, `已恢复 ${journal.entries.length} 个原文件名`)
    return journal
  } catch (error) {
    return failWithJournal(directory, journal, error)
  }
}

export async function listJournals(
  directory: FileSystemDirectoryHandle,
): Promise<JournalSummary[]> {
  const summaries: JournalSummary[] = []
  for await (const [name, handle] of directory.entries()) {
    if (handle.kind !== 'file' || !JOURNAL_PATTERN.test(name)) continue
    try {
      const fileHandle = handle as FileSystemFileHandle
      const file = await fileHandle.getFile()
      if (file.size > MAX_JOURNAL_BYTES) continue
      const journal = parseJournal(await file.text(), name)
      summaries.push({
        name,
        state: journal.state,
        createdAt: journal.createdAt,
        count: journal.entries.length,
      })
    } catch {
      // Invalid journals are untrusted input and are never offered as actions.
    }
  }
  return summaries.sort((left, right) => right.createdAt.localeCompare(left.createdAt))
}
