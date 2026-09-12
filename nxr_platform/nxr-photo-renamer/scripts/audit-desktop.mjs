import { createHash } from 'node:crypto'
import {
  lstat,
  readFile,
  readdir,
  readlink,
} from 'node:fs/promises'
import path from 'node:path'
import { inflateRawSync } from 'node:zlib'

import { extractFile, listPackage, statFile } from '@electron/asar'

const PHOTO_EXTENSIONS = new Set([
  '.arw', '.avif', '.bmp', '.dng', '.gif', '.heic', '.heif',
  '.jpeg', '.jpg', '.png', '.raw', '.tif', '.tiff', '.webp',
])
const DIST_EXTENSIONS = new Set([
  '.css', '.html', '.ico', '.js', '.json', '.svg', '.txt', '.wasm',
])
const RUNTIME_PACKAGE_KEYS = ['main', 'name', 'productName', 'version']
const REQUIRED_STAGE_FILES = new Set([
  'desktop/main.cjs',
  'desktop/policy.cjs',
  'desktop/preload.cjs',
  'dist/index.html',
  'package.json',
])

export function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

export async function sha256File(filePath) {
  return sha256(await readFile(filePath))
}

export function asarLookupPath(archiveMember, separator = path.sep) {
  if (separator !== '/' && separator !== '\\') {
    throw new Error(`unsupported ASAR path separator: ${separator}`)
  }
  return archiveMember
    .replace(/^[/\\]+/, '')
    .replaceAll('/', separator)
    .replaceAll('\\', separator)
}

export function assertSafePackagedPath(relativePath, label = 'packaged path') {
  if (typeof relativePath !== 'string' || !relativePath || relativePath.includes('\\')
    || relativePath.includes('\0') || path.posix.isAbsolute(relativePath)
    || path.posix.normalize(relativePath) !== relativePath) {
    throw new Error(`${label} is not a safe relative path: ${String(relativePath)}`)
  }

  const parts = relativePath.split('/')
  if (parts.some(part => !part || part === '.' || part === '..' || part.startsWith('.'))
    || parts.some(part => part.toLowerCase() === '.phoenixbrain')) {
    throw new Error(`${label} contains a hidden or reserved component: ${relativePath}`)
  }
  if (PHOTO_EXTENSIONS.has(path.posix.extname(relativePath).toLowerCase())) {
    throw new Error(`${label} contains a forbidden photo-like file: ${relativePath}`)
  }
}

export async function inventoryRegularTree(rootPath) {
  const resolvedRoot = path.resolve(rootPath)
  const rootInfo = await lstat(resolvedRoot)
  if (rootInfo.isSymbolicLink() || !rootInfo.isDirectory()) {
    throw new Error(`audit root must be a real directory: ${resolvedRoot}`)
  }

  const files = {}
  const directories = []

  async function visit(directoryPath, relativeDirectory = '') {
    const entries = await readdir(directoryPath, { withFileTypes: true })
    entries.sort((left, right) => left.name.localeCompare(right.name, 'en'))
    for (const entry of entries) {
      const relative = relativeDirectory
        ? `${relativeDirectory}/${entry.name}`
        : entry.name
      assertSafePackagedPath(relative)
      const absolute = path.join(directoryPath, entry.name)
      const info = await lstat(absolute)
      if (info.isSymbolicLink()) {
        throw new Error(`staging must not contain symlinks: ${relative}`)
      }
      if (info.isDirectory()) {
        directories.push(relative)
        await visit(absolute, relative)
      } else if (info.isFile()) {
        const bytes = await readFile(absolute)
        files[relative] = { size: bytes.length, sha256: sha256(bytes) }
      } else {
        throw new Error(`staging contains a non-regular member: ${relative}`)
      }
    }
  }

  await visit(resolvedRoot)
  return { files, directories: directories.sort() }
}

export async function auditDesktopStage(stageRoot, options = {}) {
  const inventory = await inventoryRegularTree(stageRoot)
  const fileNames = Object.keys(inventory.files).sort()
  for (const required of REQUIRED_STAGE_FILES) {
    if (!inventory.files[required]) throw new Error(`staging is missing ${required}`)
  }
  for (const relative of fileNames) {
    if (REQUIRED_STAGE_FILES.has(relative)) continue
    if (!relative.startsWith('dist/assets/')
      || relative.slice('dist/assets/'.length).includes('/')) {
      throw new Error(`staging member is outside the desktop whitelist: ${relative}`)
    }
    const extension = path.posix.extname(relative).toLowerCase()
    if (!DIST_EXTENSIONS.has(extension)) {
      throw new Error(`staging contains an unsupported build asset: ${relative}`)
    }
  }

  const runtimePackage = JSON.parse(await readFile(path.join(stageRoot, 'package.json'), 'utf8'))
  const packageKeys = Object.keys(runtimePackage).sort()
  if (JSON.stringify(packageKeys) !== JSON.stringify(RUNTIME_PACKAGE_KEYS)) {
    throw new Error(`runtime package.json must contain only: ${RUNTIME_PACKAGE_KEYS.join(', ')}`)
  }
  if (runtimePackage.name !== 'nxr-photo-renamer-desktop'
    || runtimePackage.productName !== 'NXR Photo Renamer'
    || runtimePackage.main !== 'desktop/main.cjs'
    || typeof runtimePackage.version !== 'string' || !runtimePackage.version) {
    throw new Error('runtime package.json metadata is invalid')
  }
  if (options.expectedVersion && runtimePackage.version !== options.expectedVersion) {
    throw new Error(`runtime version mismatch: ${runtimePackage.version}`)
  }
  return { ...inventory, runtimePackage }
}

export async function auditAppAsar({
  asarPath,
  stageInventory,
  allowedGeneratedFiles = [],
  allowedGeneratedDirectories = [],
}) {
  const expectedFiles = { ...stageInventory.files }
  for (const generated of allowedGeneratedFiles) {
    assertSafePackagedPath(generated.path, 'generated ASAR path')
    if (!Number.isInteger(generated.size) || generated.size < 0
      || !/^[0-9a-f]{64}$/.test(generated.sha256)) {
      throw new Error(`generated ASAR metadata is invalid: ${generated.path}`)
    }
    if (expectedFiles[generated.path]) {
      throw new Error(`generated ASAR path duplicates staging: ${generated.path}`)
    }
    expectedFiles[generated.path] = { size: generated.size, sha256: generated.sha256 }
  }
  const expectedDirectories = new Set(stageInventory.directories)
  for (const directory of allowedGeneratedDirectories) {
    assertSafePackagedPath(directory, 'generated ASAR directory')
    expectedDirectories.add(directory)
  }

  const actualFiles = {}
  const actualDirectories = []
  for (const archiveMember of listPackage(asarPath, { isPack: false })) {
    const relative = archiveMember.replace(/^[/\\]+/, '').replaceAll('\\', '/')
    assertSafePackagedPath(relative, 'ASAR member')
    const lookupPath = asarLookupPath(archiveMember)
    const metadata = statFile(asarPath, lookupPath, false)
    if ('link' in metadata) throw new Error(`ASAR contains a symlink: ${relative}`)
    if ('files' in metadata) {
      actualDirectories.push(relative)
      continue
    }
    if ('unpacked' in metadata && metadata.unpacked) {
      throw new Error(`ASAR contains an unpacked member: ${relative}`)
    }
    const bytes = extractFile(asarPath, lookupPath, false)
    actualFiles[relative] = { size: bytes.length, sha256: sha256(bytes) }
  }
  actualDirectories.sort()

  const expectedFileNames = Object.keys(expectedFiles).sort()
  const actualFileNames = Object.keys(actualFiles).sort()
  if (JSON.stringify(actualFileNames) !== JSON.stringify(expectedFileNames)) {
    throw new Error(`ASAR file members differ from staging: expected ${expectedFileNames.length}, found ${actualFileNames.length}`)
  }
  const expectedDirectoryNames = [...expectedDirectories].sort()
  if (JSON.stringify(actualDirectories) !== JSON.stringify(expectedDirectoryNames)) {
    throw new Error('ASAR directory members differ from staging')
  }
  for (const relative of expectedFileNames) {
    const expected = expectedFiles[relative]
    const actual = actualFiles[relative]
    if (actual.size !== expected.size || actual.sha256 !== expected.sha256) {
      throw new Error(`ASAR content differs from staging: ${relative}`)
    }
  }
  return { files: actualFiles, directories: actualDirectories }
}

export async function inventoryReleasePayload(rootPath) {
  const resolvedRoot = path.resolve(rootPath)
  const rootInfo = await lstat(resolvedRoot)
  if (rootInfo.isSymbolicLink() || !rootInfo.isDirectory()) {
    throw new Error(`release payload must be a real directory: ${resolvedRoot}`)
  }
  const entries = {}

  async function visit(directoryPath, relativeDirectory = '') {
    const children = await readdir(directoryPath, { withFileTypes: true })
    children.sort((left, right) => left.name.localeCompare(right.name, 'en'))
    for (const child of children) {
      const relative = relativeDirectory ? `${relativeDirectory}/${child.name}` : child.name
      if (relative.split('/').some(part => !part || part === '.' || part === '..'
        || part.toLowerCase() === '.phoenixbrain')) {
        throw new Error(`release payload contains a reserved path: ${relative}`)
      }
      const absolute = path.join(directoryPath, child.name)
      const info = await lstat(absolute)
      if (info.isSymbolicLink()) {
        const target = await readlink(absolute)
        const resolvedTarget = path.resolve(path.dirname(absolute), target)
        if (resolvedTarget !== resolvedRoot && !resolvedTarget.startsWith(`${resolvedRoot}${path.sep}`)) {
          throw new Error(`release symlink escapes its target directory: ${relative}`)
        }
        entries[relative] = { type: 'symlink', target }
      } else if (info.isDirectory()) {
        entries[relative] = { type: 'directory' }
        await visit(absolute, relative)
      } else if (info.isFile()) {
        const bytes = await readFile(absolute)
        entries[relative] = { type: 'file', size: bytes.length, sha256: sha256(bytes) }
      } else {
        throw new Error(`release payload contains a non-regular member: ${relative}`)
      }
    }
  }

  await visit(resolvedRoot)
  return entries
}

export async function auditZipArchive({ zipPath, payloadPath, payloadInventory }) {
  const archiveInfo = await lstat(zipPath)
  if (!archiveInfo.isFile() || archiveInfo.isSymbolicLink()) {
    throw new Error(`ZIP archive must be a regular file: ${zipPath}`)
  }
  const archive = await readFile(zipPath)
  const members = readZipMembers(archive)
  const payloadName = path.basename(payloadPath)
  const expected = {}
  for (const [relative, metadata] of Object.entries(payloadInventory)) {
    if (metadata.type === 'directory') continue
    const memberName = `${payloadName}/${relative}`
    expected[memberName] = metadata.type === 'file'
      ? { type: 'file', size: metadata.size, sha256: metadata.sha256 }
      : {
          type: 'symlink',
          size: Buffer.byteLength(metadata.target),
          sha256: sha256(Buffer.from(metadata.target)),
        }
  }

  const actual = {}
  for (const member of members) {
    const name = normalizeZipMember(member.name)
    if (member.directory) continue
    if (actual[name]) throw new Error(`ZIP contains a duplicate member: ${name}`)
    actual[name] = {
      type: member.symlink ? 'symlink' : 'file',
      size: member.bytes.length,
      sha256: sha256(member.bytes),
    }
  }
  const expectedNames = Object.keys(expected).sort()
  const actualNames = Object.keys(actual).sort()
  if (JSON.stringify(actualNames) !== JSON.stringify(expectedNames)) {
    throw new Error(`ZIP members differ from payload: expected ${expectedNames.length}, found ${actualNames.length}`)
  }
  for (const name of expectedNames) {
    const expectedEntry = expected[name]
    const actualEntry = actual[name]
    if (actualEntry.type !== expectedEntry.type
      || actualEntry.size !== expectedEntry.size
      || actualEntry.sha256 !== expectedEntry.sha256) {
      throw new Error(`ZIP content differs from payload: ${name}`)
    }
  }
  return actual
}

function readZipMembers(archive) {
  const eocdOffset = findEndOfCentralDirectory(archive)
  const diskNumber = archive.readUInt16LE(eocdOffset + 4)
  const centralDisk = archive.readUInt16LE(eocdOffset + 6)
  const entriesOnDisk = archive.readUInt16LE(eocdOffset + 8)
  const entryCount = archive.readUInt16LE(eocdOffset + 10)
  const centralSize = archive.readUInt32LE(eocdOffset + 12)
  const centralOffset = archive.readUInt32LE(eocdOffset + 16)
  if (diskNumber !== 0 || centralDisk !== 0 || entriesOnDisk !== entryCount) {
    throw new Error('multi-disk ZIP archives are not supported')
  }
  if (entryCount === 0xffff || centralSize === 0xffffffff || centralOffset === 0xffffffff) {
    throw new Error('ZIP64 archives are not supported by the desktop release audit')
  }
  if (centralOffset + centralSize > eocdOffset) throw new Error('ZIP central directory is out of bounds')

  const members = []
  let offset = centralOffset
  for (let index = 0; index < entryCount; index += 1) {
    if (offset + 46 > archive.length || archive.readUInt32LE(offset) !== 0x02014b50) {
      throw new Error(`invalid ZIP central entry at index ${index}`)
    }
    const flags = archive.readUInt16LE(offset + 8)
    const method = archive.readUInt16LE(offset + 10)
    const expectedCrc = archive.readUInt32LE(offset + 16)
    const compressedSize = archive.readUInt32LE(offset + 20)
    const uncompressedSize = archive.readUInt32LE(offset + 24)
    const nameLength = archive.readUInt16LE(offset + 28)
    const extraLength = archive.readUInt16LE(offset + 30)
    const commentLength = archive.readUInt16LE(offset + 32)
    const externalAttributes = archive.readUInt32LE(offset + 38)
    const localOffset = archive.readUInt32LE(offset + 42)
    if (flags & 0x1) throw new Error('encrypted ZIP members are not supported')
    if ([compressedSize, uncompressedSize, localOffset].includes(0xffffffff)) {
      throw new Error('ZIP64 members are not supported by the desktop release audit')
    }
    const nameStart = offset + 46
    const nameEnd = nameStart + nameLength
    if (nameEnd + extraLength + commentLength > archive.length) {
      throw new Error('ZIP central entry exceeds the archive boundary')
    }
    const name = archive.subarray(nameStart, nameEnd).toString('utf8')
    const directory = name.endsWith('/')
    const unixMode = externalAttributes >>> 16
    const symlink = (unixMode & 0o170000) === 0o120000
    const bytes = directory
      ? Buffer.alloc(0)
      : extractZipMember(archive, localOffset, compressedSize, uncompressedSize, method)
    if (!directory && crc32(bytes) !== expectedCrc) {
      throw new Error(`ZIP member failed CRC verification: ${name}`)
    }
    members.push({ name, directory, symlink, bytes })
    offset = nameEnd + extraLength + commentLength
  }
  if (offset !== centralOffset + centralSize) throw new Error('ZIP central directory size mismatch')
  return members
}

function findEndOfCentralDirectory(archive) {
  const minimumOffset = Math.max(0, archive.length - 65_557)
  for (let offset = archive.length - 22; offset >= minimumOffset; offset -= 1) {
    if (archive.readUInt32LE(offset) !== 0x06054b50) continue
    const commentLength = archive.readUInt16LE(offset + 20)
    if (offset + 22 + commentLength === archive.length) return offset
  }
  throw new Error('ZIP end-of-central-directory record was not found')
}

function extractZipMember(archive, localOffset, compressedSize, uncompressedSize, method) {
  if (localOffset + 30 > archive.length || archive.readUInt32LE(localOffset) !== 0x04034b50) {
    throw new Error('invalid ZIP local file header')
  }
  const nameLength = archive.readUInt16LE(localOffset + 26)
  const extraLength = archive.readUInt16LE(localOffset + 28)
  const dataStart = localOffset + 30 + nameLength + extraLength
  const dataEnd = dataStart + compressedSize
  if (dataEnd > archive.length) throw new Error('ZIP member data exceeds the archive boundary')
  const compressed = archive.subarray(dataStart, dataEnd)
  const bytes = method === 0 ? Buffer.from(compressed)
    : method === 8 ? inflateRawSync(compressed)
      : null
  if (!bytes) throw new Error(`unsupported ZIP compression method: ${method}`)
  if (bytes.length !== uncompressedSize) throw new Error('ZIP member size mismatch')
  return bytes
}

function normalizeZipMember(memberName) {
  const normalized = memberName.replaceAll('\\', '/')
  if (!normalized || normalized.startsWith('/') || normalized.includes('\0')
    || path.posix.normalize(normalized) !== normalized
    || normalized.split('/').some(part => part === '..' || part.toLowerCase() === '.phoenixbrain')) {
    throw new Error(`ZIP contains an unsafe member path: ${memberName}`)
  }
  return normalized.replace(/\/$/, '')
}

function crc32(bytes) {
  let value = 0xffffffff
  for (const byte of bytes) {
    value ^= byte
    for (let bit = 0; bit < 8; bit += 1) {
      value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0)
    }
  }
  return (value ^ 0xffffffff) >>> 0
}
