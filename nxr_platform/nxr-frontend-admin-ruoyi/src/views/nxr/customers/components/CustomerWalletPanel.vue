<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getMerchantProfile, saveMerchantProfile, getWallets, getWalletTransactions, getWalletRecharges, reviewWalletRecharge } from '@/api/nxr/merchantWallet'
const props = defineProps({ customerId: { type: Number, required: true } })
const company = reactive({ companyName: '', contactName: '' })
const balances = ref([])
const transactions = ref({ items: [], page: 1, pageSize: 10, total: 0 })
const recharges = ref({ items: [], page: 1, pageSize: 10, total: 0 })
const currency = ref('USD')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const reviewOpen = ref(false)
const reviewForm = reactive({ item: null, approved: true, providerTransactionId: '', note: '' })
const currencies = ['USD', 'CNY', 'EUR', 'GBP', 'HKD', 'JPY', 'CAD', 'AUD', 'SGD']
const totalPages = computed(() => Math.ceil(transactions.value.total / 10))
const money = (amount, code) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: code, currencyDisplay: 'code' }).format(Number(amount))
const time = value => value ? new Date(value).toLocaleString() : '—'
const status = code => ({ pending: '待核款', confirmed: '已到账', rejected: '已驳回', cancelled: '已取消' }[code] || code)
const errorText = (value, fallback) => value instanceof Error && value.message ? value.message : fallback
async function loadTransactions(page = 1) {
  try { transactions.value = (await getWalletTransactions(props.customerId, { currencyCode: currency.value, page, pageSize: 10 })).data }
  catch (value) { error.value = errorText(value, '余额流水加载失败，请稍后重试。') }
}
async function loadRecharges(page = 1) {
  try { recharges.value = (await getWalletRecharges(props.customerId, { page, pageSize: 10 })).data }
  catch (value) { error.value = errorText(value, '充值记录加载失败，请稍后重试。') }
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [profile, wallets] = await Promise.all([getMerchantProfile(props.customerId), getWallets(props.customerId)])
    Object.assign(company, profile.data); balances.value = wallets.data
    await Promise.all([loadTransactions(), loadRecharges()])
  } catch (value) { error.value = errorText(value, '企业钱包加载失败，请稍后重试。') }
  finally { loading.value = false }
}
async function save() {
  if (!company.companyName.trim() || !company.contactName.trim()) { ElMessage.warning('请填写公司名称和联系人'); return }
  saving.value = true
  try { await saveMerchantProfile(props.customerId, company); ElMessage.success('公司资料已保存') }
  catch (value) { ElMessage.error(errorText(value, '公司资料保存失败，请稍后重试。')) }
  finally { saving.value = false }
}
function review(item, approved) {
  Object.assign(reviewForm, { item, approved, providerTransactionId: '', note: '' }); reviewOpen.value = true
}
async function submitReview() {
  if (!reviewForm.note.trim() || (reviewForm.approved && !reviewForm.providerTransactionId.trim())) { ElMessage.warning('请填写核验说明和实际收款流水号'); return }
  saving.value = true
  try {
    await reviewWalletRecharge(props.customerId, reviewForm.item.id, reviewForm.approved, { note: reviewForm.note, providerTransactionId: reviewForm.providerTransactionId })
    reviewOpen.value = false; ElMessage.success(reviewForm.approved ? '充值已入账' : '充值已驳回'); await load()
  } catch (value) { ElMessage.error(errorText(value, '充值审核失败，请稍后重试。')) }
  finally { saving.value = false }
}
watch(() => props.customerId, load, { immediate: true })
</script>
<template>
  <div v-loading="loading">
    <el-alert v-if="error" :title="error" :closable="false" type="error" show-icon />
    <el-form label-width="86px" :model="company"><el-form-item label="公司名称"><el-input v-model="company.companyName" maxlength="191" /></el-form-item><el-form-item label="联系人"><el-input v-model="company.contactName" maxlength="128" /></el-form-item><el-form-item><el-button v-hasPermi="['nxr:customer:manage']" type="primary" :loading="saving" @click="save">保存公司资料</el-button></el-form-item></el-form>
    <h3>企业预充值余额</h3><div class="wallet-cards"><div v-for="code in currencies" :key="code"><span>{{ code }}</span><strong>{{ money(balances.find(item => item.currencyCode === code)?.balance || 0, code) }}</strong></div></div>
    <el-alert title="不同币种分别记账；充值核款后到账，订单使用同币种余额扣费。" :closable="false" type="info" />
    <h3>充值审核 <el-button link @click="load">刷新</el-button></h3>
    <el-table :data="recharges.items" size="small"><el-table-column label="充值单" prop="rechargeNo" min-width="170" /><el-table-column label="金额" min-width="125"><template #default="{ row }">{{ money(row.amount, row.currencyCode) }}</template></el-table-column><el-table-column label="状态" width="85"><template #default="{ row }">{{ status(row.statusCode) }}</template></el-table-column><el-table-column label="凭证 / 说明" min-width="150"><template #default="{ row }">{{ row.payerReference }}<br />{{ row.proofReference }}<small v-if="row.reviewNote">{{ row.reviewNote }}</small></template></el-table-column><el-table-column label="操作" width="110"><template #default="{ row }"><template v-if="row.statusCode === 'pending'"><el-button v-hasPermi="['nxr:customer:finance']" link type="success" :disabled="saving" @click="review(row, true)">到账</el-button><el-button v-hasPermi="['nxr:customer:finance']" link type="danger" :disabled="saving" @click="review(row, false)">驳回</el-button></template></template></el-table-column></el-table>
    <el-pagination v-if="recharges.total > 10" small layout="prev, pager, next" :total="recharges.total" :page-size="10" :current-page="recharges.page" @current-change="loadRecharges" />
    <h3>余额流水 <el-select v-model="currency" style="width:100px" @change="loadTransactions()"><el-option v-for="code in currencies" :key="code" :value="code" :label="code" /></el-select></h3>
    <el-table :data="transactions.items" size="small"><el-table-column label="时间" min-width="160"><template #default="{ row }">{{ time(row.createdAt) }}</template></el-table-column><el-table-column label="类型" min-width="95"><template #default="{ row }">{{ ({ recharge: '充值', order_payment: '订单扣款', order_refund: '订单退款' })[row.transactionTypeCode] || row.transactionTypeCode }}</template></el-table-column><el-table-column label="发生金额" min-width="125"><template #default="{ row }">{{ row.directionCode === 'debit' ? '−' : '+' }}{{ money(row.amount, currency) }}</template></el-table-column><el-table-column label="余额" min-width="125"><template #default="{ row }">{{ money(row.balanceAfter, currency) }}</template></el-table-column><el-table-column label="说明" prop="note" min-width="180" /></el-table>
    <el-pagination v-if="totalPages > 1" small layout="prev, pager, next" :total="transactions.total" :page-size="10" :current-page="transactions.page" @current-change="loadTransactions" />
    <el-dialog v-model="reviewOpen" :title="reviewForm.approved ? '确认充值到账' : '驳回充值'" width="480px" append-to-body>
      <p v-if="reviewForm.item">{{ reviewForm.item.rechargeNo }} · {{ money(reviewForm.item.amount, reviewForm.item.currencyCode) }}</p>
      <el-alert v-if="reviewForm.approved" title="请先核验实际收款；确认后金额将进入企业余额。" type="warning" :closable="false" />
      <el-form label-position="top"><el-form-item v-if="reviewForm.approved" label="实际收款流水号" required><el-input v-model="reviewForm.providerTransactionId" maxlength="255" /></el-form-item><el-form-item :label="reviewForm.approved ? '收款核验说明' : '驳回原因'" required><el-input v-model="reviewForm.note" type="textarea" maxlength="2000" /></el-form-item></el-form>
      <template #footer><el-button @click="reviewOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitReview">{{ reviewForm.approved ? '确认到账' : '确认驳回' }}</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>.wallet-cards{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:15px}.wallet-cards>div{padding:16px;border:1px solid var(--nxr-border);border-radius:8px}.wallet-cards span,.wallet-cards strong,small{display:block}.wallet-cards span{font-size:12px;color:var(--nxr-text-faint);margin-bottom:8px}h3{margin-top:24px}.el-pagination{margin-top:15px}@media(max-width:540px){.wallet-cards{grid-template-columns:1fr 1fr}}</style>
