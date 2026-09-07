<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import type { Pair, Photo } from '../lib/types'
import { createManualPair } from '../lib/manual-pairing'

const props = defineProps<{ photos: Photo[]; pairs: Pair[]; allFilenames: string[] }>()
const emit = defineEmits<{ addPair: [pair: Pair] }>()
const dialog = ref<HTMLDialogElement>()
const selectedId = ref('')
const pendingOnly = ref(true)
const manual = ref(false)
const certId = ref('')
const checked = ref(false)
const imageUrl = ref('')
const imageError = ref(false)
const zoomed = ref(false)
const message = ref('')
const used = computed(() => new Set(props.pairs.flatMap(pair => [pair.frontId, pair.backId])))
const pendingPhotos = computed(() => props.photos.filter(photo => !used.value.has(photo.id)))
const choices = computed(() => pendingOnly.value ? pendingPhotos.value : props.photos)
const photo = computed(() => props.photos.find(item => item.id === selectedId.value))
const front = computed(() => props.photos[props.photos.findIndex(item => item.id === selectedId.value) - 1])
const position = computed(() => choices.value.findIndex(item => item.id === selectedId.value))
const pairError = computed(() => {
  if (!photo.value) return '请选择背面图片。'
  if (!front.value) return '这张是第一张图片，没有前一张可作为正面。'
  if (used.value.has(photo.value.id)) return '这张图片已在命名预览中。'
  if (used.value.has(front.value.id)) return '前一张图片已被其他组使用，不能重复配对。'
  return ''
})
const reason = computed(() => {
  const current = photo.value
  if (!current) return ''
  if (used.value.has(current.id)) return '已配对，可在命名预览核对。'
  if (current.error) return current.error
  if (current.scanState === 'ambiguous') return '读到多个证书号，请核对标签。'
  if (current.scanState === 'found') return pairError.value || '已读到证书号，尚未配对。'
  if (current.scanState === 'pending') return '尚未完成识别。'
  return '未发现有效二维码；正面图通常没有二维码，请查看是否为背面。'
})

function releaseImage() {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''
}
function selectPhoto(id: string) {
  selectedId.value = id
  const qrIds = photo.value?.certIds || []
  const references = photo.value?.textReference?.candidates || []
  certId.value = qrIds.length === 1 ? qrIds[0]! : !qrIds.length && references.length === 1 ? references[0]! : ''
  checked.value = false
  zoomed.value = false
  imageError.value = false
  message.value = ''
  releaseImage()
  if (photo.value) imageUrl.value = URL.createObjectURL(photo.value.file)
}
function openPending(enterManually = false) {
  if (!pendingPhotos.value.length) return
  pendingOnly.value = true
  manual.value = enterManually
  const target = enterManually
    ? pendingPhotos.value.find(item => {
      const index = props.photos.findIndex(p => p.id === item.id)
      return index > 0 && !used.value.has(props.photos[index - 1]!.id)
    }) : pendingPhotos.value[0]
  selectPhoto((target || pendingPhotos.value[0])!.id)
  dialog.value?.showModal()
}
function openPhoto(id: string) {
  pendingOnly.value = false
  manual.value = false
  selectPhoto(id)
  dialog.value?.showModal()
}
function step(offset: number) {
  const target = choices.value[position.value + offset]
  if (target) selectPhoto(target.id)
}
async function save() {
  if (!checked.value || pairError.value) return
  try {
    const pair = createManualPair(props.photos, props.pairs, selectedId.value, certId.value, props.allFilenames)
    emit('addPair', pair)
    dialog.value?.close()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '录入失败，请检查证书号和图片。'
    await nextTick()
  }
}
defineExpose({ openPending, openPhoto })
onBeforeUnmount(releaseImage)
</script>

<template>
  <dialog ref="dialog" class="confirm-dialog photo-review-dialog" aria-labelledby="photo-review-title" @close="releaseImage">
    <header class="review-header">
      <h2 id="photo-review-title">{{ pendingOnly ? '待检查图片' : '查看图片' }}</h2>
      <button class="text-button" aria-label="关闭图片检查" @click="dialog?.close()">关闭 ×</button>
    </header>
    <template v-if="photo">
      <label class="field-label">{{ pendingOnly ? '待检查图片' : '原始图片' }}
        <select :value="selectedId" @change="selectPhoto(($event.target as HTMLSelectElement).value)">
          <option v-for="item in choices" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
      </label>
      <p class="review-reason">{{ reason }}</p>
      <div class="review-image" :class="{ zoomed }">
        <img v-if="imageUrl && !imageError" :src="imageUrl" :alt="photo.name" @error="imageError = true" />
        <p v-else>无法显示这张图片，请检查原文件是否损坏。</p>
      </div>
      <nav class="review-navigation" aria-label="图片检查导航">
        <button class="text-button" :disabled="position <= 0" @click="step(-1)">上一张</button>
        <button class="text-button" :disabled="imageError" @click="zoomed = !zoomed">{{ zoomed ? '适应窗口' : '放大图片' }}</button>
        <span>{{ position + 1 }} / {{ choices.length }}</span>
        <button class="text-button" :disabled="position >= choices.length - 1" @click="step(1)">下一张</button>
      </nav>
      <div v-if="!used.has(photo.id)" class="manual-entry">
        <button v-if="!manual" class="button secondary" @click="manual = true">这是背图，人工录入</button>
        <form v-else @submit.prevent="save">
          <div class="manual-sources">
            <div><span>正面 A · 前一张</span><img v-if="front?.thumbnailUrl" :src="front.thumbnailUrl" :alt="front.name" /><strong>{{ front?.name || '没有前一张图片' }}</strong></div>
            <div><span>背面 B · 当前图片</span><img v-if="photo.thumbnailUrl" :src="photo.thumbnailUrl" :alt="photo.name" /><strong>{{ photo.name }}</strong></div>
          </div>
          <p v-if="pairError" class="review-error" role="alert">{{ pairError }}</p>
          <label class="field-label">证书号
            <input v-model="certId" aria-label="人工录入证书号" autocomplete="off" maxlength="128" list="reference-certificates" :disabled="!!pairError" @input="message = ''; checked = false" />
          </label>
          <datalist id="reference-certificates"><option v-for="candidate in (photo.certIds.length ? photo.certIds : photo.textReference?.candidates || [])" :key="candidate" :value="candidate" /></datalist>
          <label class="manual-confirm"><input v-model="checked" type="checkbox" :disabled="!!pairError" />已核对标签证书号及正反面图片</label>
          <p v-if="message" class="review-error" role="alert">{{ message }}</p>
          <div class="dialog-actions"><button type="submit" class="button primary" :disabled="!!pairError || !checked || !certId.trim()">加入命名预览</button></div>
        </form>
      </div>
    </template>
  </dialog>
</template>

<style scoped>
.photo-review-dialog { width: min(820px, calc(100% - 32px)); max-height: calc(100dvh - 32px); overflow: auto; }
.review-header, .review-navigation { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.review-header h2 { margin: 0; font-size: 20px; }
.review-header { margin-bottom: 16px; }
.review-image { overflow: auto; max-height: 48vh; background: #eff1e9; text-align: center; border-radius: 6px; }
.review-image img { display: block; max-width: 100%; max-height: 48vh; object-fit: contain; margin: auto; }
.review-image.zoomed img { max-width: none; max-height: none; width: 1600px; margin: 0; }
.review-navigation { margin: 10px 0 16px; font-size: 11px; }
.review-reason { overflow-wrap: anywhere; }
.manual-entry { border-top: 1px solid #e1e8d7; padding-top: 16px; }
.manual-sources { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
.manual-sources > div { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-width: 0; font-size: 11px; }
.manual-sources span { width: 100%; color: #7d8a72; }
.manual-sources img { width: 44px; height: 60px; object-fit: contain; }
.manual-sources strong { flex: 1; min-width: 0; overflow-wrap: anywhere; font-weight: 500; }
.manual-confirm { display: flex; align-items: center; gap: 8px; font-size: 12px; margin-top: 14px; }
.review-error { color: #9c4e37; }
.text-button:disabled { cursor: default; opacity: .4; }
@media (max-width: 720px) { .photo-review-dialog { padding: 16px; } .review-header h2 { font-size: 18px; } }
</style>
