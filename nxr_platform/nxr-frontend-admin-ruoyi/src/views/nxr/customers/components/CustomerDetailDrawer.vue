<template>
  <el-drawer
    v-model="open"
    class="customer-drawer"
    :size="drawerSize"
    destroy-on-close
    append-to-body
  >
    <template #header>
      <div v-if="detail" class="drawer-identity">
        <el-avatar :size="46" :style="{ backgroundColor: avatarColor(detail.customer.id) }">
          {{ avatarText(detail.customer.displayName) }}
        </el-avatar>
        <div>
          <strong>{{ detail.customer.displayName || '未设置昵称' }}</strong>
          <span>{{ detail.customer.email }}</span>
        </div>
        <nxr-status-tag
          :code="detail.customer.active ? 'active' : 'inactive'"
          :label="detail.customer.active ? '正常' : '已停用'"
        />
      </div>
      <span v-else>用户详情</span>
    </template>

    <div v-loading="loading" class="drawer-content">
      <template v-if="detail">
        <section class="detail-metrics" aria-label="用户数据概览">
          <div><strong>{{ detail.customer.activeCardCount }}</strong><span>当前持卡</span></div>
          <div><strong>{{ detail.customer.ownershipCount }}</strong><span>流转记录</span></div>
          <div><strong>{{ detail.customer.orderCount }}</strong><span>送评订单</span></div>
          <div><strong>{{ detail.customer.activeSessionCount }}</strong><span>有效会话</span></div>
        </section>

        <el-tabs v-model="detailTab" class="detail-tabs">
          <el-tab-pane label="账号资料" name="profile">
            <dl class="profile-list">
              <div><dt>昵称</dt><dd>{{ detail.customer.displayName || '未填写' }}</dd></div>
              <div><dt>邮箱</dt><dd>{{ detail.customer.email }}</dd></div>
              <div><dt>手机</dt><dd>{{ detail.customer.mobile || '未填写' }}</dd></div>
              <div><dt>账号类型</dt><dd><el-tag :type="detail.customer.accountTypeCode === 'merchant' ? 'warning' : 'info'">{{ detail.customer.accountTypeCode === 'merchant' ? '商户' : '普通客户' }}</el-tag></dd></div>
              <div><dt>加入时间</dt><dd>{{ formatCustomerDate(detail.customer.createdAt) }}</dd></div>
              <div><dt>最近登录</dt><dd>{{ detail.customer.lastLoginAt ? formatCustomerDate(detail.customer.lastLoginAt) : '尚未登录' }}</dd></div>
              <div><dt>账号编号</dt><dd>#{{ detail.customer.id }}</dd></div>
            </dl>

            <div v-if="canManage" class="account-actions">
              <el-select :model-value="detail.customer.accountTypeCode" style="width: 140px" @change="$emit('change-type', detail.customer, $event)">
                <el-option label="普通客户" value="customer" />
                <el-option label="商户" value="merchant" />
              </el-select>
              <el-button
                :type="detail.customer.active ? 'danger' : 'success'"
                plain
                :loading="statusChangingId === detail.customer.id"
                @click="$emit('toggle-status', detail.customer)"
              >
                {{ detail.customer.active ? '停用账号' : '恢复账号' }}
              </el-button>
              <el-button :icon="Key" :loading="revokingSessions" @click="$emit('revoke-sessions')">
                退出全部设备
              </el-button>
            </div>
          </el-tab-pane>

          <el-tab-pane :label="`持卡 ${detail.cards.length}`" name="cards">
            <el-table :data="detail.cards" size="small">
              <el-table-column label="证书" prop="certId" min-width="130" />
              <el-table-column label="卡片" min-width="210" show-overflow-tooltip>
                <template #default="scope">
                  <strong class="detail-card-name">{{ scope.row.cardName || '未匹配卡片资料' }}</strong>
                  <small class="detail-card-brand">{{ scope.row.brandName || '-' }}</small>
                  <small v-if="scope.row.merchDescription" class="detail-card-brand">{{ scope.row.merchDescription }}</small>
                </template>
              </el-table-column>
              <el-table-column label="评级" width="92">
                <template #default="scope">{{ formatGrade(scope.row) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="105">
                <template #default="scope">
                  <nxr-status-tag
                    :code="scope.row.statusCode"
                    :label="ownershipLabel(scope.row.statusCode)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="绑定时间" width="160">
                <template #default="scope">{{ formatCustomerDate(scope.row.boundAt) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`流转 ${detail.ownershipEvents.length}`" name="history">
            <el-empty v-if="!detail.ownershipEvents.length" description="暂无卡片流转记录" :image-size="72" />
            <el-timeline v-else class="ownership-timeline">
              <el-timeline-item
                v-for="event in detail.ownershipEvents"
                :key="event.id"
                :timestamp="formatCustomerDate(event.createdAt)"
                placement="top"
              >
                <div class="timeline-entry">
                  <div>
                    <el-tag size="small" effect="plain">{{ eventLabel(event.eventTypeCode) }}</el-tag>
                    <strong>{{ event.certId }}</strong>
                  </div>
                  <p>{{ transferLabel(event) }}</p>
                  <small v-if="event.message">{{ event.message }}</small>
                </div>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>

          <el-tab-pane :label="`订单 ${detail.orders.length}`" name="orders">
            <el-table :data="detail.orders" size="small">
              <el-table-column label="订单号" prop="orderNo" min-width="150" />
              <el-table-column label="状态" min-width="125">
                <template #default="scope">
                  <el-tag effect="plain">{{ orderStatusLabel(scope.row.statusCode) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="卡数" prop="totalCardCount" width="74" align="center" />
              <el-table-column label="金额" width="125" align="right">
                <template #default="scope">{{ formatAmount(scope.row) }}</template>
              </el-table-column>
              <el-table-column label="创建时间" width="160">
                <template #default="scope">{{ formatCustomerDate(scope.row.createdAt) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </el-drawer>
</template>

<script setup>
import { Key } from '@element-plus/icons-vue'
import { useWindowSize } from '@vueuse/core'
import NxrStatusTag from '@/components/NxrWorkspace/StatusTag.vue'
import {
  avatarColor,
  avatarText,
  eventLabel,
  formatAmount,
  formatCustomerDate,
  formatGrade,
  orderStatusLabel,
  ownershipLabel,
  transferLabel
} from '../customerPresentation'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  detail: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  canManage: { type: Boolean, default: false },
  statusChangingId: { type: [Number, String], default: null },
  revokingSessions: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'toggle-status', 'revoke-sessions', 'change-type'])
const { width } = useWindowSize()
const detailTab = ref('profile')
const drawerSize = computed(() => width.value < 760 ? '100%' : '720px')
const open = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

watch(() => props.detail?.customer?.id, () => {
  detailTab.value = 'profile'
})
</script>

<style scoped>
.drawer-identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.drawer-identity > div {
  min-width: 0;
  flex: 1;
}

.drawer-identity strong,
.drawer-identity span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-identity strong {
  color: var(--nxr-text-strong);
  font-size: 16px;
}

.drawer-identity span {
  margin-top: 3px;
  color: var(--nxr-text-faint);
  font-size: 12px;
}

.drawer-content {
  min-height: 280px;
}

.detail-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  margin-bottom: 18px;
  border: 1px solid var(--nxr-border);
  border-radius: 8px;
  background: var(--nxr-border);
}

.detail-metrics div {
  min-width: 0;
  padding: 16px 10px;
  background: var(--nxr-surface);
  text-align: center;
}

.detail-metrics strong,
.detail-metrics span {
  display: block;
}

.detail-metrics strong {
  color: var(--nxr-accent);
  font-size: 21px;
  font-variant-numeric: tabular-nums;
}

.detail-metrics span {
  margin-top: 5px;
  color: var(--nxr-text-faint);
  font-size: 11px;
}

.profile-list {
  margin: 2px 0 0;
}

.profile-list div {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 18px;
  padding: 14px 4px;
  border-bottom: 1px solid var(--nxr-border-subtle);
}

.profile-list dt,
.profile-list dd {
  margin: 0;
  font-size: 13px;
}

.profile-list dt {
  color: var(--nxr-text-faint);
}

.profile-list dd {
  overflow-wrap: anywhere;
  color: var(--nxr-text);
}

.account-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 22px;
}

.detail-card-name,
.detail-card-brand {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-card-brand {
  margin-top: 3px;
  color: var(--nxr-text-faint);
  font-size: 11px;
}

.ownership-timeline {
  padding: 12px 4px 0;
}

.timeline-entry {
  padding: 13px 15px;
  border: 1px solid var(--nxr-border);
  border-radius: 7px;
  background: var(--nxr-surface-subtle);
}

.timeline-entry > div {
  display: flex;
  align-items: center;
  gap: 9px;
}

.timeline-entry p {
  margin: 10px 0 0;
  color: var(--nxr-text);
  font-size: 13px;
}

.timeline-entry small {
  display: block;
  margin-top: 6px;
  color: var(--nxr-text-faint);
  line-height: 1.5;
}

@media (max-width: 560px) {
  .detail-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .profile-list div {
    grid-template-columns: 88px minmax(0, 1fr);
  }
}
</style>
