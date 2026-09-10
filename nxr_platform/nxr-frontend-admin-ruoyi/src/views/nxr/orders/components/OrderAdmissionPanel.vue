<template>
  <section v-loading="loading" class="admission-panel">
    <div class="panel-heading">
      <div>
        <div class="heading-line">
          <strong>{{ $tx('Application Review') }}</strong>
          <el-tag v-if="admission" :type="statusType(admission.admissionStatus)">
            {{ statusLabel(admission.admissionStatus) }}
          </el-tag>
          <el-tag v-if="admission?.legacyOrder" type="info">{{ $tx('Legacy order') }}</el-tag>
        </div>
        <p>{{ $tx('Approve the submitted card list and frozen quote before payment opens.') }}</p>
      </div>
      <el-button icon="Refresh" plain :loading="loading" @click="load">{{ $tx('Refresh') }}</el-button>
    </div>

    <template v-if="admission">
      <el-alert
        v-if="admission.paymentExpired"
        type="warning"
        :closable="false"
        show-icon
        :title="$tx('The payment deadline has expired. A new approval is required before the customer can confirm terms again.')"
        class="mb12"
      />
      <el-alert
        v-else-if="admission.admissionStatus === 'needs_information'"
        type="warning"
        :closable="false"
        show-icon
        :title="$tx('Waiting for the customer to submit the requested information.')"
        class="mb12"
      />

      <el-descriptions :column="3" border size="small">
        <el-descriptions-item :label="$tx('Review revision')">{{ admission.admissionRevision }}</el-descriptions-item>
        <el-descriptions-item :label="$tx('Submitted At')">{{ formatTime(admission.submittedAt) }}</el-descriptions-item>
        <el-descriptions-item :label="$tx('Reviewed At')">{{ formatTime(admission.decidedAt) }}</el-descriptions-item>
        <el-descriptions-item :label="$tx('Order quote')">
          {{ admission.quoteCurrency || currencyCode || '-' }} {{ money(admission.quoteAmount ?? orderAmount) }}
        </el-descriptions-item>
        <el-descriptions-item :label="$tx('Payment deadline')">
          {{ formatTime(admission.paymentDueAtIso || admission.paymentDueAt) }}
        </el-descriptions-item>
        <el-descriptions-item :label="$tx('Customer confirmation')">
          <el-tag :type="admission.termsAcceptedAt ? 'success' : 'info'">
            {{ admission.termsAcceptedAt ? $tx('Confirmed') : $tx('Not confirmed') }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="$tx('Terms version')">{{ admission.termsVersion || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$tx('Accepted version')">{{ admission.acceptedTermsVersion || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$tx('Confirmed At')">{{ formatTime(admission.termsAcceptedAt) }}</el-descriptions-item>
        <el-descriptions-item :label="$tx('Review note')" :span="3">{{ admission.decisionNote || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$tx('Turnaround statement')" :span="3">{{ admission.turnaroundText || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div v-if="admission.termsText" class="terms-block">
        <strong>{{ $tx('Approved terms snapshot') }}</strong>
        <p>{{ admission.termsText }}</p>
      </div>

      <div v-if="admission.supplementalPhotoIds?.length" class="supplemental-block">
        <strong>{{ $tx('Supplemental photos') }}</strong>
        <div class="photo-grid">
          <order-application-photo
            v-for="(photoId, index) in admission.supplementalPhotoIds"
            :key="photoId"
            :photo-id="photoId"
            :label="`${$tx('Supplemental')} ${index + 1}`"
          />
        </div>
      </div>

      <el-form
        v-if="canDecide"
        v-hasPermi="['nxr:order:admission','nxr:order:manage']"
        label-position="top"
        class="decision-form"
        @submit.prevent
      >
        <el-form-item :label="$tx('Review note')" required>
          <el-input
            v-model="decisionNote"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            :placeholder="$tx('Record the reason or information the customer must provide')"
          />
        </el-form-item>
        <div class="decision-actions">
          <el-button
            v-if="admission.admissionStatus === 'pending_review'"
            type="warning"
            :disabled="!!savingDecision"
            :loading="savingDecision === 'request_information'"
            @click="submitDecision('request_information')"
          >{{ $tx('Request information') }}</el-button>
          <el-button
            v-if="admission.admissionStatus === 'pending_review'"
            type="danger"
            :disabled="!!savingDecision"
            :loading="savingDecision === 'reject'"
            @click="submitDecision('reject')"
          >{{ $tx('Reject application') }}</el-button>
          <el-button
            type="success"
            :disabled="!!savingDecision"
            :loading="savingDecision === 'approve'"
            @click="submitDecision('approve')"
          >{{ admission.paymentExpired ? $tx('Renew payment deadline') : $tx('Approve for payment') }}</el-button>
        </div>
      </el-form>

      <div class="history-block">
        <strong>{{ $tx('Admission history') }}</strong>
        <el-timeline v-if="admission.events?.length">
          <el-timeline-item
            v-for="event in admission.events"
            :key="event.id"
            :timestamp="formatTime(event.createdAt)"
            placement="top"
          >
            <div class="history-title">{{ event.title || statusLabel(event.eventCode) }}</div>
            <p v-if="event.detail">{{ event.detail }}</p>
            <small>{{ actorLabel(event.actorType) }}</small>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else :description="$tx('No admission history')" :image-size="52" />
      </div>
    </template>

    <el-collapse v-if="showConfig" v-hasPermi="['nxr:order:admission','nxr:order:config']" class="config-collapse" @change="loadConfigOnce">
      <el-collapse-item name="config" :title="$tx('Global admission configuration')">
        <el-form v-loading="configLoading" :model="configForm" label-position="top">
          <el-row :gutter="16">
            <el-col :xs="24" :sm="12">
              <el-form-item :label="$tx('Payment deadline (hours)')">
                <el-input-number v-model="configForm.paymentDeadlineHours" :min="1" :max="720" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12">
              <el-form-item :label="$tx('Maximum cards per order')">
                <el-input-number v-model="configForm.maxCardsPerOrder" :min="1" :max="10000" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item :label="$tx('Terms version')"><el-input v-model="configForm.termsVersion" maxlength="64" /></el-form-item>
          <el-form-item :label="$tx('Terms text')"><el-input v-model="configForm.termsText" type="textarea" :rows="5" maxlength="10000" show-word-limit /></el-form-item>
          <el-form-item :label="$tx('Turnaround statement')"><el-input v-model="configForm.turnaroundText" type="textarea" :rows="2" maxlength="1000" show-word-limit /></el-form-item>
          <el-button
            v-hasPermi="['nxr:order:config']"
            type="primary"
            :loading="savingConfig"
            @click="saveConfig"
          >{{ $tx('Save admission configuration') }}</el-button>
          <p class="config-hint">{{ $tx('Configuration changes apply only to later approvals. Existing approved orders keep their frozen terms and quote.') }}</p>
        </el-form>
      </el-collapse-item>
    </el-collapse>
  </section>
</template>

<script setup>
import { computed, getCurrentInstance, reactive, ref, watch } from 'vue'
import { decideOrderAdmission, getOrderAdmission, getOrderAdmissionConfig, updateOrderAdmissionConfig } from '@/api/nxr/orderAdmission'
import OrderApplicationPhoto from './OrderApplicationPhoto.vue'

const props = defineProps({
  orderId: { type: Number, required: true },
  orderAmount: { type: [Number, String], default: null },
  currencyCode: { type: String, default: '' },
  showConfig: { type: Boolean, default: true }
})
const emit = defineEmits(['changed'])
const { proxy } = getCurrentInstance()
const loading = ref(false)
const admission = ref(null)
const decisionNote = ref('')
const savingDecision = ref('')
const configLoading = ref(false)
const configLoaded = ref(false)
const savingConfig = ref(false)
const configForm = reactive({ paymentDeadlineHours: 48, maxCardsPerOrder: 500, termsVersion: '', termsText: '', turnaroundText: '' })

const canDecide = computed(() => admission.value?.admissionStatus === 'pending_review' || admission.value?.paymentExpired)

function statusLabel(value) {
  return {
    pending_review: proxy.$tx('Pending review'),
    approved: proxy.$tx('Approved'),
    rejected: proxy.$tx('Rejected'),
    needs_information: proxy.$tx('Needs information'),
    submitted: proxy.$tx('Submitted'),
    resubmitted: proxy.$tx('Resubmitted'),
    information_requested: proxy.$tx('Information requested'),
    terms_accepted: proxy.$tx('Terms confirmed')
  }[value] || value || proxy.$tx('Not available')
}

function statusType(value) {
  if (value === 'approved') return 'success'
  if (value === 'rejected') return 'danger'
  if (value === 'pending_review' || value === 'needs_information') return 'warning'
  return 'info'
}

function actorLabel(value) {
  return value === 'customer' ? proxy.$tx('Customer') : value === 'admin' ? proxy.$tx('Staff') : value || '-'
}

function formatTime(value) {
  return value ? proxy.parseTime(value) : '-'
}

function money(value) {
  return value == null ? '-' : Number(value).toFixed(2)
}

async function load() {
  if (!props.orderId) return
  loading.value = true
  try {
    admission.value = (await getOrderAdmission(props.orderId)).data
    decisionNote.value = ''
  } finally {
    loading.value = false
  }
}

async function submitDecision(decision) {
  if (!decisionNote.value.trim()) {
    proxy.$modal.msgWarning(proxy.$tx('Enter a review note'))
    return
  }
  const labels = {
    approve: proxy.$tx('Approve this order for customer confirmation and payment?'),
    reject: proxy.$tx('Reject this order application?'),
    request_information: proxy.$tx('Request additional information from the customer?')
  }
  try {
    await proxy.$modal.confirm(labels[decision])
  } catch {
    return
  }
  savingDecision.value = decision
  try {
    admission.value = (await decideOrderAdmission(props.orderId, {
      decision,
      note: decisionNote.value.trim(),
      expectedRevision: admission.value.admissionRevision
    })).data
    decisionNote.value = ''
    proxy.$modal.msgSuccess(proxy.$tx('Admission decision saved'))
    emit('changed', admission.value)
  } finally {
    savingDecision.value = ''
  }
}

async function loadConfig() {
  configLoading.value = true
  try {
    const value = (await getOrderAdmissionConfig()).data
    Object.assign(configForm, {
      paymentDeadlineHours: value.paymentDeadlineHours,
      maxCardsPerOrder: value.maxCardsPerOrder,
      termsVersion: value.termsVersion || '',
      termsText: value.termsText || '',
      turnaroundText: value.turnaroundText || ''
    })
    configLoaded.value = true
  } finally {
    configLoading.value = false
  }
}

function loadConfigOnce(names) {
  if ((Array.isArray(names) ? names : [names]).includes('config') && !configLoaded.value) loadConfig()
}

async function saveConfig() {
  if (!configForm.termsVersion.trim() || !configForm.termsText.trim() || !configForm.turnaroundText.trim()) {
    proxy.$modal.msgWarning(proxy.$tx('Complete the terms version, terms text and turnaround statement'))
    return
  }
  savingConfig.value = true
  try {
    await updateOrderAdmissionConfig({ ...configForm })
    proxy.$modal.msgSuccess(proxy.$tx('Admission configuration saved'))
    await loadConfig()
  } finally {
    savingConfig.value = false
  }
}

watch(() => props.orderId, load, { immediate: true })
</script>

<style scoped>
.admission-panel{display:grid;gap:14px}.panel-heading,.heading-line,.decision-actions{display:flex;align-items:center;gap:10px}.panel-heading{justify-content:space-between}.panel-heading p,.config-hint,.history-block p{margin:4px 0 0;color:var(--nxr-text-faint);line-height:1.55}.terms-block,.supplemental-block,.history-block,.decision-form{padding:14px;border:1px solid var(--nxr-border-subtle);border-radius:8px}.terms-block p{white-space:pre-wrap;line-height:1.65;margin:8px 0 0}.photo-grid{display:flex;flex-wrap:wrap;gap:12px;margin-top:10px}.decision-actions{justify-content:flex-end;flex-wrap:wrap}.history-title{font-weight:600}.history-block small{color:var(--nxr-text-faint)}.config-collapse :deep(.el-input-number){width:100%}.config-hint{display:inline-block;margin-left:12px}@media(max-width:760px){.panel-heading{align-items:stretch;flex-direction:column}.decision-actions>*{width:100%;margin-left:0!important}.config-hint{display:block;margin:8px 0 0}}
</style>
