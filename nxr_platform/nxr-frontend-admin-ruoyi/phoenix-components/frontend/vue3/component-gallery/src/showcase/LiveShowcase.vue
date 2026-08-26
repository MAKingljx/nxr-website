<script setup lang="ts">
import { ref } from 'vue'
import {
  PhoenixDanmakuLayer,
  PhoenixLiveConsole,
  PhoenixLiveMetrics,
  PhoenixLiveProductShelf,
  PhoenixMemberManager,
  PhoenixModerationQueue,
  PhoenixReplayList,
} from '../primitives/live'

const selectedProduct = ref<string | number | null>('bag')
const danmakuVisible = ref(true)
const danmakuPaused = ref(false)
const moderationId = ref<string | number | null>('m1')
const memberId = ref<string | number | null>('host')
const replayId = ref<string | number | null>('r1')
const messages = [
  { id: 1, sender: '小王', content: '这个颜色很好看', kind: 'normal' as const },
  { id: 2, sender: '系统', content: '文明互动，理性消费', kind: 'system' as const },
  { id: 3, sender: '会员用户', content: '已加入购物车', kind: 'highlight' as const },
]
</script>

<template>
  <div class="cg-live-showcase">
    <article class="is-wide"><h3>直播控制台与数据指标</h3><div class="cg-live-showcase__split"><PhoenixLiveConsole title="城市好物直播" status="live" :viewers="1286" :likes="5630" duration-label="01:28:36" :actions="['pause', 'end']" /><PhoenixLiveMetrics :metrics="[{ key: 'viewers', label: '当前观看', value: 1286, kind: 'count', trend: 12.6 }, { key: 'orders', label: '成交订单', value: 86, kind: 'count', status: 'success' }, { key: 'revenue', label: '成交金额', value: 26890, kind: 'currency', status: 'success' }, { key: 'rate', label: '转化率', value: 6.8, kind: 'percent' }]" /></div></article>
    <article><h3>直播商品货架</h3><PhoenixLiveProductShelf v-model:selected-id="selectedProduct" :products="[{ id: 'bag', title: '城市通勤双肩包', price: 269, originalPrice: 329, stock: 36, sales: 1260, featured: true }, { id: 'gift', title: '精选礼盒', price: 199, stock: 18, sales: 886 }]" /></article>
    <article><h3>弹幕层</h3><div class="cg-live-stage-preview"><PhoenixDanmakuLayer v-model:visible="danmakuVisible" v-model:paused="danmakuPaused" :messages="messages" reportable /></div></article>
    <article><h3>消息审核队列</h3><PhoenixModerationQueue v-model:selected-id="moderationId" :items="[{ id: 'm1', sender: '访客 1024', content: '请问什么时候补货？', submittedAt: '20:18', risk: 'low' }, { id: 'm2', sender: '访客 2048', content: '疑似违规推广内容', submittedAt: '20:19', risk: 'high' }]" /></article>
    <article><h3>禁言和成员管理</h3><PhoenixMemberManager v-model:selected-id="memberId" :total="1286" :roles="[{ value: 'host', label: '主播' }, { value: 'assistant', label: '场控' }, { value: 'viewer', label: '观众' }]" :members="[{ id: 'host', name: '李主播', role: 'host', online: true }, { id: 'assistant', name: '王场控', role: 'assistant', online: true }, { id: 'viewer', name: '访客 1024', role: 'viewer', muted: true }]" /></article>
    <article class="is-wide"><h3>回放列表</h3><PhoenixReplayList v-model:selected-id="replayId" :items="[{ id: 'r1', title: '城市好物专场', url: 'https://example.com/replay-1.mp4', durationLabel: '01:32:18', createdAt: '2026-08-09', views: 2680, status: 'available' }, { id: 'r2', title: '暑期学习专场', url: 'https://example.com/replay-2.mp4', durationLabel: '48:20', createdAt: '2026-08-08', views: 1860, status: 'processing' }]" /></article>
  </div>
</template>
