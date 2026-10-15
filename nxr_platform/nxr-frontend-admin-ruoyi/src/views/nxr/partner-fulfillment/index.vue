<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import MerchantBatchPanel from '../orders/components/MerchantBatchPanel.vue'
import PartnerReturnPanel from '../orders/components/PartnerReturnPanel.vue'
const router=useRouter(),route=useRoute(),tab=ref(route.query.tab==='returns'?'returns':'submissions')
function openOrder(orderId){router.push({path:'/nxr/submissions/orders',query:{orderId:String(orderId),section:'cards'}})}
</script>
<template>
  <main class="nxr-workspace partner-fulfillment">
    <nxr-page-header :kicker="$tx('PARTNER OPERATIONS')" :title="$tx('Partner fulfillment')" />
    <el-tabs v-model="tab">
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
