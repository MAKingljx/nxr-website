import { spawn } from 'node:child_process'
import {
  copyFile,
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rename,
  rm,
  writeFile,
} from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

import { packager } from '@electron/packager'

import {
  assertSafePackagedPath,
  auditAppAsar,
  auditDesktopStage,
  auditZipArchive,
  inventoryReleasePayload,
  sha256File,
} from './audit-desktop.mjs'

const APP_NAME = 'NXR Photo Renamer'
const RUNTIME_NAME = 'nxr-photo-renamer-desktop'
const APP_BUNDLE_ID = 'com.nxrgrading.photo-renamer'
const PACKAGING_PACKAGES = [
  'electron',
  '@electron/packager',
  '@electron/asar',
]
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const defaultProjectRoot = path.resolve(scriptDirectory, '..')

export function parseDesktopTarget(argv, hostPlatform = process.platform, hostArch = process.arch) {
  let platform
  let arch
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--platform' || argument === '--arch') {
      const value = argv[index + 1]
      if (!value || value.startsWith('--')) throw new Error(`${argument} requires a value`)
      if (argument === '--platform') platform = value
      else arch = value
      index += 1
    } else if (argument === '--help') {
      return { help: true }
    } else {
      throw new Error(`unknown desktop packaging argument: ${argument}`)
    }
  }

  platform ??= hostPlatform
  if (platform !== 'darwin' && platform !== 'win32') {
    throw new Error('desktop platform must be darwin or win32')
  }
  if (platform === 'darwin') {
    arch ??= hostArch === 'arm64' ? 'arm64' : 'x64'
    if (!['universal', 'arm64', 'x64'].includes(arch)) {
      throw new Error('darwin arch must be universal, arm64, or x64')
    }
    if (hostPlatform !== 'darwin') {
      throw new Error('darwin packages require a macOS host for ad-hoc signing')
    }
  } else {
    arch ??= 'x64'
    if (arch !== 'x64') throw new Error('win32 arch must be x64')
  }
  return { platform, arch, help: false }
}

export async function createDesktopStage({ projectRoot, stageRoot }) {
  const root = path.resolve(projectRoot)
  const destination = path.resolve(stageRoot)
  const projectPackage = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'))
  if (typeof projectPackage.version !== 'string' || !projectPackage.version) {
    throw new Error('project package.json has no valid version')
  }

  await mkdir(destination)
  await mkdir(path.join(destination, 'desktop'))
  await copyRequiredFile(
    path.join(root, 'desktop', 'main.cjs'),
    path.join(destination, 'desktop', 'main.cjs'),
  )
  await copyRequiredFile(
    path.join(root, 'desktop', 'policy.cjs'),
    path.join(destination, 'desktop', 'policy.cjs'),
  )
  await copyDistTree(path.join(root, 'dist'), path.join(destination, 'dist'), 'dist')

  const runtimePackage = {
    name: RUNTIME_NAME,
    productName: APP_NAME,
    version: projectPackage.version,
    main: 'desktop/main.cjs',
  }
  await writeFile(
    path.join(destination, 'package.json'),
    `${JSON.stringify(runtimePackage, null, 2)}\n`,
    { encoding: 'utf8', mode: 0o644 },
  )
  return auditDesktopStage(destination, { expectedVersion: projectPackage.version })
}

export async function packageDesktop({
  projectRoot = defaultProjectRoot,
  platform,
  arch,
} = {}) {
  const root = path.resolve(projectRoot)
  const target = parseDesktopTarget([
    ...(platform ? ['--platform', platform] : []),
    ...(arch ? ['--arch', arch] : []),
  ])
  const projectPackage = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'))
  const version = projectPackage.version
  if (typeof version !== 'string' || !/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(version)) {
    throw new Error(`invalid desktop release version: ${String(version)}`)
  }
  const packagingVersions = await verifyPackagingVersions(root, projectPackage)
  const releaseRoot = path.join(root, 'release')
  const versionRoot = path.join(releaseRoot, version)
  await ensureRealDirectory(releaseRoot)
  await ensureRealDirectory(versionRoot)

  const targetSlug = `${target.platform}-${target.arch}`
  const finalTarget = path.join(versionRoot, targetSlug)
  if (await pathExists(finalTarget)) {
    throw new Error(`desktop target already exists: ${finalTarget}`)
  }

  const workRoot = await mkdtemp(path.join(versionRoot, 'desktop-package-work-'))
  try {
    const stageRoot = path.join(workRoot, 'stage')
    const stageInventory = await createDesktopStage({ projectRoot: root, stageRoot })
    const packagerOutput = path.join(workRoot, 'packager-output')
    await mkdir(packagerOutput)
    const packagedPaths = await packager({
      dir: stageRoot,
      out: packagerOutput,
      name: APP_NAME,
      executableName: APP_NAME,
      platform: target.platform,
      arch: target.arch,
      electronVersion: packagingVersions.electron,
      appVersion: version,
      buildVersion: version,
      appBundleId: APP_BUNDLE_ID,
      asar: true,
      prune: false,
      derefSymlinks: false,
      overwrite: false,
      sanitizePackageJson: [runtimePackage => runtimePackage],
      osxUniversal: target.arch === 'universal' ? { mergeASARs: true } : undefined,
      win32metadata: target.platform === 'win32' ? {
        CompanyName: 'NXR Grading',
        FileDescription: 'NXR Photo Renamer',
        ProductName: 'NXR Photo Renamer',
        InternalName: 'NXRPhotoRenamer',
        OriginalFilename: `${APP_NAME}.exe`,
        'requested-execution-level': 'asInvoker',
      } : undefined,
    })
    if (packagedPaths.length !== 1 || typeof packagedPaths[0] !== 'string') {
      throw new Error(`packager returned ${packagedPaths.length} targets; expected one`)
    }

    const candidate = path.join(workRoot, 'candidate')
    await mkdir(candidate)
    const payload = await arrangePackagerOutput({
      packagedPath: packagedPaths[0],
      candidate,
      platform: target.platform,
      electronVersion: packagingVersions.electron,
    })
    const asarPath = appAsarPath(payload, target.platform)
    await assertSingleApplicationAsar(asarPath)
    const asarInventory = await auditAppAsar({ asarPath, stageInventory })

    if (target.platform === 'darwin') await signAndVerifyMacApp(payload)

    const payloadInventory = await inventoryReleasePayload(payload)
    const archiveName = `NXR-Photo-Renamer-${version}-${targetSlug}.zip`
    const archivePath = path.join(candidate, archiveName)
    await createZip(payload, archivePath)
    const zipInventory = await auditZipArchive({
      zipPath: archivePath,
      payloadPath: payload,
      payloadInventory,
    })
    const archive = {
      file: archiveName,
      size: (await lstat(archivePath)).size,
      sha256: await sha256File(archivePath),
      entries: zipInventory,
    }
    const manifest = {
      schemaVersion: 1,
      application: APP_NAME,
      version,
      platform: target.platform,
      arch: target.arch,
      electronVersion: packagingVersions.electron,
      createdAt: new Date().toISOString(),
      payload: {
        path: path.basename(payload),
        entries: payloadInventory,
      },
      staging: {
        files: stageInventory.files,
        directories: stageInventory.directories,
      },
      appAsar: {
        path: path.relative(candidate, asarPath).split(path.sep).join('/'),
        files: asarInventory.files,
        directories: asarInventory.directories,
      },
      archive,
    }
    const manifestPath = path.join(candidate, 'manifest.json')
    await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
    const sums = [
      `${archive.sha256}  ${archiveName}`,
      `${await sha256File(manifestPath)}  manifest.json`,
    ]
    await writeFile(path.join(candidate, 'SHA256SUMS'), `${sums.join('\n')}\n`, 'utf8')

    if (await pathExists(finalTarget)) {
      throw new Error(`desktop target appeared during packaging: ${finalTarget}`)
    }
    await rename(candidate, finalTarget)
    return {
      target: finalTarget,
      payload: path.join(finalTarget, path.basename(payload)),
      archive: path.join(finalTarget, archiveName),
      manifest: path.join(finalTarget, 'manifest.json'),
      sha256: path.join(finalTarget, 'SHA256SUMS'),
    }
  } finally {
    await removePackagingWorkRoot(workRoot, versionRoot)
  }
}

async function copyRequiredFile(source, destination) {
  const info = await lstat(source)
  if (info.isSymbolicLink() || !info.isFile()) {
    throw new Error(`desktop source must be a regular file: ${source}`)
  }
  await copyFile(source, destination)
}

async function copyDistTree(source, destination, relative) {
  assertSafePackagedPath(relative)
  const sourceInfo = await lstat(source)
  if (sourceInfo.isSymbolicLink() || !sourceInfo.isDirectory()) {
    throw new Error(`dist source must be a real directory: ${source}`)
  }
  await mkdir(destination)
  const entries = await readdir(source, { withFileTypes: true })
  entries.sort((left, right) => left.name.localeCompare(right.name, 'en'))
  for (const entry of entries) {
    const childRelative = `${relative}/${entry.name}`
    assertSafePackagedPath(childRelative)
    const sourcePath = path.join(source, entry.name)
    const destinationPath = path.join(destination, entry.name)
    const info = await lstat(sourcePath)
    if (info.isSymbolicLink()) throw new Error(`dist must not contain symlinks: ${childRelative}`)
    if (info.isDirectory()) {
      await copyDistTree(sourcePath, destinationPath, childRelative)
    } else if (info.isFile()) {
      await copyFile(sourcePath, destinationPath)
    } else {
      throw new Error(`dist contains a non-regular member: ${childRelative}`)
    }
  }
}

async function verifyPackagingVersions(projectRoot, projectPackage) {
  const versions = {}
  for (const packageName of PACKAGING_PACKAGES) {
    const declared = projectPackage.devDependencies?.[packageName]
    if (typeof declared !== 'string' || !/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(declared)) {
      throw new Error(`${packageName} must use an exact devDependency version`)
    }
    const installedPackage = JSON.parse(await readFile(
      path.join(projectRoot, 'node_modules', ...packageName.split('/'), 'package.json'),
      'utf8',
    ))
    if (installedPackage.version !== declared) {
      throw new Error(`${packageName} installed version does not match package.json`)
    }
    versions[packageName] = declared
  }
  return {
    electron: versions.electron,
    packager: versions['@electron/packager'],
    asar: versions['@electron/asar'],
  }
}

async function arrangePackagerOutput({ packagedPath, candidate, platform, electronVersion }) {
  const source = path.resolve(packagedPath)
  if (platform === 'win32') {
    const portable = path.join(candidate, 'NXR-Photo-Renamer-portable')
    await rename(source, portable)
    return portable
  }

  const sourceInfo = await lstat(source)
  if (!sourceInfo.isDirectory() || sourceInfo.isSymbolicLink()) {
    throw new Error(`packager produced an invalid macOS output: ${source}`)
  }
  let appSource = source
  if (!source.endsWith('.app')) {
    const entries = await readdir(source, { withFileTypes: true })
    const appEntries = entries.filter(entry => entry.isDirectory() && entry.name.endsWith('.app'))
    const allowedNames = new Set([
      appEntries[0]?.name,
      'LICENSE',
      'LICENSES.chromium.html',
      'version',
    ])
    if (appEntries.length !== 1 || entries.some(entry => !allowedNames.has(entry.name))) {
      throw new Error(`macOS packager output contains unexpected members: ${entries.map(entry => entry.name).join(', ')}`)
    }
    appSource = path.join(source, appEntries[0].name)
  }
  const appDestination = path.join(candidate, path.basename(appSource))
  await rename(appSource, appDestination)

  if (source !== appSource) {
    const runtimeResources = path.join(
      appDestination,
      'Contents',
      'Resources',
      'electron-runtime',
    )
    await mkdir(runtimeResources)
    for (const name of ['LICENSE', 'LICENSES.chromium.html', 'version']) {
      const generatedPath = path.join(source, name)
      const info = await lstat(generatedPath)
      if (!info.isFile() || info.isSymbolicLink()) {
        throw new Error(`macOS packager generated an unsafe ${name}`)
      }
      await rename(generatedPath, path.join(runtimeResources, name))
    }
    const generatedVersion = (await readFile(path.join(runtimeResources, 'version'), 'utf8')).trim()
    if (generatedVersion !== electronVersion) {
      throw new Error(`packaged Electron version mismatch: ${generatedVersion}`)
    }
  }
  return appDestination
}

function appAsarPath(payload, platform) {
  return platform === 'darwin'
    ? path.join(payload, 'Contents', 'Resources', 'app.asar')
    : path.join(payload, 'resources', 'app.asar')
}

async function assertSingleApplicationAsar(asarPath) {
  const info = await lstat(asarPath)
  if (!info.isFile() || info.isSymbolicLink()) throw new Error('final app.asar is missing or unsafe')
  const resources = path.dirname(asarPath)
  const entries = await readdir(resources)
  const applicationAsars = entries.filter(name => /^app(?:-|\.)[^/]*asar(?:\.unpacked)?$/i.test(name))
  if (applicationAsars.length !== 1 || applicationAsars[0] !== 'app.asar') {
    throw new Error(`unexpected application ASAR outputs: ${applicationAsars.join(', ')}`)
  }
}

async function signAndVerifyMacApp(appPath) {
  await runCommand('codesign', ['--force', '--deep', '--sign', '-', appPath])
  await runCommand('codesign', ['--verify', '--deep', '--strict', '--verbose=2', appPath])
}

async function createZip(payload, archivePath) {
  if (process.platform === 'darwin') {
    const arguments_ = [
      '-c', '-k', '--norsrc', '--noextattr', '--noqtn', '--noacl',
      '--keepParent', payload, archivePath,
    ]
    await runCommand('ditto', arguments_)
    return
  }
  if (process.platform === 'win32') {
    // Compress-Archive can omit hidden files. ZipFile includes every runtime
    // member; fixed environment names avoid quoting paths with spaces.
    const command = [
      '& {',
      "$ErrorActionPreference = 'Stop'",
      'Add-Type -AssemblyName System.IO.Compression.FileSystem',
      '[System.IO.Compression.ZipFile]::CreateFromDirectory($env:NXR_PACKAGE_SOURCE, $env:NXR_PACKAGE_ARCHIVE, [System.IO.Compression.CompressionLevel]::Optimal, $true)',
      '}',
    ].join('; ')
    await runCommand('powershell.exe', [
      '-NoLogo', '-NoProfile', '-NonInteractive', '-Command', command,
    ], {
      env: {
        ...process.env,
        NXR_PACKAGE_SOURCE: payload,
        NXR_PACKAGE_ARCHIVE: archivePath,
      },
    })
    return
  }
  await runCommand('zip', ['-q', '-r', archivePath, path.basename(payload)], {
    cwd: path.dirname(payload),
  })
}

async function runCommand(command, arguments_, options = {}) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, arguments_, {
      cwd: options.cwd,
      env: options.env,
      stdio: ['ignore', 'inherit', 'inherit'],
      windowsHide: true,
    })
    child.once('error', reject)
    child.once('exit', (code, signal) => {
      if (code === 0) resolve()
      else reject(new Error(`${command} failed with ${signal ? `signal ${signal}` : `exit ${code}`}`))
    })
  })
}

async function ensureRealDirectory(directoryPath) {
  if (!(await pathExists(directoryPath))) {
    await mkdir(directoryPath)
    return
  }
  const info = await lstat(directoryPath)
  if (!info.isDirectory() || info.isSymbolicLink()) {
    throw new Error(`release path must be a real directory: ${directoryPath}`)
  }
}

async function pathExists(targetPath) {
  try {
    await lstat(targetPath)
    return true
  } catch (error) {
    if (error?.code === 'ENOENT') return false
    throw error
  }
}

async function removePackagingWorkRoot(workRoot, versionRoot) {
  const relative = path.relative(versionRoot, workRoot)
  if (relative.includes(path.sep) || !relative.startsWith('desktop-package-work-')) {
    throw new Error(`refusing to remove unexpected packaging path: ${workRoot}`)
  }
  await rm(workRoot, { recursive: true, force: true })
}

function usage() {
  return [
    'Usage: node scripts/package-desktop.mjs [--platform darwin|win32] [--arch universal|arm64|x64]',
    'darwin: universal, arm64, or x64; win32: x64 only.',
  ].join('\n')
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const target = parseDesktopTarget(process.argv.slice(2))
    if (target.help) {
      process.stdout.write(`${usage()}\n`)
    } else {
      const result = await packageDesktop(target)
      process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
    }
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
    process.exitCode = 1
  }
}
