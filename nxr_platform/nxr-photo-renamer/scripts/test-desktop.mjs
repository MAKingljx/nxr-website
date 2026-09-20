import assert from 'node:assert/strict';
import { mkdtemp, rm, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { _electron, expect } from '@playwright/test';
import { blankPhoto, qrPhoto, installOpfsPicker, seedOpfsDirectory, directoryFiles } from '../tests/fixtures.ts';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const profile = await mkdtemp(path.join(tmpdir(), 'nxr-desktop-test-'));
const nativeDirectory = process.env.NXR_TEST_NATIVE_DIRECTORY;
const executablePath = process.env.NXR_DESKTOP_EXECUTABLE;
const photos = [blankPhoto('0001-front.png'), await qrPhoto('0002-back.png', 'https://nxrgrading.com/card/7123456789')];
const folder = 'desktop-smoke';
const errors = [], remoteRequests = [];
let desktop;
try {
  desktop = await _electron.launch({ executablePath, cwd: root,
    args: [...(executablePath ? [] : ['.']), `--user-data-dir=${profile}`], timeout: 60_000 });
  const page = await desktop.firstWindow();
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (/^https?:/.test(request.url())) remoteRequests.push(request.url()); });
  await page.waitForURL('nxr://app/');
  await expect(page.locator('h1')).toContainText('卡片图片命名');
  assert.deepEqual(await page.evaluate(() => ({ secure: isSecureContext, picker: typeof showDirectoryPicker,
    node: typeof window.require, process: typeof window.process })),
  { secure: true, picker: 'function', node: 'undefined', process: 'undefined' });
  const prefs = await desktop.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].webContents.getLastWebPreferences());
  assert.equal(prefs.sandbox, true); assert.equal(prefs.contextIsolation, true); assert.equal(prefs.nodeIntegration, false);
  assert.equal(await desktop.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].webContents.getBackgroundThrottling()), false);
  const device = await page.evaluate(() => window.nxrDesktop.capabilities);
  assert.ok(device.hardwareConcurrency > 0 && device.deviceMemory > 0);
  const performanceMetrics = await page.evaluate(() => window.nxrDesktop.getPerformanceMetrics());
  assert.deepEqual(Object.keys(performanceMetrics).sort(), ['measuredAtMs', 'privateBytes', 'workingSetBytes']);
  assert.ok(Object.values(performanceMetrics).every(value => Number.isFinite(value) && value >= 0));
  assert.ok(performanceMetrics.workingSetBytes > 0);
  await desktop.evaluate(({ powerSaveBlocker }) => {
    const start = powerSaveBlocker.start.bind(powerSaveBlocker);
    globalThis.nxrTestBlockerIds = [];
    powerSaveBlocker.start = type => { const id = start(type); globalThis.nxrTestBlockerIds.push(id); return id; };
  });
  await page.evaluate(() => window.nxrDesktop.setProcessingBusy(true));
  await expect.poll(() => desktop.evaluate(({ powerSaveBlocker }) => globalThis.nxrTestBlockerIds.some(id => powerSaveBlocker.isStarted(id)))).toBe(true);
  await page.evaluate(() => window.nxrDesktop.setProcessingBusy(false));
  await expect.poll(() => desktop.evaluate(({ powerSaveBlocker }) => globalThis.nxrTestBlockerIds.some(id => powerSaveBlocker.isStarted(id)))).toBe(false);
  if (nativeDirectory) {
    // Interactive native picker acceptance: NXR_TEST_NATIVE_DIRECTORY must be an
    // isolated copy. No file-system or permission method is replaced in this mode.
    console.log('NATIVE_PICKER_READY: choose the isolated test directory');
  } else {
    // tsx preserves function names in the shared TypeScript fixture helpers.
    // This test-only identity helper lets their serialized callbacks run here.
    await page.evaluate(() => { globalThis.__name = value => value; });
    await installOpfsPicker({ addInitScript: (fn, arg) => page.evaluate(fn, arg) }, folder);
    await seedOpfsDirectory(page, folder, photos);
  }
  await page.getByRole('button', { name: '选择照片文件夹', exact: true }).click();
  await expect(page.getByTestId('photo-row')).toHaveCount(nativeDirectory ? 12 : 2, { timeout: nativeDirectory ? 180_000 : 10_000 }).catch(async error => {
    console.error(await page.locator('body').innerText()); throw error;
  });
  const inputHashes = nativeDirectory ? await hashes(nativeDirectory) : Object.fromEntries(photos.map(p => [p.name, p.sha256]));
  await page.getByRole('button', { name: '开始识别', exact: true }).click();
  await expect(page.getByRole('button', { name: '重新识别', exact: true })).toBeEnabled({ timeout: 360_000 });
  await expect(page.getByTestId('pair-row')).toHaveCount(nativeDirectory ? 6 : 1);
  await page.getByRole('button', { name: '执行改名', exact: true }).click();
  await page.getByRole('button', { name: '确认改名', exact: true }).click();
  await expect(page.locator('.progress-strip')).toHaveCount(0, { timeout: 360_000 });
  await expect(page.getByRole('status').filter({ hasText: /已完成 .* 张图片改名/ })).toBeVisible();
  const converted = nativeDirectory ? await hashes(nativeDirectory) : await directoryFiles(page, folder);
  assert.equal(Object.keys(converted).filter(name => name.endsWith('.webp')).length, nativeDirectory ? 12 : 2);
  await page.getByRole('button', { name: '恢复文件名', exact: true }).click();
  await page.getByRole('button', { name: '确认恢复', exact: true }).click();
  await expect(page.locator('.progress-strip')).toHaveCount(0, { timeout: 180_000 });
  await expect(page.getByRole('status').filter({ hasText: '原文件名已恢复' })).toBeVisible();
  const restored = nativeDirectory ? await hashes(nativeDirectory) : await directoryFiles(page, folder);
  for (const [name, hash] of Object.entries(inputHashes)) assert.equal(nativeDirectory ? restored[name] : restored[name].sha256, hash, name);
  assert.equal(Object.keys(restored).filter(name => name.endsWith('.webp')).length, 0);
  assert.deepEqual(errors, []); assert.deepEqual(remoteRequests, []);
  // Test the real main-process close guard without opening a modal in CI.
  // Electron owns beforeunload dialogs; suppress Playwright's automatic reply.
  page.on('dialog', () => {});
  await desktop.evaluate(({ dialog }) => { globalThis.closeGuardCalls = 0; dialog.showMessageBoxSync = () => { globalThis.closeGuardCalls++; return 0; }; });
  await page.evaluate(() => addEventListener('beforeunload', event => { event.preventDefault(); event.returnValue = ''; }));
  await desktop.evaluate(({ BrowserWindow }) => { BrowserWindow.getAllWindows()[0].close(); });
  await expect.poll(() => desktop.evaluate(() => globalThis.closeGuardCalls)).toBe(1);
  assert.equal(page.isClosed(), false);
  await desktop.evaluate(({ dialog }) => { dialog.showMessageBoxSync = () => 1; });
  const report = { platform: process.platform, arch: process.arch, packaged: !!executablePath, nativePicker: !!nativeDirectory,
    device, aggregateMemoryMetrics: true, backgroundThrottling: false, sleepProtection: true, photos: nativeDirectory ? 12 : 2, recognizedPairs: nativeDirectory ? 6 : 1, webpOutputs: nativeDirectory ? 12 : 2,
    restoredBytes: true, offline: true, sandbox: true, closeGuard: true };
  await mkdir(path.join(root, 'test-results'), { recursive: true });
  await page.screenshot({ path: path.join(root, 'test-results', 'desktop.png'), fullPage: true });
  // Last: a prevented navigation can leave Playwright's action auto-wait pending.
  await page.evaluate(() => { const link = document.createElement('a'); link.href = 'https://example.com/'; document.body.append(link); link.click(); link.remove(); });
  assert.equal(page.url(), 'nxr://app/');
  await writeFile(path.join(root, 'test-results', 'desktop-smoke.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} finally {
  if (desktop) {
    await desktop.evaluate(({ dialog }) => { dialog.showMessageBoxSync = () => 1; }).catch(() => {});
    await desktop.close();
  }
  await rm(profile, { recursive: true, force: true });
}

async function hashes(directory) {
  const result = {};
  for (const name of await readdir(directory)) if (/\.(jpe?g|png|webp)$/i.test(name))
    result[name] = createHash('sha256').update(await readFile(path.join(directory, name))).digest('hex');
  return result;
}
