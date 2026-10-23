<script setup lang="ts">
import { tx, localizeBackendMessage } from '@/i18n'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { emptyAgentAddress, useAgentApi, useAgentActions, type CustomerAddress } from '../lib/agentWorkbench'
import { addressLabel } from '../lib/workbenchOrders'
import AgentAddressFields from './AgentAddressFields.vue'
const api = useAgentApi(), rows = ref<CustomerAddress[]>([]), editing = ref(false), editId = ref<number | null>(null), loading = ref(false)
const blank = () => ({ label: '', address: emptyAgentAddress(), defaultAddress: rows.value.length === 0 })
const form = ref(blank()), { busy, error, success, run } = useAgentActions()
const settingDefaultId = ref<number | null>(null)
let active = true, loadGeneration = 0
const addressDrawerOpen = computed({ get: () => editing.value, set: value => { if (!value && !busy.value) editing.value = false } })
async function load() {
  const current = ++loadGeneration
  loading.value = true; error.value = ''
  try { const result = await api.fetchCustomerAddresses(); if (active && current === loadGeneration) rows.value = result }
  catch (e) { if (active && current === loadGeneration) error.value = e instanceof Error ? e.message : tx('Unable to load addresses.') }
  finally { if (active && current === loadGeneration) loading.value = false }
}
async function setDefault(row: CustomerAddress) {
  if (busy.value || loading.value || settingDefaultId.value !== null || row.defaultAddress) return
  settingDefaultId.value = row.id
  try {
    if (await run(`address:${row.id}:default`, { id: row.id }, key => api.setDefaultAddress(row.id, key), saved => {
      rows.value = rows.value.map(item => ({ ...item, defaultAddress: item.id === saved.id }))
    }, tx('Default address updated.'))) await load()
  } finally { if (active) settingDefaultId.value = null }
}
function edit(row?: CustomerAddress) {
  editId.value = row?.id || null
  form.value = row ? { label: row.label, address: { contactName: row.contactName, phone: row.contactPhone, addressLine1: row.addressLine1, addressLine2: row.addressLine2 || '', city: row.city, region: row.region || '', postalCode: row.postalCode, country: row.country }, defaultAddress: row.defaultAddress } : blank()
  editing.value = true
}
async function save() {
  const { phone, ...address } = form.value.address
  const payload = { ...address, contactPhone: phone, label: form.value.label, defaultAddress: form.value.defaultAddress }
  if (await run(`address:${editId.value || 'create'}`, payload, key => api.saveAddress(editId.value, payload, key), () => { editing.value = false }, tx('Company address saved.'))) await load()
}
onMounted(load)
onBeforeUnmount(() => { active = false; ++loadGeneration })
</script>
<template>
  <section data-testid="agent-addresses-panel">
    <div class="agent-toolbar"><el-button type="primary" :disabled="busy" data-testid="agent-new-address" @click="edit()">{{ $tx('Add address') }}</el-button></div>
    <p class="address-purpose">{{ $tx('NXR returns cards to this company address.') }}</p>
    <el-alert v-if="error" :title="localizeBackendMessage(error)" type="error" :closable="false" /><el-button v-if="error && !editing" @click="load">{{ $tx('Retry') }}</el-button>
    <el-alert v-if="success" :title="success" type="success" :closable="false" />
    <p v-if="loading">{{ $tx('Loading…') }}</p>
    <div v-else class="agent-address-list">
      <article v-for="row in rows" :key="row.id" class="agent-stock-card"><div class="agent-toolbar"><strong>{{ row.label }}</strong><el-tag v-if="row.defaultAddress" type="success">{{ $tx('Default address') }}</el-tag><el-button :disabled="busy" :data-testid="`agent-edit-address-${row.id}`" @click="edit(row)">{{ $tx('Edit') }}</el-button></div><p>{{ row.contactName }} · {{ row.contactPhone }}</p><p>{{ addressLabel(row) }}</p>
        <div class="address-default"><el-checkbox :model-value="row.defaultAddress" :disabled="busy || row.defaultAddress || settingDefaultId !== null" size="small" :data-testid="`agent-default-address-${row.id}`" @click.prevent="setDefault(row)">{{ $tx(row.defaultAddress ? 'Default address' : 'Set as default address') }}</el-checkbox><span v-if="settingDefaultId === row.id" class="default-saving" role="status">{{ $tx('Saving…') }}</span></div>
      </article>
      <el-empty v-if="!rows.length && !error" :description="$tx('Your company address is not set.')" />
    </div>
    <el-drawer v-model="addressDrawerOpen" :title="editId ? $tx('Edit company address') : $tx('Add company address')" size="min(720px, 96vw)" direction="rtl" :append-to-body="false" destroy-on-close :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" data-testid="agent-address-drawer">
      <form v-if="editing" class="portal-form agent-operation" data-testid="agent-address-form" @submit.prevent="save">
        <el-alert v-if="error" :title="localizeBackendMessage(error)" type="error" :closable="false" />
        <fieldset :disabled="busy"><label><span>{{ $tx('Address label') }} <b class="required" aria-hidden="true">*</b></span><input v-model="form.label" required maxlength="128" /></label><AgentAddressFields v-model="form.address" /><label class="agent-checkbox"><input v-model="form.defaultAddress" type="checkbox" />{{ $tx('Set as default address') }}</label></fieldset>
        <div class="agent-actions"><button class="btn-primary" :disabled="busy" data-testid="agent-save-address">{{ $tx('Save address') }}</button><el-button :disabled="busy" @click="editing = false">{{ $tx('Cancel') }}</el-button></div>
      </form>
    </el-drawer>
  </section>
</template>
<style scoped>.agent-address-list{display:grid;gap:14px}section>.agent-toolbar{justify-content:flex-end}.address-purpose{margin:0 0 20px;color:var(--el-text-color-secondary);font-size:13px}.required{color:var(--el-color-danger)}.address-default{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-top:14px;padding-top:10px;border-top:1px solid var(--el-border-color-lighter)}.default-saving{font-size:12px;color:var(--el-text-color-secondary)}</style>
