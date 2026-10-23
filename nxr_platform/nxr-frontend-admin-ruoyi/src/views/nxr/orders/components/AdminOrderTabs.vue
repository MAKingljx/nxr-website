<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { workspacePath } from '@/utils/submissionWorkspace'
import { ADMIN_ORDERS_PATH } from '@/utils/adminOrderNavigation'

const route = useRoute(), router = useRouter(), { t } = useI18n()
const selected = computed(() => route.meta.workspaceView === 'orders' ? 'customers' : 'grading')
function select(view) {
  if (view === selected.value) return
  void router.push(view === 'customers' ? workspacePath('orders') : { path: ADMIN_ORDERS_PATH, query: { orderChannel: 'partner' } })
}
</script>

<template>
  <el-tabs v-if="route.meta.adminUnified && route.meta.adminOrderWorkspaceAvailable"
    :model-value="selected" @tab-change="select" data-testid="admin-order-tabs">
    <el-tab-pane :label="t('nav.partnerCustomerOrders')" name="customers" />
    <el-tab-pane :label="t('nav.partnerAdmissionGrading')" name="grading" />
  </el-tabs>
</template>
