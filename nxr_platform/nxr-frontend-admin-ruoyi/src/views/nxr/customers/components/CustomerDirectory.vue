<template>
  <section class="directory-table">
    <el-table v-loading="loading" :data="rows" row-key="id" class="desktop-user-table">
      <el-table-column :label="$tx('Customer')" min-width="280" fixed="left">
        <template #default="scope">
          <button type="button" class="identity-cell" @click="$emit('open-detail', scope.row.id)">
            <el-avatar :size="38" :style="{ backgroundColor: avatarColor(scope.row.id) }">
              {{ avatarText(scope.row.displayName) }}
            </el-avatar>
            <span>
              <strong>{{ scope.row.displayName || $tx('Name not set') }}</strong>
              <small>{{ scope.row.email }}</small>
            </span>
          </button>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Cards')" width="132" align="center">
        <template #default="scope">
          <span class="count-value">{{ scope.row.activeCardCount }}</span>
          <small class="count-label">{{ $tx('active ·') }} {{ scope.row.ownershipCount }} {{ $tx('events') }}</small>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Orders')" width="88" align="center">
        <template #default="scope">
          <span class="count-value">{{ scope.row.orderCount }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Last Sign-in')" min-width="165">
        <template #default="scope">
          <span class="date-value">{{ scope.row.lastLoginAt ? formatCustomerDate(scope.row.lastLoginAt) : $tx('Never signed in') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Status')" width="108" align="center">
        <template #default="scope">
          <el-tooltip v-if="canManage" :content="scope.row.active ? $tx('Account active') : $tx('Account inactive')" placement="top">
            <el-switch
              :model-value="scope.row.active"
              :loading="statusChangingId === scope.row.id"
              :aria-label="scope.row.active ? $tx('Deactivate account') : $tx('Activate account')"
              @change="$emit('toggle-status', scope.row)"
            />
          </el-tooltip>
          <nxr-status-tag
            v-else
            :code="scope.row.active ? 'active' : 'inactive'"
            :label="scope.row.active ? $tx('Active') : $tx('Inactive')"
          />
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Details')" width="72" align="center" fixed="right">
        <template #default="scope">
          <el-tooltip :content="$tx('View customer details')" placement="top">
            <el-button link type="primary" :icon="View" :aria-label="$tx('View customer details')" @click="$emit('open-detail', scope.row.id)" />
          </el-tooltip>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="$tx('No matching customers')" :image-size="88" />
      </template>
    </el-table>

    <div v-loading="loading" class="mobile-user-list">
      <article v-for="customer in rows" :key="customer.id" class="mobile-user-item">
        <header>
          <button type="button" class="identity-cell" @click="$emit('open-detail', customer.id)">
            <el-avatar :size="40" :style="{ backgroundColor: avatarColor(customer.id) }">
              {{ avatarText(customer.displayName) }}
            </el-avatar>
            <span>
              <strong>{{ customer.displayName || $tx('Name not set') }}</strong>
              <small>{{ customer.email }}</small>
            </span>
          </button>
          <el-switch
            v-if="canManage"
            :model-value="customer.active"
            :loading="statusChangingId === customer.id"
            :aria-label="customer.active ? $tx('Deactivate account') : $tx('Activate account')"
            @change="$emit('toggle-status', customer)"
          />
          <nxr-status-tag
            v-else
            :code="customer.active ? 'active' : 'inactive'"
            :label="customer.active ? $tx('Active') : $tx('Inactive')"
          />
        </header>
        <div class="mobile-user-metrics">
          <span><strong>{{ customer.activeCardCount }}</strong>{{ $tx('Active Cards') }}</span>
          <span><strong>{{ customer.orderCount }}</strong>{{ $tx('Grading Orders') }}</span>
          <span><strong>{{ customer.lastLoginAt ? formatCustomerDate(customer.lastLoginAt) : $tx('Never') }}</strong>{{ $tx('Last Sign-in') }}</span>
        </div>
        <button
          type="button"
          class="mobile-detail-link"
          :aria-label="$tx('View details for {name}', { name: customer.displayName || $tx('customer') })"
          @click="$emit('open-detail', customer.id)"
        > {{ $tx('View Customer Details') }} <el-icon><ArrowRight /></el-icon>
        </button>
      </article>
      <el-empty v-if="!loading && !rows.length" :description="$tx('No matching customers')" :image-size="72" />
    </div>
  </section>
</template>

<script setup>
import { ArrowRight, View } from '@element-plus/icons-vue'
import NxrStatusTag from '@/components/NxrWorkspace/StatusTag.vue'
import { avatarColor, avatarText, formatCustomerDate } from '../customerPresentation'

defineProps({
  rows: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  canManage: { type: Boolean, default: false },
  statusChangingId: { type: [Number, String], default: null }
})

defineEmits(['open-detail', 'toggle-status'])
</script>

<style scoped>
.directory-table {
  overflow: hidden;
  border: 1px solid var(--nxr-border);
  border-radius: 0 0 8px 8px;
  background: var(--nxr-surface);
}

.mobile-user-list {
  display: none;
}

.identity-cell {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 11px;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.identity-cell > span:last-child {
  min-width: 0;
}

.identity-cell strong,
.identity-cell small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-cell strong {
  color: var(--nxr-text);
  font-size: 13px;
}

.identity-cell small {
  margin-top: 4px;
  color: var(--nxr-text-faint);
  font-size: 11px;
}

.date-value {
  color: var(--nxr-text-muted);
  font-size: 12px;
}

.count-value,
.count-label {
  display: block;
}

.count-value {
  color: var(--nxr-text);
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.count-label {
  margin-top: 3px;
  color: var(--nxr-text-placeholder);
  font-size: 10px;
}

:deep(.el-table__inner-wrapper::before) {
  display: none;
}

:deep(.el-table th.el-table__cell) {
  height: 44px;
  background: var(--nxr-surface-subtle);
  color: var(--nxr-text-muted);
  font-size: 12px;
}

:deep(.el-table td.el-table__cell) {
  height: 62px;
  border-bottom-color: var(--nxr-border-subtle);
}

@media (max-width: 760px) {
  .desktop-user-table {
    display: none;
  }

  .mobile-user-list {
    display: block;
    min-height: 160px;
  }

  .mobile-user-item {
    padding: 16px;
    border-bottom: 1px solid var(--nxr-border);
    background: var(--nxr-surface);
  }

  .mobile-user-item:last-child {
    border-bottom: 0;
  }

  .mobile-user-item header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .mobile-user-item .identity-cell {
    min-width: 0;
    flex: 1;
  }

  .mobile-user-metrics {
    display: grid;
    grid-template-columns: 0.8fr 0.8fr 1.4fr;
    gap: 8px;
    margin-top: 15px;
    padding: 12px;
    border-radius: 7px;
    background: var(--nxr-surface-muted);
  }

  .mobile-user-metrics span,
  .mobile-user-metrics strong {
    display: block;
    min-width: 0;
  }

  .mobile-user-metrics span {
    color: var(--nxr-text-faint);
    font-size: 10px;
  }

  .mobile-user-metrics strong {
    overflow: hidden;
    margin-bottom: 4px;
    color: var(--nxr-text);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .mobile-detail-link {
    display: flex;
    width: 100%;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-top: 10px;
    padding: 8px 0 0;
    border: 0;
    background: transparent;
    color: var(--nxr-accent);
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
  }
}
</style>
