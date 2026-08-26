<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixMarketingAppearance } from './PhoenixLuckyDraw.vue'

export interface PhoenixBargainParticipant {
  id: string | number
  name: string
  avatar?: string
  contribution?: number
}

const props = withDefaults(defineProps<{
  title: string
  image?: string
  originalPrice: number
  currentPrice: number
  targetPrice: number
  participants?: PhoenixBargainParticipant[]
  remainingSeconds?: number
  status?: 'active' | 'success' | 'expired'
  appearance?: PhoenixMarketingAppearance
  actionLabel?: string
  shareLabel?: string
  currency?: string
  disabled?: boolean
}>(), {
  image: '',
  participants: () => [],
  remainingSeconds: 0,
  status: 'active',
  appearance: 'modern',
  actionLabel: '帮忙砍一刀',
  shareLabel: '邀请好友',
  currency: 'CNY',
  disabled: false,
})

const emit = defineEmits<{
  bargain: []
  share: []
}>()

function finite(value: number) {
  return Number.isFinite(value) ? Math.max(0, value) : 0
}

const original = computed(() => finite(props.originalPrice))
const target = computed(() => Math.min(original.value, finite(props.targetPrice)))
const current = computed(() => Math.min(original.value, Math.max(target.value, finite(props.currentPrice))))
const progress = computed(() => {
  const range = original.value - target.value
  return range <= 0 ? 100 : Math.round(((original.value - current.value) / range) * 100)
})
const remaining = computed(() => Math.max(0, Math.trunc(finite(props.remainingSeconds))))
const countdown = computed(() => {
  const days = Math.floor(remaining.value / 86400)
  const hours = Math.floor((remaining.value % 86400) / 3600)
  const minutes = Math.floor((remaining.value % 3600) / 60)
  const seconds = remaining.value % 60
  const clock = [hours, minutes, seconds].map((value) => String(value).padStart(2, '0')).join(':')
  return days ? `${days} 天 ${clock}` : clock
})
const statusLabel = computed(() => ({ active: '进行中', success: '砍价成功', expired: '活动已结束' })[props.status])
const actionDisabled = computed(() => props.disabled || props.status !== 'active')

function money(value: number) {
  try {
    return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: props.currency }).format(value)
  } catch {
    return `${props.currency} ${value.toFixed(2)}`
  }
}

function safeImage(value?: string) {
  if (!value) return ''
  const normalized = value.trim()
  return /^(https?:\/\/|\/|\.\/|\.\.\/|blob:|data:image\/(?!svg\+xml)[a-z0-9.+-]+;base64,)/i.test(normalized)
    ? normalized
    : ''
}
</script>

<template>
  <article class="px-bargain-campaign" :data-appearance="appearance" :class="`is-${status}`">
    <div class="px-bargain-campaign__media">
      <img v-if="safeImage(image)" :src="safeImage(image)" :alt="title">
      <span v-else aria-hidden="true">砍</span>
      <strong>{{ statusLabel }}</strong>
    </div>
    <div class="px-bargain-campaign__body">
      <h3>{{ title }}</h3>
      <div class="px-bargain-campaign__price"><strong>{{ money(current) }}</strong><del>{{ money(original) }}</del></div>
      <div class="px-bargain-campaign__progress">
        <div role="progressbar" aria-label="砍价进度" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="progress"><span :style="{ width: `${progress}%` }" /></div>
        <p><span>已完成 {{ progress }}%</span><strong>目标 {{ money(target) }}</strong></p>
      </div>
      <div class="px-bargain-campaign__people">
        <span v-for="person in participants.slice(0, 5)" :key="person.id" :title="person.name">
          <img v-if="safeImage(person.avatar)" :src="safeImage(person.avatar)" :alt="person.name">
          <strong v-else aria-hidden="true">{{ person.name.slice(0, 1) }}</strong>
        </span>
        <p>{{ participants.length }} 人已助力</p>
      </div>
      <p v-if="status === 'active'" class="px-bargain-campaign__time">剩余 {{ countdown }}</p>
      <div class="px-bargain-campaign__actions">
        <button type="button" :disabled="actionDisabled" @click="emit('bargain')">{{ actionLabel }}</button>
        <button type="button" :disabled="disabled" @click="emit('share')">{{ shareLabel }}</button>
      </div>
    </div>
  </article>
</template>
