<template>
  <aside class="action-rail">
    <section class="waitlist-panel">
      <div class="waitlist-panel__top">
        <span class="waitlist-panel__icon"><el-icon><Clock /></el-icon></span>
        <span>候补名单</span>
      </div>
      <strong>{{ formattedWaitlist }}</strong>
      <p>等待后续服务通知</p>
      <el-button class="waitlist-panel__button" @click="$emit('navigate', '/nxr/waitlist')">
        查看名单
        <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </section>

    <section class="quick-panel">
      <header>
        <p>QUICK ACTIONS</p>
        <h2>快捷操作</h2>
      </header>
      <button
        v-for="action in actions"
        :key="action.path"
        type="button"
        class="quick-action"
        @click="$emit('navigate', action.path)"
      >
        <span class="quick-action__icon"><el-icon><component :is="action.icon" /></el-icon></span>
        <span class="quick-action__copy">
          <strong>{{ action.label }}</strong>
          <small>{{ action.detail }}</small>
        </span>
        <el-icon class="quick-action__arrow"><ArrowRight /></el-icon>
      </button>
    </section>
  </aside>
</template>

<script setup>
import { ArrowRight, Clock, Collection, Plus, ShoppingBag, UploadFilled, User } from '@element-plus/icons-vue'

const props = defineProps({
  waitlistCount: { type: Number, default: 0 }
})

defineEmits(['navigate'])

const formattedWaitlist = computed(() => new Intl.NumberFormat('zh-CN').format(props.waitlistCount || 0))

const actions = [
  { label: '录入卡片', detail: '新建或处理评级资料', path: '/nxr/entries', icon: Plus },
  { label: '上传图片', detail: '导入卡片正反面图片', path: '/nxr/upload', icon: UploadFilled },
  { label: '送评订单', detail: '处理付款与物流进度', path: '/nxr/orders', icon: ShoppingBag },
  { label: '用户管理', detail: '查看账号与持卡记录', path: '/nxr/customers', icon: User },
  { label: '品牌设置', detail: '维护品牌与显示名称', path: '/nxr/brands', icon: Collection }
]
</script>

<style scoped>
.action-rail {
  display: grid;
  min-width: 0;
  gap: 16px;
  margin: 0;
  padding: 0;
  background: transparent;
  color: var(--nxr-text);
  font-family: inherit;
  font-size: inherit;
  line-height: normal;
}

.waitlist-panel,
.quick-panel {
  border: 1px solid var(--nxr-border);
  border-radius: 8px;
  background: var(--nxr-surface);
}

.waitlist-panel {
  padding: 20px;
  border-color: var(--nxr-border);
  background: var(--nxr-surface);
}

.waitlist-panel__top {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--nxr-text);
  font-size: 13px;
  font-weight: 700;
}

.waitlist-panel__icon {
  display: inline-flex;
  width: 30px;
  height: 30px;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: var(--nxr-accent-soft);
  color: var(--nxr-accent);
}

.waitlist-panel > strong {
  display: block;
  margin-top: 16px;
  color: var(--nxr-text-strong);
  font-size: 30px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.waitlist-panel p {
  margin: 8px 0 16px;
  color: var(--nxr-text-faint);
  font-size: 12px;
}

.waitlist-panel__button {
  width: 100%;
  border-color: var(--nxr-border);
  background: var(--nxr-surface);
  color: var(--nxr-accent);
}

.quick-panel {
  overflow: hidden;
}

.quick-panel header {
  padding: 17px 18px 13px;
  border-bottom: 1px solid var(--nxr-border-subtle);
}

.quick-panel header p {
  margin: 0 0 4px;
  color: var(--nxr-accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0;
}

.quick-panel header h2 {
  margin: 0;
  color: var(--nxr-text-strong);
  font-size: 16px;
}

.quick-action {
  display: grid;
  width: 100%;
  min-height: 61px;
  grid-template-columns: 32px minmax(0, 1fr) 18px;
  align-items: center;
  gap: 11px;
  padding: 10px 16px;
  border: 0;
  border-bottom: 1px solid var(--nxr-border-subtle);
  background: var(--nxr-surface);
  color: var(--nxr-text);
  text-align: left;
  cursor: pointer;
  transition: background-color 160ms ease;
}

.quick-action:last-child {
  border-bottom: 0;
}

.quick-action:hover,
.quick-action:focus-visible {
  background: var(--nxr-surface-hover);
  outline: none;
}

.quick-action__icon {
  display: inline-flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: var(--nxr-surface-muted);
  color: var(--nxr-accent);
}

.quick-action__copy {
  min-width: 0;
}

.quick-action__copy strong,
.quick-action__copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quick-action__copy strong {
  font-size: 13px;
}

.quick-action__copy small {
  margin-top: 3px;
  color: var(--nxr-text-faint);
  font-size: 11px;
}

.quick-action__arrow {
  color: var(--nxr-text-placeholder);
}
</style>
