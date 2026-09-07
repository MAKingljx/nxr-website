<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef } from "vue";
import type { Pair, Photo, RenameRequest } from "./lib/types";
import {
  isSupportedImage,
  naturalCompare,
  validatePairs,
} from "./lib/pairing";
import { scanPhoto, cancelScan, getScanConcurrency } from "./lib/scanner";
import { scanTextReference, cancelTextReference } from "./lib/text-reference";
import PhotoReviewDialog from "./components/PhotoReviewDialog.vue";
import { deepScanCandidates, mergeDeepScanPairs, mergeScanEvidence, type ScanMode } from "./lib/scan-policy";
import { convertToLosslessWebp, getWebpConcurrency } from "./lib/webp-converter";
import {
  hashFile,
  listJournals,
  renameFiles,
  restoreJournal,
} from "./lib/file-operations";

const photos = ref<Photo[]>([]);
const pairs = ref<Pair[]>([]);
const directory = shallowRef<FileSystemDirectoryHandle | null>(null);
const folderName = ref("尚未选择文件夹");
const allNames = ref<string[]>([]);
const writable = ref(false);
const busy = ref("");
const progress = ref(0);
const progressText = ref("");
const stoppingConversion = ref(false);
let conversionController: AbortController | null = null;
const notice = ref<{ text: string; tone: string } | null>(null);
const journals = ref<
  { name: string; state: string; createdAt: string; count: number }[]
>([]);
const dialog = ref<HTMLDialogElement>();
const dialogKind = ref<"rename" | "restore">("rename");
const restoreName = ref("");
const previewInput = ref<HTMLInputElement>();
const photoReview = ref<InstanceType<typeof PhotoReviewDialog>>();
let scanGeneration = 0;
let referenceGeneration = 0;
const planned = ref<{ source: Photo; targetName: string }[]>([]);
const supportsWrite =
  typeof window.showDirectoryPicker === "function" && window.isSecureContext;
const selectedPairs = computed(() =>
  pairs.value.filter((pair) => pair.selected),
);
const photoById = computed(
  () => new Map(photos.value.map((photo) => [photo.id, photo])),
);
const issues = computed(() =>
  validatePairs(photos.value, selectedPairs.value, allNames.value),
);
const validCount = computed(
  () =>
    selectedPairs.value.filter((pair) => !issues.value.get(pair.id)?.length)
      .length,
);
const recognizedCount = computed(
  () => photos.value.filter((photo) => photo.scanState === "found").length,
);
const canRename = computed(
  () =>
    !!directory.value &&
    writable.value &&
    !busy.value &&
    selectedPairs.value.length > 0 &&
    validCount.value === selectedPairs.value.length,
);
const scannedCount = computed(
  () =>
    photos.value.filter(
      (photo) => !["pending", "scanning"].includes(photo.scanState),
    ).length,
);
const hasScan = computed(() => scannedCount.value > 0);
const unresolvedCount = computed(() => {
  const used = new Set(
    pairs.value.flatMap((pair) => [pair.frontId, pair.backId]),
  );
  return photos.value.filter((photo) => !used.has(photo.id)).length;
});

function showNotice(text: string, tone = "info") {
  notice.value = { text, tone };
}
function errorMessage(error: unknown) {
  if (error instanceof DOMException && error.name === "AbortError")
    return "操作已取消，文件未被自动重命名。";
  if (error instanceof DOMException && error.name === "NotAllowedError")
    return "未获得文件夹权限。请重新选择或授权。";
  return error instanceof Error
    ? error.message
    : "操作未完成，请检查文件夹后重试。";
}
function releasePhotos() {
  stopTextReferences();
  for (const photo of photos.value)
    if (photo.thumbnailUrl) URL.revokeObjectURL(photo.thumbnailUrl);
  photos.value = [];
  pairs.value = [];
}
async function thumbnail(file: File) {
  try {
    if (file.size > 32 * 1024 * 1024) return "";
    const bitmap = await createImageBitmap(file, { resizeWidth: 180 });
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
    bitmap.close();
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, "image/webp", 0.72),
    );
    return blob ? URL.createObjectURL(blob) : "";
  } catch {
    return "";
  }
}
async function makePhoto(
  file: File,
  handle?: FileSystemFileHandle,
): Promise<Photo> {
  return {
    id: crypto.randomUUID(),
    name: file.name,
    file,
    handle,
    thumbnailUrl: await thumbnail(file),
    scanState: "pending",
    certIds: [],
    qrTexts: [],
  };
}
async function refreshJournals() {
  if (!directory.value) {
    journals.value = [];
    return;
  }
  journals.value = await listJournals(directory.value);
}
async function loadDirectory(handle: FileSystemDirectoryHandle) {
  releasePhotos();
  allNames.value = [];
  const handles: FileSystemFileHandle[] = [];
  let subfolders = 0;
  for await (const [name, child] of handle.entries()) {
    allNames.value.push(name);
    if (child.kind === "directory") {
      subfolders++;
      continue;
    }
    if (isSupportedImage(name)) handles.push(child);
  }
  handles.sort((a, b) => naturalCompare(a.name, b.name));
  for (const child of handles) {
    const file = await child.getFile();
    photos.value.push(await makePhoto(file, child));
  }
  await refreshJournals();
  if (subfolders)
    showNotice(
      `已读取 ${photos.value.length} 张图片；${subfolders} 个子文件夹未处理。`,
    );
  else if (!photos.value.length)
    showNotice("这个文件夹没有 JPG、PNG 或 WebP 图片。");
}
async function selectDirectory() {
  if (busy.value) return;
  if (!supportsWrite) {
    previewInput.value?.click();
    return;
  }
  try {
    // The picker is called directly from the click to retain browser activation.
    const handle = await window.showDirectoryPicker({
      mode: "readwrite",
      id: "nxr-photo-renamer",
    });
    busy.value = "读取图片";
    notice.value = null;
    directory.value = handle;
    folderName.value = handle.name;
    writable.value =
      (await handle.queryPermission({ mode: "readwrite" })) === "granted";
    await loadDirectory(handle);
    if (!writable.value)
      showNotice(
        "已读取图片。修改文件名前，请允许此网页写入该文件夹。",
        "warning",
      );
  } catch (error) {
    if (!(error instanceof DOMException && error.name === "AbortError"))
      showNotice(errorMessage(error), "error");
  } finally {
    busy.value = "";
  }
}
async function grantWrite() {
  try {
    writable.value =
      (await directory.value?.requestPermission({ mode: "readwrite" })) ===
      "granted";
    if (!writable.value)
      showNotice("写入权限未获准，可以继续识别和预览。", "warning");
    else showNotice("已允许修改所选文件夹。", "success");
  } catch (error) {
    showNotice(errorMessage(error), "error");
  }
}
async function selectPreview(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = [...(input.files || [])];
  if (!files.length || busy.value) return;
  busy.value = "读取图片";
  try {
    releasePhotos();
    directory.value = null;
    writable.value = false;
    journals.value = [];
    folderName.value = "只读预览";
    const topLevel = files.filter(
      (file) =>
        !file.webkitRelativePath ||
        file.webkitRelativePath.split("/").length === 2,
    );
    allNames.value = topLevel.map((file) => file.name);
    for (const file of topLevel
      .filter((file) => isSupportedImage(file.name))
      .sort((a, b) => naturalCompare(a.name, b.name))) {
      photos.value.push(await makePhoto(file));
    }
    showNotice(
      "当前为只读预览。直接改名需要在电脑端 Chrome / Edge 中选择并授权文件夹。",
    );
  } catch (error) {
    showNotice(errorMessage(error), "error");
  } finally {
    busy.value = "";
    input.value = "";
  }
}
async function startScan() {
  if (busy.value || !photos.value.length) return;
  // Manual confirmations survive rescanning; neither of their photos is reused.
  stopTextReferences();
  const manualPairs = pairs.value.filter((pair) => pair.manual);
  const manualPhotos = new Set(manualPairs.flatMap((pair) => [pair.frontId, pair.backId]));
  const selections = new Map(pairs.value.map((pair) => [pair.id, pair.selected]));
  const targets = photos.value.filter((photo) => !manualPhotos.has(photo.id));
  if (!targets.length) return;
  busy.value = "识别二维码";
  notice.value = null;
  pairs.value = manualPairs;
  progress.value = 0;
  const run = ++scanGeneration;
  for (const photo of targets) {
    photo.scanState = "pending";
    photo.certIds = [];
    photo.qrTexts = [];
    photo.error = undefined;
    photo.textReference = undefined;
  }
  const updatePairs = (previous: Pair[], scannedIds: Set<string>) => {
    pairs.value = mergeDeepScanPairs(photos.value, previous, scannedIds);
    for (const pair of pairs.value) {
      if (selections.has(pair.id)) pair.selected = selections.get(pair.id)!;
    }
  };
  const scanOne = async (photo: Photo, mode: ScanMode): Promise<boolean> => {
    const previous = { scanState: photo.scanState, certIds: photo.certIds,
      qrTexts: photo.qrTexts, error: photo.error };
    photo.scanState = "scanning";
    let result: Awaited<ReturnType<typeof scanPhoto>>;
    try {
      result = await scanPhoto(photo.file, mode);
    } catch (error) {
      result = { certIds: [], qrTexts: [], error: errorMessage(error) };
    }
    if (run !== scanGeneration) {
      Object.assign(photo, previous);
      return false;
    }
    if (mode === "deep") result = mergeScanEvidence(previous, result);
    photo.certIds = result.certIds;
    photo.qrTexts = result.qrTexts;
    photo.error = result.error;
    photo.scanState = result.certIds.length > 1 ? "ambiguous"
      : result.error ? "error" : result.certIds.length === 1 ? "found" : "none";
    if (!result.certIds.length && result.qrTexts.length) {
      photo.scanState = "error";
      photo.error = "二维码不是可识别的 NXR 证书链接";
    }
    return true;
  };
  try {
    let nextIndex = 0;
    let completed = 0;
    const active = new Map<number, string>();
    const updateProgress = () => {
      const names = [...active].sort(([a], [b]) => a - b).map(([, name]) => name);
      progressText.value = `${completed} / ${targets.length}${names.length ? ` · ${names.join("、")}` : ""}`;
      progress.value = Math.round((completed / targets.length) * 100);
    };
    // Each lane takes the next photo as soon as it is free. Completion order
    // never changes the photo array or the natural-order pairing rule.
    await Promise.all(Array.from({ length: Math.min(getScanConcurrency(), targets.length) }, async () => {
      while (nextIndex < targets.length && run === scanGeneration) {
        const index = nextIndex++;
        const photo = targets[index]!;
        active.set(index, photo.name);
        updateProgress();
        if (!await scanOne(photo, "standard")) break;
        active.delete(index);
        completed++;
        updateProgress();
      }
    }));
    updatePairs(manualPairs, new Set(targets.map((photo) => photo.id)));
    // Finish the ordinary pass first so known A/B pairs do not need a retry.
    // Scan remaining files from the end: a recovered B also resolves its A.
    const retries = deepScanCandidates(photos.value, pairs.value).reverse();
    let retryCount = 0;
    for (let index = 0; index < retries.length && run === scanGeneration; index++) {
      const photo = retries[index]!;
      if (!deepScanCandidates(photos.value, pairs.value).some((item) => item.id === photo.id)) continue;
      progressText.value = `自动深度补扫 · ${index + 1} / ${retries.length} · ${photo.name}`;
      progress.value = Math.round((index / retries.length) * 100);
      retryCount++;
      if (!await scanOne(photo, "deep")) break;
      updatePairs(pairs.value, new Set([photo.id]));
    }
    progress.value = 100;
    const count = pairs.value.length;
    showNotice(run === scanGeneration
      ? `识别完成${retryCount ? "，已自动深度补扫" : ""}，生成 ${count} 组新文件名。${unresolvedCount.value ? "点击“待检查”查看剩余图片。" : "确认后即可改名。"}`
      : `识别已停止，保留已生成的 ${count} 组结果。`, count ? "success" : "info");
  } catch (error) {
    showNotice(errorMessage(error), "error");
  } finally {
    cancelScan();
    busy.value = "";
    progressText.value = "";
  }
  // OCR is advisory and runs after pairing, without blocking any user action.
  if (run === scanGeneration) void annotateTextReferences();
}
function stopTextReferences() {
  referenceGeneration++;
  cancelTextReference();
}
async function annotateTextReferences() {
  const run = ++referenceGeneration;
  const fronts = new Set(pairs.value.map((pair) => pair.frontId));
  const targets = photos.value.filter((photo) => !fronts.has(photo.id) && !photo.textReference);
  try {
    for (const photo of targets) {
      if (run !== referenceGeneration) break;
      try {
        const reference = await scanTextReference(photo.file,
          photo.certIds.length === 1 ? photo.certIds[0] : undefined);
        if (run !== referenceGeneration) break;
        photo.textReference = reference;
      } catch {
        // OCR failure never changes a QR result, pair, filename or review count.
        if (run !== referenceGeneration) break;
      }
    }
  } finally {
    if (run === referenceGeneration) cancelTextReference();
  }
}
function addManualPair(pair: Pair) {
  if (busy.value) return;
  pairs.value.push(pair);
  const positions = new Map(photos.value.map((photo, index) => [photo.id, index]));
  pairs.value.sort((a, b) => positions.get(a.backId)! - positions.get(b.backId)!);
  showNotice(`已录入 ${pair.certId}，请在命名预览核对 A / B 图片。`, "success");
}
function stopScan() {
  stopTextReferences();
  scanGeneration++;
  cancelScan();
}
function targetName(pair: Pair, side: "A" | "B") {
  return pair.certId.trim()
    ? `${pair.certId.trim()}_${side}.webp`
    : `证书号_${side}.webp`;
}
function scanLabel(photo: Photo) {
  return {
    pending: "待识别",
    scanning: "识别中",
    found: photo.certIds[0] || "已识别",
    none: "未发现二维码",
    error: "需要检查",
    ambiguous: "多个证书号",
  }[photo.scanState];
}
function openRename() {
  if (!canRename.value) return;
  planned.value = selectedPairs.value
    .flatMap((pair) =>
      (["A", "B"] as const).map((side) => ({
        source: photoById.value.get(side === "A" ? pair.frontId : pair.backId)!,
        targetName: targetName(pair, side),
      })),
    )
    .filter((item) => item.source.name !== item.targetName);
  if (!planned.value.length) {
    showNotice("所选图片已经使用目标名称，无需改名。", "success");
    return;
  }
  dialogKind.value = "rename";
  dialog.value?.showModal();
}
function openRestore(name: string) {
  restoreName.value = name;
  dialogKind.value = "restore";
  dialog.value?.showModal();
}
async function withFileLock<T>(operation: () => Promise<T>): Promise<T> {
  if (!navigator.locks) return operation();
  return navigator.locks.request(
    "nxr-photo-renamer-files",
    { ifAvailable: true },
    async (lock) => {
      if (!lock)
        throw new Error("另一个 NXR 命名工具页面正在修改文件，请稍后再试。");
      return operation();
    },
  );
}
async function confirmDialog() {
  if (busy.value) return;
  dialog.value?.close();
  const handle = directory.value;
  if (!handle) return;
  busy.value = dialogKind.value === "rename" ? "正在改名" : "正在恢复";
  stopTextReferences();
  const controller = new AbortController();
  conversionController = dialogKind.value === "rename" ? controller : null;
  stoppingConversion.value = false;
  progressText.value = "正在检查文件";
  let resultText = "";
  try {
    // Request permission before hashing while the confirmation click is active.
    if ((await handle.requestPermission({ mode: "readwrite" })) !== "granted") {
      writable.value = false;
      showNotice("没有写入权限，文件未修改。", "warning");
      return;
    }
    await withFileLock(async () => {
      if (dialogKind.value === "restore") {
        await restoreJournal(handle, restoreName.value, (text) => {
          progressText.value = text;
        });
        resultText = "原文件名已恢复，原图内容与处理前一致。";
      } else {
        const requests: RenameRequest[] = [];
        for (const item of planned.value) {
          if (controller.signal.aborted) throw new DOMException("已取消本次改名。", "AbortError");
          requests.push({
            sourceName: item.source.name,
            targetName: item.targetName,
            expectedSize: item.source.file.size,
            expectedLastModified: item.source.file.lastModified,
            expectedHash: await hashFile(item.source.file),
            outputFormat: "webp-lossless",
          });
        }
        await renameFiles(handle, requests, (text) => {
          progressText.value = text;
        }, convertToLosslessWebp, {
          signal: controller.signal,
          conversionConcurrency: getWebpConcurrency(),
        });
        resultText = `已完成 ${requests.length} 张图片改名，已输出原始尺寸的 WebP。原图已保留，可恢复。`;
      }
    });
    await loadDirectory(handle);
    showNotice(resultText, "success");
  } catch (error) {
    const message = errorMessage(error);
    // Always re-read names after an interrupted write; the in-memory plan is stale.
    try {
      await loadDirectory(handle);
    } catch {
      /* Keep the original operation error visible. */
    }
    showNotice(
      `${message} 如有部分文件已处理，可在“改名记录”中恢复。`,
      "error",
    );
  } finally {
    controller.abort();
    conversionController = null;
    stoppingConversion.value = false;
    busy.value = "";
    progressText.value = "";
    planned.value = [];
  }
}
function stopConversion() {
  stoppingConversion.value = true;
  conversionController?.abort();
}
function journalState(state: string) {
  return (
    (
      {
        completed: "已完成",
        complete: "已完成",
        restored: "已恢复",
        failed: "待恢复",
        planned: "待恢复",
        copying: "待恢复",
        deleting: "待恢复",
        restoring: "待恢复",
      } as Record<string, string>
    )[state] || state
  );
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (busy.value.includes("改名") || busy.value.includes("恢复")) {
    event.preventDefault();
    event.returnValue = "";
  }
}
window.addEventListener("beforeunload", beforeUnload);
onBeforeUnmount(() => {
  conversionController?.abort();
  stopScan();
  releasePhotos();
  window.removeEventListener("beforeunload", beforeUnload);
});
</script>

<template>
  <div class="app-shell">
    <header class="site-header">
      <a class="brand" href="./" aria-label="NXR 卡片图片命名"
        ><span class="brand-symbol" aria-hidden="true">N<span>×</span>R</span
        ><span class="brand-divider"></span><span>收藏工具</span></a
      >
      <div class="local-badge">
        <span class="status-dot"></span> 本机处理 · 图片不上传
      </div>
    </header>
    <main>
      <section class="page-intro">
        <div>
          <div class="eyebrow">PHOTO RENAME / 01</div>
          <h1>卡片图片命名<span class="heading-dot">.</span></h1>
          <p>识别背面二维码，为正反面配上同一个证书号。</p>
        </div>
        <div class="naming-example" aria-label="命名规则">
          <span>证书号</span><b>_A</b><span>正面</span><i></i><span>证书号</span
          ><b>_B</b><span>背面</span>
        </div>
      </section>
      <div v-if="!supportsWrite" class="notice warning" role="status">
        当前浏览器仅支持预览。直接修改本地文件名，请使用电脑端 Chrome /
        Edge，并通过 localhost 或 HTTPS 打开。
      </div>
      <div v-if="notice" class="notice" :class="notice.tone" role="status">
        <span>{{ notice.text }}</span
        ><button class="dismiss" aria-label="关闭提示" @click="notice = null">
          ×
        </button>
      </div>

      <section class="workspace-toolbar">
        <div class="folder-meta">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"
            />
          </svg>
          <div>
            <strong :title="folderName">{{ folderName }}</strong
            ><small>{{
              photos.length
                ? `${photos.length} 张图片 · 原文件名自然排序`
                : "JPG / JPEG / PNG / WebP"
            }}</small>
          </div>
        </div>
        <div class="toolbar-actions">
          <button
            v-if="directory && !writable"
            class="button secondary"
            :disabled="!!busy"
            @click="grantWrite"
          >
            允许修改文件
          </button>
          <button
            class="button secondary"
            :disabled="!!busy"
            @click="selectDirectory"
          >
            <span aria-hidden="true">＋</span> 选择照片文件夹
          </button>
          <button
            v-if="photos.length"
            class="button secondary"
            :disabled="!!busy || !unresolvedCount"
            @click="photoReview?.openPending(true)"
          >
            人工录入
          </button>
          <button
            v-if="busy === '识别二维码'"
            class="button stop"
            @click="stopScan"
          >
            停止识别
          </button>
          <button
            v-else
            class="button primary"
            :disabled="!photos.length || !!busy"
            @click="startScan()"
          >
            <span class="scan-icon" aria-hidden="true"></span
            >{{ hasScan ? "重新识别" : "开始识别" }}
          </button>
        </div>
        <input
          ref="previewInput"
          data-testid="preview-input"
          class="visually-hidden"
          type="file"
          accept=".jpg,.jpeg,.png,.webp"
          multiple
          webkitdirectory
          @change="selectPreview"
        />
      </section>
      <div v-if="busy" class="progress-strip" role="status">
        <div
          class="progress-line"
          :style="{ width: busy === '识别二维码' ? `${progress}%` : '100%' }"
        ></div>
        <span class="spinner"></span><strong>{{ busy }}</strong
        ><span>{{ progressText }}</span>
        <button v-if="busy === '正在改名'" class="text-button" :disabled="stoppingConversion" @click="stopConversion">{{ stoppingConversion ? '正在停止…' : '停止处理' }}</button>
      </div>

      <section class="workspace" :class="{ 'has-photos': photos.length }">
        <aside class="photo-panel">
          <div class="panel-title">
            <h2>
              原始图片
              <span>{{ photos.length.toString().padStart(2, "0") }}</span>
            </h2>
            <span class="micro-label">按名称排序</span>
          </div>
          <div v-if="!photos.length" class="photo-placeholder">
            <div class="placeholder-lines"><i></i><i></i><i></i></div>
            <p>选好文件夹后<br />图片会按原文件名排列</p>
          </div>
          <div v-else class="photo-list">
            <div
              v-for="(photo, index) in photos"
              :key="photo.id"
              class="photo-row"
              data-testid="photo-row"
              role="button"
              :tabindex="busy ? -1 : 0"
              :aria-disabled="!!busy"
              :aria-label="`查看图片 ${photo.name}`"
              @click="!busy && photoReview?.openPhoto(photo.id)"
              @keydown.enter="!busy && photoReview?.openPhoto(photo.id)"
              @keydown.space.prevent="!busy && photoReview?.openPhoto(photo.id)"
            >
              <span class="photo-index">{{
                String(index + 1).padStart(2, "0")
              }}</span>
              <img
                v-if="photo.thumbnailUrl"
                :src="photo.thumbnailUrl"
                :alt="photo.name"
                loading="lazy"
              />
              <div v-else class="image-unavailable">?</div>
              <div class="photo-info">
                <strong :title="photo.name">{{ photo.name }}</strong
                ><small
                  class="photo-state"
                  :class="photo.scanState"
                  :title="photo.error || scanLabel(photo)"
                  >{{ scanLabel(photo) }}</small
                >
              </div>
              <span
                v-if="photo.scanState === 'found'"
                class="found-mark"
                aria-hidden="true"
                >✓</span
              >
            </div>
          </div>
          <div class="photo-panel-footer">
            <span class="tiny-square"></span>仅处理当前文件夹，不包含子文件夹
          </div>
        </aside>

        <section class="pair-panel">
          <div class="panel-title">
            <h2>
              命名预览
              <span>{{ pairs.length.toString().padStart(2, "0") }}</span>
            </h2>
            <span class="micro-label">二维码图为 B · 前一张为 A</span>
          </div>
          <div v-if="!pairs.length" class="empty-state">
            <div class="card-illustration" aria-hidden="true">
              <div class="demo-card front">
                <div class="card-top">NXR <b>10</b></div>
                <div class="card-art">
                  <span class="art-orbit"></span><span class="art-card"></span
                  ><em>A</em>
                </div>
                <small>FRONT</small>
              </div>
              <div class="demo-card back">
                <div class="card-top">NXR <b>✓</b></div>
                <div class="qr-illustration">
                  <i></i><i></i><i></i><span></span>
                </div>
                <em>B</em><small>BACK</small>
              </div>
              <span class="pair-connector">↔</span>
            </div>
            <h3>
              {{
                !photos.length
                  ? "两张照片，一个证书号"
                  : busy === "识别二维码"
                    ? "正在寻找背面的二维码"
                    : hasScan
                      ? "暂未生成新文件名"
                      : "图片已就位，开始识别吧"
              }}
            </h3>
            <p>
              {{
                hasScan && !busy
                  ? "请检查背面二维码是否清晰，以及它的前一张是否为正面。"
                  : "按“正面 → 背面”的顺序拍摄，背面二维码决定两张图片的新名字。"
              }}
            </p>
            <div class="example-filenames">
              <code>5703018202_A.webp</code><span>+</span
              ><code>5703018202_B.webp</code>
            </div>
          </div>
          <div v-else class="pairs-list">
            <article
              v-for="(pair, index) in pairs"
              :key="pair.id"
              class="pair-card"
              :class="{
                invalid: issues.get(pair.id)?.length,
                unselected: !pair.selected,
              }"
              data-testid="pair-row"
            >
              <div class="pair-card-header">
                <label class="pair-select"
                  ><input
                    v-model="pair.selected"
                    type="checkbox"
                    :disabled="!!busy"
                    :aria-label="`选择第 ${index + 1} 组`"
                  /><span
                    >第 {{ String(index + 1).padStart(2, "0") }} 组</span
                  ></label
                ><span
                  class="pill"
                  :class="issues.get(pair.id)?.length ? 'warning' : 'success'"
                  >{{
                    issues.get(pair.id)?.length ? "需要检查" : pair.manual ? "人工录入" : "按顺序命名"
                  }}</span
                ><button
                  class="remove-pair"
                  :disabled="!!busy"
                  :aria-label="`跳过第 ${index + 1} 组`"
                  @click="pairs = pairs.filter((item) => item.id !== pair.id)"
                >
                  ×
                </button>
              </div>
              <div class="pair-content">
                <div class="pair-thumbnails">
                  <div
                    v-for="side in ['A', 'B'] as const"
                    :key="side"
                    class="pair-thumbnail"
                  >
                    <img
                      v-if="
                        photoById.get(side === 'A' ? pair.frontId : pair.backId)
                          ?.thumbnailUrl
                      "
                      :src="
                        photoById.get(side === 'A' ? pair.frontId : pair.backId)
                          ?.thumbnailUrl
                      "
                      :alt="side === 'A' ? '正面预览' : '背面预览'"
                    /><span
                      >{{ side }}
                      <small>{{ side === "A" ? "正面" : "背面" }}</small></span
                    >
                  </div>
                </div>
                <div class="pair-fields">
                  <label class="field-label"
                    >证书号<input
                      :value="pair.certId"
                      readonly
                      maxlength="64"
                      spellcheck="false"
                      autocomplete="off"
                      :aria-label="`证书号 ${pair.id}`"
                      :disabled="!!busy"
                  /></label>
                  <div class="side-fields">
                    <label class="field-label"
                      >正面 A<span
                        class="source-filename"
                        data-testid="front-source"
                        :title="photoById.get(pair.frontId)?.name"
                        >{{ photoById.get(pair.frontId)?.name }}</span
                      ><code :title="targetName(pair, 'A')"
                        >→ {{ targetName(pair, "A") }}</code
                      ></label
                    ><label class="field-label"
                      >背面 B<span
                        class="source-filename"
                        data-testid="back-source"
                        :title="photoById.get(pair.backId)?.name"
                        >{{ photoById.get(pair.backId)?.name }}</span
                      ><code :title="targetName(pair, 'B')"
                        >→ {{ targetName(pair, "B") }}</code
                      ></label
                    >
                  </div>
                </div>
              </div>
              <ul v-if="issues.get(pair.id)?.length" class="pair-errors">
                <li v-for="issue in issues.get(pair.id)" :key="issue">
                  {{ issue }}
                </li>
              </ul>
            </article>
          </div>
          <div class="pair-panel-footer">
            <span
              >已识别 <b>{{ recognizedCount }}</b> 个证书<span v-if="hasScan">
                · <button class="text-button review-link" :disabled="!!busy || !unresolvedCount"
                  @click="photoReview?.openPending()">待检查 <b>{{ unresolvedCount }}</b> 张 ↗</button></span
              ></span
            ><span>确认新文件名后执行</span>
          </div>
        </section>
      </section>

      <section class="action-bar">
        <div>
          <strong>{{
            selectedPairs.length
              ? `已选择 ${selectedPairs.length} 组`
              : "等待识别结果"
          }}</strong
          ><span>{{
            selectedPairs.length
              ? `${validCount} 组可用 · ${selectedPairs.length * 2} 张图片`
              : "先识别、核对，再执行"
          }}</span>
        </div>
        <div class="action-buttons">
          <button
            class="button dark"
            :disabled="!canRename"
            @click="openRename"
          >
            执行改名 <span aria-hidden="true">↗</span>
          </button>
        </div>
      </section>
      <details v-if="journals.length" class="history" open>
        <summary>
          改名记录 <span>{{ journals.length }}</span>
        </summary>
        <div
          v-for="journal in journals"
          :key="journal.name"
          class="history-row"
        >
          <div>
            <strong
              >{{ journalState(journal.state) }} ·
              {{ journal.count }} 张图片</strong
            ><small>{{
              new Date(journal.createdAt).toLocaleString("zh-CN")
            }}</small>
          </div>
          <button
            v-if="journal.state !== 'restored'"
            class="button secondary"
            :disabled="!!busy || !writable"
            @click="openRestore(journal.name)"
          >
            恢复文件名
          </button>
        </div>
      </details>
      <footer class="page-footer">
        <span>NXR GRADING</span>
        <p>
          本地生成 WebP，原图可恢复。执行期间请保持窗口打开，不要同时修改所选文件夹。
        </p>
        <button
          v-if="supportsWrite"
          class="text-button"
          :disabled="!!busy"
          @click="previewInput?.click()"
        >
          只读预览
        </button>
      </footer>
    </main>
    <PhotoReviewDialog ref="photoReview" :photos="photos" :pairs="pairs"
      :all-filenames="allNames" @add-pair="addManualPair" />
    <dialog ref="dialog" class="confirm-dialog" @cancel="dialog?.close()">
      <div class="dialog-eyebrow">
        {{ dialogKind === "restore" ? "RESTORE" : "READY TO RENAME" }}
      </div>
      <h2>
        {{
          dialogKind === "restore"
            ? "恢复原文件名？"
            : `确认生成 ${planned.length} 张 WebP 图片？`
        }}
      </h2>
      <p v-if="dialogKind === 'rename'">
        将生成原始尺寸的无损 WebP，已有 WebP 保留原内容。原图备份和恢复记录会保存在同目录的隐藏文件夹与文件中；全部校验通过后移除旧名称。请保留备份以便恢复，已有同名文件不会被覆盖。
      </p>
      <p v-else-if="dialogKind === 'restore'">
        根据记录恢复原文件和原名称。若原图备份、图片内容已改变或原名称被占用，将停止相应操作以保留文件。
      </p>
      <div v-if="dialogKind === 'rename'" class="confirmation-names">
        <div v-for="item in planned" :key="item.source.id">
          <span>{{ item.source.name }}</span
          ><b>→</b><code>{{ item.targetName }}</code>
        </div>
      </div>
      <div class="dialog-actions">
        <button class="button secondary" @click="dialog?.close()">取消</button
        ><button class="button dark" @click="confirmDialog">
          {{ dialogKind === "restore" ? "确认恢复" : "确认改名" }}
        </button>
      </div>
    </dialog>
  </div>
</template>
