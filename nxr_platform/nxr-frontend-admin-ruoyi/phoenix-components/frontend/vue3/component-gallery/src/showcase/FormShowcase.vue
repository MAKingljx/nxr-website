<script setup lang="ts">
import { ref } from 'vue'
import PhoenixCard from '../primitives/PhoenixCard.vue'
import {
  PhoenixAutocomplete,
  PhoenixColorPicker,
  PhoenixDateRangePicker,
  PhoenixMultiSelect,
  PhoenixNumberInput,
  PhoenixOtpInput,
  PhoenixRate,
  PhoenixSegmented,
  PhoenixSlider,
  PhoenixTextarea,
  PhoenixTimePicker,
  PhoenixTransfer,
} from '../primitives/forms'

const text = ref('欢迎补充资源说明')
const number = ref<number | null>(3)
const multiple = ref<Array<string | number>>(['teaching', 'library'])
const time = ref('14:30')
const range = ref<[string, string]>(['2026-08-10', '2026-08-16'])
const autocomplete = ref('课程资料')
const slider = ref(68)
const rate = ref(4)
const color = ref('#635bff')
const transfer = ref<Array<string | number>>(['audit'])
const segmented = ref<string | number | null>('list')
const otp = ref('268')

const resourceOptions = [
  { label: '教学资源', value: 'teaching' },
  { label: '图书资源', value: 'library' },
  { label: '旅游内容', value: 'travel' },
  { label: '商城商品', value: 'commerce' },
]
const suggestions = [
  { label: '课程资料', value: '课程资料' },
  { label: '馆藏目录', value: '馆藏目录' },
  { label: '目的地指南', value: '目的地指南' },
]
const permissionOptions = [
  { label: '查看', value: 'read' },
  { label: '编辑', value: 'write' },
  { label: '审核', value: 'audit' },
  { label: '删除', value: 'delete' },
]
const viewOptions = [
  { label: '列表', value: 'list' },
  { label: '卡片', value: 'card' },
  { label: '看板', value: 'board' },
]
</script>

<template>
  <div class="cg-form-showcase">
    <PhoenixCard title="文本与数字" padding="large" class="cg-demo-card">
      <div class="cg-control-stack">
        <PhoenixTextarea v-model="text" label="资源说明" :max-length="80" show-count />
        <PhoenixNumberInput v-model="number" label="借阅数量" :min="1" :max="20" />
      </div>
    </PhoenixCard>

    <PhoenixCard title="选择与补全" padding="large" class="cg-demo-card">
      <div class="cg-control-stack">
        <PhoenixMultiSelect v-model="multiple" :options="resourceOptions" label="资源分类" :size="4" />
        <PhoenixAutocomplete v-model="autocomplete" :options="suggestions" label="资源名称" />
      </div>
    </PhoenixCard>

    <PhoenixCard title="日期与时间" padding="large" class="cg-demo-card cg-demo-card--wide">
      <div class="cg-control-row">
        <PhoenixDateRangePicker v-model="range" label="预约日期" />
        <PhoenixTimePicker v-model="time" label="预约时间" min="08:00" max="22:00" />
      </div>
    </PhoenixCard>

    <PhoenixCard title="数值与外观" padding="large" class="cg-demo-card">
      <div class="cg-control-stack">
        <PhoenixSlider v-model="slider" label="完成进度" />
        <PhoenixRate v-model="rate" label="资源评分" />
        <PhoenixColorPicker v-model="color" label="主题颜色" />
      </div>
    </PhoenixCard>

    <PhoenixCard title="视图与验证码" padding="large" class="cg-demo-card">
      <div class="cg-control-stack">
        <PhoenixSegmented v-model="segmented" :options="viewOptions" label="展示方式" full-width />
        <PhoenixOtpInput v-model="otp" label="登录验证码" :length="6" />
      </div>
    </PhoenixCard>

    <PhoenixCard title="权限分配" padding="large" class="cg-demo-card cg-demo-card--wide">
      <PhoenixTransfer v-model="transfer" :options="permissionOptions" source-title="可选权限" target-title="已选权限" />
    </PhoenixCard>
  </div>
</template>
