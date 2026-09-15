<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { tx, localizeBackendMessage } from '@/i18n'
import { agentDateLabel, agentStatusLabel, emptyAgentPage, formatMoney } from '../lib/agentWorkbench'
import { fetchAgentOverview, type AgentOverviewRow } from '../lib/agentOverview'
import AgentPagination from './AgentPagination.vue'

const props = defineProps<{ view: string; financeAllowed: boolean }>()
const emit = defineEmits<{ open: [row: AgentOverviewRow] }>()
const rows = ref(emptyAgentPage<AgentOverviewRow>()), query = ref(''), loading = ref(false), error = ref('')
const denied = computed(() => props.view === 'wallet' && !props.financeAllowed)
const titleLabel = computed(() => tx(({ clients:'Customer', intakes:'Carrier / tracking number', batches:'Batch name', returns:'Carrier / tracking number', wallet:'Currency', addresses:'Contact name' } as Record<string,string>)[props.view]))
let generation = 0, controller: AbortController | null = null
async function load(page = 1) {
  if (denied.value) return
  const current = ++generation
  controller?.abort(); controller = new AbortController()
  loading.value = true; error.value = ''; rows.value = emptyAgentPage<AgentOverviewRow>()
  try { const result = await fetchAgentOverview(props.view, { query:query.value.trim(), page, pageSize:20 }, controller.signal); if (current === generation) rows.value = result }
  catch (e) { if (current === generation) error.value = localizeBackendMessage(e instanceof Error ? e.message : tx('Unable to load workspace records.')) }
  finally { if (current === generation) loading.value = false }
}
function status(code: string | null) {
  if (code === 'active') return tx('Active')
  if (code === 'inactive') return tx('Disabled')
  if (code === 'archived') return tx('Archived')
  return code ? agentStatusLabel(code) : '—'
}
onMounted(() => load())
onBeforeUnmount(() => { generation++; controller?.abort() })
</script>

<template>
  <el-card shadow="never" class="workspace-overview" data-testid="agent-overview">
    <el-alert v-if="denied" :title="$tx('Finance permission is required to view company balances.')" type="info" :closable="false" />
    <template v-else>
      <form class="overview-search" @submit.prevent="load()">
        <el-input v-model="query" clearable :placeholder="$tx('Search company, reference or name')" :aria-label="$tx('Search workspace records')" data-testid="agent-overview-query" @clear="load()" />
        <el-button type="primary" native-type="submit" :loading="loading">{{ $tx('Search') }}</el-button>
        <span class="record-count">{{ $tx('{count} records', { count:rows.total }) }}</span>
      </form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="overview-error" />
      <el-table v-loading="loading" :data="rows.items" row-key="id" data-testid="agent-overview-table" border>
        <el-table-column :label="$tx('Partner company')" min-width="180">
          <template #default="{row}">{{ row.companyName }} <el-tag v-if="!row.companyActive" size="small" type="info">{{ $tx('Disabled') }}</el-tag></template>
        </el-table-column>
        <el-table-column v-if="view !== 'wallet'" prop="reference" :label="view === 'addresses' ? $tx('Address name') : $tx('Reference')" min-width="170" />
        <el-table-column :prop="['intakes','returns'].includes(view) ? 'detail' : 'title'" :label="titleLabel" min-width="160" />
        <el-table-column v-if="['intakes','returns'].includes(view)" prop="clientName" :label="$tx('Customer')" min-width="140" />
        <el-table-column v-if="['clients','intakes','batches','returns'].includes(view)" :label="$tx('Cards')" width="90"><template #default="{row}">{{ row.cardCount ?? '—' }}</template></el-table-column>
        <el-table-column v-if="view === 'wallet'" :label="$tx('Balance')" min-width="150"><template #default="{row}">{{ row.amount != null && row.currencyCode ? formatMoney(row.amount,row.currencyCode) : '—' }}</template></el-table-column>
        <el-table-column v-if="view === 'addresses'" prop="detail" :label="$tx('Address')" min-width="260" />
        <el-table-column v-if="!['wallet','addresses'].includes(view)" :label="$tx('Status')" min-width="155"><template #default="{row}">{{ status(row.statusCode) }}</template></el-table-column>
        <el-table-column :label="$tx('Updated')" min-width="170"><template #default="{row}">{{ agentDateLabel(row.updatedAt) }}</template></el-table-column>
        <el-table-column :label="$tx('Actions')" width="130" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="!row.companyActive" :data-testid="`agent-overview-open-${row.id}`" @click="emit('open',row)">{{ $tx('View records') }}</el-button></template></el-table-column>
      </el-table>
      <AgentPagination v-bind="rows" :disabled="loading" @change="load" />
    </template>
  </el-card>
</template>
<style scoped>
.overview-search{display:flex;align-items:center;gap:12px;margin-bottom:20px}.overview-search .el-input{max-width:420px}.record-count{margin-left:auto;color:var(--el-text-color-secondary);white-space:nowrap}.overview-error{margin-bottom:16px}@media(max-width:700px){.overview-search{flex-wrap:wrap}.record-count{margin-left:0}}
</style>
