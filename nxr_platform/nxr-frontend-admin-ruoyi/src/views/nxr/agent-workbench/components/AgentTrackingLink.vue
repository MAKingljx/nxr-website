<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { tx } from '@/i18n'
import { copyText } from '@/utils/copyText'
import { privateTrackingUrl, useAgentApi } from '../lib/agentWorkbench'

const props = defineProps<{ orderId: number }>()
const api = useAgentApi()
const state = ref<{ active: boolean; version: number | null; legacyActive: boolean } | null>(null)
const link = ref(''), loading = ref(false), busy = ref(false), error = ref(''), message = ref('')
let generation = 0
async function load() {
  const current = ++generation, id = props.orderId
  loading.value = true; error.value = ''; state.value = null
  try { const value = await api.fetchOrderTrackingState(id); if (current === generation) state.value = value }
  catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : tx('Unable to load customer link status.') }
  finally { if (current === generation) loading.value = false }
}
async function act(action: 'create' | 'replace' | 'revoke' | 'legacy') {
  if (!state.value || busy.value) return
  const current = generation, id = props.orderId, snapshot = { ...state.value }
  if ((action === 'create' && snapshot.active) || (['replace', 'revoke'].includes(action) && !snapshot.active) || (action === 'legacy' && !snapshot.legacyActive)) return
  if (['replace', 'revoke'].includes(action) && (typeof snapshot.version !== 'number' || !Number.isSafeInteger(snapshot.version))) { error.value = tx('Unable to load customer link status.'); return }
  busy.value = true; error.value = ''; message.value = ''
  try {
    const prompts = { replace: 'Replace this link? The current order link will stop working.', revoke: 'Revoke this order link?', legacy: 'Revoke this order’s older Submit link?' }
    if (action !== 'create') { try { await ElMessageBox.confirm(tx(prompts[action]), tx('Confirm'), { type: 'warning' }) } catch { return } }
    if (current !== generation || id !== props.orderId) return
    if (action === 'create' || action === 'replace') {
      const value = action === 'create' ? await api.createOrderTrackingLink(id) : await api.replaceOrderTrackingLink(id, snapshot.version!)
      if (current !== generation) return
      link.value = privateTrackingUrl(value.trackingUrl)
      message.value = tx('Copy this link now. It cannot be shown again after closing this view.')
    } else if (action === 'revoke') {
      await api.revokeOrderTrackingLink(id, snapshot.version!)
      if (current !== generation) return
      link.value = ''; message.value = tx('Order link revoked.')
    } else {
      await api.revokeLegacyOrderTrackingLink(id)
      if (current !== generation) return
      message.value = tx('Older Submit link revoked.')
    }
    await load()
  } catch (e) {
    if (current !== generation) return
    link.value = ''
    const text = e instanceof Error ? e.message : tx('Unable to update the customer link.')
    await load(); error.value = text
  } finally { busy.value = false }
}
async function copy() {
  if (await copyText(link.value)) { error.value = ''; message.value = tx('Customer tracking link copied.') }
  else error.value = tx('The browser did not allow copying. Select the link and copy it manually.')
}
watch(() => props.orderId, () => { link.value = ''; message.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="tracking-link" :data-testid="`order-tracking-${orderId}`">
    <p v-if="loading" role="status">{{ $tx('Loading link status…') }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="message" :title="message" type="success" :closable="false" />
    <template v-if="state">
      <div class="tracking-actions">
        <el-button v-if="!state.active" link type="primary" :disabled="busy" :data-testid="`tracking-create-${orderId}`" @click="act('create')">{{ $tx('Create link') }}</el-button>
        <el-button v-else link type="primary" :disabled="busy" :data-testid="`tracking-replace-${orderId}`" @click="act('replace')">{{ $tx('Replace link') }}</el-button>
        <el-button v-if="state.active" link type="danger" :disabled="busy" :data-testid="`tracking-revoke-${orderId}`" @click="act('revoke')">{{ $tx('Revoke') }}</el-button>
      </div>
      <p v-if="state.active && !link" class="tracking-note">{{ $tx('An order link is active. Replace it if you need a new copy.') }}</p>
      <div v-if="state.legacyActive" class="tracking-note">{{ $tx('An older Submit link is still active.') }} <el-button link type="danger" :disabled="busy" :data-testid="`tracking-revoke-legacy-${orderId}`" @click="act('legacy')">{{ $tx('Revoke older link') }}</el-button></div>
    </template>
    <template v-if="link"><el-input :model-value="link" readonly :aria-label="$tx('Private customer tracking link')" @focus="($event.target as HTMLInputElement).select()" /><el-button link :disabled="busy" :data-testid="`tracking-copy-${orderId}`" @click="copy">{{ $tx('Copy') }}</el-button></template>
    <el-button v-else-if="error" link type="primary" :disabled="busy" @click="load">{{ $tx('Retry') }}</el-button>
  </div>
</template>
<style scoped>.tracking-link{min-width:0}.tracking-actions{display:flex;gap:8px;flex-wrap:wrap}.tracking-actions .el-button{margin-left:0}.tracking-note{font-size:12px;color:var(--el-text-color-secondary);margin:5px 0}</style>
