<script setup lang="ts">
import { tx } from '@/i18n'
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import auth from '@/plugins/auth'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import { resolveWorkspaceView, workspacePath } from '@/utils/submissionWorkspace'
import AgentWorkspace from './components/AgentWorkspace.vue'
import AgentOverviewPanel from './components/AgentOverviewPanel.vue'
import { createAgentApi, type AgentApi, type Company } from './lib/agentWorkbench'
import type { AgentOverviewRow } from './lib/agentOverview'

defineOptions({ name: 'NxrAgentWorkbench' })
const route = useRoute(), router = useRouter()
const management = shallowRef(createAgentApi()), activeApi = shallowRef<AgentApi | null>(null)
const company = ref<Company | null>(null), platformManager = ref(false), companies = ref<Company[]>([])
const selectedId = ref<number>(), loading = ref(true), searching = ref(false), error = ref('')
const view = computed(() => resolveWorkspaceView(route))
const titles: Record<string, string> = { clients:'Customer records', cards:'Card data', intakes:'Intakes and inventory', batches:'Submission batches', returns:'Customer returns', wallet:'Enterprise credits', addresses:'Return addresses' }
const title = computed(() => tx(titles[view.value]))
let generation = 0, searchGeneration = 0, initialization = 0, initializing = false, visible = true
let scopeCompanyId: number | undefined
function requestedCompany() { const id = Number(route.query.company); return Number.isSafeInteger(id) && id > 0 ? id : undefined }
async function searchCompanies(query = '') {
  const current = ++searchGeneration
  searching.value = true
  try { const result = await management.value.fetchCompanies({ query, page:1, pageSize:50 }); if (current === searchGeneration) companies.value = result.items }
  catch (e) { if (current === searchGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load companies.') }
  finally { if (current === searchGeneration) searching.value = false }
}
async function loadCompany(id?: number) {
  const current = ++generation
  scopeCompanyId = id
  activeApi.value?.dispose(); activeApi.value = null; company.value = null; selectedId.value = id
  loading.value = true; error.value = ''
  if (!id) { loading.value = false; return }
  const candidate = createAgentApi(id)
  try {
    const context = await candidate.fetchContext()
    if (current !== generation || !visible) { candidate.dispose(); return }
    if (!context.company || context.company.id !== id) throw new Error(tx('Unable to verify access to this company.'))
    company.value = context.company; activeApi.value = candidate
  } catch (e) { candidate.dispose(); if (current === generation) error.value = e instanceof Error ? e.message : tx('Unable to load the company workspace.') }
  finally { if (current === generation) loading.value = false }
}
async function selectCompany(id?: number) {
  if (activeApi.value?.busy.value) return
  await router.replace({ path:workspacePath(view.value), query:id ? { company:String(id) } : {} })
}
async function openRecord(row: AgentOverviewRow) {
  if (!row.companyActive) return
  const query: Record<string, string> = { company:String(row.companyId) }
  if (['clients','intakes','returns'].includes(view.value)) query.record = String(row.id)
  if (view.value === 'batches') query.batch = row.reference
  if (view.value === 'wallet' && row.currencyCode) query.currency = row.currencyCode
  await router.push({ path:workspacePath(view.value), query })
}
async function initialize() {
  if (initializing) return
  const current = ++initialization
  initializing = true; loading.value = true; error.value = ''
  if (!management.value.isActive()) management.value = createAgentApi()
  try {
    const context = await management.value.fetchContext()
    if (current !== initialization || !visible) return
    platformManager.value = context.platformManager
    if (context.platformManager) await searchCompanies()
    if (current !== initialization || !visible) return
    // Unbound platform staff see an overview. Bound operators always resolve to their own live company.
    const id = context.platformManager ? requestedCompany() : context.company?.id
    if (!context.platformManager && requestedCompany() && requestedCompany() !== id) {
      throw new Error(tx('Unable to verify access to this company.'))
    }
    // From this point, scope changes must invalidate in-flight company loads immediately.
    initializing = false
    await loadCompany(id)
  } catch (e) { if (current === initialization && visible) { error.value = e instanceof Error ? e.message : tx('Unable to load Submission Workspace.'); loading.value = false } }
  finally { if (current === initialization) initializing = false }
}
function dispose() { generation++; searchGeneration++; initialization++; initializing = false; activeApi.value?.dispose(); activeApi.value = null; company.value = null; management.value.dispose() }
watch(() => route.query.company, () => {
  if (initializing || !visible) return
  if (platformManager.value) { if (requestedCompany() !== scopeCompanyId || error.value) void loadCompany(requestedCompany()) }
  else void initialize()
})
onBeforeRouteLeave(() => !activeApi.value?.busy.value)
onBeforeRouteUpdate(() => !activeApi.value?.busy.value)
onMounted(initialize)
onActivated(() => { visible = true; if (!management.value.isActive()) void initialize() })
onDeactivated(() => { visible = false; dispose() })
onBeforeUnmount(dispose)
</script>

<template>
  <main class="nxr-workspace agent-admin-page" data-testid="agent-admin-workbench">
    <NxrPageHeader :title="title" :summary="company ? company.companyName || company.displayName : platformManager ? $tx('All partner companies') : ''" />
    <el-card v-if="platformManager" shadow="never" class="agent-company-card">
      <el-form inline>
        <el-form-item :label="$tx('Partner company')">
          <el-select v-model="selectedId" filterable remote clearable :remote-method="searchCompanies" :loading="searching"
            :disabled="activeApi?.busy.value" :placeholder="$tx('All partner companies')" class="company-select"
            data-testid="agent-company-select" @change="selectCompany">
            <el-option v-if="company && !companies.some(item => item.id === company?.id)" :value="company.id" :label="company.companyName || company.displayName" />
            <el-option v-for="item in companies" :key="item.id" :value="item.id" :label="item.companyName || item.displayName" />
          </el-select>
        </el-form-item>
        <el-button v-if="company" :disabled="activeApi?.busy.value" data-testid="agent-all-companies" @click="selectCompany()">{{ $tx('All partner companies') }}</el-button>
      </el-form>
    </el-card>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="agent-context-error" />
    <el-button v-if="error" @click="initialize">{{ $tx('Retry') }}</el-button>
    <p v-if="loading" class="agent-loading" role="status">{{ $tx('Loading…') }}</p>
    <AgentWorkspace v-else-if="activeApi && company" :key="`${company.id}-${generation}`" :api="activeApi" />
    <AgentOverviewPanel v-else-if="platformManager && !error" :key="view" :view="view" :finance-allowed="auth.hasPermi('nxr:customer:finance')" @open="openRecord" />
    <el-empty v-else-if="!error" :description="$tx('This account is not linked to a sub-agent company. Please contact the platform administrator.')" data-testid="agent-binding-required" />
  </main>
</template>
<style scoped>
.agent-admin-page{overflow-x:hidden}.agent-company-card{margin-bottom:20px}.agent-company-card :deep(.el-form-item){margin-bottom:0}.agent-context-error{margin-bottom:12px}.agent-loading{padding:30px;color:var(--el-text-color-secondary)}.company-select{width:360px;max-width:100%}@media(max-width:700px){.company-select{width:260px}.agent-company-card :deep(.el-form){display:flex;flex-wrap:wrap;gap:10px}}
</style>
