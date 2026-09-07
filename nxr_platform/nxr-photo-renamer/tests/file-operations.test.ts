import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  RenameOperationError,
  hashFile,
  listJournals,
  renameFiles,
  restoreJournal,
} from '../src/lib/file-operations.ts'

interface MemoryEntry {
  bytes: Uint8Array
  lastModified: number
  type: string
}

function bytes(value: string): Uint8Array {
  return new TextEncoder().encode(value)
}

function memoryFile(name: string, entry: MemoryEntry): File {
  const blob = new Blob([entry.bytes], { type: entry.type }) as File
  Object.defineProperties(blob, {
    name: { value: name, enumerable: true },
    lastModified: { value: entry.lastModified, enumerable: true },
    webkitRelativePath: { value: '', enumerable: true },
  })
  return blob
}

class MemoryFileHandle {
  readonly kind = 'file' as const

  constructor(
    readonly name: string,
    private readonly directory: MemoryDirectory,
  ) {}

  async getFile(): Promise<File> {
    const entry = this.directory.files.get(this.name)
    if (!entry) throw new DOMException('missing', 'NotFoundError')
    return memoryFile(this.name, entry)
  }

  async isSameEntry(other: FileSystemHandle): Promise<boolean> {
    return other.kind === 'file' && other.name === this.name
  }

  async createWritable(): Promise<FileSystemWritableFileStream> {
    const chunks: BlobPart[] = []
    let aborted = false
    return {
      write: async (data: FileSystemWriteChunkType) => {
        if (aborted) throw new DOMException('aborted', 'AbortError')
        if (data instanceof Blob || typeof data === 'string' || data instanceof ArrayBuffer) {
          chunks.push(data)
          return
        }
        if (ArrayBuffer.isView(data)) {
          chunks.push(data as ArrayBufferView<ArrayBuffer>)
          return
        }
        throw new Error('unsupported test write')
      },
      close: async () => {
        if (this.directory.failWrites.has(this.name)) {
          throw new Error(`injected write failure: ${this.name}`)
        }
        const blob = new Blob(chunks)
        this.directory.files.set(this.name, {
          bytes: new Uint8Array(await blob.arrayBuffer()),
          lastModified: this.directory.clock++,
          type: this.directory.files.get(this.name)?.type ?? 'application/octet-stream',
        })
      },
      abort: async () => {
        aborted = true
      },
      seek: async () => undefined,
      truncate: async () => undefined,
      getWriter: () => {
        throw new Error('not implemented')
      },
      locked: false,
      pipeThrough: (() => {
        throw new Error('not implemented')
      }) as FileSystemWritableFileStream['pipeThrough'],
      pipeTo: (() => {
        throw new Error('not implemented')
      }) as FileSystemWritableFileStream['pipeTo'],
      tee: (() => {
        throw new Error('not implemented')
      }) as FileSystemWritableFileStream['tee'],
      values: (() => {
        throw new Error('not implemented')
      }) as FileSystemWritableFileStream['values'],
      [Symbol.asyncIterator]: (() => {
        throw new Error('not implemented')
      }) as FileSystemWritableFileStream[typeof Symbol.asyncIterator],
    } as FileSystemWritableFileStream
  }
}

class MemoryDirectory {
  readonly kind = 'directory' as const
  readonly files = new Map<string, MemoryEntry>()
  readonly directories = new Map<string, MemoryDirectory>()
  readonly failWrites = new Set<string>()
  readonly failRemove = new Set<string>()
  clock = 10_000

  constructor(readonly name = 'photos') {}

  add(name: string, content: string, lastModified = this.clock++): this {
    this.files.set(name, { bytes: bytes(content), lastModified, type: 'image/jpeg' })
    return this
  }

  text(name: string): string {
    const entry = this.files.get(name)
    if (!entry) throw new Error(`missing ${name}`)
    return new TextDecoder().decode(entry.bytes)
  }

  directory(name: string): MemoryDirectory {
    const directory = this.directories.get(name)
    if (!directory) throw new Error(`missing directory ${name}`)
    return directory
  }

  async getFileHandle(name: string, options?: { create?: boolean }): Promise<FileSystemFileHandle> {
    if (this.directories.has(name)) throw new DOMException('directory exists', 'TypeMismatchError')
    if (!this.files.has(name)) {
      if (!options?.create) throw new DOMException('missing', 'NotFoundError')
      this.files.set(name, {
        bytes: new Uint8Array(),
        lastModified: this.clock++,
        type: 'application/octet-stream',
      })
    }
    return new MemoryFileHandle(name, this) as unknown as FileSystemFileHandle
  }

  async getDirectoryHandle(
    name: string,
    options?: { create?: boolean },
  ): Promise<FileSystemDirectoryHandle> {
    if (this.files.has(name)) throw new DOMException('file exists', 'TypeMismatchError')
    let directory = this.directories.get(name)
    if (!directory) {
      if (!options?.create) throw new DOMException('missing', 'NotFoundError')
      directory = new MemoryDirectory(name)
      directory.clock = this.clock
      this.directories.set(name, directory)
    }
    return directory.handle()
  }

  async removeEntry(name: string): Promise<void> {
    if (this.failRemove.delete(name)) throw new Error(`injected remove failure: ${name}`)
    if (this.files.delete(name)) return
    const directory = this.directories.get(name)
    if (!directory) throw new DOMException('missing', 'NotFoundError')
    if (directory.files.size || directory.directories.size) {
      throw new DOMException('directory not empty', 'InvalidModificationError')
    }
    this.directories.delete(name)
  }

  async *entries(): AsyncIterableIterator<[string, FileSystemHandle]> {
    for (const name of [...this.files.keys()]) {
      yield [name, new MemoryFileHandle(name, this) as unknown as FileSystemHandle]
    }
    for (const [name, directory] of [...this.directories.entries()]) {
      yield [name, directory.handle()]
    }
  }

  handle(): FileSystemDirectoryHandle {
    return this as unknown as FileSystemDirectoryHandle
  }
}

function request(
  directory: MemoryDirectory,
  sourceName: string,
  targetName: string,
) {
  const entry = directory.files.get(sourceName)
  assert(entry)
  return {
    sourceName,
    targetName,
    expectedSize: entry.bytes.byteLength,
    expectedLastModified: entry.lastModified,
  }
}

function conversionRequest(
  directory: MemoryDirectory,
  sourceName: string,
  targetName: string,
) {
  return {
    ...request(directory, sourceName, targetName),
    outputFormat: 'webp-lossless' as const,
  }
}

async function fakeLosslessWebp(file: File): Promise<Blob> {
  return new Blob(['webp:', await file.arrayBuffer()], { type: 'image/webp' })
}

function parallelFixture() {
  const directory = new MemoryDirectory()
  const requests = ['one.jpg', 'two.jpg', 'three.jpg', 'four.jpg'].map((name, index) => {
    directory.add(name, `original ${index}`)
    return conversionRequest(directory, name, `PAR${Math.floor(index / 2)}_${index % 2 ? 'B' : 'A'}.webp`)
  })
  return { directory, requests }
}

test('parallel encoding is bounded and every original survives until all outputs are verified', async () => {
  const { directory, requests } = parallelFixture()
  let active = 0, maximum = 0
  const journal = await renameFiles(directory.handle(), requests, undefined, async file => {
    active++; maximum = Math.max(maximum, active)
    for (const item of requests) assert(directory.files.has(item.sourceName))
    const persisted = JSON.parse(directory.text(journalName(directory)))
    const entry = persisted.entries.find((item: { sourceName: string }) => item.sourceName === file.name)
    assert.equal(directory.directory(persisted.backupDirectory).text(entry.backupName), await file.text())
    await new Promise<void>(resolve => setImmediate(resolve))
    const output = await fakeLosslessWebp(file)
    active--
    return output
  }, { conversionConcurrency: 2 })
  assert.equal(maximum, 2)
  assert.equal(journal.state, 'complete')
  assert(journal.entries.every(entry => entry.state === 'deleted' && entry.targetHash))
  await restoreJournal(directory.handle(), journal.name)
  requests.forEach((item, index) => assert.equal(directory.text(item.sourceName), `original ${index}`))
})

test('parallel failure aborts its sibling and waits for cleanup before returning a recoverable journal', async () => {
  const { directory, requests } = parallelFixture()
  let calls = 0, siblingStopped = false
  await assert.rejects(renameFiles(directory.handle(), requests, undefined, async (_file, options) => {
    calls++
    if (calls === 1) {
      await new Promise<void>(resolve => setImmediate(resolve))
      throw new Error('primary encoding failure')
    }
    await new Promise<void>(resolve => options!.signal!.addEventListener('abort', () => setImmediate(resolve), { once: true }))
    siblingStopped = true
    throw new DOMException('sibling stopped', 'AbortError')
  }, { conversionConcurrency: 2 }), /primary encoding failure/)
  assert.equal(calls, 2)
  assert(siblingStopped)
  requests.forEach(item => assert(directory.files.has(item.sourceName) && !directory.files.has(item.targetName)))
  const name = journalName(directory)
  assert.equal(JSON.parse(directory.text(name)).state, 'failed')
  await restoreJournal(directory.handle(), name)
  assert.equal(directory.directories.size, 0)
})

test('cancelling a later batch stops both encoders and restores previously written outputs', async () => {
  const { directory, requests } = parallelFixture()
  const controller = new AbortController()
  let calls = 0, cancelled = 0
  await assert.rejects(renameFiles(directory.handle(), requests, undefined, async (file, options) => {
    calls++
    if (calls <= 2) return fakeLosslessWebp(file)
    if (calls === 4) queueMicrotask(() => controller.abort())
    await new Promise<void>(resolve => options!.signal!.addEventListener('abort', () => setImmediate(resolve), { once: true }))
    cancelled++
    throw new DOMException('cancelled', 'AbortError')
  }, { conversionConcurrency: 2, signal: controller.signal }), /cancelled/)
  assert.equal(cancelled, 2)
  requests.forEach(item => assert(directory.files.has(item.sourceName)))
  assert(directory.files.has(requests[0]!.targetName))
  await restoreJournal(directory.handle(), journalName(directory))
  requests.forEach((item, index) => {
    assert.equal(directory.text(item.sourceName), `original ${index}`)
    assert(!directory.files.has(item.targetName))
  })
})

test('an already cancelled operation creates no files or backup directory', async () => {
  const { directory, requests } = parallelFixture()
  const controller = new AbortController(); controller.abort()
  await assert.rejects(renameFiles(directory.handle(), requests, undefined, fakeLosslessWebp, { signal: controller.signal }), /已取消/)
  assert.equal(directory.files.size, 4)
  assert.equal(directory.directories.size, 0)
})

function journalName(directory: MemoryDirectory): string {
  const names = [...directory.files.keys()].filter((name) => name.endsWith('.json'))
  assert.equal(names.length, 1)
  return names[0]
}

test('hashFile returns lowercase SHA-256', async () => {
  assert.equal(
    await hashFile(new Blob(['abc'])),
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
  )
})

test('lossless WebP conversion keeps an exact hidden backup and journals encoded bytes', async () => {
  const directory = new MemoryDirectory().add('front.jpg', 'front bytes', 101)
  const journal = await renameFiles(
    directory.handle(),
    [conversionRequest(directory, 'front.jpg', '7123456789_A.webp')],
    undefined,
    fakeLosslessWebp,
  )

  assert.equal(journal.schemaVersion, 2)
  assert.match(journal.backupDirectory ?? '', /^\.nxr-originals-[0-9a-f-]{36}$/)
  assert.equal(directory.files.has('front.jpg'), false)
  assert.equal(directory.text('7123456789_A.webp'), 'webp:front bytes')
  assert.equal(directory.directory(journal.backupDirectory!).text('0.nxr-source'), 'front bytes')

  const expectedTarget = new Blob(['webp:front bytes'], { type: 'image/webp' })
  assert.equal(journal.entries[0].targetHash, await hashFile(expectedTarget))
  assert.equal(journal.entries[0].targetSize, expectedTarget.size)
  assert.equal(journal.entries[0].state, 'deleted')

  const persisted = JSON.parse(directory.text(journal.name))
  assert.equal(persisted.schemaVersion, 2)
  assert.equal(persisted.entries[0].targetHash, journal.entries[0].targetHash)
  assert.equal(persisted.entries[0].targetSize, journal.entries[0].targetSize)
  assert.equal(persisted.entries[0].backupName, '0.nxr-source')
})

test('conversion preflight rejects an occupied WebP target before creating recovery artifacts', async () => {
  const directory = new MemoryDirectory()
    .add('front.jpg', 'source')
    .add('7123456789_A.WEBP', 'occupied')
  let converterCalls = 0

  await assert.rejects(
    renameFiles(
      directory.handle(),
      [conversionRequest(directory, 'front.jpg', '7123456789_A.webp')],
      undefined,
      async (file) => {
        converterCalls++
        return fakeLosslessWebp(file)
      },
    ),
    /目标文件已存在.*大小写冲突/,
  )

  assert.equal(converterCalls, 0)
  assert.equal(directory.text('front.jpg'), 'source')
  assert.equal(directory.text('7123456789_A.WEBP'), 'occupied')
  assert.equal([...directory.files.keys()].some((name) => name.endsWith('.json')), false)
  assert.equal(directory.directories.size, 0)
})

test('converter failure leaves the source and its exact backup recoverable', async () => {
  const directory = new MemoryDirectory().add('front.png', 'original png')
  let failedJournal: string | undefined
  try {
    await renameFiles(
      directory.handle(),
      [conversionRequest(directory, 'front.png', '7123456789_A.webp')],
      undefined,
      async () => { throw new Error('synthetic encoder failure') },
    )
  } catch (error) {
    assert(error instanceof RenameOperationError)
    failedJournal = error.journalName
  }

  assert(failedJournal)
  assert.equal(directory.text('front.png'), 'original png')
  assert.equal(directory.files.has('7123456789_A.webp'), false)
  const persisted = JSON.parse(directory.text(failedJournal))
  assert.equal(persisted.state, 'failed')
  assert.equal(persisted.entries[0].targetHash, undefined)
  assert.equal(
    directory.directory(persisted.backupDirectory).text('0.nxr-source'),
    'original png',
  )

  const restored = await restoreJournal(directory.handle(), failedJournal)
  assert.equal(restored.state, 'restored')
  assert.equal(directory.text('front.png'), 'original png')
  assert.equal(directory.directories.has(persisted.backupDirectory), false)
})

test('target write failure retains the pre-journaled encoded hash for safe recovery', async () => {
  const directory = new MemoryDirectory().add('front.jpg', 'original')
  directory.failWrites.add('7123456789_A.webp')

  let failedJournal: string | undefined
  await assert.rejects(
    async () => {
      try {
        await renameFiles(
          directory.handle(),
          [conversionRequest(directory, 'front.jpg', '7123456789_A.webp')],
          undefined,
          fakeLosslessWebp,
        )
      } catch (error) {
        if (error instanceof RenameOperationError) failedJournal = error.journalName
        throw error
      }
    },
    /操作已停止/,
  )

  assert(failedJournal)
  const persisted = JSON.parse(directory.text(failedJournal))
  assert.match(persisted.entries[0].targetHash, /^[0-9a-f]{64}$/)
  assert.equal(persisted.entries[0].targetSize, new Blob(['webp:original']).size)
  assert.equal(directory.text('front.jpg'), 'original')
  await restoreJournal(directory.handle(), failedJournal)
  assert.equal(directory.files.has('7123456789_A.webp'), false)
})

test('conversion recovery restores every original after deletion is interrupted', async () => {
  const directory = new MemoryDirectory()
    .add('front.jpg', 'front original')
    .add('back.png', 'back original')
  const requests = [
    conversionRequest(directory, 'front.jpg', '7123456789_A.webp'),
    conversionRequest(directory, 'back.png', '7123456789_B.webp'),
  ]
  directory.failRemove.add('back.png')

  let failedJournal: string | undefined
  try {
    await renameFiles(directory.handle(), requests, undefined, fakeLosslessWebp)
  } catch (error) {
    assert(error instanceof RenameOperationError)
    failedJournal = error.journalName
  }

  assert(failedJournal)
  const failed = JSON.parse(directory.text(failedJournal))
  assert.equal(directory.files.has('front.jpg'), false)
  assert.equal(directory.text('back.png'), 'back original')
  assert.equal(directory.text('7123456789_A.webp'), 'webp:front original')
  assert.equal(directory.text('7123456789_B.webp'), 'webp:back original')
  assert.equal(directory.directory(failed.backupDirectory).text('0.nxr-source'), 'front original')
  assert.equal(directory.directory(failed.backupDirectory).text('1.nxr-source'), 'back original')

  const restored = await restoreJournal(directory.handle(), failedJournal)
  assert.equal(restored.state, 'restored')
  assert.equal(directory.text('front.jpg'), 'front original')
  assert.equal(directory.text('back.png'), 'back original')
  assert.equal(directory.files.has('7123456789_A.webp'), false)
  assert.equal(directory.files.has('7123456789_B.webp'), false)
  assert.equal(directory.directories.has(failed.backupDirectory), false)
})

test('conversion recovery refuses a tampered original backup', async () => {
  const directory = new MemoryDirectory().add('front.jpg', 'original')
  const journal = await renameFiles(
    directory.handle(),
    [conversionRequest(directory, 'front.jpg', '7123456789_A.webp')],
    undefined,
    fakeLosslessWebp,
  )
  directory.directory(journal.backupDirectory!).add('0.nxr-source', 'tampered')

  await assert.rejects(
    restoreJournal(directory.handle(), journal.name),
    /原图备份校验失败/,
  )
  assert.equal(directory.files.has('front.jpg'), false)
  assert.equal(directory.text('7123456789_A.webp'), 'webp:original')
  assert.equal(directory.directory(journal.backupDirectory!).text('0.nxr-source'), 'tampered')
})

test('conversion recovery restores bytes but never deletes a tampered WebP target', async () => {
  const directory = new MemoryDirectory().add('front.jpg', 'original')
  const journal = await renameFiles(
    directory.handle(),
    [conversionRequest(directory, 'front.jpg', '7123456789_A.webp')],
    undefined,
    fakeLosslessWebp,
  )
  directory.add('7123456789_A.webp', 'externally edited')

  await assert.rejects(
    restoreJournal(directory.handle(), journal.name),
    /新文件已被修改，拒绝删除/,
  )
  assert.equal(directory.text('front.jpg'), 'original')
  assert.equal(directory.text('7123456789_A.webp'), 'externally edited')
  assert.equal(directory.directory(journal.backupDirectory!).text('0.nxr-source'), 'original')
})

test('copies and verifies every target before deleting sources', async () => {
  const directory = new MemoryDirectory().add('front.jpg', 'front').add('back.jpg', 'back')
  const journal = await renameFiles(directory.handle(), [
    request(directory, 'front.jpg', 'VRA001_A.jpg'),
    request(directory, 'back.jpg', 'VRA001_B.jpg'),
  ])

  assert.equal(journal.state, 'complete')
  assert.equal(directory.files.has('front.jpg'), false)
  assert.equal(directory.files.has('back.jpg'), false)
  assert.equal(directory.text('VRA001_A.jpg'), 'front')
  assert.equal(directory.text('VRA001_B.jpg'), 'back')
  assert.deepEqual(journal.entries.map((entry) => entry.state), ['deleted', 'deleted'])
  assert.deepEqual(await listJournals(directory.handle()), [
    {
      name: journal.name,
      state: 'complete',
      createdAt: journal.createdAt,
      count: 2,
    },
  ])
})

test('refuses changed sources and pre-existing targets including case variants', async () => {
  const changed = new MemoryDirectory().add('photo.jpg', 'before', 100)
  const stale = request(changed, 'photo.jpg', 'NXR1_A.jpg')
  changed.add('photo.jpg', 'after', 101)
  await assert.rejects(renameFiles(changed.handle(), [stale]), /源文件在预览后发生变化/)

  const exact = new MemoryDirectory().add('photo.jpg', 'source').add('NXR1_A.jpg', 'occupied')
  await assert.rejects(
    renameFiles(exact.handle(), [request(exact, 'photo.jpg', 'NXR1_A.jpg')]),
    /目标文件已存在/,
  )

  const folded = new MemoryDirectory().add('photo.jpg', 'source').add('nxr1_a.JPG', 'occupied')
  await assert.rejects(
    renameFiles(folded.handle(), [request(folded, 'photo.jpg', 'NXR1_A.jpg')]),
    /大小写冲突/,
  )
})

test('rejects duplicate requests before creating a journal', async () => {
  const directory = new MemoryDirectory().add('one.jpg', 'one').add('two.jpg', 'two')
  await assert.rejects(
    renameFiles(directory.handle(), [
      request(directory, 'one.jpg', 'NXR1_A.jpg'),
      request(directory, 'one.jpg', 'NXR2_A.jpg'),
    ]),
    /源文件重复/,
  )
  assert.equal([...directory.files.keys()].some((name) => name.endsWith('.json')), false)
})

test('a failed first journal write never changes image files', async () => {
  const directory = new MemoryDirectory().add('photo.jpg', 'source')
  const originalGet = directory.getFileHandle.bind(directory)
  directory.getFileHandle = async (name, options) => {
    if (name.startsWith('.nxr-rename-') && name.endsWith('.json')) directory.failWrites.add(name)
    return originalGet(name, options)
  }

  await assert.rejects(
    renameFiles(directory.handle(), [request(directory, 'photo.jpg', 'NXR1_A.jpg')]),
    /未修改任何图片/,
  )
  assert.equal(directory.text('photo.jpg'), 'source')
  assert.equal(directory.files.has('NXR1_A.jpg'), false)
})

test('copy write failure preserves the source and records failure', async () => {
  const directory = new MemoryDirectory().add('photo.jpg', 'source')
  directory.failWrites.add('NXR1_A.jpg')
  let error: unknown
  try {
    await renameFiles(directory.handle(), [request(directory, 'photo.jpg', 'NXR1_A.jpg')])
  } catch (caught) {
    error = caught
  }
  assert(error instanceof RenameOperationError)
  assert.match(error.message, /操作已停止/)
  assert.equal(directory.text('photo.jpg'), 'source')
  const journal = JSON.parse(directory.text(journalName(directory)))
  assert.equal(journal.state, 'failed')
})

test('a second copy failure leaves every original in place', async () => {
  const directory = new MemoryDirectory().add('front.jpg', 'front').add('back.jpg', 'back')
  directory.failWrites.add('NXR9_B.jpg')
  await assert.rejects(
    renameFiles(directory.handle(), [
      request(directory, 'front.jpg', 'NXR9_A.jpg'),
      request(directory, 'back.jpg', 'NXR9_B.jpg'),
    ]),
    /操作已停止/,
  )
  assert.equal(directory.text('front.jpg'), 'front')
  assert.equal(directory.text('back.jpg'), 'back')
  assert.equal(directory.text('NXR9_A.jpg'), 'front')
})

test('filters an exact no-op while safely renaming the remaining request', async () => {
  const directory = new MemoryDirectory()
    .add('NXR0_A.jpg', 'already named')
    .add('back.jpg', 'back')
  const journal = await renameFiles(directory.handle(), [
    request(directory, 'NXR0_A.jpg', 'NXR0_A.jpg'),
    request(directory, 'back.jpg', 'NXR0_B.jpg'),
  ])
  assert.equal(journal.entries.length, 1)
  assert.equal(directory.text('NXR0_A.jpg'), 'already named')
  assert.equal(directory.text('NXR0_B.jpg'), 'back')
})

test('restores safely after deletion is interrupted', async () => {
  const directory = new MemoryDirectory().add('front.jpg', 'front').add('back.jpg', 'back')
  const requests = [
    request(directory, 'front.jpg', 'NXR2_A.jpg'),
    request(directory, 'back.jpg', 'NXR2_B.jpg'),
  ]
  directory.failRemove.add('back.jpg')

  let failedJournal: string | undefined
  try {
    await renameFiles(directory.handle(), requests)
  } catch (error) {
    assert(error instanceof RenameOperationError)
    failedJournal = error.journalName
  }
  assert(failedJournal)
  assert.equal(directory.files.has('front.jpg'), false)
  assert.equal(directory.files.has('back.jpg'), true)
  assert.equal(directory.files.has('NXR2_A.jpg'), true)
  assert.equal(directory.files.has('NXR2_B.jpg'), true)

  const restored = await restoreJournal(directory.handle(), failedJournal)
  assert.equal(restored.state, 'restored')
  assert.equal(directory.text('front.jpg'), 'front')
  assert.equal(directory.text('back.jpg'), 'back')
  assert.equal(directory.files.has('NXR2_A.jpg'), false)
  assert.equal(directory.files.has('NXR2_B.jpg'), false)

  // Recovery is resumable when the originals already exist with identical bytes.
  const restoredAgain = await restoreJournal(directory.handle(), failedJournal)
  assert.equal(restoredAgain.state, 'restored')
})

test('recovery never overwrites a changed original or deletes a changed target', async () => {
  const changedOriginal = new MemoryDirectory().add('photo.jpg', 'original')
  const completeOriginal = await renameFiles(changedOriginal.handle(), [
    request(changedOriginal, 'photo.jpg', 'NXR3_A.jpg'),
  ])
  changedOriginal.add('photo.jpg', 'unrelated replacement')
  await assert.rejects(
    restoreJournal(changedOriginal.handle(), completeOriginal.name),
    /原文件名已被不同内容占用/,
  )
  assert.equal(changedOriginal.text('photo.jpg'), 'unrelated replacement')
  assert.equal(changedOriginal.text('NXR3_A.jpg'), 'original')

  const changedTarget = new MemoryDirectory().add('photo.jpg', 'original')
  const completeTarget = await renameFiles(changedTarget.handle(), [
    request(changedTarget, 'photo.jpg', 'NXR4_A.jpg'),
  ])
  changedTarget.add('photo.jpg', 'original')
  changedTarget.add('NXR4_A.jpg', 'edited target')
  await assert.rejects(
    restoreJournal(changedTarget.handle(), completeTarget.name),
    /新文件已被修改，拒绝删除/,
  )
  assert.equal(changedTarget.text('photo.jpg'), 'original')
  assert.equal(changedTarget.text('NXR4_A.jpg'), 'edited target')
})

test('malicious journals are ignored in listings and rejected for recovery', async () => {
  const directory = new MemoryDirectory().add('victim.jpg', 'safe')
  const name = '.nxr-rename-123e4567-e89b-42d3-a456-426614174000.json'
  directory.files.set(name, {
    bytes: bytes(JSON.stringify({
      schemaVersion: 1,
      name,
      state: 'failed',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      entries: [{
        sourceName: '../victim.jpg',
        targetName: 'NXR1_A.jpg',
        expectedSize: 4,
        expectedLastModified: 1,
        sourceHash: '0'.repeat(64),
        state: 'copied',
        updatedAt: new Date().toISOString(),
      }],
    })),
    lastModified: 1,
    type: 'application/json',
  })

  assert.deepEqual(await listJournals(directory.handle()), [])
  await assert.rejects(restoreJournal(directory.handle(), name), /不是安全的当前目录文件名/)
  assert.equal(directory.text('victim.jpg'), 'safe')
})

test('edited journals cannot route a target over another source', async () => {
  const directory = new MemoryDirectory().add('one.jpg', 'one').add('NXR5_A.jpg', 'two')
  const name = '.nxr-rename-123e4567-e89b-42d3-a456-426614174001.json'
  const timestamp = new Date().toISOString()
  const entry = (sourceName: string, targetName: string) => ({
    sourceName,
    targetName,
    expectedSize: 3,
    expectedLastModified: 1,
    sourceHash: '0'.repeat(64),
    state: 'copied',
    updatedAt: timestamp,
  })
  directory.files.set(name, {
    bytes: bytes(JSON.stringify({
      schemaVersion: 1,
      name,
      state: 'failed',
      createdAt: timestamp,
      updatedAt: timestamp,
      entries: [
        entry('one.jpg', 'NXR5_A.jpg'),
        entry('NXR5_A.jpg', 'NXR6_A.jpg'),
      ],
    })),
    lastModified: 1,
    type: 'application/json',
  })

  assert.deepEqual(await listJournals(directory.handle()), [])
  await assert.rejects(restoreJournal(directory.handle(), name), /交叉覆盖/)
  assert.equal(directory.text('one.jpg'), 'one')
  assert.equal(directory.text('NXR5_A.jpg'), 'two')
})

test('legacy v1 journals remain listed and restorable without backup fields', async () => {
  const directory = new MemoryDirectory().add('legacy.jpg', 'legacy bytes')
  const journal = await renameFiles(directory.handle(), [
    request(directory, 'legacy.jpg', 'VRA900_A.jpg'),
  ])

  assert.equal(journal.schemaVersion, 1)
  assert.equal(journal.backupDirectory, undefined)
  assert.equal(journal.entries[0].backupName, undefined)
  assert.equal(journal.entries[0].targetHash, undefined)
  assert.deepEqual((await listJournals(directory.handle())).map((item) => item.name), [journal.name])

  await restoreJournal(directory.handle(), journal.name)
  assert.equal(directory.text('legacy.jpg'), 'legacy bytes')
  assert.equal(directory.files.has('VRA900_A.jpg'), false)
})

test('v2 journal validation rejects a mismatched backup directory and backup leaf', async () => {
  const wrongDirectory = new MemoryDirectory().add('front.jpg', 'original')
  const directoryJournal = await renameFiles(
    wrongDirectory.handle(),
    [conversionRequest(wrongDirectory, 'front.jpg', '7123456789_A.webp')],
    undefined,
    fakeLosslessWebp,
  )
  const directoryRecord = JSON.parse(wrongDirectory.text(directoryJournal.name))
  directoryRecord.backupDirectory = '.nxr-originals-123e4567-e89b-42d3-a456-426614174999'
  wrongDirectory.files.set(directoryJournal.name, {
    bytes: bytes(JSON.stringify(directoryRecord)),
    lastModified: wrongDirectory.clock++,
    type: 'application/json',
  })
  assert.deepEqual(await listJournals(wrongDirectory.handle()), [])
  await assert.rejects(
    restoreJournal(wrongDirectory.handle(), directoryJournal.name),
    /备份目录与操作记录不一致/,
  )

  const wrongLeaf = new MemoryDirectory().add('front.jpg', 'original')
  const leafJournal = await renameFiles(
    wrongLeaf.handle(),
    [conversionRequest(wrongLeaf, 'front.jpg', '7123456789_A.webp')],
    undefined,
    fakeLosslessWebp,
  )
  const leafRecord = JSON.parse(wrongLeaf.text(leafJournal.name))
  leafRecord.entries[0].backupName = '7123456789_A.jpg'
  wrongLeaf.files.set(leafJournal.name, {
    bytes: bytes(JSON.stringify(leafRecord)),
    lastModified: wrongLeaf.clock++,
    type: 'application/json',
  })
  assert.deepEqual(await listJournals(wrongLeaf.handle()), [])
  await assert.rejects(
    restoreJournal(wrongLeaf.handle(), leafJournal.name),
    /转换记录无效/,
  )
})

test('journal versions reject conversion metadata assigned to the wrong schema', async () => {
  const v1Directory = new MemoryDirectory().add('legacy.jpg', 'legacy')
  const v1Journal = await renameFiles(v1Directory.handle(), [
    request(v1Directory, 'legacy.jpg', 'VRA901_A.jpg'),
  ])
  const v1Record = JSON.parse(v1Directory.text(v1Journal.name))
  v1Record.backupDirectory = '.nxr-originals-123e4567-e89b-42d3-a456-426614174999'
  v1Directory.files.set(v1Journal.name, {
    bytes: bytes(JSON.stringify(v1Record)),
    lastModified: v1Directory.clock++,
    type: 'application/json',
  })
  assert.deepEqual(await listJournals(v1Directory.handle()), [])
  await assert.rejects(restoreJournal(v1Directory.handle(), v1Journal.name), /旧版操作记录/)

  const emptyV2Directory = new MemoryDirectory().add('legacy.jpg', 'legacy')
  const emptyV2Journal = await renameFiles(emptyV2Directory.handle(), [
    request(emptyV2Directory, 'legacy.jpg', 'VRA902_A.jpg'),
  ])
  const emptyV2Record = JSON.parse(emptyV2Directory.text(emptyV2Journal.name))
  emptyV2Record.schemaVersion = 2
  emptyV2Record.backupDirectory = emptyV2Journal.name
    .replace('.nxr-rename-', '.nxr-originals-')
    .replace(/\.json$/, '')
  emptyV2Directory.files.set(emptyV2Journal.name, {
    bytes: bytes(JSON.stringify(emptyV2Record)),
    lastModified: emptyV2Directory.clock++,
    type: 'application/json',
  })
  assert.deepEqual(await listJournals(emptyV2Directory.handle()), [])
  await assert.rejects(
    restoreJournal(emptyV2Directory.handle(), emptyV2Journal.name),
    /备份目录与操作记录不一致/,
  )
})
