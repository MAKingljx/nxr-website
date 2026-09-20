import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { mkdtemp, mkdir, readFile, rm, symlink, unlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test, { type TestContext } from 'node:test'
import { promisify } from 'node:util'

import { createPackage } from '@electron/asar'

import {
  auditAppAsar,
  auditDesktopStage,
  auditZipArchive,
  asarLookupPath,
  inventoryReleasePayload,
} from '../scripts/audit-desktop.mjs'
import {
  createDesktopStage,
  parseDesktopTarget,
} from '../scripts/package-desktop.mjs'

const execFileAsync = promisify(execFile)

async function createProjectFixture(context: TestContext) {
  const root = await mkdtemp(path.join(tmpdir(), 'nxr-desktop-package-test-'))
  context.after(() => rm(root, { recursive: true, force: true }))
  await mkdir(path.join(root, 'desktop'), { recursive: true })
  await mkdir(path.join(root, 'dist', 'assets'), { recursive: true })
  await writeFile(path.join(root, 'package.json'), JSON.stringify({
    name: 'source-project',
    version: '0.2.0',
    dependencies: { 'source-only-package': '1.0.0' },
  }))
  await writeFile(path.join(root, 'desktop', 'main.cjs'), 'require("./policy.cjs")\n')
  await writeFile(path.join(root, 'desktop', 'policy.cjs'), 'module.exports = {}\n')
  await writeFile(path.join(root, 'desktop', 'preload.cjs'), 'void 0\n')
  await writeFile(path.join(root, 'dist', 'index.html'), '<main>local app</main>\n')
  await writeFile(path.join(root, 'dist', 'assets', 'main.js'), 'console.log("local")\n')
  await writeFile(path.join(root, '.PhoenixBrain'), 'private routing marker\n')
  return root
}

test('desktop target parser accepts only supported platform and architecture pairs', () => {
  assert.deepEqual(
    parseDesktopTarget(['--platform', 'darwin', '--arch', 'universal'], 'darwin', 'arm64'),
    { platform: 'darwin', arch: 'universal', help: false },
  )
  assert.deepEqual(
    parseDesktopTarget(['--platform', 'win32'], 'darwin', 'arm64'),
    { platform: 'win32', arch: 'x64', help: false },
  )
  assert.deepEqual(
    parseDesktopTarget([], 'darwin', 'arm64'),
    { platform: 'darwin', arch: 'arm64', help: false },
  )
  assert.throws(
    () => parseDesktopTarget(['--platform', 'win32', '--arch', 'arm64'], 'darwin', 'arm64'),
    /win32 arch must be x64/,
  )
  assert.throws(
    () => parseDesktopTarget(['--platform', 'darwin'], 'win32', 'x64'),
    /require a macOS host/,
  )
})

test('desktop staging contains only runtime metadata, host files and built assets', async (context) => {
  const root = await createProjectFixture(context)
  const stage = path.join(root, 'stage')
  const inventory = await createDesktopStage({ projectRoot: root, stageRoot: stage })

  assert.deepEqual(Object.keys(inventory.files).sort(), [
    'desktop/main.cjs',
    'desktop/policy.cjs',
    'desktop/preload.cjs',
    'dist/assets/main.js',
    'dist/index.html',
    'package.json',
  ])
  const runtimePackage = JSON.parse(await readFile(path.join(stage, 'package.json'), 'utf8'))
  assert.deepEqual(runtimePackage, {
    name: 'nxr-photo-renamer-desktop',
    productName: 'NXR Photo Renamer',
    version: '0.2.0',
    main: 'desktop/main.cjs',
  })
  assert.equal('dependencies' in runtimePackage, false)
  assert.equal('.PhoenixBrain' in inventory.files, false)
})

test('stage audit rejects reserved metadata, photo-like files and symlinks', async (context) => {
  const root = await createProjectFixture(context)
  const stage = path.join(root, 'stage')
  await createDesktopStage({ projectRoot: root, stageRoot: stage })

  await writeFile(path.join(stage, '.PhoenixBrain'), 'must not ship\n')
  await assert.rejects(() => auditDesktopStage(stage), /hidden or reserved/)
  await unlink(path.join(stage, '.PhoenixBrain'))

  await writeFile(path.join(stage, 'dist', 'assets', 'synthetic-photo.jpg'), 'not image data\n')
  await assert.rejects(() => auditDesktopStage(stage), /photo-like/)
  await unlink(path.join(stage, 'dist', 'assets', 'synthetic-photo.jpg'))

  try {
    await symlink('main.js', path.join(stage, 'dist', 'assets', 'linked.js'))
  } catch (error) {
    if (error instanceof Error && 'code' in error && error.code === 'EPERM') {
      context.skip('host does not permit creating a test symlink')
      return
    }
    throw error
  }
  await assert.rejects(() => auditDesktopStage(stage), /must not contain symlinks/)
})

test('ASAR lookup paths use the host separator without a root separator', () => {
  assert.equal(asarLookupPath('/dist/assets/main.js', '/'), 'dist/assets/main.js')
  assert.equal(asarLookupPath('\\dist\\assets\\main.js', '\\'), 'dist\\assets\\main.js')
  assert.equal(asarLookupPath('/dist/assets/main.js', '\\'), 'dist\\assets\\main.js')
})

test('ASAR audit compares exact members and hashes with the audited stage', async (context) => {
  const root = await createProjectFixture(context)
  const stage = path.join(root, 'stage')
  const inventory = await createDesktopStage({ projectRoot: root, stageRoot: stage })
  const exactAsar = path.join(root, 'exact.asar')
  await createPackage(stage, exactAsar)

  const exact = await auditAppAsar({ asarPath: exactAsar, stageInventory: inventory })
  assert.deepEqual(exact.files, inventory.files)
  assert.deepEqual(exact.directories, inventory.directories)

  await writeFile(path.join(stage, 'dist', 'assets', 'injected.js'), 'unexpected member\n')
  const extraAsar = path.join(root, 'extra.asar')
  await createPackage(stage, extraAsar)
  await assert.rejects(
    () => auditAppAsar({ asarPath: extraAsar, stageInventory: inventory }),
    /file members differ/,
  )
  await unlink(path.join(stage, 'dist', 'assets', 'injected.js'))

  await writeFile(path.join(stage, 'dist', 'assets', 'main.js'), 'changed after stage audit\n')
  const changedAsar = path.join(root, 'changed.asar')
  await createPackage(stage, changedAsar)
  await assert.rejects(
    () => auditAppAsar({ asarPath: changedAsar, stageInventory: inventory }),
    /content differs from staging/,
  )
})

test('ditto ZIP audit verifies exact payload bytes, hidden runtime files and symlinks', async (context) => {
  if (process.platform !== 'darwin') {
    context.skip('ditto ZIP verification runs on macOS')
    return
  }
  const root = await mkdtemp(path.join(tmpdir(), 'nxr-desktop-zip-test-'))
  context.after(() => rm(root, { recursive: true, force: true }))
  const payload = path.join(root, 'Runtime Payload')
  await mkdir(payload)
  await writeFile(path.join(payload, 'runtime.bin'), 'runtime bytes\n')
  await writeFile(path.join(payload, '.runtime-hidden'), 'hidden runtime bytes\n')
  await symlink('runtime.bin', path.join(payload, 'runtime-link'))
  const payloadInventory = await inventoryReleasePayload(payload)
  const archive = path.join(root, 'runtime.zip')

  await execFileAsync('ditto', [
    '-c', '-k', '--norsrc', '--noextattr', '--noqtn', '--noacl',
    '--keepParent', payload, archive,
  ])
  const zipInventory = await auditZipArchive({
    zipPath: archive,
    payloadPath: payload,
    payloadInventory,
  })

  assert.equal(zipInventory['Runtime Payload/runtime.bin']?.type, 'file')
  assert.equal(zipInventory['Runtime Payload/.runtime-hidden']?.type, 'file')
  assert.equal(zipInventory['Runtime Payload/runtime-link']?.type, 'symlink')
})
