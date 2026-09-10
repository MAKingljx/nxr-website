<template>
  <section class="commerce-policy-panel">
    <div class="commerce-policy-panel__header">
      <div>
        <h3>报价与作业范围</h3>
        <p>配置分层单价、按重量返程运费、订单业务线与作业中心。未配置新策略时沿用现有全局价格。</p>
      </div>
      <el-button icon="Refresh" plain :loading="loading" @click="loadAll">刷新</el-button>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <el-tabs v-model="activeTab" v-loading="loading">
      <el-tab-pane label="报价预览" name="quote">
        <el-form :model="quoteForm" inline label-position="top" class="policy-form-row">
          <el-form-item label="客户 ID"><el-input-number v-model="quoteForm.customerId" :min="1" :controls="false" /></el-form-item>
          <el-form-item label="目的地国家/地区"><el-input v-model="quoteForm.country" maxlength="128" placeholder="例如 US" /></el-form-item>
          <el-form-item label="币种">
            <el-select v-model="quoteForm.currency" style="width: 120px"><el-option v-for="item in currencies" :key="item" :value="item" /></el-select>
          </el-form-item>
          <el-form-item label="卡片数"><el-input-number v-model="quoteForm.count" :min="1" :max="10000" /></el-form-item>
          <el-form-item label="旧运费选项（仅回退时）"><el-input v-model="quoteForm.shippingOptionCode" maxlength="32" clearable /></el-form-item>
          <el-form-item label=" "><el-button type="primary" :loading="quoteLoading" @click="previewQuote">计算</el-button></el-form-item>
        </el-form>
        <el-descriptions v-if="quote" :column="4" border>
          <el-descriptions-item label="客户类型">{{ quote.customerSegmentCode }}</el-descriptions-item>
          <el-descriptions-item label="单价">{{ money(quote.unitPrice, quote.currencyCode) }}</el-descriptions-item>
          <el-descriptions-item label="评级费">{{ money(quote.serviceFee, quote.currencyCode) }}</el-descriptions-item>
          <el-descriptions-item label="返程运费">{{ money(quote.returnShippingFee, quote.currencyCode) }}</el-descriptions-item>
          <el-descriptions-item label="合计">{{ money(quote.totalAmount, quote.currencyCode) }}</el-descriptions-item>
          <el-descriptions-item label="价格来源">{{ quote.pricingSourceCode }}</el-descriptions-item>
          <el-descriptions-item label="运费来源">{{ quote.shippingSourceCode }}</el-descriptions-item>
          <el-descriptions-item label="计费重量">{{ quote.chargeableWeightGrams == null ? '固定运费' : `${quote.chargeableWeightGrams} g` }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>

      <el-tab-pane label="分层价格" name="prices">
        <div class="policy-actions"><el-button type="primary" v-hasPermi="['nxr:commerce:config']" @click="editPrice()">新增价格策略</el-button></div>
        <el-table :data="catalog.pricePolicies" empty-text="尚未配置分层价格，将使用现有全局价格">
          <el-table-column prop="displayName" label="名称" min-width="150" />
          <el-table-column prop="customerSegmentCode" label="客户层级" width="110" />
          <el-table-column prop="customerId" label="指定企业 ID" width="120"><template #default="{ row }">{{ row.customerId || '—' }}</template></el-table-column>
          <el-table-column prop="currencyCode" label="币种" width="80" />
          <el-table-column label="数量区间" width="130"><template #default="{ row }">{{ row.minimumQuantity }}–{{ row.maximumQuantity || '∞' }}</template></el-table-column>
          <el-table-column label="单价" width="120"><template #default="{ row }">{{ money(row.unitPrice, row.currencyCode) }}</template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.active ? 'success' : 'info'">{{ row.active ? '启用' : '停用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" @click="editPrice(row)">编辑</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="重量运费" name="shipping">
        <div class="policy-actions"><el-button type="primary" v-hasPermi="['nxr:commerce:config']" @click="editShipping()">新增运费策略</el-button></div>
        <el-table :data="catalog.shippingPolicies" empty-text="尚未配置重量运费，将使用现有返程运费选项">
          <el-table-column prop="displayName" label="名称" min-width="150" />
          <el-table-column prop="destinationCountry" label="目的地" width="90" />
          <el-table-column prop="currencyCode" label="币种" width="75" />
          <el-table-column label="重量" min-width="190"><template #default="{ row }">每卡 {{ row.perCardWeightGrams }}g + 包装 {{ row.packagingWeightGrams }}g</template></el-table-column>
          <el-table-column label="首/续重" min-width="210"><template #default="{ row }">{{ row.firstWeightGrams }}g / {{ row.additionalWeightGrams }}g</template></el-table-column>
          <el-table-column label="优惠/包邮" min-width="180"><template #default="{ row }">{{ row.discountQuantityThreshold ? `${row.discountQuantityThreshold} 张减 ${row.discountPercent}%` : '无折扣' }}；{{ row.freeShippingQuantityThreshold ? `${row.freeShippingQuantityThreshold} 张包邮` : '不包邮' }}</template></el-table-column>
          <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" @click="editShipping(row)">编辑</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="业务线与中心" name="routing">
        <div class="routing-grid">
          <el-card shadow="never">
            <template #header><div class="card-title"><b>业务线</b><el-button link type="primary" @click="editLine()">新增</el-button></div></template>
            <el-table :data="catalog.businessLines"><el-table-column prop="displayName" label="名称" /><el-table-column prop="orderOriginCode" label="订单来源" /><el-table-column label="默认" width="65"><template #default="{ row }">{{ row.defaultLine ? '是' : '否' }}</template></el-table-column><el-table-column width="60"><template #default="{ row }"><el-button link @click="editLine(row)">编辑</el-button></template></el-table-column></el-table>
          </el-card>
          <el-card shadow="never">
            <template #header><div class="card-title"><b>作业中心</b><el-button link type="primary" @click="editCenter()">新增</el-button></div></template>
            <el-table :data="catalog.workCenters"><el-table-column prop="displayName" label="名称" /><el-table-column prop="centerCode" label="代码" /><el-table-column label="默认" width="65"><template #default="{ row }">{{ row.defaultCenter ? '是' : '否' }}</template></el-table-column><el-table-column width="60"><template #default="{ row }"><el-button link @click="editCenter(row)">编辑</el-button></template></el-table-column></el-table>
          </el-card>
        </div>
        <el-divider content-position="left">客户订单路由</el-divider>
        <el-form :model="routingForm" inline class="policy-form-row">
          <el-form-item label="客户 ID"><el-input-number v-model="routingForm.customerId" :min="1" :controls="false" /></el-form-item>
          <el-form-item label="业务线"><el-select v-model="routingForm.businessLineId"><el-option v-for="item in customerSubmissionLines" :key="item.id" :label="item.displayName" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="作业中心"><el-select v-model="routingForm.workCenterId"><el-option v-for="item in activeCenters" :key="item.id" :label="item.displayName" :value="item.id" /></el-select></el-form-item>
          <el-button type="primary" :loading="saving" @click="saveRouting">保存路由</el-button>
        </el-form>
        <el-divider content-position="left">未绑定订单的自有库存送评</el-divider>
        <el-form :model="submissionRoutingForm" inline class="policy-form-row">
          <el-form-item label="送评记录 ID"><el-input-number v-model="submissionRoutingForm.submissionId" :min="1" :controls="false" /></el-form-item>
          <el-form-item label="自有库存业务线"><el-select v-model="submissionRoutingForm.businessLineId"><el-option v-for="item in ownedInventoryLines" :key="item.id" :label="item.displayName" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="作业中心"><el-select v-model="submissionRoutingForm.workCenterId"><el-option v-for="item in activeCenters" :key="item.id" :label="item.displayName" :value="item.id" /></el-select></el-form-item>
          <el-button type="primary" :loading="saving" @click="saveSubmissionRouting">保存归属</el-button>
        </el-form>
        <el-alert type="info" title="只能归属未绑定客户订单的送评记录；已绑定订单的记录始终继承订单范围。" :closable="false" />
      </el-tab-pane>

      <el-tab-pane label="员工范围" name="scope">
        <el-alert v-if="scopeError" type="warning" :title="scopeError" :closable="false" show-icon />
        <el-table :data="staffScopes">
          <el-table-column label="员工" min-width="150"><template #default="{ row }">{{ row.nickName || row.userName }}（{{ row.userName }}）</template></el-table-column>
          <el-table-column label="业务线"><template #default="{ row }">{{ namesFor(row.businessLineIds, catalog.businessLines) }}</template></el-table-column>
          <el-table-column label="作业中心"><template #default="{ row }">{{ namesFor(row.workCenterIds, catalog.workCenters) }}</template></el-table-column>
          <el-table-column label="范围" width="120"><template #default="{ row }"><el-tag :type="row.unrestricted ? 'success' : ((row.businessLineIds?.length && row.workCenterIds?.length) ? 'primary' : 'danger')">{{ row.unrestricted ? '全部订单' : ((row.businessLineIds?.length && row.workCenterIds?.length) ? '映射范围' : '无访问范围') }}</el-tag></template></el-table-column>
          <el-table-column width="90"><template #default="{ row }"><el-button v-if="!row.unrestricted" link type="primary" @click="editScope(row)">配置</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="editor.open" :title="editor.title" width="680px" destroy-on-close>
      <el-form :model="editor.form" label-position="top">
        <template v-if="editor.type === 'price'">
          <div class="form-grid"><el-form-item label="策略代码"><el-input v-model="editor.form.policyCode" maxlength="64" /></el-form-item><el-form-item label="名称"><el-input v-model="editor.form.displayName" maxlength="128" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="客户层级"><el-select v-model="editor.form.customerSegmentCode"><el-option label="C 端" value="consumer" /><el-option label="B 端" value="business" /><el-option label="全部" value="all" /></el-select></el-form-item><el-form-item label="指定企业客户 ID"><el-input-number v-model="editor.form.customerId" :min="1" :controls="false" clearable /></el-form-item></div>
          <div class="form-grid"><el-form-item label="币种"><el-select v-model="editor.form.currencyCode"><el-option v-for="item in currencies" :key="item" :value="item" /></el-select></el-form-item><el-form-item label="单价"><el-input-number v-model="editor.form.unitPrice" :min="0" :precision="currencyPrecision(editor.form.currencyCode)" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="最少数量"><el-input-number v-model="editor.form.minimumQuantity" :min="1" /></el-form-item><el-form-item label="最多数量（空为不限）"><el-input-number v-model="editor.form.maximumQuantity" :min="1" /></el-form-item></div>
        </template>
        <template v-else-if="editor.type === 'shipping'">
          <div class="form-grid"><el-form-item label="策略代码"><el-input v-model="editor.form.policyCode" maxlength="64" /></el-form-item><el-form-item label="名称"><el-input v-model="editor.form.displayName" maxlength="128" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="目的地国家/地区（* 为全部）"><el-input v-model="editor.form.destinationCountry" maxlength="128" /></el-form-item><el-form-item label="币种"><el-select v-model="editor.form.currencyCode"><el-option v-for="item in currencies" :key="item" :value="item" /></el-select></el-form-item></div>
          <div class="form-grid"><el-form-item label="每张封装卡重量 (g)"><el-input-number v-model="editor.form.perCardWeightGrams" :min="1" /></el-form-item><el-form-item label="包装重量 (g)"><el-input-number v-model="editor.form.packagingWeightGrams" :min="0" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="首重 (g)"><el-input-number v-model="editor.form.firstWeightGrams" :min="1" /></el-form-item><el-form-item label="首重价格"><el-input-number v-model="editor.form.firstWeightPrice" :min="0" :precision="currencyPrecision(editor.form.currencyCode)" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="续重单位 (g)"><el-input-number v-model="editor.form.additionalWeightGrams" :min="1" /></el-form-item><el-form-item label="每单位续重价格"><el-input-number v-model="editor.form.additionalWeightPrice" :min="0" :precision="currencyPrecision(editor.form.currencyCode)" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="折扣数量门槛"><el-input-number v-model="editor.form.discountQuantityThreshold" :min="1" /></el-form-item><el-form-item label="折扣百分比"><el-input-number v-model="editor.form.discountPercent" :min="0" :max="100" :precision="2" /></el-form-item></div>
          <el-form-item label="包邮数量门槛"><el-input-number v-model="editor.form.freeShippingQuantityThreshold" :min="1" /></el-form-item>
        </template>
        <template v-else-if="editor.type === 'line'">
          <div class="form-grid"><el-form-item label="业务线代码"><el-input v-model="editor.form.lineCode" maxlength="48" /></el-form-item><el-form-item label="名称"><el-input v-model="editor.form.displayName" maxlength="128" /></el-form-item></div>
          <el-form-item label="订单来源"><el-select v-model="editor.form.orderOriginCode"><el-option label="客户送评" value="customer_submission" /><el-option label="自有库存" value="owned_inventory" /></el-select></el-form-item>
          <el-checkbox v-model="editor.form.defaultLine">作为该来源的默认业务线</el-checkbox>
        </template>
        <template v-else-if="editor.type === 'center'">
          <div class="form-grid"><el-form-item label="中心代码"><el-input v-model="editor.form.centerCode" maxlength="48" /></el-form-item><el-form-item label="名称"><el-input v-model="editor.form.displayName" maxlength="128" /></el-form-item></div>
          <el-checkbox v-model="editor.form.defaultCenter">作为默认作业中心</el-checkbox>
        </template>
        <template v-else-if="editor.type === 'scope'">
          <el-form-item label="业务线"><el-select v-model="editor.form.businessLineIds" multiple><el-option v-for="item in activeLines" :key="item.id" :label="item.displayName" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="作业中心"><el-select v-model="editor.form.workCenterIds" multiple><el-option v-for="item in activeCenters" :key="item.id" :label="item.displayName" :value="item.id" /></el-select></el-form-item>
          <el-alert type="info" title="业务线和作业中心必须同时匹配；任一项为空时该员工无法访问订单。" :closable="false" />
        </template>
        <el-form-item v-if="['price','shipping','line','center'].includes(editor.type)"><el-checkbox v-model="editor.form.active">启用</el-checkbox></el-form-item>
      </el-form>
      <template #footer><el-button @click="editor.open = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEditor">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup name="CommercePolicyPanel">
import {
  getCommercePolicyCatalog, getCommerceStaffScopes, previewCommerceQuote,
  saveCommerceBusinessLine, saveCommerceCustomerRouting, saveCommercePricePolicy,
  saveCommerceShippingPolicy, saveCommerceStaffScope, saveCommerceWorkCenter,
  saveOwnedInventorySubmissionRouting
} from '@/api/nxr/commercePolicy'

const props = defineProps({ refreshKey: { type: [String, Number], default: 0 } })
const emit = defineEmits(['changed'])
const { proxy } = getCurrentInstance()
const currencies = ['USD', 'CNY', 'EUR', 'GBP', 'HKD', 'JPY', 'CAD', 'AUD', 'SGD']
const activeTab = ref('quote')
const loading = ref(false)
const saving = ref(false)
const quoteLoading = ref(false)
const error = ref('')
const scopeError = ref('')
const quote = ref(null)
const catalog = reactive({ pricePolicies: [], shippingPolicies: [], businessLines: [], workCenters: [], customerRoutings: [] })
const staffScopes = ref([])
const quoteForm = reactive({ customerId: 1, country: '', currency: 'USD', count: 1, shippingOptionCode: '' })
const routingForm = reactive({ customerId: 1, businessLineId: null, workCenterId: null, orderOriginCode: 'customer_submission' })
const submissionRoutingForm = reactive({ submissionId: null, businessLineId: null, workCenterId: null })
const editor = reactive({ open: false, type: '', title: '', form: {} })
const activeLines = computed(() => catalog.businessLines.filter((item) => item.active))
const activeCenters = computed(() => catalog.workCenters.filter((item) => item.active))
const customerSubmissionLines = computed(() => activeLines.value.filter((item) => item.orderOriginCode === 'customer_submission'))
const ownedInventoryLines = computed(() => activeLines.value.filter((item) => item.orderOriginCode === 'owned_inventory'))

async function loadAll() {
  loading.value = true
  error.value = ''
  scopeError.value = ''
  const [catalogResult, scopesResult] = await Promise.allSettled([getCommercePolicyCatalog(), getCommerceStaffScopes()])
  if (catalogResult.status === 'fulfilled') Object.assign(catalog, catalogResult.value.data || {})
  else error.value = catalogResult.reason?.msg || catalogResult.reason?.message || '商业策略加载失败'
  if (scopesResult.status === 'fulfilled') staffScopes.value = scopesResult.value.data?.scopes || []
  else scopeError.value = '当前账号无员工范围配置权限，或范围数据加载失败。'
  loading.value = false
}

async function previewQuote() {
  quoteLoading.value = true
  try {
    const response = await previewCommerceQuote({ ...quoteForm, shippingOptionCode: quoteForm.shippingOptionCode || undefined })
    quote.value = response.data
  } catch (failure) {
    proxy.$modal.msgError(failure?.msg || '报价计算失败')
  } finally { quoteLoading.value = false }
}

function openEditor(type, title, form) { Object.assign(editor, { open: true, type, title, form }) }
function editPrice(row = null) { openEditor('price', row ? '编辑价格策略' : '新增价格策略', row ? { ...row } : { policyCode: '', displayName: '', customerSegmentCode: 'consumer', customerId: null, currencyCode: 'USD', minimumQuantity: 1, maximumQuantity: null, unitPrice: null, priorityNo: 0, active: true }) }
function editShipping(row = null) { openEditor('shipping', row ? '编辑运费策略' : '新增运费策略', row ? { ...row } : { policyCode: '', displayName: '', destinationCountry: '*', currencyCode: 'USD', perCardWeightGrams: null, packagingWeightGrams: 0, firstWeightGrams: null, firstWeightPrice: null, additionalWeightGrams: null, additionalWeightPrice: null, discountQuantityThreshold: null, discountPercent: 0, freeShippingQuantityThreshold: null, priorityNo: 0, active: true }) }
function editLine(row = null) { openEditor('line', row ? '编辑业务线' : '新增业务线', row ? { ...row } : { lineCode: '', displayName: '', orderOriginCode: 'customer_submission', defaultLine: false, active: true }) }
function editCenter(row = null) { openEditor('center', row ? '编辑作业中心' : '新增作业中心', row ? { ...row } : { centerCode: '', displayName: '', defaultCenter: false, active: true }) }
function editScope(row) { openEditor('scope', `配置 ${row.nickName || row.userName} 的订单范围`, { userId: row.userId, businessLineIds: [...(row.businessLineIds || [])], workCenterIds: [...(row.workCenterIds || [])] }) }

async function saveEditor() {
  const operations = { price: saveCommercePricePolicy, shipping: saveCommerceShippingPolicy, line: saveCommerceBusinessLine, center: saveCommerceWorkCenter, scope: saveCommerceStaffScope }
  saving.value = true
  try {
    await operations[editor.type](editor.form)
    editor.open = false
    proxy.$modal.msgSuccess('配置已保存')
    emit('changed')
    await loadAll()
  } catch (failure) { proxy.$modal.msgError(failure?.msg || '配置保存失败') }
  finally { saving.value = false }
}

async function saveRouting() {
  saving.value = true
  try {
    await saveCommerceCustomerRouting(routingForm)
    proxy.$modal.msgSuccess('客户路由已保存')
    emit('changed')
    await loadAll()
  } catch (failure) { proxy.$modal.msgError(failure?.msg || '客户路由保存失败') }
  finally { saving.value = false }
}

async function saveSubmissionRouting() {
  saving.value = true
  try {
    await saveOwnedInventorySubmissionRouting(submissionRoutingForm)
    proxy.$modal.msgSuccess('自有库存送评归属已保存')
    emit('changed')
  } catch (failure) { proxy.$modal.msgError(failure?.msg || '送评归属保存失败') }
  finally { saving.value = false }
}

function currencyPrecision(currency) { return currency === 'JPY' ? 0 : 2 }
function money(value, currency) { return value == null ? '—' : `${currency || ''} ${Number(value).toFixed(currencyPrecision(currency))}` }
function namesFor(ids, options) { return ids?.length ? ids.map((id) => options.find((item) => item.id === id)?.displayName || `#${id}`).join('、') : '未映射' }

watch(() => props.refreshKey, loadAll)
onMounted(loadAll)
</script>

<style scoped>
.commerce-policy-panel { padding: 4px 0; }
.commerce-policy-panel__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 16px; }
.commerce-policy-panel__header h3 { margin: 0 0 6px; font-size: 18px; }
.commerce-policy-panel__header p { margin: 0; color: var(--el-text-color-secondary); }
.policy-actions { display: flex; justify-content: flex-end; margin-bottom: 12px; }
.policy-form-row { display: flex; align-items: flex-end; gap: 4px; }
.routing-grid, .form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.card-title { display: flex; align-items: center; justify-content: space-between; }
.el-select, .el-input-number { width: 100%; }
@media (max-width: 900px) { .routing-grid, .form-grid { grid-template-columns: 1fr; } .commerce-policy-panel__header { flex-direction: column; } }
</style>
