<script setup lang="ts">
import { ref } from 'vue'
import {
  PhoenixAddressForm,
  PhoenixAddressSelector,
  PhoenixCouponSelector,
  PhoenixFavoriteButton,
  PhoenixInventoryTable,
  PhoenixLogisticsTracker,
  PhoenixPaymentMethodSelector,
  PhoenixRating,
  PhoenixRefundPanel,
  PhoenixReviewComposer,
  PhoenixSeatRoomSelector,
  PhoenixSkuEditor,
  PhoenixTimeSlotPicker,
} from '../primitives/commerce'

const skuItems = ref([
  { id: 'blue-m', name: '蓝色 M', code: 'BLUE-M', price: 269, stock: 28 },
  { id: 'black-l', name: '黑色 L', code: 'BLACK-L', price: 279, stock: 16 },
])
const inventoryItems = [
  { id: 1, name: '蓝色 M', sku: 'BLUE-M', stock: 28, reserved: 6, lowStockThreshold: 10 },
  { id: 2, name: '黑色 L', sku: 'BLACK-L', stock: 5, reserved: 2, lowStockThreshold: 8 },
]
const addressId = ref<string | number | null>('home')
const addresses = [
  { id: 'home', recipient: '李明', phone: '138****0000', region: '天津市和平区', address: '南京路 100 号', isDefault: true },
  { id: 'office', recipient: '李明', phone: '138****0000', region: '天津市河西区', address: '友谊路 20 号' },
]
const address = ref({ recipient: '李明', phone: '13800000000', province: '天津市', city: '天津市', district: '和平区', address: '南京路 100 号', isDefault: true })
const couponId = ref<string | number | null>('new')
const paymentCode = ref('wallet')
const timeSlotId = ref<string | number | null>('am')
const selectedSeats = ref<(string | number)[]>(['A1'])
const rating = ref(4)
const favorite = ref(false)
const reviewContent = ref('')
</script>

<template>
  <div class="cg-commerce-showcase">
    <article><h3>商品规格 SKU 编辑器</h3><PhoenixSkuEditor v-model="skuItems" /></article>
    <article><h3>库存管理表</h3><PhoenixInventoryTable :items="inventoryItems" /></article>
    <article><h3>地址选择</h3><PhoenixAddressSelector v-model="addressId" :addresses="addresses" /></article>
    <article><h3>地址表单</h3><PhoenixAddressForm v-model="address" /></article>
    <article><h3>优惠券选择</h3><PhoenixCouponSelector v-model="couponId" :order-amount="299" :coupons="[{ id: 'new', title: '新人立减券', discount: 30, minSpend: 199, expiresAt: '2026-08-31' }, { id: 'vip', title: '会员优惠券', discount: 50, minSpend: 499 }]" /></article>
    <article><h3>支付方式选择</h3><PhoenixPaymentMethodSelector v-model="paymentCode" :methods="[{ code: 'wallet', label: '平台钱包', description: '使用账户余额' }, { code: 'bank', label: '银行卡', description: '跳转受信支付服务' }]" /></article>
    <article><h3>退款申请</h3><PhoenixRefundPanel :max-amount="299" :amount="99" reason-code="not-needed" note="暂不需要" /></article>
    <article><h3>物流跟踪</h3><PhoenixLogisticsTracker carrier="顺丰速运" tracking-number="SF10000001" :events="[{ id: 1, title: '包裹已揽收', time: '08-09 16:20', status: 'complete' }, { id: 2, title: '正在运输中', time: '08-10 09:10', location: '天津转运中心', status: 'current' }, { id: 3, title: '等待派送', status: 'pending' }]" /></article>
    <article><h3>预约时间段</h3><PhoenixTimeSlotPicker v-model="timeSlotId" :slots="[{ id: 'am', label: '上午场', start: '09:00', end: '11:00', remaining: 12 }, { id: 'pm', label: '下午场', start: '14:00', end: '16:00', remaining: 3 }]" /></article>
    <article><h3>房间与座位</h3><PhoenixSeatRoomSelector v-model="selectedSeats" room-id="room-a" :rooms="[{ id: 'room-a', name: '一号放映厅', seats: [{ id: 'A1', label: 'A1', price: 39 }, { id: 'A2', label: 'A2', price: 39 }, { id: 'A3', label: 'A3', status: 'reserved' }] }]" /></article>
    <article><h3>评价编辑</h3><PhoenixReviewComposer :rating="rating" :content="reviewContent" @update:rating="rating = $event" @update:content="reviewContent = $event" /></article>
    <article class="cg-commerce-showcase__inline"><h3>评分与收藏</h3><div><PhoenixRating v-model="rating" :count="128" /><PhoenixFavoriteButton v-model="favorite" :count="86" /></div></article>
  </div>
</template>
