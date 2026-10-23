<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { tx, localizeBackendMessage } from '@/i18n'
import { workspacePath } from '@/utils/submissionWorkspace'
import { useAgentApi, type CustomerAddress } from '../lib/agentWorkbench'
import { addressLabel } from '../lib/workbenchOrders'
const api = useAgentApi(), router = useRouter(), rows = ref<CustomerAddress[]>([]), loading = ref(true), error = ref('')
let active = true, generation = 0
const address = computed(() => rows.value.find(row => row.defaultAddress) || rows.value[0])
async function load() {
  const current = ++generation
  loading.value = true; error.value = ''
  try { const value = await api.fetchCustomerAddresses(); if (active && current === generation) rows.value = value }
  catch (e) { if (active && current === generation) error.value = e instanceof Error ? e.message : tx('Unable to load addresses.') }
  finally { if (active && current === generation) loading.value = false }
}
function manage() { return router.push({ path: workspacePath('addresses'), query: { company: String(api.companyId) } }) }
watch(api.addressVersion, load)
onMounted(load)
onBeforeUnmount(() => { active = false; ++generation })
</script>
<template>
  <section class="company-address" data-testid="agent-company-address">
    <div><strong>{{ $tx('Your company address') }}</strong><p v-if="loading">{{ $tx('Loading…') }}</p><template v-else-if="address"><p>{{ address.contactName }} · {{ address.contactPhone }}</p><p>{{ addressLabel(address) }}</p></template><p v-else-if="!error">{{ $tx('Your company address is not set.') }}</p><p v-if="error" role="alert">{{ localizeBackendMessage(error) }} <el-button link type="primary" @click="load">{{ $tx('Retry') }}</el-button></p></div>
    <el-button :disabled="api.busy.value" data-testid="agent-manage-company-address" @click="manage">{{ $tx('My addresses') }}</el-button>
  </section>
</template>
<style scoped>.company-address{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:16px 18px;border:1px solid var(--el-border-color-light);border-radius:8px;margin-bottom:22px;background:var(--el-fill-color-lighter)}.company-address>div{min-width:0}.company-address p{margin:7px 0 0;font-size:13px;color:var(--el-text-color-secondary);overflow-wrap:anywhere}@media(max-width:650px){.company-address{align-items:flex-start;flex-direction:column;padding:14px}}</style>
