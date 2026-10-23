<script setup>
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import MerchantBatchPanel from '../orders/components/MerchantBatchPanel.vue'
import PartnerReturnPanel from '../orders/components/PartnerReturnPanel.vue'
import { ADMIN_RETURNS_PATH } from '@/utils/adminOrderNavigation'
const router=useRouter(),route=useRoute(),tab=ref(route.query.tab==='returns'?'returns':'submissions')
const { t } = useI18n()
function openOrder(orderId){router.push({path:'/nxr/submissions/orders',query:{orderId:String(orderId),section:'cards'}})}
</script>
<template>
  <main class="nxr-workspace partner-fulfillment">
    <nxr-page-header :title="route.meta.adminUnified ? t(route.meta.adminView === 'batches' ? 'nav.submitManagement' : 'nav.fulfillmentManagement') : $tx('Partner fulfillment')" />
    <template v-if="route.meta.adminUnified">
      <merchant-batch-panel v-if="route.meta.adminView === 'batches'" @open-order="openOrder" @open-returns="router.push(ADMIN_RETURNS_PATH)" />
      <partner-return-panel v-else @open-order="openOrder" />
    </template>
    <el-tabs v-else v-model="tab">
      <el-tab-pane :label="$tx('Submission batches')" name="submissions">
        <merchant-batch-panel v-if="tab==='submissions'" @open-order="openOrder" @open-returns="tab='returns'" />
      </el-tab-pane>
      <el-tab-pane :label="$tx('Return to B')" name="returns">
        <partner-return-panel v-if="tab==='returns'" @open-order="openOrder" />
      </el-tab-pane>
    </el-tabs>
  </main>
</template>
<style scoped>
.partner-fulfillment{min-width:0}
</style>
