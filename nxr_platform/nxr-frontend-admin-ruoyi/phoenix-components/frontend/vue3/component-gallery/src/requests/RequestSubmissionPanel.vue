<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import {
  AnonymousApiError,
  copySensitiveValue,
  createAnonymousRecordClient,
  createEditToken,
  createIdempotencyKey,
} from '../feedback'
import { canSendEditCredential } from '../feedback/security'
import type { EditableRequestRecord, FetchClient } from '../feedback'

const props = defineProps<{ fetchClient?: FetchClient }>()

type RequestPayload = Omit<EditableRequestRecord, 'id' | 'status'>

const emptyPayload = (): RequestPayload => ({
  title: '',
  description: '',
  capabilityArea: '',
  useCase: '',
  expectedOutcome: '',
  targetStacks: [],
  priority: 'medium',
  referenceUrl: '',
})

const api = computed(() => createAnonymousRecordClient(props.fetchClient))
const createForm = reactive({ ...emptyPayload(), targetStacksText: '' })
const editForm = reactive({ ...emptyPayload(), targetStacksText: '' })
const credential = reactive({ id: '', token: '' })
const created = ref<{ id: string; status: string; editToken: string } | null>(null)
const loaded = ref<EditableRequestRecord | null>(null)
const createError = ref('')
const editError = ref('')
const editNotice = ref('')
const copied = ref(false)
const creating = ref(false)
const loading = ref(false)
const updating = ref(false)
let idempotencyKey = ''
let clientEditToken = ''

function toStacks(value: string) {
  return [...new Set(value.split(/[,，\n]/).map((item) => item.trim()).filter(Boolean))]
}

function requestPayload(form: typeof createForm): RequestPayload {
  return {
    title: form.title.trim(),
    description: form.description.trim(),
    capabilityArea: form.capabilityArea.trim(),
    useCase: form.useCase.trim(),
    expectedOutcome: form.expectedOutcome.trim(),
    targetStacks: toStacks(form.targetStacksText),
    priority: form.priority,
    referenceUrl: form.referenceUrl?.trim() || null,
  }
}

function missingRequired(payload: RequestPayload) {
  return !payload.title || !payload.description || !payload.capabilityArea || !payload.useCase || !payload.expectedOutcome
}

async function submitRequest() {
  if (creating.value) return
  const payload = requestPayload(createForm)
  if (missingRequired(payload)) {
    createError.value = '请填写所有必填项。'
    return
  }
  creating.value = true
  createError.value = ''
  copied.value = false
  idempotencyKey ||= createIdempotencyKey('request')
  clientEditToken ||= createEditToken()
  try {
    const response = await api.value.createRequest(payload, idempotencyKey, clientEditToken)
    created.value = {
      id: response.record.id,
      status: response.record.status,
      editToken: response.editToken ?? '',
    }
    if (!response.editToken) {
      createError.value = response.replayed
        ? '需求已提交，但编辑凭证只在首次成功响应中返回。'
        : '需求已提交，但服务未返回编辑凭证。'
    }
  } catch (error) {
    createError.value = error instanceof AnonymousApiError ? error.message : '提交失败，请稍后再试。'
  } finally {
    creating.value = false
  }
}

function startAnother() {
  Object.assign(createForm, emptyPayload(), { targetStacksText: '' })
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

function fillEditForm(record: EditableRequestRecord) {
  Object.assign(editForm, {
    title: record.title ?? '',
    description: record.description ?? '',
    capabilityArea: record.capabilityArea ?? '',
    useCase: record.useCase ?? '',
    expectedOutcome: record.expectedOutcome ?? '',
    targetStacks: record.targetStacks ?? [],
    targetStacksText: (record.targetStacks ?? []).join('，'),
    priority: record.priority ?? 'medium',
    referenceUrl: record.referenceUrl ?? '',
  })
}

async function loadRequest() {
  if (loading.value || updating.value) return
  if (!credential.id.trim() || !credential.token.trim()) {
    editError.value = '请输入需求编号和编辑凭证。'
    return
  }
  loading.value = true
  editError.value = ''
  editNotice.value = ''
  try {
    const response = await api.value.loadRequest(credential.id)
    loaded.value = response.record
    fillEditForm(response.record)
    editNotice.value = '已加载，可以修改后保存。'
  } catch (error) {
    loaded.value = null
    editError.value = error instanceof AnonymousApiError ? error.message : '加载失败，请核对编号和编辑凭证。'
  } finally {
    loading.value = false
  }
}

async function updateRequest() {
  if (updating.value || loading.value || !loaded.value || !ensureSecureEdit()) return
  const payload = requestPayload(editForm)
  if (missingRequired(payload)) {
    editError.value = '请填写所有必填项。'
    return
  }
  updating.value = true
  editError.value = ''
  editNotice.value = ''
  try {
    const response = await api.value.updateRequest(credential.id, credential.token, payload)
    loaded.value = response.record
    fillEditForm(response.record)
    editNotice.value = '需求已更新。'
  } catch (error) {
    editError.value = error instanceof AnonymousApiError ? error.message : '更新失败，请稍后再试。'
  } finally {
    updating.value = false
  }
}
</script>

<template>
  <section class="px-contribution" aria-labelledby="request-submit-title">
    <header><h2 id="request-submit-title">提交新需求</h2></header>
    <form v-if="!created" class="px-contribution__form" @submit.prevent="submitRequest">
      <label class="px-field px-field--wide"><span>需求标题</span><input v-model="createForm.title" required maxlength="120" autocomplete="off"></label>
      <label class="px-field"><span>能力分类</span><input v-model="createForm.capabilityArea" required maxlength="80" autocomplete="off" placeholder="例如：数据处理"></label>
      <label class="px-field"><span>技术方向</span><input v-model="createForm.targetStacksText" maxlength="200" autocomplete="off" placeholder="多个方向用逗号分隔"></label>
      <label class="px-field px-field--wide"><span>需求内容</span><textarea v-model="createForm.description" required maxlength="2000" rows="4"></textarea></label>
      <label class="px-field px-field--wide"><span>真实使用场景</span><textarea v-model="createForm.useCase" required maxlength="2000" rows="4"></textarea></label>
      <label class="px-field px-field--wide"><span>预期结果</span><textarea v-model="createForm.expectedOutcome" required maxlength="2000" rows="4"></textarea></label>
      <label class="px-field"><span>优先级</span><select v-model="createForm.priority"><option value="low">低</option><option value="medium">中</option><option value="high">高</option></select></label>
      <label class="px-field"><span>参考地址</span><input v-model="createForm.referenceUrl" type="url" maxlength="500" autocomplete="url"></label>
      <div class="px-contribution__actions px-field--wide"><button type="submit" :disabled="creating">{{ creating ? '提交中' : '匿名提交需求' }}</button></div>
    </form>

    <div v-else class="px-credential" role="status" aria-live="polite">
      <strong>需求已提交</strong>
      <dl><div><dt>编号</dt><dd><code>{{ created.id }}</code></dd></div><div><dt>状态</dt><dd>{{ created.status }}</dd></div></dl>
      <template v-if="created.editToken">
        <label><span>编辑凭证（仅显示这一次）</span><textarea :value="created.editToken" readonly rows="3" @focus="($event.target as HTMLTextAreaElement).select()"></textarea></label>
        <button type="button" @click="copyCredential">{{ copied ? '已复制' : '复制编号和凭证' }}</button>
      </template>
      <button type="button" class="is-secondary" @click="startAnother">继续提交</button>
    </div>
    <p v-if="createError" class="px-contribution__message is-error" role="alert">{{ createError }}</p>

    <details class="px-contribution__edit">
      <summary>更新我的需求</summary>
      <form class="px-credential-lookup" @submit.prevent="loadRequest">
        <label class="px-field"><span>需求编号</span><input v-model="credential.id" required autocomplete="off"></label>
        <label class="px-field"><span>编辑凭证</span><input v-model="credential.token" required type="password" autocomplete="off"></label>
        <button type="submit" :disabled="loading || updating">{{ loading ? '加载中' : '加载需求' }}</button>
      </form>
      <form v-if="loaded" class="px-contribution__form" @submit.prevent="updateRequest">
        <output class="px-record-status px-field--wide">当前状态：{{ loaded.status }}</output>
        <label class="px-field px-field--wide"><span>需求标题</span><input v-model="editForm.title" required maxlength="120"></label>
        <label class="px-field"><span>能力分类</span><input v-model="editForm.capabilityArea" required maxlength="80"></label>
        <label class="px-field"><span>技术方向</span><input v-model="editForm.targetStacksText" maxlength="200"></label>
        <label class="px-field px-field--wide"><span>需求内容</span><textarea v-model="editForm.description" required maxlength="2000" rows="4"></textarea></label>
        <label class="px-field px-field--wide"><span>真实使用场景</span><textarea v-model="editForm.useCase" required maxlength="2000" rows="4"></textarea></label>
        <label class="px-field px-field--wide"><span>预期结果</span><textarea v-model="editForm.expectedOutcome" required maxlength="2000" rows="4"></textarea></label>
        <label class="px-field"><span>优先级</span><select v-model="editForm.priority"><option value="low">低</option><option value="medium">中</option><option value="high">高</option></select></label>
        <label class="px-field"><span>参考地址</span><input v-model="editForm.referenceUrl" type="url" maxlength="500"></label>
        <div class="px-contribution__actions px-field--wide"><button type="submit" :disabled="updating || loading">{{ updating ? '保存中' : '保存修改' }}</button></div>
      </form>
      <p v-if="editNotice" class="px-contribution__message" role="status" aria-live="polite">{{ editNotice }}</p>
      <p v-if="editError" class="px-contribution__message is-error" role="alert">{{ editError }}</p>
    </details>
  </section>
</template>
