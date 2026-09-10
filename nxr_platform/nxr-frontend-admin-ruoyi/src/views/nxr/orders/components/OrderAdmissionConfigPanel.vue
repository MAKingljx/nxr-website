<template>
  <section v-loading="loading" class="admission-config-panel">
    <el-form :model="form" label-position="top">
      <el-row :gutter="16">
        <el-col :xs="24" :sm="12">
          <el-form-item :label="$tx('Payment deadline (hours)')">
            <el-input-number v-model="form.paymentDeadlineHours" :min="1" :max="720" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item :label="$tx('Maximum cards per order')">
            <el-input-number v-model="form.maxCardsPerOrder" :min="1" :max="10000" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item :label="$tx('Terms version')"><el-input v-model="form.termsVersion" maxlength="64" /></el-form-item>
      <el-form-item :label="$tx('Terms text')"><el-input v-model="form.termsText" type="textarea" :rows="6" maxlength="10000" show-word-limit /></el-form-item>
      <el-form-item :label="$tx('Turnaround statement')"><el-input v-model="form.turnaroundText" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
      <el-button v-hasPermi="['nxr:order:config']" type="primary" :loading="saving" @click="save">{{ $tx('Save admission configuration') }}</el-button>
      <p>{{ $tx('Configuration changes apply only to later approvals. Existing approved orders keep their frozen terms and quote.') }}</p>
    </el-form>
  </section>
</template>

<script setup>
import { getCurrentInstance, onMounted, reactive, ref } from 'vue'
import { getOrderAdmissionConfig, updateOrderAdmissionConfig } from '@/api/nxr/orderAdmission'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const form = reactive({ paymentDeadlineHours: 48, maxCardsPerOrder: 100, termsVersion: '', termsText: '', turnaroundText: '' })

async function load() {
  loading.value = true
  try { Object.assign(form, (await getOrderAdmissionConfig()).data || {}) }
  finally { loading.value = false }
}

async function save() {
  saving.value = true
  try {
    Object.assign(form, (await updateOrderAdmissionConfig({ ...form })).data || {})
    proxy.$modal.msgSuccess('Admission configuration saved')
  } finally { saving.value = false }
}

onMounted(load)
</script>

<style scoped>
.admission-config-panel{max-width:780px}.admission-config-panel :deep(.el-input-number){width:100%}.admission-config-panel p{margin:10px 0 0;color:var(--nxr-text-faint);line-height:1.55}
</style>
