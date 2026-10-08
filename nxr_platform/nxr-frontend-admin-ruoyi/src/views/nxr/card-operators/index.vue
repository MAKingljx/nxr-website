<template>
  <main class="nxr-workspace card-operators">
    <nxr-page-header :kicker="$tx('CARD OPERATIONS')" :title="$tx('Card Operators')">
      <template #actions>
        <el-button type="primary" plain icon="Plus" v-hasPermi="['nxr:card-user:add']" @click="openCreate">{{ $tx('Add Card Operator') }}</el-button>
      </template>
    </nxr-page-header>

    <el-form class="operator-search" :inline="true" @submit.prevent="search">
      <el-form-item :label="$tx('Username or display name')">
        <el-input v-model="query.query" clearable :placeholder="$tx('Search card operators')" @keyup.enter="search" />
      </el-form-item>
      <el-form-item :label="$tx('Status')">
        <el-select v-model="query.status" clearable :placeholder="$tx('All statuses')">
          <el-option :label="$tx('Active')" value="0" />
          <el-option :label="$tx('Disabled')" value="1" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="search">{{ $tx('Search') }}</el-button>
        <el-button icon="Refresh" @click="clearFilters">{{ $tx('Reset') }}</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="loadError" type="error" :closable="false" class="load-error">
      <template #title>{{ $tx('Unable to load card operators. Please retry.') }}</template>
      <el-button size="small" @click="getList">{{ $tx('Retry') }}</el-button>
    </el-alert>
    <el-table v-loading="loading" :data="rows" :empty-text="loadError ? $tx('Loading failed') : $tx('No card operators found')">
      <el-table-column :label="$tx('Username')" prop="userName" min-width="180" show-overflow-tooltip />
      <el-table-column :label="$tx('Display Name')" prop="nickName" min-width="170" show-overflow-tooltip />
      <el-table-column :label="$tx('Status')" width="110">
        <template #default="scope"><el-tag :type="scope.row.status === '0' ? 'success' : 'info'">{{ $tx(scope.row.status === '0' ? 'Active' : 'Disabled') }}</el-tag></template>
      </el-table-column>
      <el-table-column :label="$tx('Created At')" min-width="180">
        <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
      </el-table-column>
      <el-table-column :label="$tx('Actions')" min-width="240">
        <template #default="scope">
          <el-button link :type="scope.row.status === '0' ? 'warning' : 'primary'" :disabled="busyUsers.has(scope.row.userId)"
            v-hasPermi="['nxr:card-user:edit']" @click="changeStatus(scope.row)">{{ $tx(scope.row.status === '0' ? 'Disable' : 'Enable') }}</el-button>
          <el-button link type="primary" :disabled="busyUsers.has(scope.row.userId)"
            v-hasPermi="['nxr:card-user:resetPwd']" @click="openReset(scope.row)">{{ $tx('Reset Password') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-if="total > 0" :total="total" v-model:page="query.page" v-model:limit="query.pageSize" @pagination="getList" />

    <el-drawer v-model="createOpen" :title="$tx('Add Card Operator')" size="min(480px, 96vw)" append-to-body
      :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting" @closed="clearCreate">
      <el-form ref="createRef" :model="form" :rules="createRules" label-position="top" @submit.prevent="submitCreate">
        <el-form-item :label="$tx('Username')" prop="username">
          <el-input v-model="form.username" maxlength="20" autocomplete="off" :disabled="submitting" />
        </el-form-item>
        <el-form-item :label="$tx('Display Name')" prop="nickName">
          <el-input v-model="form.nickName" maxlength="30" :disabled="submitting" />
        </el-form-item>
        <el-form-item :label="$tx('Password')" prop="password">
          <el-input v-model="form.password" type="password" show-password maxlength="20" autocomplete="new-password" :disabled="submitting" />
        </el-form-item>
        <el-form-item :label="$tx('Roles')">
          <el-tag>{{ $tx('NXR Card Operator') }}</el-tag>
        </el-form-item>
        <p class="form-note">{{ $tx('Share the username and password securely with the operator. Passwords are not displayed again after saving.') }}</p>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="createOpen = false">{{ $tx('Cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">{{ $tx('Create Account') }}</el-button>
      </template>
    </el-drawer>

    <el-drawer v-model="resetOpen" :title="$tx('Reset Password')" size="min(480px, 96vw)" append-to-body
      :close-on-click-modal="!resetting" :close-on-press-escape="!resetting" :show-close="!resetting" @closed="clearReset">
      <p class="account-name">{{ resetTarget?.userName }}</p>
      <el-alert type="warning" :closable="false" :title="$tx('Saving a new password signs this operator out of all previous sessions.')" />
      <el-form ref="resetRef" :model="resetForm" :rules="resetRules" label-position="top" class="reset-form" @submit.prevent="submitReset">
        <el-form-item :label="$tx('New password')" prop="password">
          <el-input v-model="resetForm.password" type="password" show-password maxlength="20" autocomplete="new-password" :disabled="resetting" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="resetting" @click="resetOpen = false">{{ $tx('Cancel') }}</el-button>
        <el-button type="primary" :loading="resetting" @click="submitReset">{{ $tx('Save Password') }}</el-button>
      </template>
    </el-drawer>
  </main>
</template>

<script setup name="NxrCardOperators">
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import { activeLocale, tx } from '@/i18n'
import { usePasswordRule } from '@/utils/passwordRule'
import { listCardOperators, createCardOperator, changeCardOperatorStatus, resetCardOperatorPassword } from '@/api/nxr/cardOperators'

const { proxy } = getCurrentInstance()
const { pwdValidator } = usePasswordRule()
const query = reactive({ page: 1, pageSize: 20, query: '', status: '' })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const loadError = ref(false)
let requestNumber = 0
const busyUsers = reactive(new Set())
const createOpen = ref(false)
const submitting = ref(false)
const createRef = ref()
const form = reactive({ username: '', nickName: '', password: '' })
const createRules = computed(() => ({
  username: [{ required: true, message: tx('Username is required'), trigger: 'blur' },
    { pattern: /^[A-Za-z0-9][A-Za-z0-9_-]{1,19}$/, message: tx('Username must contain 2 to 20 letters, digits, underscores or hyphens'), trigger: 'blur' }],
  nickName: [{ required: true, message: tx('Display name is required'), trigger: 'blur' }],
  password: pwdValidator.value
}))
const resetOpen = ref(false)
const resetting = ref(false)
const resetTarget = ref(null)
const resetRef = ref()
const resetForm = reactive({ password: '' })
const resetRules = computed(() => ({ password: pwdValidator.value }))

function formatDate(value) {
  const date = value ? new Date(value) : null
  return date && !Number.isNaN(date.getTime()) ? new Intl.DateTimeFormat(activeLocale(), { dateStyle: 'short', timeStyle: 'short' }).format(date) : '-'
}

async function getList() {
  const current = ++requestNumber
  loading.value = true
  loadError.value = false
  try {
    const response = await listCardOperators({ ...query })
    if (current !== requestNumber) return
    rows.value = response.data.items
    total.value = response.data.total
  } catch {
    if (current === requestNumber) loadError.value = true
  } finally {
    if (current === requestNumber) loading.value = false
  }
}

function search() { query.page = 1; getList() }
function clearFilters() { Object.assign(query, { page: 1, query: '', status: '' }); getList() }
function clearCreate() { Object.assign(form, { username: '', nickName: '', password: '' }); createRef.value?.clearValidate() }
function openCreate() { clearCreate(); createOpen.value = true }
async function submitCreate() {
  if (submitting.value || !await createRef.value.validate().catch(() => false)) return
  submitting.value = true
  try {
    await createCardOperator({ ...form })
    proxy.$modal.msgSuccess(tx('Card operator account created'))
    form.password = ''
    createOpen.value = false
    query.page = 1
    await getList()
  } finally { submitting.value = false }
}

async function changeStatus(row) {
  if (busyUsers.has(row.userId)) return
  const status = row.status === '0' ? '1' : '0'
  const message = status === '1' ? tx('Disable {name}? Existing sessions will be signed out.', { name: row.userName }) : tx('Enable {name}? The operator will need to sign in again.', { name: row.userName })
  try { await proxy.$modal.confirm(message) } catch { return }
  if (busyUsers.has(row.userId)) return
  busyUsers.add(row.userId)
  try {
    await changeCardOperatorStatus(row.userId, status)
    proxy.$modal.msgSuccess(tx('Operator status updated'))
    await getList()
  } finally { busyUsers.delete(row.userId) }
}

function openReset(row) { resetTarget.value = row; resetForm.password = ''; resetOpen.value = true }
function clearReset() { resetForm.password = ''; resetTarget.value = null; resetRef.value?.clearValidate() }
async function submitReset() {
  if (resetting.value || !resetTarget.value || !await resetRef.value.validate().catch(() => false)) return
  resetting.value = true
  try {
    await resetCardOperatorPassword(resetTarget.value.userId, resetForm.password)
    proxy.$modal.msgSuccess(tx('Operator password updated'))
    resetForm.password = ''
    resetOpen.value = false
  } finally { resetting.value = false }
}

getList()
</script>

<style scoped>
.load-error { margin-bottom: 16px; }
.operator-search { display: flex; flex-wrap: wrap; gap: 0 12px; margin-top: 18px; }
.operator-search :deep(.el-input), .operator-search :deep(.el-select) { width: 230px; }
.form-note { color: var(--el-text-color-secondary); line-height: 1.7; }
.account-name { font-weight: 600; overflow-wrap: anywhere; }
.reset-form { margin-top: 24px; }
@media (max-width: 640px) {
  .operator-search :deep(.el-form-item) { width: 100%; margin-right: 0; }
  .operator-search :deep(.el-input), .operator-search :deep(.el-select) { width: 100%; }
}
</style>
