<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LegacySiteFooter from '../components/LegacySiteFooter.vue'
import LegacySiteNav from '../components/LegacySiteNav.vue'
import AgentClientsPanel from '../components/agent-workbench/AgentClientsPanel.vue'
import AgentIntakesPanel from '../components/agent-workbench/AgentIntakesPanel.vue'
import AgentReturnsPanel from '../components/agent-workbench/AgentReturnsPanel.vue'
import { customerSession, refreshCustomerSession } from '../lib/customer'
import { agentWorkbenchBusy } from '../lib/agentWorkbench'

const router = useRouter(), route = useRoute()
type AgentTab = 'clients' | 'intakes' | 'returns'
const tabs: Array<{ id: AgentTab; label: string }> = [{ id: 'clients', label: '客户档案' }, { id: 'intakes', label: '来件与库存' }, { id: 'returns', label: '客户回寄' }]
const tab = computed<AgentTab>(() => tabs.some(item => item.id === route.query.tab) ? route.query.tab as AgentTab : 'clients')
const selectedClientId = computed(() => { const id = Number(route.query.clientId); return Number.isSafeInteger(id) && id > 0 ? id : undefined })
const loading = ref(true), error = ref('')
const merchant = computed(() => customerSession.value?.customer.accountTypeCode === 'merchant')
const login = () => router.replace({ path: '/account/login', query: { next: '/account/agent' } })
async function navigate(next: AgentTab, clientId?: number) {
  if (agentWorkbenchBusy.value) return
  await router.replace({ path: '/account/agent', query: { tab: next, ...(clientId ? { clientId: String(clientId) } : {}) } })
}
async function initialize() {
  error.value = ''; loading.value = true
  if (!customerSession.value) { await login(); return }
  const refreshed = await refreshCustomerSession()
  if (!refreshed) {
    if (!customerSession.value) await login()
    else error.value = '账户验证失败，请检查网络后重试。'
  }
  loading.value = false
}
watch(customerSession, session => { if (!session) void login() })
onMounted(initialize)
</script>

<template>
  <LegacySiteNav active="account" cta-href="/submit/order" cta-label="New order" />
  <main class="portal-page agent-workbench" data-testid="agent-workbench">
    <div class="portal-heading"><div><p class="section-tag">Agent workbench</p><h1>代理工作台</h1></div><div class="form-row form-actions"><router-link class="btn-secondary" to="/account/orders">我的订单</router-link><router-link v-if="merchant" class="btn-secondary" to="/account/merchant-orders">送评批次</router-link><router-link v-if="merchant" class="btn-secondary" to="/account/company">企业余额</router-link></div></div>
    <p v-if="loading" class="portal-empty" role="status">正在验证账户…</p>
    <div v-else-if="error" class="portal-empty"><p class="form-error" role="alert">{{ error }}</p><button type="button" class="btn-secondary" @click="initialize">重试</button></div>
    <p v-else-if="!merchant" class="portal-empty" data-testid="agent-access-required">代理工作台需由管理员开通企业账户后使用。</p>
    <template v-else><nav class="agent-tabs" aria-label="代理工作台导航"><button v-for="item in tabs" :key="item.id" type="button" :class="{ active: tab === item.id }" :aria-current="tab === item.id ? 'page' : undefined" :disabled="agentWorkbenchBusy" :data-testid="`agent-tab-${item.id}`" @click="navigate(item.id)">{{ item.label }}</button></nav><AgentClientsPanel v-if="tab === 'clients'" @navigate="navigate" /><AgentIntakesPanel v-else-if="tab === 'intakes'" :key="`intakes-${selectedClientId || 0}`" :initial-client-id="selectedClientId" /><AgentReturnsPanel v-else :key="`returns-${selectedClientId || 0}`" :initial-client-id="selectedClientId" /></template>
  </main>
  <LegacySiteFooter />
</template>

<style scoped>
.agent-workbench :deep(.agent-toolbar > div){min-width:0;max-width:100%}.agent-workbench :deep(h2){overflow-wrap:anywhere}
.agent-workbench{max-width:1440px;margin:0 auto}.agent-tabs{display:flex;gap:6px;border-bottom:1px solid var(--border2);margin-bottom:28px}.agent-tabs button{padding:14px 22px;background:transparent;color:var(--text2);font:inherit;cursor:pointer;border:0;border-bottom:3px solid transparent}.agent-tabs button.active{color:var(--text);border-bottom-color:var(--red)}
.agent-workbench :deep(.agent-split){display:grid;grid-template-columns:minmax(260px,340px) minmax(0,1fr);gap:32px}.agent-workbench :deep(.agent-list-pane){border-right:1px solid var(--border2);padding-right:24px;min-width:0}.agent-workbench :deep(.agent-detail-pane){min-width:0}.agent-workbench :deep(h2){font-size:21px;margin:0 0 12px}.agent-workbench :deep(h3){font-size:16px;margin:24px 0 12px}.agent-workbench :deep(.agent-toolbar){display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.agent-workbench :deep(.agent-toolbar h2),.agent-workbench :deep(.agent-toolbar h3){margin:0}.agent-workbench :deep(.agent-toolbar label){flex:1;min-width:0}.agent-workbench :deep(.agent-actions){display:flex;align-items:center;flex-wrap:wrap;gap:12px;margin:22px 0}.agent-workbench :deep(.agent-search){margin:20px 0}.agent-workbench :deep(.agent-record-list){display:grid;gap:10px}.agent-workbench :deep(.agent-record){display:grid;gap:6px;min-width:0;width:100%;padding:16px;text-align:left;background:var(--bg2);color:var(--text);border:1px solid var(--border2);border-radius:6px;font:inherit;cursor:pointer;overflow-wrap:anywhere}.agent-workbench :deep(.agent-record.selected){border-color:var(--red);box-shadow:inset 3px 0 0 var(--red)}.agent-workbench :deep(.agent-record span),.agent-workbench :deep(.agent-record small){color:var(--text2);font-size:12px}.agent-workbench :deep(.agent-intake-row){display:flex;align-items:center;gap:10px}.agent-workbench :deep(.agent-select-record input){width:18px;height:18px;accent-color:var(--red)}.agent-workbench :deep(.agent-selection-bar){display:grid;gap:12px;margin-top:20px;padding:16px;border:1px solid var(--border2);border-radius:6px;background:var(--bg2)}
.agent-workbench :deep(fieldset){display:grid;gap:16px;border:0;padding:0;margin:0;min-width:0}.agent-workbench :deep(.agent-client-picker){display:grid;gap:8px}.agent-workbench :deep(.agent-client-picker .agent-toolbar){align-items:flex-end}.agent-workbench :deep(.agent-facts){display:grid;gap:14px;margin:20px 0}.agent-workbench :deep(.agent-facts>div){display:grid;grid-template-columns:105px minmax(0,1fr);gap:12px}.agent-workbench :deep(.agent-facts dt){color:var(--text2);font-size:13px}.agent-workbench :deep(.agent-facts dd){margin:0;overflow-wrap:anywhere;white-space:pre-wrap}.agent-workbench :deep(.agent-operation){padding:20px;border:1px solid var(--border2);border-radius:6px;background:var(--bg2);margin:22px 0}.agent-workbench :deep(.agent-operation h3){margin:0}.agent-workbench :deep(.agent-card-entry),.agent-workbench :deep(.agent-stock-card){padding:18px;border:1px solid var(--border2);border-radius:6px;min-width:0}.agent-workbench :deep(.agent-card-entry){display:grid;gap:14px}.agent-workbench :deep(.agent-inventory-cards){display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.agent-workbench :deep(.agent-inventory-code){display:block;font-size:14px;margin:12px 0;overflow-wrap:anywhere;user-select:all}.agent-workbench :deep(.agent-photo-controls){display:grid;gap:10px;margin-top:12px}.agent-workbench :deep(.agent-photo-controls label){display:grid;gap:7px;color:var(--text2);font-size:12px}.agent-workbench :deep(.agent-photo-controls input){font-size:11px;max-width:100%}.agent-workbench :deep(.agent-checkbox){display:flex;align-items:center;gap:10px}.agent-workbench :deep(.agent-checkbox input){width:18px;min-height:18px;accent-color:var(--red)}.agent-workbench :deep(.agent-card-choice){display:flex;align-items:center;gap:12px;border:1px solid var(--border2);padding:14px;border-radius:6px}.agent-workbench :deep(.agent-card-choice input){width:18px;min-height:18px;accent-color:var(--red);flex:none}.agent-workbench :deep(.agent-card-choice span){display:grid;gap:6px;overflow-wrap:anywhere}.agent-workbench :deep(.agent-card-choice small){font-weight:400}.agent-workbench :deep(.agent-quote){display:grid;gap:10px;border:1px solid var(--border2);border-radius:6px;padding:20px;margin:16px 0}.agent-workbench :deep(.agent-success){padding:14px 18px;border:1px solid #386348;border-radius:6px;background:#102419;color:#c5e8d0;margin:0 0 20px;line-height:1.6}.agent-workbench :deep(.agent-success a){text-decoration:underline;margin-left:6px}.agent-workbench :deep(.agent-history){border-top:1px solid var(--border2);margin-top:28px;padding-top:18px}.agent-workbench :deep(.agent-history summary){cursor:pointer;font-weight:700}.agent-workbench :deep(.agent-timeline){list-style:none;padding:0;display:grid;gap:0;margin:16px 0}.agent-workbench :deep(.agent-timeline li){padding:14px 0;border-bottom:1px solid var(--border2);font-size:13px}.agent-workbench :deep(.agent-timeline li>div){display:flex;justify-content:space-between;gap:10px;margin-bottom:6px}.agent-workbench :deep(.agent-timeline time){color:var(--text2);font-size:12px}.agent-workbench :deep(.agent-timeline p){white-space:pre-wrap;margin-top:7px;line-height:1.6}.agent-workbench :deep(.agent-pagination){display:flex;align-items:center;justify-content:space-between;gap:8px;margin:20px 0;font-size:12px}.agent-workbench :deep(.agent-pagination button){padding:8px 10px}.agent-workbench :deep(.portal-table small){display:block;margin-top:6px;color:var(--text2);font-size:11px}.agent-workbench :deep(.portal-table td){overflow-wrap:anywhere}.agent-workbench :deep(.agent-full-width){width:100%}.agent-workbench :deep(.agent-align-bottom){align-self:end}.agent-workbench :deep(button:disabled){cursor:not-allowed;opacity:.55}
@media(max-width:1000px){.agent-workbench :deep(.agent-split){grid-template-columns:minmax(230px,290px) minmax(0,1fr);gap:22px}.agent-workbench :deep(.agent-inventory-cards){grid-template-columns:1fr}.agent-workbench :deep(.agent-toolbar){flex-wrap:wrap}.agent-workbench :deep(.agent-list-pane){padding-right:18px}}
@media(max-width:760px){.agent-tabs{gap:0}.agent-tabs button{flex:1;padding:14px 8px;font-size:14px}.agent-workbench :deep(.agent-split){grid-template-columns:1fr;gap:28px}.agent-workbench :deep(.agent-list-pane){padding-right:0;border-right:0;padding-bottom:22px;border-bottom:1px solid var(--border2)}.agent-workbench :deep(.agent-record-list){max-height:320px;overflow:auto}.agent-workbench :deep(.agent-facts>div){grid-template-columns:85px minmax(0,1fr)}.agent-workbench :deep(.agent-timeline li>div){display:grid;gap:5px}.agent-workbench :deep(.agent-operation){padding:16px}.agent-workbench :deep(.form-grid){grid-template-columns:1fr}.agent-workbench :deep(.form-wide){grid-column:auto}.agent-workbench :deep(.agent-toolbar .btn-primary),.agent-workbench :deep(.agent-toolbar .btn-secondary){padding:10px 13px}}
</style>
