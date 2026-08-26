<script setup lang="ts">
import { ref } from 'vue'
import {
  BookingPage,
  CheckoutPage,
  DashboardPage,
  LearningPage,
  LiveRoomPage,
  ResourceManagementPage,
} from '../patterns'

const dashboardNavigation = ref(true)
const query = ref('')
const view = ref<'table' | 'grid'>('table')
const bookingStep = ref(2)
const lesson = ref(3)
const muted = ref(false)
const handRaised = ref(false)
const livePanel = ref<'chat' | 'participants'>('chat')
</script>

<template>
  <div class="cg-pattern-showcase">
    <article class="cg-pattern-preview cg-pattern-preview--wide">
      <h3>管理工作台</h3>
      <DashboardPage v-model:sidebar-open="dashboardNavigation" title="运营工作台" :show-refresh="true">
        <template #navigation><nav class="cg-pattern-links"><button>概览</button><button>资源</button><button>订单</button></nav></template>
        <template #metrics><div class="cg-pattern-metrics"><strong>1,286</strong><strong>96.8%</strong><strong>24</strong></div></template>
        <template #default><div class="cg-pattern-content">业务数据区域</div></template>
      </DashboardPage>
    </article>

    <article class="cg-pattern-preview cg-pattern-preview--wide">
      <h3>资源管理</h3>
      <ResourceManagementPage v-model:query="query" v-model:view="view" title="教学资源" :selected-count="2">
        <template #default><div class="cg-pattern-list"><span>课程资料</span><span>实验任务</span><span>成绩记录</span></div></template>
      </ResourceManagementPage>
    </article>

    <article class="cg-pattern-preview">
      <h3>订单结算</h3>
      <CheckoutPage title="确认订单">
        <template #address>收货人：王宁</template>
        <template #items>数据结构教材 × 1</template>
        <template #payment>支付方式由业务系统提供</template>
        <template #summary><strong>合计：¥68.00</strong></template>
      </CheckoutPage>
    </article>

    <article class="cg-pattern-preview">
      <h3>预约流程</h3>
      <BookingPage v-model:step="bookingStep" title="预约研修室" :total-steps="3">
        <template #default>选择时间：2026-08-12 14:00</template>
        <template #summary>地点：三层 301</template>
      </BookingPage>
    </article>

    <article class="cg-pattern-preview">
      <h3>学习中心</h3>
      <LearningPage v-model:current-lesson="lesson" title="软件工程基础" :total-lessons="12" :progress="66">
        <template #outline>课程目录</template>
        <template #default>第 {{ lesson }} 节课程内容</template>
      </LearningPage>
    </article>

    <article class="cg-pattern-preview">
      <h3>直播间</h3>
      <LiveRoomPage
        v-model:muted="muted"
        v-model:hand-raised="handRaised"
        v-model:panel="livePanel"
        title="在线公开课"
        status="live"
        :participant-count="286"
      >
        <template #stage><div class="cg-live-stage">直播画面插槽</div></template>
        <template #chat>互动消息区域</template>
        <template #participants>成员列表区域</template>
      </LiveRoomPage>
    </article>
  </div>
</template>
