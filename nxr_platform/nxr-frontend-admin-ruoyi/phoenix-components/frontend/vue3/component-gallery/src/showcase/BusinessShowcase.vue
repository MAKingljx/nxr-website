<script setup lang="ts">
import { ref } from 'vue'
import PhoenixCard from '../primitives/PhoenixCard.vue'
import {
  PhoenixBookingSummary,
  PhoenixCartSummary,
  PhoenixChatPanel,
  PhoenixCourseProgress,
  PhoenixOrderTimeline,
  PhoenixParticipantList,
  PhoenixPaymentStatus,
  PhoenixPriceDisplay,
  PhoenixQuantityStepper,
  PhoenixRecommendationList,
  PhoenixResourceCard,
  PhoenixStreamStatus,
} from '../primitives/business'

const quantity = ref(2)
const message = ref('')
const participant = ref<string | number | null>('teacher')
const cartItems = [
  { id: 'book', title: '数据结构教材', quantity: 1, unitPrice: 68 },
  { id: 'course', title: '在线课程', quantity: 2, unitPrice: 99 },
]
const orderEvents = [
  { id: 1, title: '订单已创建', time: '10:10', status: 'complete' as const },
  { id: 2, title: '等待支付', time: '10:11', status: 'current' as const },
  { id: 3, title: '安排履约', status: 'pending' as const },
]
const messages = [
  { id: 1, sender: '李老师', content: '课程将在十分钟后开始。', time: '14:20', status: 'sent' as const },
  { id: 2, sender: '我', content: '好的，已经准备完成。', time: '14:21', self: true, status: 'sent' as const },
]
const participants = [
  { id: 'teacher', name: '李老师', role: '主讲', status: 'online' as const },
  { id: 'student-a', name: '王宁', role: '学员', status: 'online' as const },
  { id: 'student-b', name: '张雨', role: '学员', status: 'away' as const, muted: true },
]
const recommendations = [
  { id: 1, title: '数据库系统概论', summary: '本周热门课程', badge: '教学', score: 4.9 },
  { id: 2, title: '城市博物馆路线', summary: '适合周末参观', badge: '旅游', score: 4.7 },
  { id: 3, title: '软件工程实践', summary: '馆藏可借', badge: '图书', score: 4.8 },
]
</script>

<template>
  <div class="cg-business-showcase">
    <PhoenixCard title="资源与推荐" padding="large" class="cg-demo-card cg-demo-card--wide">
      <div class="cg-business-grid">
        <PhoenixResourceCard title="城市文化探索" category="旅游" location="天津" :tags="['路线', '讲解']" />
        <PhoenixRecommendationList :items="recommendations" show-refresh />
      </div>
    </PhoenixCard>

    <PhoenixCard title="商品价格" padding="large" class="cg-demo-card">
      <div class="cg-control-stack">
        <PhoenixPriceDisplay :value="199" :original-price="259" size="large" />
        <PhoenixQuantityStepper v-model="quantity" :max="10" />
      </div>
    </PhoenixCard>

    <PhoenixCard title="购物车" padding="large" class="cg-demo-card">
      <PhoenixCartSummary :items="cartItems" :shipping="8" :discount="20" />
    </PhoenixCard>

    <PhoenixCard title="订单与支付" padding="large" class="cg-demo-card cg-demo-card--wide">
      <div class="cg-business-grid">
        <PhoenixOrderTimeline :items="orderEvents" order-number="订单 20260810001" />
        <div class="cg-control-stack">
          <PhoenixPaymentStatus status="pending" :amount="266" reference="等待服务端确认" action-label="继续支付" />
          <PhoenixPaymentStatus status="paid" :amount="266" reference="支付结果仅作展示" />
        </div>
      </div>
    </PhoenixCard>

    <PhoenixCard title="预约与学习" padding="large" class="cg-demo-card cg-demo-card--wide">
      <div class="cg-business-grid">
        <PhoenixBookingSummary title="图书馆研修室" date="2026-08-12" time="14:00" location="三层 301" status="confirmed" editable cancellable />
        <PhoenixCourseProgress title="软件工程基础" :completed="8" :total="12" current-lesson="需求分析" />
      </div>
    </PhoenixCard>

    <PhoenixCard title="直播互动" padding="large" class="cg-demo-card cg-demo-card--wide">
      <PhoenixStreamStatus status="live" title="软件工程公开课" :viewers="286" started-at="14:00" action-label="进入直播间" />
      <div class="cg-business-grid cg-business-grid--spaced">
        <PhoenixChatPanel v-model="message" :messages="messages" />
        <PhoenixParticipantList v-model:selected-id="participant" :participants="participants" show-invite />
      </div>
    </PhoenixCard>
  </div>
</template>
