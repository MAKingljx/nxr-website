<script setup lang="ts">
const props = withDefaults(defineProps<{
  title?: string
  status?: 'idle' | 'processing' | 'success' | 'failed'
  canSubmit?: boolean
  submitLabel?: string
  cancelLabel?: string
}>(), {
  title: '确认订单',
  status: 'idle',
  canSubmit: true,
  submitLabel: '提交订单',
  cancelLabel: '取消',
})

const emit = defineEmits<{
  submit: []
  cancel: []
}>()

const statusText: Record<NonNullable<typeof props.status>, string> = {
  idle: '',
  processing: '处理中',
  success: '已完成',
  failed: '提交失败',
}

function submit() {
  if (props.canSubmit && props.status !== 'processing') emit('submit')
}
</script>

<template>
  <main class="px-page-pattern px-checkout-page" :aria-label="title" :aria-busy="status === 'processing'">
    <header class="px-page-pattern__header">
      <h1><slot name="title">{{ title }}</slot></h1>
      <slot name="header-actions" />
    </header>

    <div v-if="statusText[status]" class="px-page-pattern__status" :class="`is-${status}`" role="status" aria-live="polite">
      <slot name="status" :status="status">{{ statusText[status] }}</slot>
    </div>

    <div class="px-checkout-page__layout">
      <div class="px-checkout-page__details">
        <section v-if="$slots.address" class="px-page-pattern__surface" aria-label="收货信息"><slot name="address" /></section>
        <section class="px-page-pattern__surface" aria-label="订单内容"><slot name="items" /></section>
        <section v-if="$slots.promotion" class="px-page-pattern__surface" aria-label="优惠信息"><slot name="promotion" /></section>
        <section v-if="$slots.payment" class="px-page-pattern__surface" aria-label="支付方式"><slot name="payment" /></section>
      </div>
      <aside class="px-page-pattern__surface px-checkout-page__summary" aria-label="订单汇总">
        <slot name="summary" />
        <div v-if="$slots.notice" class="px-checkout-page__notice"><slot name="notice" /></div>
        <div class="px-checkout-page__actions">
          <slot name="actions" :submit="submit" :status="status">
            <button type="button" class="px-page-pattern__button" @click="emit('cancel')">{{ cancelLabel }}</button>
            <button
              type="button"
              class="px-page-pattern__button px-page-pattern__button--primary"
              :disabled="!canSubmit || status === 'processing'"
              @click="submit"
            >
              {{ status === 'processing' ? '处理中' : submitLabel }}
            </button>
          </slot>
        </div>
      </aside>
    </div>
  </main>
</template>
