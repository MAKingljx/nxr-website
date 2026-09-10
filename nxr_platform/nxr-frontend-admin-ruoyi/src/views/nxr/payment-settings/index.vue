<template>
  <main class="nxr-workspace payment-settings">
    <nxr-page-header
      kicker="FINANCE"
      title="支付渠道"
      summary="选择支付渠道并配置商户信息。启用且配置完整的渠道会显示在客户结算页。"
    >
      <template #actions>
        <el-button icon="Refresh" plain :disabled="loading || hasUnsavedChanges || channels.some(channel => channel.saving)" @click="load">刷新</el-button>
      </template>
    </nxr-page-header>

    <section v-loading="loading" class="payment-settings__layout">
      <nav class="payment-settings__nav" aria-label="支付渠道选择">
        <div class="payment-settings__nav-heading">选择渠道</div>
        <button
          v-for="channel in channels"
          :key="channel.provider"
          type="button"
          class="payment-settings__nav-item"
          :class="{ 'is-active': selectedProvider === channel.provider }"
          :aria-current="selectedProvider === channel.provider ? 'true' : undefined"
          aria-controls="payment-channel-config"
          @click="selectedProvider = channel.provider"
        >
          <span class="payment-settings__nav-title">{{ providerLabels[channel.provider] || channel.displayName }}</span>
          <span class="payment-settings__nav-description">{{ providerDescriptions[channel.provider] }}</span>
          <span class="payment-settings__nav-status">
            <span class="payment-settings__status-dot" :class="{ 'is-ready': channel.enabled && channel.ready }" />
            {{ channel.enabled ? '已启用' : '已停用' }} · {{ channel.ready ? '配置完整' : '待配置' }}
          </span>
          <span v-if="isDirty(channel)" class="payment-settings__draft">未保存</span>
        </button>
        <p class="payment-settings__nav-hint">切换渠道会保留未保存的内容。</p>
      </nav>

      <div id="payment-channel-config" class="payment-settings__content">
        <el-card v-for="channel in activeChannels" :key="channel.provider" shadow="never" class="payment-channel-card">
          <template #header>
            <div class="payment-channel-card__header">
              <div>
                <h2>{{ providerLabels[channel.provider] || channel.displayName }}</h2>
                <p>{{ providerDescriptions[channel.provider] }}</p>
              </div>
              <div class="payment-channel-card__status">
                <el-tag :type="channel.ready ? 'success' : 'info'">{{ channel.ready ? '配置完整' : '待配置' }}</el-tag>
                <el-switch v-model="channel.form.enabled" :disabled="channel.saving" active-text="启用" inactive-text="停用" />
              </div>
            </div>
          </template>

          <el-form :model="channel.form" :disabled="channel.saving" label-position="top">
            <section class="payment-settings__section" aria-label="基本设置">
              <h3>基本设置</h3>
              <div class="payment-settings__row">
                <el-form-item label="显示名称">
                  <el-input v-model="channel.form.displayName" maxlength="80" />
                </el-form-item>
                <el-form-item label="运行模式">
                  <el-select v-model="channel.form.mode" :disabled="channel.provider === 'wechat_pay_native'" @change="handleModeChange(channel)">
                    <el-option v-for="mode in modesFor(channel.provider)" :key="mode" :label="modeLabel(mode)" :value="mode" />
                  </el-select>
                </el-form-item>
              </div>
              <el-form-item label="支持币种">
                <el-select v-model="channel.form.supportedCurrencies" multiple :disabled="channel.provider !== 'paypal'">
                  <el-option v-for="currency in currenciesFor(channel.provider)" :key="currency" :label="currency" :value="currency" />
                </el-select>
              </el-form-item>
            </section>

            <section class="payment-settings__section" aria-label="商户凭据">
              <h3>商户凭据</h3>
              <p class="payment-settings__section-hint">已保存的凭据不会回显原文，留空即可保留当前值。</p>
              <div class="payment-settings__credentials">
                <el-form-item
                  v-for="field in credentialFields(channel.provider)"
                  :key="field.key"
                  :label="field.label"
                  class="credential-field"
                  :class="{ 'credential-field--wide': field.multiline }"
                >
                  <el-input
                    v-model="channel.form.credentials[field.key]"
                    :type="field.multiline ? 'textarea' : 'password'"
                    :rows="field.multiline ? 3 : undefined"
                    :show-password="!field.multiline"
                    autocomplete="new-password"
                    placeholder="留空表示保持当前值"
                  />
                  <div class="credential-field__state">
                    <el-tag size="small" :type="channel.credentialConfigured[field.key] ? 'success' : 'info'">
                      {{ channel.credentialConfigured[field.key] ? '已保存' : '未配置' }}
                    </el-tag>
                    <span v-if="channel.maskedCredentials[field.key]">{{ channel.maskedCredentials[field.key] }}</span>
                  </div>
                </el-form-item>
              </div>
            </section>

            <section class="payment-settings__section" aria-label="接口与回调">
              <h3>接口与回调</h3>
              <el-form-item label="API 地址（仅允许官方地址）">
                <el-input v-model="channel.form.apiBaseUrl" />
              </el-form-item>
              <el-form-item label="支付通知 URL">
                <el-input v-model="channel.form.notifyUrl" placeholder="https://your-domain.example/api/payments/webhooks/provider" />
              </el-form-item>
              <el-form-item v-if="channel.provider === 'paypal'" label="付款完成返回 URL">
                <el-input v-model="channel.form.returnUrl" placeholder="https://your-domain.example/account/orders" />
              </el-form-item>
            </section>

            <div class="payment-settings__footer">
              <span class="payment-settings__save-hint">{{ isDirty(channel) ? '当前渠道有未保存的修改' : '修改后仅保存当前渠道' }}</span>
              <div class="payment-settings__actions">
                <el-button :disabled="!isDirty(channel)" @click="reset(channel)">还原修改</el-button>
                <el-button
                  type="primary"
                  :loading="channel.saving"
                  v-hasPermi="['nxr:payment:config']"
                  @click="save(channel)"
                >保存配置</el-button>
              </div>
            </div>
          </el-form>
        </el-card>
        <el-empty v-if="!loading && !channels.length" description="暂无支付渠道配置" />
      </div>
    </section>
  </main>
</template>

<script setup name="NxrPaymentSettings">
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import { fetchPaymentSettings, updatePaymentSetting } from '@/api/nxr/paymentSettings'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const channels = ref([])
const selectedProvider = ref('wechat_pay_native')
const activeChannels = computed(() => channels.value.filter((channel) => channel.provider === selectedProvider.value))
const hasUnsavedChanges = computed(() => channels.value.some(isDirty))
const providerLabels = {
  wechat_pay_native: '微信支付',
  alipay: '支付宝',
  paypal: 'PayPal'
}
const providerDescriptions = {
  wechat_pay_native: 'Native 扫码支付 · CNY',
  alipay: '当面付 · CNY',
  paypal: '海外支付 · 多币种'
}

const paypalCurrencies = ['AUD', 'BRL', 'CAD', 'CNY', 'CZK', 'DKK', 'EUR', 'HKD', 'HUF', 'ILS', 'JPY', 'MYR', 'MXN', 'TWD', 'NZD', 'NOK', 'PHP', 'PLN', 'GBP', 'SGD', 'SEK', 'CHF', 'THB', 'USD']
const fields = {
  wechat_pay_native: [
    { key: 'appId', label: 'AppID' },
    { key: 'merchantId', label: '商户号' },
    { key: 'merchantSerialNo', label: '商户 API 证书序列号' },
    { key: 'merchantPrivateKey', label: '商户 PKCS#8 私钥', multiline: true },
    { key: 'apiV3Key', label: 'API v3 密钥' },
    { key: 'platformSerialNo', label: '微信支付平台证书序列号 / 公钥 ID' },
    { key: 'platformPublicKey', label: '微信支付平台公钥 / 平台证书', multiline: true }
  ],
  alipay: [
    { key: 'appId', label: '应用 AppID' },
    { key: 'sellerId', label: '收款账号 PID / seller_id' },
    { key: 'merchantPrivateKey', label: '应用 PKCS#8 私钥', multiline: true },
    { key: 'alipayPublicKey', label: '支付宝公钥', multiline: true }
  ],
  paypal: [
    { key: 'clientId', label: 'Client ID' },
    { key: 'clientSecret', label: 'Client Secret' },
    { key: 'webhookId', label: 'Webhook ID' }
  ]
}

const officialApiBaseUrls = {
  wechat_pay_native: { live: 'https://api.mch.weixin.qq.com' },
  alipay: { sandbox: 'https://openapi.alipaydev.com/gateway.do', live: 'https://openapi.alipay.com/gateway.do' },
  paypal: { sandbox: 'https://api-m.sandbox.paypal.com', live: 'https://api-m.paypal.com' }
}

function toEditable(channel) {
  const form = {
    displayName: channel.displayName,
    mode: channel.mode,
    enabled: channel.enabled,
    supportedCurrencies: [...channel.supportedCurrencies],
    apiBaseUrl: channel.apiBaseUrl,
    notifyUrl: channel.notifyUrl || '',
    returnUrl: channel.returnUrl || '',
    credentials: Object.fromEntries(credentialFields(channel.provider).map((field) => [field.key, '']))
  }
  return { ...channel, saving: false, form, initialForm: JSON.stringify(form) }
}

function isDirty(channel) {
  return JSON.stringify(channel.form) !== channel.initialForm
}

function reset(channel) {
  channel.form = JSON.parse(channel.initialForm)
}

function load() {
  loading.value = true
  fetchPaymentSettings()
    .then((response) => {
      channels.value = (response.data || []).map(toEditable)
      if (!channels.value.some((channel) => channel.provider === selectedProvider.value)) {
        selectedProvider.value = channels.value[0]?.provider || ''
      }
    })
    .finally(() => {
      loading.value = false
    })
}

function save(channel) {
  channel.saving = true
  const credentials = Object.fromEntries(
    Object.entries(channel.form.credentials).filter(([, value]) => value && value.trim())
  )
  updatePaymentSetting(channel.provider, { ...channel.form, credentials })
    .then((response) => {
      const updated = toEditable(response.data)
      channels.value = channels.value.map((item) => (item.provider === channel.provider ? updated : item))
      proxy.$modal.msgSuccess('支付渠道设置已保存')
    })
    .finally(() => {
      channel.saving = false
    })
}

function modesFor(provider) {
  return provider === 'wechat_pay_native' ? ['live'] : ['sandbox', 'live']
}

function modeLabel(mode) {
  return mode === 'sandbox' ? '沙箱' : '正式'
}

function handleModeChange(channel) {
  channel.form.apiBaseUrl = officialApiBaseUrls[channel.provider]?.[channel.form.mode] || channel.form.apiBaseUrl
}

function currenciesFor(provider) {
  return provider === 'paypal' ? paypalCurrencies : ['CNY']
}

function credentialFields(provider) {
  return fields[provider] || []
}

load()
</script>

<style scoped>
.payment-settings__layout {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  align-items: start;
  gap: 20px;
}

.payment-settings__nav {
  position: sticky;
  top: 20px;
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.payment-settings__nav-heading {
  margin: 0 4px 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
}

.payment-settings__nav-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  margin-bottom: 8px;
  padding: 14px 12px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.payment-settings__nav-item:hover {
  background: var(--el-fill-color-light);
}

.payment-settings__nav-item.is-active {
  border-color: var(--nxr-accent);
  background: var(--nxr-accent-soft);
}

.payment-settings__nav-item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.payment-settings__nav-title {
  font-size: 15px;
  font-weight: 600;
}

.is-active .payment-settings__nav-title {
  color: var(--el-color-primary);
}

.payment-settings__nav-description,
.payment-settings__nav-status,
.payment-settings__nav-hint,
.payment-settings__section-hint,
.payment-settings__save-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.payment-settings__nav-status {
  display: flex;
  align-items: center;
  gap: 5px;
}

.payment-settings__status-dot {
  flex: 0 0 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
}

.payment-settings__status-dot.is-ready {
  background: var(--el-color-success);
}

.payment-settings__draft {
  color: var(--el-color-warning);
  font-size: 12px;
}

.payment-settings__nav-hint {
  margin: 16px 4px 0;
}

.payment-settings__content,
.payment-channel-card {
  min-width: 0;
}

.payment-channel-card__header,
.payment-channel-card__status,
.payment-settings__footer {
  display: flex;
  align-items: center;
  gap: 14px;
}

.payment-channel-card__header,
.payment-settings__footer {
  justify-content: space-between;
  flex-wrap: wrap;
}

.payment-channel-card__header h2 {
  margin: 0;
  font-size: 18px;
}

.payment-channel-card__header p {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.payment-settings__section + .payment-settings__section {
  margin-top: 4px;
  padding-top: 24px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.payment-settings__section h3 {
  margin: 0 0 18px;
  font-size: 14px;
  font-weight: 600;
}

.payment-settings__section-hint {
  margin: -8px 0 20px;
}

.payment-settings__row,
.payment-settings__credentials {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 20px;
}

.credential-field--wide {
  grid-column: 1 / -1;
}

.credential-field__state {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  overflow-wrap: anywhere;
}

.payment-settings__footer {
  padding-top: 20px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.payment-settings__actions {
  display: flex;
  align-items: center;
}

@media (max-width: 1100px) {
  .payment-settings__layout {
    grid-template-columns: 190px minmax(0, 1fr);
    gap: 16px;
  }

  .payment-settings__nav {
    padding: 10px;
  }

  .payment-settings__row,
  .payment-settings__credentials {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 760px) {
  .payment-settings__layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .payment-settings__nav {
    position: static;
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 6px;
  }

  .payment-settings__nav-heading,
  .payment-settings__nav-hint {
    grid-column: 1 / -1;
    margin: 4px;
  }

  .payment-settings__nav-item {
    margin: 0;
    padding: 12px 8px;
  }

  .payment-settings__nav-description {
    display: none;
  }

  .payment-settings__nav-status {
    flex-wrap: wrap;
  }
}
</style>
