import { createHash } from 'node:crypto'

import { expect, test, type Page } from '@playwright/test'

import {
  blankPhoto,
  directoryFiles,
  installOpfsPicker,
  materializeTestDirectory,
  qrPhoto,
  seedOpfsDirectory,
  uniqueDirectory,
  writeCanvasPhoto,
  type TestPhoto,
} from './fixtures'

const CERT_A = '7123456789'
const CERT_B = '8123456789'

function cardUrl(certId: string): string {
  return `https://nxrgrading.com/card/${certId}`
}

function brokenPhoto(name: string): TestPhoto {
  const content = Buffer.from('not-a-decodable-image')
  return {
    name,
    bytesBase64: content.toString('base64'),
    sha256: createHash('sha256').update(content).digest('hex'),
  }
}

async function openDirectory(page: Page): Promise<void> {
  await page.getByRole('button', { name: '选择照片文件夹', exact: true }).click()
}

async function scan(page: Page): Promise<void> {
  await page.getByRole('button', { name: '开始识别', exact: true }).click()
  await expect(page.getByRole('button', { name: '重新识别', exact: true })).toBeEnabled()
}

async function confirmAction(page: Page, action: '确认改名' | '确认恢复'): Promise<void> {
  const dialog = page.getByRole('dialog').filter({ has: page.getByRole('button', { name: action, exact: true }) })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: action, exact: true }).click()
  await expect(dialog).toBeHidden()
}

function photoRow(page: Page, name: string) {
  return page.getByTestId('photo-row').filter({ hasText: name })
}

async function pixelSnapshots(page: Page, directoryName: string, names: string[]) {
  return page.evaluate(async ({ folder, files }) => {
    const directory = await (await navigator.storage.getDirectory()).getDirectoryHandle(folder)
    const results = []
    for (const name of files) {
      const bitmap = await createImageBitmap(await (await directory.getFileHandle(name)).getFile())
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height)
      const context = canvas.getContext('2d')!
      context.drawImage(bitmap, 0, 0)
      const rgba = context.getImageData(0, 0, bitmap.width, bitmap.height).data
      const digest = await crypto.subtle.digest('SHA-256', rgba)
      results.push({ width: bitmap.width, height: bitmap.height, rgba: Array.from(new Uint8Array(digest)).join(',') })
      bitmap.close()
    }
    return results
  }, { folder: directoryName, files: names })
}

async function conversionRecord(page: Page, directoryName: string) {
  return page.evaluate(async (folder) => {
    const directory = await (await navigator.storage.getDirectory()).getDirectoryHandle(folder)
    let journal: any
    for await (const [name, handle] of directory.entries()) {
      if (name.startsWith('.nxr-rename-') && handle.kind === 'file') {
        journal = JSON.parse(await (await (handle as FileSystemFileHandle).getFile()).text())
      }
    }
    const originals = await directory.getDirectoryHandle(journal.backupDirectory)
    const backupHashes: string[] = [], backupNames: string[] = [], lossless: boolean[] = []
    for (const entry of journal.entries) {
      backupNames.push(entry.backupName)
      const original = await (await originals.getFileHandle(entry.backupName)).getFile()
      const digest = await crypto.subtle.digest('SHA-256', await original.arrayBuffer())
      backupHashes.push(Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join(''))
      const encoded = new Uint8Array(await (await (await directory.getFileHandle(entry.targetName)).getFile()).arrayBuffer())
      const view = new DataView(encoded.buffer)
      let found = false
      for (let offset = 12; offset + 8 <= encoded.length;) {
        const chunk = String.fromCharCode(...encoded.slice(offset, offset + 4))
        if (chunk === 'VP8L') found = true
        offset += 8 + view.getUint32(offset + 4, true) + (view.getUint32(offset + 4, true) & 1)
      }
      lossless.push(String.fromCharCode(...encoded.slice(0, 4)) === 'RIFF'
        && String.fromCharCode(...encoded.slice(8, 12)) === 'WEBP' && found)
    }
    return { backupHashes, backupNames, lossless }
  }, directoryName)
}

test('从真实 OPFS 目录识别后输出无损 WebP，像素不变且可恢复原字节', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const photos = [
    blankPhoto('0001-front.png'),
    await qrPhoto('0002-back.png', cardUrl(CERT_A), { dark: '#172554', light: '#ffffff' }),
  ]

  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  await openDirectory(page)
  await scan(page)

  await expect(page.getByTestId('photo-row')).toHaveCount(2)
  await expect(page.getByTestId('pair-row')).toHaveCount(1)
  await expect(page.getByTestId('pair-row').getByLabel(/^证书号 /)).toHaveValue(CERT_A)

  const originalPixels = await pixelSnapshots(page, directoryName, photos.map(item => item.name))
  await page.getByRole('button', { name: '执行改名', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
  const beforeConfirmation = await directoryFiles(page, directoryName)
  expect(beforeConfirmation['0001-front.png'].sha256).toBe(photos[0].sha256)
  expect(beforeConfirmation['0002-back.png'].sha256).toBe(photos[1].sha256)

  await page.getByRole('button', { name: '执行改名', exact: true }).click()
  await confirmAction(page, '确认改名')
  await expect(page.getByRole('status').filter({ hasText: /已完成 2 张图片改名/ })).toBeVisible()

  await expect
    .poll(async () => Object.keys(await directoryFiles(page, directoryName)))
    .toEqual(expect.arrayContaining([`${CERT_A}_A.webp`, `${CERT_A}_B.webp`]))
  const renamed = await directoryFiles(page, directoryName)
  expect(Object.keys(renamed)).toEqual(
    expect.arrayContaining([`${CERT_A}_A.webp`, `${CERT_A}_B.webp`]),
  )
  expect(renamed).not.toHaveProperty('0001-front.png')
  expect(renamed).not.toHaveProperty('0002-back.png')
  const convertedPixels = await pixelSnapshots(page, directoryName, [`${CERT_A}_A.webp`, `${CERT_A}_B.webp`])
  expect(convertedPixels).toEqual(originalPixels)
  expect(renamed[`${CERT_A}_A.webp`].sha256).not.toBe(photos[0].sha256)
  const backup = await conversionRecord(page, directoryName)
  expect(backup.backupHashes).toEqual(photos.map(item => item.sha256))
  expect(backup.lossless).toEqual([true, true])
  expect(backup.backupNames.every(name => name.endsWith('.nxr-source'))).toBe(true)

  await page.getByRole('button', { name: '恢复文件名', exact: true }).click()
  await confirmAction(page, '确认恢复')
  await expect(page.getByRole('status').filter({ hasText: '原文件名已恢复' })).toBeVisible()

  await expect
    .poll(async () => Object.keys(await directoryFiles(page, directoryName)))
    .toEqual(expect.arrayContaining(['0001-front.png', '0002-back.png']))
  const restored = await directoryFiles(page, directoryName)
  expect(restored['0001-front.png'].sha256).toBe(photos[0].sha256)
  expect(restored['0002-back.png'].sha256).toBe(photos[1].sha256)
  expect(restored).not.toHaveProperty(`${CERT_A}_A.webp`)
  expect(restored).not.toHaveProperty(`${CERT_A}_B.webp`)
})

test('无二维码与非 NXR 域名二维码给出不同的识别结果', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const photos = [
    blankPhoto('0001-no-qr.png'),
    await qrPhoto('0002-wrong-domain.png', `https://example.com/card/${CERT_A}`),
  ]

  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  await openDirectory(page)
  await scan(page)

  await expect(photoRow(page, '0001-no-qr.png')).toContainText('未发现二维码')
  await expect(photoRow(page, '0002-wrong-domain.png').locator('.photo-state')).toHaveAttribute(
    'title',
    /域名|NXR|nxrgrading\.com|有效证书/i,
  )
  await expect(page.getByTestId('pair-row')).toHaveCount(0)
})

test('JPEG、透明 PNG 和已有 WebP 混合输入保留尺寸与像素，恢复原文件', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const photos = [
    blankPhoto('0001-front.jpg'),
    await qrPhoto('0002-back.png', cardUrl(CERT_A)),
    blankPhoto('0003-alpha.png'),
    await qrPhoto('0004-back.webp', cardUrl(CERT_B)),
  ]
  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  // Stage real JPEG/WebP encodings and a PNG containing transparent pixels.
  await page.evaluate(async folder => {
    const directory = await (await navigator.storage.getDirectory()).getDirectoryHandle(folder)
    for (const [name, mime] of [['0001-front.jpg', 'image/jpeg'], ['0004-back.webp', 'image/webp']]) {
      const handle = await directory.getFileHandle(name)
      const bitmap = await createImageBitmap(await handle.getFile())
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height)
      canvas.getContext('2d')!.drawImage(bitmap, 0, 0)
      bitmap.close()
      const blob = await canvas.convertToBlob({ type: mime, quality: 0.95 })
      const writer = await handle.createWritable()
      await writer.write(blob)
      await writer.close()
    }
    const alphaCanvas = new OffscreenCanvas(381, 509)
    const context = alphaCanvas.getContext('2d')!
    context.fillStyle = 'rgba(91, 37, 201, 0.5)'
    context.fillRect(0, 0, 381, 400)
    const handle = await directory.getFileHandle('0003-alpha.png')
    const writer = await handle.createWritable()
    await writer.write(await alphaCanvas.convertToBlob({ type: 'image/png' }))
    await writer.close()
  }, directoryName)
  const originalFiles = await directoryFiles(page, directoryName)
  const originalPixels = await pixelSnapshots(page, directoryName, photos.map(item => item.name))
  await openDirectory(page)
  await scan(page)
  await expect(page.getByTestId('pair-row')).toHaveCount(2)
  await page.getByRole('button', { name: '执行改名', exact: true }).click()
  await confirmAction(page, '确认改名')
  await expect(page.getByRole('status').filter({ hasText: /已完成 4 张图片改名/ })).toBeVisible()
  const targets = [`${CERT_A}_A.webp`, `${CERT_A}_B.webp`, `${CERT_B}_A.webp`, `${CERT_B}_B.webp`]
  expect(await pixelSnapshots(page, directoryName, targets)).toEqual(originalPixels)
  const output = await directoryFiles(page, directoryName)
  expect(output[`${CERT_B}_B.webp`].sha256).toBe(originalFiles['0004-back.webp'].sha256)
  expect((await conversionRecord(page, directoryName)).lossless.slice(0, 3)).toEqual([true, true, true])
  await page.getByRole('button', { name: '恢复文件名', exact: true }).click()
  await confirmAction(page, '确认恢复')
  await expect(page.getByRole('status').filter({ hasText: /原文件名已恢复/ })).toBeVisible()
  const restored = await directoryFiles(page, directoryName)
  for (const photo of photos) expect(restored[photo.name].sha256).toBe(originalFiles[photo.name].sha256)
  expect(await page.evaluate(async folder => {
    const directory = await (await navigator.storage.getDirectory()).getDirectoryHandle(folder)
    for await (const name of directory.keys()) if (name.startsWith('.nxr-originals-')) return true
    return false
  }, directoryName)).toBe(false)
})

test('前图损坏导致转换失败时保留原件并可结束恢复', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const photos = [brokenPhoto('0001-broken.png'), await qrPhoto('0002-back.png', cardUrl(CERT_A))]
  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  await openDirectory(page)
  await scan(page)
  await page.getByRole('button', { name: '执行改名', exact: true }).click()
  await confirmAction(page, '确认改名')
  await expect(page.getByRole('status').filter({ hasText: /操作已停止/ })).toBeVisible()
  const after = await directoryFiles(page, directoryName)
  for (const photo of photos) expect(after[photo.name].sha256).toBe(photo.sha256)
  expect(after).not.toHaveProperty(`${CERT_A}_A.webp`)
  await page.getByRole('button', { name: '恢复文件名', exact: true }).click()
  await confirmAction(page, '确认恢复')
  await expect(page.getByRole('status').filter({ hasText: /原文件名已恢复/ })).toBeVisible()
})

test('可识别旋转二维码和大图中的小二维码', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const rotatedQr = await qrPhoto('source-rotated.png', cardUrl(CERT_A))
  const smallQr = await qrPhoto('source-small.png', `nxrgrading.com/card/${CERT_B}`)

  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, [
    blankPhoto('0001-rotated-front.png'),
    blankPhoto('0003-small-front.png'),
  ])
  await writeCanvasPhoto(
    page,
    directoryName,
    '0002-rotated-back.png',
    { width: 900, height: 900 },
    [{ photo: rotatedQr, x: 90, y: 90, width: 720, height: 720, rotation: 90 }],
  )
  await writeCanvasPhoto(
    page,
    directoryName,
    '0004-small-back.png',
    { width: 1600, height: 1200 },
    [{ photo: smallQr, x: 710, y: 510, width: 180, height: 180 }],
  )

  await openDirectory(page)
  await scan(page)

  await expect(page.getByTestId('pair-row')).toHaveCount(2)
  const certificateValues = await page
    .getByTestId('pair-row')
    .getByLabel(/^证书号 /)
    .evaluateAll((inputs: HTMLInputElement[]) => inputs.map((input) => input.value))
  expect(certificateValues.sort()).toEqual([CERT_A, CERT_B].sort())
})

test('同一张图片含多个有效证书二维码时标记为歧义且不自动配对', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const firstQr = await qrPhoto('source-a.png', cardUrl(CERT_A))
  const secondQr = await qrPhoto('source-b.png', cardUrl(CERT_B))

  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, [blankPhoto('0001-front.png')])
  await writeCanvasPhoto(
    page,
    directoryName,
    '0002-multiple-back.png',
    { width: 3000, height: 1500 },
    [
      { photo: firstQr, x: 300, y: 100, width: 600, height: 600 },
      { photo: secondQr, x: 2100, y: 100, width: 600, height: 600 },
    ],
  )

  await openDirectory(page)
  await scan(page)

  await expect(photoRow(page, '0002-multiple-back.png')).toContainText('多个证书号')
  await expect(page.getByTestId('pair-row')).toHaveCount(0)
})

test('文件输入回退路径只读预览并禁止执行改名', async ({ page }) => {
  const photos = [
    blankPhoto('0001-preview-front.png'),
    await qrPhoto('0002-preview-back.png', cardUrl(CERT_A)),
  ]
  const fixtureDirectory = await materializeTestDirectory(photos)

  try {
    await page.goto('/')
    await page.getByTestId('preview-input').setInputFiles(fixtureDirectory.path)
    await expect(page.getByRole('status').filter({ hasText: '当前为只读预览' })).toBeVisible()
    await scan(page)

    await expect(page.getByTestId('pair-row')).toHaveCount(1)
    await expect(page.getByRole('button', { name: '执行改名', exact: true })).toBeDisabled()
  } finally {
    await fixtureDirectory.cleanup()
  }
})

test('自动配对固定前后图片，隐藏清单且排除整组后仅确认已选图片', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const photos = [
    blankPhoto('0001-first-front.png'),
    await qrPhoto('0002-first-back.png', cardUrl(CERT_A)),
    blankPhoto('0003-second-front.png'),
    await qrPhoto('0004-second-back.png', cardUrl(CERT_B)),
  ]

  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  await openDirectory(page)
  await scan(page)

  const pairs = page.getByTestId('pair-row')
  await expect(pairs).toHaveCount(2)
  await expect(page.getByRole('button', { name: /手动配对/ })).toHaveCount(0)
  await expect(pairs.locator('select')).toHaveCount(0)
  await expect(pairs.nth(0).getByLabel(/^证书号 /)).toHaveAttribute('readonly', '')
  await expect(pairs.nth(0).getByTestId('front-source')).toHaveText('0001-first-front.png')
  await expect(pairs.nth(0).getByTestId('back-source')).toHaveText('0002-first-back.png')
  await expect(pairs.nth(1).getByTestId('front-source')).toHaveText('0003-second-front.png')
  await expect(pairs.nth(1).getByTestId('back-source')).toHaveText('0004-second-back.png')

  const execute = page.getByRole('button', { name: '执行改名', exact: true })
  await expect(execute).toBeEnabled()

  await pairs.nth(0).getByRole('checkbox', { name: '选择第 1 组' }).uncheck()
  await expect(execute).toBeEnabled()
  await expect(page.getByText('已选择 1 组', { exact: true })).toBeVisible()

  await expect(page.getByRole('button', { name: '导出清单', exact: true })).toHaveCount(0)
  await execute.click()
  const confirmation = page.getByRole('dialog').filter({ hasText: '确认生成' })
  await expect(confirmation).not.toContainText('0001-first-front.png')
  await expect(confirmation).not.toContainText(`${CERT_A}_A.webp`)
  await expect(confirmation).toContainText('0003-second-front.png')
  await expect(confirmation).toContainText(`${CERT_B}_A.webp`)
  await expect(confirmation).toContainText('0004-second-back.png')
  await expect(confirmation).toContainText(`${CERT_B}_B.webp`)
  await confirmation.getByRole('button', { name: '取消', exact: true }).click()
})

test('扫描可停止，随后可完成识别并重新扫描', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const back = await qrPhoto('source-back.png', cardUrl(CERT_A))

  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, [])
  await writeCanvasPhoto(
    page,
    directoryName,
    '0001-large-front.png',
    { width: 2400, height: 2400 },
    [],
  )
  await writeCanvasPhoto(
    page,
    directoryName,
    '0002-back.png',
    { width: 900, height: 900 },
    [{ photo: back, x: 90, y: 90, width: 720, height: 720 }],
  )
  await openDirectory(page)

  await page.getByRole('button', { name: '开始识别', exact: true }).click()
  await page.getByRole('button', { name: '停止识别', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: /取消|停止/ })).toBeVisible()

  await scan(page)
  await expect(page.getByTestId('pair-row')).toHaveCount(1)
  await page.getByRole('button', { name: '重新识别', exact: true }).click()
  await expect(page.getByRole('button', { name: '重新识别', exact: true })).toBeEnabled()
  await expect(page.getByTestId('pair-row')).toHaveCount(1)
})

test('相同证书号产生多对图片时阻止目标文件名冲突', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const photos = [
    blankPhoto('0001-front.png'),
    await qrPhoto('0002-back.png', cardUrl(CERT_A)),
    blankPhoto('0003-front.png'),
    await qrPhoto('0004-back.png', cardUrl(CERT_A)),
  ]

  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  await openDirectory(page)
  await scan(page)

  await expect(page.getByTestId('pair-row')).toHaveCount(2)
  await expect(
    page.getByText(/重复|冲突|used by more than one|conflict/i, { exact: false }).first(),
  ).toBeVisible()
  await expect(page.getByRole('button', { name: '执行改名', exact: true })).toBeDisabled()

  const unchanged = await directoryFiles(page, directoryName)
  for (const item of photos) expect(unchanged[item.name].sha256).toBe(item.sha256)
})

test('前一张识别失败或也含二维码时仍固定为A，并防止连续背面重复用图', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const photos: TestPhoto[] = [
    brokenPhoto('0001-error-front.png'),
    await qrPhoto('0002-first-back.png', cardUrl(CERT_A)),
    await qrPhoto('0003-qr-front.png', cardUrl(CERT_A)),
    await qrPhoto('0004-second-back.png', cardUrl(CERT_B), { dark: '#3f3f46', light: '#ffffff' }),
    await qrPhoto('0005-extra-back.png', cardUrl(CERT_A)),
  ]

  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  await openDirectory(page)
  await scan(page)

  await expect(photoRow(page, '0001-error-front.png')).toContainText('需要检查')
  const pairs = page.getByTestId('pair-row')
  await expect(pairs).toHaveCount(2)
  await expect(pairs.nth(0).getByTestId('front-source')).toHaveText('0001-error-front.png')
  await expect(pairs.nth(0).getByTestId('back-source')).toHaveText('0002-first-back.png')
  await expect(pairs.nth(1).getByTestId('front-source')).toHaveText('0003-qr-front.png')
  await expect(pairs.nth(1).getByTestId('back-source')).toHaveText('0004-second-back.png')
  expect(await pairs.getByTestId('front-source').allTextContents()).not.toContain('0002-first-back.png')
  expect(await pairs.getByTestId('back-source').allTextContents()).not.toContain('0005-extra-back.png')
  await expect(pairs.locator('select')).toHaveCount(0)
})

test('目录写权限被拒绝时仍可只读预览但不修改图片', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const photos = [
    blankPhoto('0001-permission-front.png'),
    await qrPhoto('0002-permission-back.png', cardUrl(CERT_A)),
  ]

  await installOpfsPicker(page, directoryName, { permission: 'denied' })
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  await openDirectory(page)

  await expect(page.getByText(/允许|权限|授权|拒绝/, { exact: false }).first()).toBeVisible()
  await expect(page.getByTestId('photo-row')).toHaveCount(2)
  await scan(page)
  await expect(page.getByTestId('pair-row')).toHaveCount(1)
  await expect(page.getByRole('button', { name: '执行改名', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '允许修改文件', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: /权限未获准/ })).toBeVisible()
  const unchanged = await directoryFiles(page, directoryName)
  expect(unchanged['0001-permission-front.png'].sha256).toBe(photos[0].sha256)
  expect(unchanged['0002-permission-back.png'].sha256).toBe(photos[1].sha256)
})

test('目标写入失败时保留全部原文件字节', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const photos = [
    blankPhoto('0001-write-front.png'),
    await qrPhoto('0002-write-back.png', cardUrl(CERT_A)),
  ]

  await installOpfsPicker(page, directoryName, {
    failCreateWritableFor: `${CERT_A}_[AB]\\.webp$`,
  })
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  await openDirectory(page)
  await scan(page)
  await page.getByRole('button', { name: '执行改名', exact: true }).click()
  await confirmAction(page, '确认改名')

  await expect(page.getByText(/失败|错误|拒绝|停止/, { exact: false }).first()).toBeVisible()
  const afterFailure = await directoryFiles(page, directoryName)
  expect(afterFailure['0001-write-front.png'].sha256).toBe(photos[0].sha256)
  expect(afterFailure['0002-write-back.png'].sha256).toBe(photos[1].sha256)
})

test('窄屏在载入照片和配对表后没有横向页面溢出', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const directoryName = uniqueDirectory(testInfo.title)
  const photos = [
    blankPhoto('0001-mobile-front-with-a-long-file-name.png'),
    await qrPhoto('0002-mobile-back-with-a-long-file-name.png', cardUrl(CERT_A)),
  ]

  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  await openDirectory(page)
  await scan(page)
  await expect(page.getByTestId('pair-row')).toHaveCount(1)

  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    page: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
  }))
  expect(dimensions.page).toBeLessThanOrEqual(dimensions.viewport)
  expect(dimensions.body).toBeLessThanOrEqual(dimensions.viewport)
})

test('三组以上命名结果滚动展示，目标文件名不被卡片裁切', async ({ page }, testInfo) => {
  const directoryName = uniqueDirectory(testInfo.title)
  const photos: TestPhoto[] = []
  for (let i = 0; i < 3; i++) {
    photos.push(blankPhoto(`000${i * 2 + 1}-front.png`))
    photos.push(await qrPhoto(`000${i * 2 + 2}-back.png`, cardUrl(`${i + 5}123456789`)))
  }
  await installOpfsPicker(page, directoryName)
  await page.goto('/')
  await seedOpfsDirectory(page, directoryName, photos)
  await openDirectory(page)
  await scan(page)
  await expect(page.getByTestId('pair-row')).toHaveCount(3)
  const bounds = await page.getByTestId('pair-row').evaluateAll(rows => rows.map(row => {
    const box = row.getBoundingClientRect()
    return [...row.querySelectorAll('.side-fields code')].every(code => {
      const rect = code.getBoundingClientRect()
      return rect.top >= box.top && rect.bottom <= box.bottom
    })
  }))
  expect(bounds).toEqual([true, true, true])
  const panel = await page.locator('.pairs-list').evaluate(el => ({ scroll: el.scrollHeight, client: el.clientHeight }))
  expect(panel.scroll).toBeGreaterThan(panel.client)
  await page.screenshot({ path: testInfo.outputPath('layout.png'), fullPage: true })
})
