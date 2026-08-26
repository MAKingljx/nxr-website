<script setup lang="ts">
import { ref } from 'vue'
import PhoenixButton from '../primitives/PhoenixButton.vue'
import PhoenixCard from '../primitives/PhoenixCard.vue'
import {
  PhoenixAvatar,
  PhoenixBadge,
  PhoenixCascader,
  PhoenixCheckbox,
  PhoenixDescriptions,
  PhoenixFileUpload,
  PhoenixNotification,
  PhoenixRadioGroup,
  PhoenixSteps,
  PhoenixTooltip,
  PhoenixTree,
} from '../primitives/interaction'

const checked = ref(true)
const radio = ref<string | number | null>('list')
const files = ref<File[]>([])
const treeValue = ref<string | number | null>('team-a')
const expandedValues = ref<Array<string | number>>(['org'])
const cascadeValue = ref<Array<string | number>>(['north', 'beijing'])
const step = ref(1)
const notificationVisible = ref(true)

const radioOptions = [
  { label: '列表', value: 'list' },
  { label: '卡片', value: 'card' },
]

const treeNodes = [
  { label: '总部', value: 'org', children: [{ label: '产品组', value: 'team-a' }, { label: '运营组', value: 'team-b' }] },
  { label: '区域中心', value: 'region' },
]

const cascadeOptions = [
  { label: '华北', value: 'north', children: [{ label: '北京', value: 'beijing' }, { label: '天津', value: 'tianjin' }] },
  { label: '华东', value: 'east', children: [{ label: '上海', value: 'shanghai' }] },
]

const steps = [{ title: '创建' }, { title: '审核' }, { title: '完成' }]
const details = [
  { label: '资源名称', value: '课程资料' },
  { label: '所属分类', value: '教学资源' },
  { label: '当前状态', value: '已发布' },
]
</script>

<template>
  <div class="cg-interaction-showcase">
    <PhoenixCard title="选择控件" padding="large" class="cg-demo-card">
      <div class="cg-selection-demo">
        <PhoenixCheckbox v-model="checked" label="启用通知" />
        <PhoenixRadioGroup v-model="radio" :options="radioOptions" />
      </div>
    </PhoenixCard>

    <PhoenixCard title="文件选择" padding="large" class="cg-demo-card">
      <PhoenixFileUpload v-model="files" accept=".pdf,.docx,.xlsx" :limit="3" multiple />
    </PhoenixCard>

    <PhoenixCard title="树形选择" padding="large" class="cg-demo-card">
      <PhoenixTree v-model="treeValue" v-model:expanded-values="expandedValues" :nodes="treeNodes" />
    </PhoenixCard>

    <PhoenixCard title="级联选择" padding="large" class="cg-demo-card">
      <PhoenixCascader v-model="cascadeValue" :options="cascadeOptions" />
    </PhoenixCard>

    <PhoenixCard title="步骤流程" padding="large" class="cg-demo-card cg-demo-card--wide">
      <PhoenixSteps v-model="step" :items="steps" clickable />
    </PhoenixCard>

    <PhoenixCard title="详细信息" padding="large" class="cg-demo-card cg-demo-card--wide">
      <PhoenixDescriptions :items="details" :columns="3" bordered />
    </PhoenixCard>

    <PhoenixCard title="身份与标记" padding="large" class="cg-demo-card">
      <div class="cg-component-row">
        <PhoenixAvatar name="张明" status="online" />
        <PhoenixAvatar name="李华" status="busy" />
        <PhoenixBadge :value="8"><PhoenixButton variant="outline">待办事项</PhoenixButton></PhoenixBadge>
        <PhoenixTooltip content="查看资源的完整信息"><PhoenixButton variant="ghost">悬停提示</PhoenixButton></PhoenixTooltip>
      </div>
    </PhoenixCard>

    <PhoenixCard title="通知" padding="large" class="cg-demo-card">
      <PhoenixNotification v-model="notificationVisible" title="审核完成" message="资源已进入发布队列" variant="success" action-text="查看" />
      <PhoenixButton v-if="!notificationVisible" size="small" @click="notificationVisible = true">重新显示</PhoenixButton>
    </PhoenixCard>
  </div>
</template>
