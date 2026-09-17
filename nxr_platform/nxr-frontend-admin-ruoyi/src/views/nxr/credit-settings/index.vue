<template>
  <main class="nxr-workspace credit-settings">
    <nxr-page-header :title="t('title')">
      <template #actions>
        <el-button icon="Refresh" plain :disabled="loading || saving" @click="reload">{{ t('reload') }}</el-button>
      </template>
    </nxr-page-header>

    <el-alert v-if="errorMessage" class="credit-settings__alert" :title="errorMessage" type="error" :closable="false" show-icon />

    <section v-loading="loading" :aria-label="t('title')" :aria-busy="loading">
      <template v-if="savedSettings">
        <el-form label-position="top" :disabled="loading || saving" @submit.prevent="save">
          <div class="credit-settings__base">
            <el-form-item :label="t('baseRate')" :error="validationErrors.pointsPerCny">
              <div class="credit-settings__base-input">
                <span>1 CNY =</span>
                <el-input
                  v-model="form.pointsPerCny"
                  inputmode="decimal"
                  maxlength="32"
                  :aria-label="t('pointsPerCny')"
                />
                <span>{{ t('points') }}</span>
              </div>
            </el-form-item>
            <div><p class="credit-settings__formula">{{ t('formula') }}</p><p class="credit-settings__formula">{{ t('futureOnly') }}</p></div>
          </div>

          <el-table :data="form.rates" row-key="currencyCode" class="credit-settings__rates">
            <el-table-column prop="currencyCode" :label="t('currency')" width="120" />
            <el-table-column :label="t('cnyPerUnit')" min-width="250">
              <template #default="{ row }">
                <el-form-item :error="validationErrors[row.currencyCode]" class="credit-settings__rate-field">
                  <div class="credit-settings__rate-input">
                    <el-input
                      v-model="row.cnyPerUnit"
                      inputmode="decimal"
                      maxlength="32"
                      :disabled="row.currencyCode === 'CNY' || loading || saving"
                      :placeholder="t('notConfigured')"
                      :aria-label="`${row.currencyCode} — ${t('cnyPerUnit')}`"
                    />
                    <span>CNY</span>
                  </div>
                </el-form-item>
              </template>
            </el-table-column>
            <el-table-column :label="t('enabled')" width="150">
              <template #default="{ row }">
                <el-switch
                  v-model="row.enabled"
                  :disabled="row.currencyCode === 'CNY' || loading || saving"
                  :aria-label="`${row.currencyCode} — ${t('enabled')}`"
                />
              </template>
            </el-table-column>
            <el-table-column :label="t('status')" min-width="140">
              <template #default="{ row }">
                <el-tag :type="rateStatus(row) === 'enabled' ? 'success' : 'info'" effect="plain">
                  {{ t(rateStatus(row)) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>

          <div class="credit-settings__footer">
            <div class="credit-settings__state" role="status">
              <el-tag v-if="dirty" type="warning" effect="plain">{{ t('unsaved') }}</el-tag>
              <span v-else-if="savedSettings.updatedAt">{{ t('updatedAt') }}: {{ formatUpdatedAt(savedSettings.updatedAt) }}</span>
            </div>
            <div class="credit-settings__actions">
              <el-button :disabled="!dirty || loading || saving" @click="cancel">{{ t('cancel') }}</el-button>
              <el-button
                v-hasPermi="['nxr:credit:config']"
                type="primary"
                :disabled="!dirty || loading || conflict"
                :loading="saving"
                @click="save"
              >{{ t('save') }}</el-button>
            </div>
          </div>
        </el-form>
      </template>
      <el-empty v-else-if="!loading" :description="t('loadFailed')" />
      <div v-else class="credit-settings__placeholder" />
    </section>
  </main>
</template>

<script setup lang="ts" name="NxrCreditSettings">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import {
  CREDIT_CURRENCIES,
  getCreditApiError,
  getEnterpriseCreditSettings,
  updateEnterpriseCreditSettings,
  type CreditDecimal,
  type CreditRate,
  type EnterpriseCreditSettings
} from '@/api/nxr/enterpriseCredit'

const { t, locale } = useI18n({
  useScope: 'local',
  inheritLocale: true,
  messages: {
    en: {
      title: 'Enterprise Credit Settings', reload: 'Reload', baseRate: 'Base Credit Rate',
      pointsPerCny: 'Points per CNY', points: 'PTS', formula: 'Points = Amount × CNY rate × Points per CNY', futureOnly: 'Changes apply to future quotes. Existing credits and recorded conversions remain unchanged.',
      currency: 'Currency', cnyPerUnit: 'CNY per 1 currency unit', enabled: 'Enabled', disabled: 'Disabled',
      status: 'Status', notConfigured: 'Not configured', unsaved: 'Unsaved changes', updatedAt: 'Updated',
      cancel: 'Cancel', save: 'Save Settings', saved: 'Enterprise credit settings saved',
      loadFailed: 'Unable to load credit settings. Please retry.', saveFailed: 'Unable to save. Your changes have been kept.',
      conflict: 'Settings changed elsewhere. Your changes have been kept. Reload the latest settings before saving.',
      positiveRate: 'Enter a value above 0 and up to 1,000,000,000, with at most 8 decimal places.',
      discard: 'Discard your unsaved changes?', discardTitle: 'Unsaved Changes', discardConfirm: 'Discard Changes',
      keepEditing: 'Keep Editing'
    },
    'zh-CN': {
      title: '企业额度设置', reload: '重新加载', baseRate: '基础积分比例',
      pointsPerCny: '每元人民币对应积分', points: '积分', formula: '积分 = 金额 × 人民币折算率 × 每元人民币对应积分',
      futureOnly: '新比例用于后续报价；已到账积分和已记录的兑换结果保持不变。',
      currency: '币种', cnyPerUnit: '每 1 单位货币折合人民币', enabled: '已启用', disabled: '已停用',
      status: '状态', notConfigured: '未配置', unsaved: '未保存', updatedAt: '更新于',
      cancel: '取消', save: '保存设置', saved: '企业额度设置已保存',
      loadFailed: '无法加载额度设置，请重试。', saveFailed: '保存失败，已保留本次修改。',
      conflict: '设置已被其他人更新，已保留本次修改。请重新加载最新设置后再保存。',
      positiveRate: '请输入大于 0 且不超过 10 亿的数值，最多 8 位小数。',
      discard: '放弃尚未保存的修改吗？', discardTitle: '未保存的修改', discardConfirm: '放弃修改',
      keepEditing: '继续编辑'
    }
  }
})

type RateDraft = Omit<CreditRate, 'cnyPerUnit'> & { cnyPerUnit: string }
type SettingsDraft = { pointsPerCny: string; rates: RateDraft[] }
const form = reactive<SettingsDraft>({ pointsPerCny: '', rates: [] })
const savedSettings = ref<EnterpriseCreditSettings | null>(null)
const savedDraft = ref('')
const loading = ref(false)
const saving = ref(false)
const conflict = ref(false)
const errorMessage = ref('')
const validationErrors = reactive<Record<string, string>>({})
const dirty = computed(() => Boolean(savedSettings.value) && JSON.stringify(form) !== savedDraft.value)

function applySettings(settings: EnterpriseCreditSettings) {
  savedSettings.value = settings
  form.pointsPerCny = rateText(settings.pointsPerCny)
  form.rates = CREDIT_CURRENCIES.map(currencyCode => {
    const saved = settings.rates.find(rate => rate.currencyCode === currencyCode)
    return {
      currencyCode,
      cnyPerUnit: currencyCode === 'CNY' ? '1' : rateText(saved?.cnyPerUnit),
      enabled: currencyCode === 'CNY' || saved?.enabled === true
    }
  })
  savedDraft.value = JSON.stringify(form)
  conflict.value = false
  errorMessage.value = ''
  clearValidation()
}

function clearValidation() {
  Object.keys(validationErrors).forEach(key => delete validationErrors[key])
}

function rateText(value: CreditDecimal | null | undefined): string {
  if (value == null) return ''
  const text = String(value)
  // BigDecimal may serialize a small valid rate as 1E-8; expand without Number rounding.
  const scientific = text.match(/^(\d+)(?:\.(\d*))?[eE]([+-]?\d+)$/)
  if (!scientific) return text
  const exponent = Number(scientific[3])
  if (!Number.isInteger(exponent) || Math.abs(exponent) > 24) return text
  const digits = scientific[1] + (scientific[2] || '')
  const decimalIndex = scientific[1].length + exponent
  if (decimalIndex <= 0) return `0.${'0'.repeat(-decimalIndex)}${digits}`
  if (decimalIndex >= digits.length) return digits + '0'.repeat(decimalIndex - digits.length)
  return `${digits.slice(0, decimalIndex)}.${digits.slice(decimalIndex)}`
}

function validRate(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const text = value.trim()
  if (!/^(?:\d+(?:\.\d{0,8})?|\.\d{1,8})$/.test(text)) return false
  const [whole = '0', fraction = ''] = text.split('.')
  const scaled = BigInt(whole || '0') * 100000000n + BigInt(fraction.padEnd(8, '0'))
  return scaled > 0n && scaled <= 100000000000000000n
}

function validate() {
  clearValidation()
  if (!validRate(form.pointsPerCny)) validationErrors.pointsPerCny = t('positiveRate')
  for (const rate of form.rates) {
    if ((rate.enabled || rate.cnyPerUnit.trim() !== '') && !validRate(rate.cnyPerUnit)) {
      validationErrors[rate.currencyCode] = t('positiveRate')
    }
  }
  return Object.keys(validationErrors).length === 0
}

function rateStatus(rate: RateDraft) {
  if (!validRate(rate.cnyPerUnit)) return 'notConfigured'
  return rate.enabled ? 'enabled' : 'disabled'
}

async function load() {
  loading.value = true
  try {
    applySettings(await getEnterpriseCreditSettings())
  } catch {
    errorMessage.value = t('loadFailed')
  } finally {
    loading.value = false
  }
}

async function confirmDiscard() {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm(t('discard'), t('discardTitle'), {
      confirmButtonText: t('discardConfirm'), cancelButtonText: t('keepEditing'), type: 'warning'
    })
    return true
  } catch {
    return false
  }
}

async function reload() {
  if (loading.value || saving.value || !await confirmDiscard()) return
  await load()
}

function cancel() {
  if (!savedSettings.value || loading.value || saving.value) return
  const hadConflict = conflict.value
  applySettings(savedSettings.value)
  if (hadConflict) {
    conflict.value = true
    errorMessage.value = t('conflict')
  }
}

async function save() {
  if (!savedSettings.value || saving.value || loading.value || conflict.value || !dirty.value || !validate()) return
  saving.value = true
  errorMessage.value = ''
  try {
    const settings = await updateEnterpriseCreditSettings({
      expectedVersion: savedSettings.value.version,
      pointsPerCny: form.pointsPerCny.trim(),
      rates: form.rates.map(rate => ({ ...rate, cnyPerUnit: rate.cnyPerUnit.trim() || null }))
    })
    applySettings(settings)
    ElMessage.success(t('saved'))
  } catch (error) {
    conflict.value = getCreditApiError(error).status === 409
    errorMessage.value = t(conflict.value ? 'conflict' : 'saveFailed')
  } finally {
    saving.value = false
  }
}

function formatUpdatedAt(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(locale.value)
}

function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value && !saving.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(async () => {
  if (saving.value || !await confirmDiscard()) return false
  // This page is cached by the admin shell, so discard the retained draft too.
  if (dirty.value) cancel()
  return true
})
onMounted(() => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  load()
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnBeforeUnload))
</script>

<style scoped>
.credit-settings__alert { margin-bottom: 18px; }
.credit-settings__base { display: flex; align-items: center; flex-wrap: wrap; gap: 16px 40px; margin-bottom: 16px; }
.credit-settings__base-input, .credit-settings__rate-input { display: flex; align-items: center; gap: 12px; }
.credit-settings__base-input { color: var(--nxr-text-strong); }
.credit-settings__base-input .el-input { width: 180px; }
.credit-settings__formula { color: var(--nxr-text-muted); font-size: 13px; margin: 0 0 8px; }
.credit-settings__rates { width: 100%; }
.credit-settings__rate-field { margin: 8px 0 14px; }
.credit-settings__rate-input .el-input { width: 200px; }
.credit-settings__footer { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 16px; margin-top: 24px; }
.credit-settings__state { color: var(--nxr-text-muted); font-size: 13px; }
.credit-settings__actions { display: flex; gap: 12px; }
.credit-settings__actions .el-button + .el-button { margin-left: 0; }
.credit-settings__placeholder { min-height: 320px; }
@media (max-width: 720px) {
  .credit-settings__base { display: block; }
  .credit-settings__footer { align-items: flex-start; }
  .credit-settings__actions { margin-left: auto; }
}
</style>
