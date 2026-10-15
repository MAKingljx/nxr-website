<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { tx, localizeBackendMessage } from '@/i18n'
import { agentDateLabel, emptyAgentPage, useAgentApi, type PartnerReturnParcel } from '../lib/agentWorkbench'
import AgentPagination from './AgentPagination.vue'
const props = defineProps<{ orderId?: number | null; batchId?: number | null }>()
const api = useAgentApi(), rows = ref(emptyAgentPage<PartnerReturnParcel>()), loading = ref(false), error = ref('')
const selected = ref<PartnerReturnParcel | null>(null), detailLoading = ref(false)
let generation = 0, detailGeneration = 0
async function load(page = 1) {
  const current = ++generation
  if (!props.orderId && !props.batchId) { rows.value = emptyAgentPage(); return }
  loading.value = true; error.value = ''
  try { const value = await api.fetchPartnerReturnParcels({ orderId: props.orderId || undefined, batchId: props.batchId || undefined, page, pageSize: 10 }); if (current === generation) rows.value = value }
  catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : tx('Unable to load return parcels.') }
  finally { if (current === generation) loading.value = false }
}
async function open(row: PartnerReturnParcel) {
  const current = ++detailGeneration
  detailLoading.value = true; error.value = ''
  try { const value = await api.fetchPartnerReturnParcel(row.id); if (current === detailGeneration) selected.value = value }
  catch (e) { if (current === detailGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load return parcels.') }
  finally { if (current === detailGeneration) detailLoading.value = false }
}
watch(() => [props.orderId, props.batchId], () => { selected.value = null; detailGeneration++; void load() }, { immediate: true })
onBeforeUnmount(() => { generation++; detailGeneration++ })
</script>
<template>
  <section class="partner-return-history" data-testid="agent-partner-returns">
    <div class="agent-toolbar"><h3>{{ $tx('Return parcels') }}</h3><el-button link :loading="loading" @click="load(rows.page)">{{ $tx('Refresh') }}</el-button></div>
    <el-alert v-if="error" :title="localizeBackendMessage(error)" type="error" :closable="false" />
    <el-table v-loading="loading" :data="rows.items" border :empty-text="$tx('No return parcels yet.')" data-testid="agent-partner-return-table">
      <el-table-column prop="parcelNo" :label="$tx('Parcel')" min-width="160" />
      <el-table-column :label="$tx('Carrier / tracking number')" min-width="200"><template #default="{ row }">{{ row.carrierName }}<br><code>{{ row.trackingNumber }}</code></template></el-table-column>
      <el-table-column prop="cardCount" :label="$tx('Cards in parcel')" width="130" />
      <el-table-column :label="$tx('Status')" min-width="150"><template #default="{ row }"><el-tag :type="row.statusCode === 'delivered' ? 'success' : 'warning'">{{ row.statusCode === 'delivered' ? $tx('Delivered to partner') : $tx('Returning to partner') }}</el-tag></template></el-table-column>
      <el-table-column :label="$tx('Shipped at')" min-width="170"><template #default="{ row }">{{ agentDateLabel(row.shippedAt) }}</template></el-table-column>
      <el-table-column :label="$tx('Actions')" width="85"><template #default="{ row }"><el-button link type="primary" :loading="detailLoading" @click="open(row)">{{ $tx('View') }}</el-button></template></el-table-column>
    </el-table>
    <AgentPagination v-if="rows.total > rows.pageSize" v-bind="rows" :disabled="loading" @change="load" />
    <el-drawer :model-value="Boolean(selected)" :title="selected?.parcelNo || $tx('Return parcel')" size="min(1000px, 94vw)" :append-to-body="false" @close="selected = null; detailGeneration++">
      <template v-if="selected">
        <dl class="agent-facts"><div><dt>{{ $tx('Carrier') }}</dt><dd>{{ selected.carrierName }}</dd></div><div><dt>{{ $tx('Tracking number') }}</dt><dd>{{ selected.trackingNumber }}</dd></div><div><dt>{{ $tx('Return address') }}</dt><dd>{{ selected.returnAddress }}</dd></div><div><dt>{{ $tx('Delivered at') }}</dt><dd>{{ agentDateLabel(selected.deliveredAt) }}</dd></div></dl>
        <el-table :data="selected.items || []" border>
          <el-table-column :label="$tx('Order')" min-width="180"><template #default="{ row }">{{ row.orderReference || row.orderNo }}</template></el-table-column>
          <el-table-column prop="batchNo" :label="$tx('Submit')" min-width="180" />
          <el-table-column prop="cardName" :label="$tx('Card name')" min-width="160" />
          <el-table-column prop="certId" :label="$tx('Certificate ID')" min-width="150" />
        </el-table>
      </template>
    </el-drawer>
  </section>
</template>
<style scoped>.partner-return-history{margin-top:28px;min-width:0}</style>
