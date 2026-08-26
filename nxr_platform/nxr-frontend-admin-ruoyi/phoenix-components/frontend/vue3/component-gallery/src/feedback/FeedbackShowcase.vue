<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import componentCatalog from '../data/components.json'
import {
  AnonymousApiError,
  createAnonymousRecordClient,
  createEditToken,
  createIdempotencyKey,
} from './api'
import { canSendEditCredential, copySensitiveValue } from './security'
import type { EditableFeedbackRecord, FetchClient } from './types'

interface CatalogComponent { id: string; version: string }
const props = defineProps<{
  fetchClient?: FetchClient
  componentIds?: readonly string[]
  components?: readonly CatalogComponent[]
}>()
type FeedbackPayload = Omit<EditableFeedbackRecord, 'id' | 'status'>

const defaultComponents = (componentCatalog as Array<{ id?: string; version?: string }>)
  .filter((item): item is CatalogComponent => Boolean(item.id && item.version))
const availableComponents = computed(() => props.components ?? defaultComponents)
const availableComponentIds = computed(() => props.componentIds ?? availableComponents.value.map((item) => item.id))
const componentVersionById = computed(() => new Map(availableComponents.value.map((item) => [item.id, item.version])))
const api = computed(() => createAnonymousRecordClient(props.fetchClient))
const emptyPayload = (): FeedbackPayload => ({
  componentId: '',
  componentVersion: '',
  title: '',
  useCase: '',
  problem: '',
  impact: '',
  keyImprovement: '',
  acceptanceCriteria: '',
})
const createForm = reactive(emptyPayload())
const editForm = reactive(emptyPayload())
const credential = reactive({ id: '', token: '' })
const created = ref<{ id: string; status: string; editToken: string } | null>(null)
const loaded = ref<EditableFeedbackRecord | null>(null)
const creating = ref(false)
const loading = ref(false)
const updating = ref(false)
const createError = ref('')
const editError = ref('')
const editNotice = ref('')
const copied = ref(false)
const publicRecords = ref<EditableFeedbackRecord[]>([])
const publicLoading = ref(true)
const publicError = ref('')
let idempotencyKey = ''
let clientEditToken = ''

async function loadPublicFeedbacks() {
  publicLoading.value = true
  publicError.value = ''
  try {
    const response = await api.value.listFeedbacks()
    publicRecords.value = Array.isArray(response.records) ? response.records : []
  } catch {
    publicError.value = '暂时无法读取最新反馈，仍可浏览组件并稍后重试。'
  } finally {
    publicLoading.value = false
  }
}

function payloadOf(form: FeedbackPayload): FeedbackPayload {
  return {
    componentId: form.componentId.trim(),
    ...(form.componentVersion?.trim() ? { componentVersion: form.componentVersion.trim() } : {}),
    title: form.title.trim(),
    useCase: form.useCase.trim(),
    problem: form.problem.trim(),
    impact: form.impact.trim(),
    keyImprovement: form.keyImprovement.trim(),
    acceptanceCriteria: form.acceptanceCriteria.trim(),
  }
}

function isIncomplete(payload: FeedbackPayload, allowLegacyVersion = false) {
  return !payload.componentId || (!allowLegacyVersion && !payload.componentVersion) || !payload.title || !payload.useCase
    || !payload.problem || !payload.impact || !payload.keyImprovement || !payload.acceptanceCriteria
}

function syncComponentVersion(form: FeedbackPayload) {
  form.componentVersion = componentVersionById.value.get(form.componentId.trim()) ?? ''
}

async function submitFeedback() {
  if (creating.value) return
  const payload = payloadOf(createForm)
  if (isIncomplete(payload)) {
    createError.value = '请填写所有必填项。'
    return
  }
  creating.value = true
  createError.value = ''
  copied.value = false
  idempotencyKey ||= createIdempotencyKey('feedback')
  clientEditToken ||= createEditToken()
  try {
    const response = await api.value.createFeedback(payload, idempotencyKey, clientEditToken)
    created.value = { id: response.record.id, status: response.record.status, editToken: response.editToken ?? '' }
    if (!response.editToken) {
      createError.value = response.replayed
        ? '反馈已提交，但编辑凭证只在首次成功响应中返回。'
        : '反馈已提交，但服务未返回编辑凭证。'
    }
  } catch (error) {
    createError.value = error instanceof AnonymousApiError ? error.message : '提交失败，请稍后再试。'
  } finally {
    creating.value = false
  }
}

function startAnother() {
  Object.assign(createForm, emptyPayload())
  created.value = null
  createError.value = ''
  copied.value = false
  idempotencyKey = ''
  clientEditToken = ''
}

async function copyCredential() {
  if (!created.value?.editToken) return
  copied.value = await copySensitiveValue(`${created.value.id}\n${created.value.editToken}`)
  if (!copied.value) createError.value = '复制失败，请手动保存编号和编辑凭证。'
}

function ensureSecureEdit() {
  if (canSendEditCredential()) return true
  editError.value = '当前页面不是安全连接，不能发送编辑凭证。请通过 HTTPS 访问。'
  return false
}

async function loadFeedback() {
  if (loading.value || updating.value) return
  if (!credential.id.trim() || !credential.token.trim()) {
    editError.value = '请输入反馈编号和编辑凭证。'
    return
  }
  loading.value = true
  editError.value = ''
  editNotice.value = ''
  try {
    const response = await api.value.loadFeedback(credential.id)
    loaded.value = response.record
    Object.assign(editForm, payloadOf(response.record))
    editNotice.value = '已加载，可以修改后保存。'
  } catch (error) {
    loaded.value = null
    editError.value = error instanceof AnonymousApiError ? error.message : '加载失败，请核对编号和编辑凭证。'
  } finally {
    loading.value = false
  }
}

async function updateFeedback() {
  if (updating.value || loading.value || !loaded.value || !ensureSecureEdit()) return
  const payload = payloadOf(editForm)
  if (isIncomplete(payload, !loaded.value.componentVersion || loaded.value.componentBinding === 'legacy')) {
    editError.value = '请填写所有必填项。'
    return
  }
  updating.value = true
  editError.value = ''
  editNotice.value = ''
  try {
    const response = await api.value.updateFeedback(credential.id, credential.token, payload)
    loaded.value = response.record
    Object.assign(editForm, payloadOf(response.record))
    editNotice.value = '反馈已更新。'
  } catch (error) {
    editError.value = error instanceof AnonymousApiError ? error.message : '更新失败，请稍后再试。'
  } finally {
    updating.value = false
  }
}

onMounted(loadPublicFeedbacks)
</script>

<template>
  <div class="px-feedback" aria-labelledby="feedback-title">
    <header class="px-feedback__hero">
      <h1 id="feedback-title">使用反馈</h1>
    </header>

    <section class="px-public-records" aria-labelledby="public-feedback-title">
      <header><h2 id="public-feedback-title">最新反馈</h2><button type="button" :disabled="publicLoading" @click="loadPublicFeedbacks">{{ publicLoading ? '读取中' : '刷新' }}</button></header>
      <p v-if="publicError" class="px-contribution__message is-error" role="alert">{{ publicError }}</p>
      <div v-if="publicRecords.length" class="px-public-records__list" aria-live="polite">
        <article v-for="record in publicRecords" :key="record.id" :data-feedback-id="record.id">
          <div><code>{{ record.componentId }}<template v-if="record.componentVersion">@{{ record.componentVersion }}</template></code><span>{{ record.status }}</span></div>
          <h3>{{ record.title }}</h3>
          <dl><div><dt>使用场景</dt><dd>{{ record.useCase }}</dd></div><div><dt>问题</dt><dd>{{ record.problem }}</dd></div><div><dt>关键改进</dt><dd>{{ record.keyImprovement }}</dd></div><div><dt>验收标准</dt><dd>{{ record.acceptanceCriteria }}</dd></div></dl>
        </article>
      </div>
      <p v-else-if="!publicLoading && !publicError" class="px-public-records__empty" role="status">暂无公开反馈</p>
    </section>

    <section class="px-contribution" aria-labelledby="feedback-submit-title">
      <header><h2 id="feedback-submit-title">提交使用反馈</h2></header>
      <form v-if="!created" class="px-contribution__form" @submit.prevent="submitFeedback">
        <label class="px-field px-field--wide"><span>组件 ID</span><input v-model="createForm.componentId" list="feedback-component-ids" required maxlength="180" autocomplete="off" @input="syncComponentVersion(createForm)"><datalist id="feedback-component-ids"><option v-for="id in availableComponentIds" :key="id" :value="id" /></datalist></label>
        <label class="px-field px-field--wide"><span>组件版本</span><input v-model="createForm.componentVersion" required readonly autocomplete="off"></label>
        <label class="px-field px-field--wide"><span>反馈标题</span><input v-model="createForm.title" required maxlength="120" autocomplete="off"></label>
        <label class="px-field px-field--wide"><span>真实使用场景</span><textarea v-model="createForm.useCase" required maxlength="2000" rows="4"></textarea></label>
        <label class="px-field px-field--wide"><span>遇到的问题</span><textarea v-model="createForm.problem" required maxlength="2000" rows="4"></textarea></label>
        <label class="px-field px-field--wide"><span>造成的影响</span><textarea v-model="createForm.impact" required maxlength="2000" rows="4"></textarea></label>
        <label class="px-field px-field--wide"><span>关键改进</span><textarea v-model="createForm.keyImprovement" required maxlength="2000" rows="4"></textarea></label>
        <label class="px-field px-field--wide"><span>验收标准</span><textarea v-model="createForm.acceptanceCriteria" required maxlength="2000" rows="4"></textarea></label>
        <div class="px-contribution__actions px-field--wide"><button type="submit" :disabled="creating">{{ creating ? '提交中' : '匿名提交反馈' }}</button></div>
      </form>

      <div v-else class="px-credential" role="status" aria-live="polite">
        <strong>反馈已提交</strong>
        <dl><div><dt>编号</dt><dd><code>{{ created.id }}</code></dd></div><div><dt>状态</dt><dd>{{ created.status }}</dd></div></dl>
        <template v-if="created.editToken">
          <label><span>编辑凭证（仅显示这一次）</span><textarea :value="created.editToken" readonly rows="3" @focus="($event.target as HTMLTextAreaElement).select()"></textarea></label>
          <button type="button" @click="copyCredential">{{ copied ? '已复制' : '复制编号和凭证' }}</button>
        </template>
        <button type="button" class="is-secondary" @click="startAnother">继续提交</button>
      </div>
      <p v-if="createError" class="px-contribution__message is-error" role="alert">{{ createError }}</p>

      <details class="px-contribution__edit">
        <summary>更新我的反馈</summary>
        <form class="px-credential-lookup" @submit.prevent="loadFeedback">
          <label class="px-field"><span>反馈编号</span><input v-model="credential.id" required autocomplete="off"></label>
          <label class="px-field"><span>编辑凭证</span><input v-model="credential.token" required type="password" autocomplete="off"></label>
          <button type="submit" :disabled="loading || updating">{{ loading ? '加载中' : '加载反馈' }}</button>
        </form>
        <form v-if="loaded" class="px-contribution__form" @submit.prevent="updateFeedback">
          <output class="px-record-status px-field--wide">当前状态：{{ loaded.status }}</output>
          <label class="px-field px-field--wide"><span>组件 ID</span><input v-model="editForm.componentId" list="feedback-component-ids" required maxlength="180" @input="syncComponentVersion(editForm)"></label>
          <label class="px-field px-field--wide"><span>组件版本</span><input v-model="editForm.componentVersion" :required="loaded.componentBinding !== 'legacy'" readonly></label>
          <label class="px-field px-field--wide"><span>反馈标题</span><input v-model="editForm.title" required maxlength="120"></label>
          <label class="px-field px-field--wide"><span>真实使用场景</span><textarea v-model="editForm.useCase" required maxlength="2000" rows="4"></textarea></label>
          <label class="px-field px-field--wide"><span>遇到的问题</span><textarea v-model="editForm.problem" required maxlength="2000" rows="4"></textarea></label>
          <label class="px-field px-field--wide"><span>造成的影响</span><textarea v-model="editForm.impact" required maxlength="2000" rows="4"></textarea></label>
          <label class="px-field px-field--wide"><span>关键改进</span><textarea v-model="editForm.keyImprovement" required maxlength="2000" rows="4"></textarea></label>
          <label class="px-field px-field--wide"><span>验收标准</span><textarea v-model="editForm.acceptanceCriteria" required maxlength="2000" rows="4"></textarea></label>
          <div class="px-contribution__actions px-field--wide"><button type="submit" :disabled="updating || loading">{{ updating ? '保存中' : '保存修改' }}</button></div>
        </form>
        <p v-if="editNotice" class="px-contribution__message" role="status" aria-live="polite">{{ editNotice }}</p>
        <p v-if="editError" class="px-contribution__message is-error" role="alert">{{ editError }}</p>
      </details>
    </section>
  </div>
</template>
