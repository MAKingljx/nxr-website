import { createHash } from 'node:crypto'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { deflateSync } from 'node:zlib'

import type { Page } from '@playwright/test'
import QRCode from 'qrcode'

export interface TestPhoto {
  name: string
  bytesBase64: string
  sha256: string
}

export interface PickerOptions {
  permission?: PermissionState
  failCreateWritableFor?: string
}

export interface CanvasPlacement {
  photo: TestPhoto
  x: number
  y: number
  width: number
  height: number
  rotation?: 0 | 90 | 180 | 270
}

function crc32(bytes: Buffer): number {
  let value = 0xffffffff
  for (const byte of bytes) {
    value ^= byte
    for (let bit = 0; bit < 8; bit += 1) {
      value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0)
    }
  }
  return (value ^ 0xffffffff) >>> 0
}

function pngChunk(type: string, data: Buffer): Buffer {
  const typeBytes = Buffer.from(type, 'ascii')
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const checksum = Buffer.alloc(4)
  checksum.writeUInt32BE(crc32(Buffer.concat([typeBytes, data])))
  return Buffer.concat([length, typeBytes, data, checksum])
}

function makeBlankPng(width = 96, height = 96): Buffer {
  const header = Buffer.alloc(13)
  header.writeUInt32BE(width, 0)
  header.writeUInt32BE(height, 4)
  header[8] = 8
  header[9] = 6

  const stride = width * 4 + 1
  const pixels = Buffer.alloc(stride * height)
  for (let y = 0; y < height; y += 1) {
    pixels[y * stride] = 0
    for (let x = 0; x < width; x += 1) {
      const offset = y * stride + 1 + x * 4
      const shade = (Math.floor(x / 12) + Math.floor(y / 12)) % 2 ? 232 : 248
      pixels[offset] = shade
      pixels[offset + 1] = shade
      pixels[offset + 2] = shade
      pixels[offset + 3] = 255
    }
  }

  return Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'),
    pngChunk('IHDR', header),
    pngChunk('IDAT', deflateSync(pixels)),
    pngChunk('IEND', Buffer.alloc(0)),
  ])
}

function photo(name: string, bytes: Buffer): TestPhoto {
  return {
    name,
    bytesBase64: bytes.toString('base64'),
    sha256: createHash('sha256').update(bytes).digest('hex'),
  }
}

export async function qrPhoto(
  name: string,
  qrText: string,
  color: { dark: string; light: string } = { dark: '#111827', light: '#ffffff' },
): Promise<TestPhoto> {
  const bytes = await QRCode.toBuffer(qrText, {
    type: 'png',
    width: 720,
    margin: 6,
    errorCorrectionLevel: 'M',
    color,
  })
  return photo(name, bytes)
}

export function blankPhoto(name: string): TestPhoto {
  return photo(name, makeBlankPng())
}

/**
 * Install a deterministic picker for one test directory.
 *
 * The returned object delegates all file operations to a genuine OPFS
 * FileSystemDirectoryHandle. Only the permission methods and optional injected
 * createWritable failure are supplied by the test. This exercises the app's
 * real getFile/getFileHandle/createWritable/removeEntry flow without claiming
 * to automate Chromium's native operating-system picker dialog.
 */
export async function installOpfsPicker(
  page: Page,
  directoryName: string,
  options: PickerOptions = {},
): Promise<void> {
  await page.addInitScript(
    ({ directoryName: injectedName, permission, failCreateWritableFor }) => {
      const nativeHandles = new WeakMap<FileSystemHandle, FileSystemHandle>()
      const wrapFile = (handle: FileSystemFileHandle): FileSystemFileHandle => {
        const shouldFail = failCreateWritableFor
          ? new RegExp(failCreateWritableFor, 'i').test(handle.name)
          : false
        if (!shouldFail) return handle
        const wrapped = {
          kind: 'file',
          name: handle.name,
          getFile: handle.getFile.bind(handle),
          createWritable: async () => {
            throw new DOMException('Synthetic write failure', 'NotAllowedError')
          },
          isSameEntry: (other: FileSystemHandle) =>
            handle.isSameEntry(nativeHandles.get(other) ?? other),
        } as FileSystemFileHandle
        nativeHandles.set(wrapped, handle)
        return wrapped
      }

      const wrapDirectory = (handle: FileSystemDirectoryHandle): FileSystemDirectoryHandle => {
        const wrapped = {
          kind: 'directory',
          name: handle.name,
          getDirectoryHandle: handle.getDirectoryHandle.bind(handle),
          getFileHandle: async (name: string, createOptions?: FileSystemGetFileOptions) =>
            wrapFile(await handle.getFileHandle(name, createOptions)),
          removeEntry: handle.removeEntry.bind(handle),
          resolve: handle.resolve.bind(handle),
          isSameEntry: (other: FileSystemHandle) =>
            handle.isSameEntry(nativeHandles.get(other) ?? other),
          entries: handle.entries.bind(handle),
          keys: handle.keys.bind(handle),
          values: handle.values.bind(handle),
          [Symbol.asyncIterator]: handle[Symbol.asyncIterator].bind(handle),
          queryPermission: async () => permission,
          requestPermission: async () => permission,
        } as FileSystemDirectoryHandle
        nativeHandles.set(wrapped, handle)
        return wrapped
      }

      Object.defineProperty(window, 'showDirectoryPicker', {
        configurable: true,
        value: async () => {
          const root = await navigator.storage.getDirectory()
          const directory = await root.getDirectoryHandle(injectedName, { create: true })
          return wrapDirectory(directory)
        },
      })
    },
    {
      directoryName,
      permission: options.permission ?? 'granted',
      failCreateWritableFor: options.failCreateWritableFor ?? '',
    },
  )
}

export async function seedOpfsDirectory(
  page: Page,
  directoryName: string,
  photos: TestPhoto[],
): Promise<void> {
  await page.evaluate(
    async ({ directoryName: seededName, photos: seededPhotos }) => {
      const root = await navigator.storage.getDirectory()
      try {
        await root.removeEntry(seededName, { recursive: true })
      } catch (error) {
        if (!(error instanceof DOMException) || error.name !== 'NotFoundError') throw error
      }
      const directory = await root.getDirectoryHandle(seededName, { create: true })
      for (const seededPhoto of seededPhotos) {
        const bytes = Uint8Array.from(atob(seededPhoto.bytesBase64), (character) =>
          character.charCodeAt(0),
        )
        const handle = await directory.getFileHandle(seededPhoto.name, { create: true })
        const writable = await handle.createWritable()
        await writable.write(bytes)
        await writable.close()
      }
    },
    { directoryName, photos },
  )
}

export async function writeCanvasPhoto(
  page: Page,
  directoryName: string,
  name: string,
  canvasSize: { width: number; height: number },
  placements: CanvasPlacement[],
): Promise<{ size: number; sha256: string }> {
  return page.evaluate(
    async ({ directoryName: targetDirectory, name: targetName, canvasSize: size, placements: items }) => {
      const canvas = document.createElement('canvas')
      canvas.width = size.width
      canvas.height = size.height
      const context = canvas.getContext('2d')!
      context.fillStyle = '#f8fafc'
      context.fillRect(0, 0, canvas.width, canvas.height)

      for (const item of items) {
        const bytes = Uint8Array.from(atob(item.photo.bytesBase64), (character) =>
          character.charCodeAt(0),
        )
        const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }))
        context.save()
        const rotation = item.rotation || 0
        context.translate(item.x + item.width / 2, item.y + item.height / 2)
        context.rotate((rotation * Math.PI) / 180)
        const rotated = rotation === 90 || rotation === 270
        context.drawImage(
          bitmap,
          -item.width / 2,
          -item.height / 2,
          rotated ? item.height : item.width,
          rotated ? item.width : item.height,
        )
        context.restore()
        bitmap.close()
      }

      const blob = await new Promise<Blob>((resolve, reject) =>
        canvas.toBlob((value) => (value ? resolve(value) : reject(new Error('Canvas PNG failed'))), 'image/png'),
      )
      const root = await navigator.storage.getDirectory()
      const directory = await root.getDirectoryHandle(targetDirectory)
      const handle = await directory.getFileHandle(targetName, { create: true })
      const writable = await handle.createWritable()
      await writable.write(blob)
      await writable.close()
      const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
      return {
        size: blob.size,
        sha256: Array.from(new Uint8Array(digest), (byte) =>
          byte.toString(16).padStart(2, '0'),
        ).join(''),
      }
    },
    { directoryName, name, canvasSize, placements },
  )
}

export async function directoryFiles(
  page: Page,
  directoryName: string,
): Promise<Record<string, { size: number; sha256: string }>> {
  return page.evaluate(async (inspectedName) => {
    const root = await navigator.storage.getDirectory()
    const directory = await root.getDirectoryHandle(inspectedName)
    const result: Record<string, { size: number; sha256: string }> = {}
    for await (const [name, handle] of directory.entries()) {
      if (handle.kind !== 'file') continue
      const file = await (handle as FileSystemFileHandle).getFile()
      const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
      result[name] = {
        size: file.size,
        sha256: Array.from(new Uint8Array(digest), (byte) =>
          byte.toString(16).padStart(2, '0'),
        ).join(''),
      }
    }
    return result
  }, directoryName)
}

export async function materializeTestDirectory(
  photos: TestPhoto[],
): Promise<{ path: string; cleanup: () => Promise<void> }> {
  const path = await mkdtemp(join(tmpdir(), 'nxr-photo-renamer-e2e-'))
  await Promise.all(
    photos.map((item) => writeFile(join(path, item.name), Buffer.from(item.bytesBase64, 'base64'))),
  )
  return {
    path,
    cleanup: () => rm(path, { recursive: true, force: true }),
  }
}

export function uniqueDirectory(testName: string): string {
  return `e2e-${testName.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}-${Date.now()}-${Math.random()
    .toString(16)
    .slice(2)}`
}
