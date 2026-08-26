<script setup lang="ts">
import { ref } from 'vue'
import {
  PhoenixBargainCampaign,
  PhoenixCommentThread,
  PhoenixLuckyDraw,
  PhoenixProductCard,
} from '../primitives/marketing'
import type { PhoenixCommentItem } from '../primitives/marketing'

const drawItems = [
  { id: 'coupon', label: '优惠券' },
  { id: 'points', label: '积分' },
  { id: 'gift', label: '礼盒' },
  { id: 'member', label: '会员' },
  { id: 'thanks', label: '谢谢参与' },
  { id: 'ticket', label: '体验券' },
]
const participants = [
  { id: 1, name: '李明', contribution: 8 },
  { id: 2, name: '王芳', contribution: 5 },
  { id: 3, name: '陈晓', contribution: 3 },
]
const commentText = ref('')
const actionMessage = ref('点击组件即可体验交互')
const comments = ref<PhoenixCommentItem[]>([
  { id: 1, author: '林同学', content: '包装很好，发货也很快。', createdAt: '今天 10:20', likes: 12, canReport: true, replies: [{ id: 11, author: '店主', content: '感谢支持，我们会继续做好服务。' }] },
  { id: 2, author: '张老师', content: '课程内容清晰，适合作为入门材料。', createdAt: '昨天 18:40', likes: 8, liked: true, canDelete: true },
])

function submitComment(content: string) {
  comments.value = [...comments.value, { id: Date.now(), author: '当前用户', content, createdAt: '刚刚', likes: 0 }]
  commentText.value = ''
  actionMessage.value = '评论提交事件已触发'
}
</script>

<template>
  <div class="cg-marketing-showcase">
    <p class="cg-marketing-showcase__status" role="status">{{ actionMessage }}</p>
    <section class="cg-marketing-block">
      <h3>抽奖组件</h3>
      <div class="cg-marketing-styles">
        <article><h4>现代风格</h4><PhoenixLuckyDraw :items="drawItems" selected-id="coupon" appearance="modern" layout="grid" @start="actionMessage = '抽奖请求已提交，等待服务端结果'" /></article>
        <article><h4>节庆风格</h4><PhoenixLuckyDraw :items="drawItems" selected-id="gift" appearance="festive" layout="grid" @start="actionMessage = '节庆抽奖请求已提交'" /></article>
        <article><h4>极简风格</h4><PhoenixLuckyDraw :items="drawItems" selected-id="points" appearance="minimal" layout="grid" @start="actionMessage = '极简抽奖请求已提交'" /></article>
      </div>
    </section>
    <section class="cg-marketing-block">
      <h3>好友助力砍价</h3>
      <div class="cg-marketing-styles">
        <article><h4>现代风格</h4><PhoenixBargainCampaign title="城市旅行套装" :original-price="499" :current-price="329" :target-price="199" :participants="participants" :remaining-seconds="8638" appearance="modern" @bargain="actionMessage = '砍价请求已触发'" /></article>
        <article><h4>节庆风格</h4><PhoenixBargainCampaign title="节日精选礼盒" :original-price="299" :current-price="219" :target-price="99" :participants="participants" :remaining-seconds="3661" appearance="festive" @share="actionMessage = '邀请好友事件已触发'" /></article>
        <article><h4>极简风格</h4><PhoenixBargainCampaign title="学习会员年卡" :original-price="199" :current-price="99" :target-price="99" :participants="participants" status="success" appearance="minimal" /></article>
      </div>
    </section>
    <section class="cg-marketing-block">
      <h3>社区评论</h3>
      <div class="cg-marketing-styles">
        <article><h4>现代风格</h4><PhoenixCommentThread v-model="commentText" :comments="comments" appearance="modern" @submit="submitComment" /></article>
        <article><h4>节庆风格</h4><PhoenixCommentThread :comments="comments.slice(0, 1)" appearance="festive" title="活动评论" /></article>
        <article><h4>极简风格</h4><PhoenixCommentThread :comments="comments.slice(1)" appearance="minimal" title="商品评价" /></article>
      </div>
    </section>
    <section class="cg-marketing-block">
      <h3>商品卡片</h3>
      <div class="cg-marketing-products">
        <PhoenixProductCard title="城市通勤双肩包" description="轻量、防泼水、多层收纳" :price="269" :original-price="329" :rating="4.8" :sales="1260" :inventory="36" badge="新品" appearance="modern" @add-cart="actionMessage = '商品加购事件已触发'" />
        <PhoenixProductCard title="节日精选礼盒" description="六款人气好物组合" :price="199" :original-price="259" :rating="4.9" :sales="886" :inventory="18" badge="限时" appearance="festive" layout="horizontal" @favorite="actionMessage = '收藏事件已触发'" />
        <PhoenixProductCard title="在线课程会员" :price="99" :rating="4.7" :sales="2350" :inventory="999" appearance="minimal" layout="compact" />
      </div>
    </section>
  </div>
</template>
