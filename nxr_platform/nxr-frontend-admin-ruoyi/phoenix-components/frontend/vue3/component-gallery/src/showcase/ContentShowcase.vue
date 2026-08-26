<script setup lang="ts">
import { ref } from 'vue'
import {
  PhoenixActivityFeed,
  PhoenixAnnouncementBanner,
  PhoenixCommentComposer,
  PhoenixDocumentPreview,
  PhoenixFileDropzone,
  PhoenixImagePreview,
  PhoenixMediaGallery,
  PhoenixMentionInput,
  PhoenixMessageInbox,
  PhoenixNotificationCenter,
} from '../primitives/content'

const notificationFilter = ref<'all' | 'unread'>('all')
const selectedThread = ref<string | number>('m1')
const query = ref('')
const comment = ref('')
const mention = ref('正在与 @王')
const selectedMedia = ref<string | number>('cover')
const notifications = [
  { id: 1, title: '审批任务已通过', description: '项目发布申请已经完成审核。', timestamp: '10 分钟前', tone: 'success' as const, read: false, actionLabel: '查看' },
  { id: 2, title: '库存数量偏低', description: '城市通勤包库存剩余 18 件。', timestamp: '1 小时前', tone: 'warning' as const, read: false },
  { id: 3, title: '月度报表已生成', timestamp: '昨天', read: true, dismissible: true },
]
const threads = [
  { id: 'm1', sender: '王芳', subject: '项目发布确认', preview: '组件测试已全部通过，可以安排发布。', timestamp: '10:28', unread: true, starred: true, messageCount: 3 },
  { id: 'm2', sender: '系统通知', subject: '安全策略更新', preview: '登录会话策略将在今晚生效。', timestamp: '昨天' },
]
const activities = [
  { id: 1, actor: '李明', action: '更新了', target: '商品资料', timestamp: '刚刚', tone: 'info' as const },
  { id: 2, actor: '王芳', action: '完成了', target: '订单审核', timestamp: '20 分钟前', tone: 'success' as const },
  { id: 3, actor: '系统', action: '生成了', target: '月度统计', timestamp: '1 小时前' },
]
const media = [
  { id: 'cover', kind: 'image' as const, title: '商品主图', metadata: '1200 × 1200' },
  { id: 'detail', kind: 'image' as const, title: '详情配图', metadata: '1600 × 900' },
  { id: 'video', kind: 'video' as const, title: '产品介绍', duration: '02:36', metadata: '1080P' },
]
const pixel = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL4WQAAAABJRU5ErkJggg=='
</script>

<template>
  <div class="cg-content-showcase">
    <div class="cg-content-banners"><PhoenixAnnouncementBanner title="系统维护通知" description="今晚 23:00 进行例行维护。" action-label="查看安排" appearance="modern" /><PhoenixAnnouncementBanner title="数据同步完成" tone="success" appearance="soft" /><PhoenixAnnouncementBanner title="请处理风险提醒" tone="warning" appearance="minimal" /></div>
    <div class="cg-content-grid">
      <PhoenixNotificationCenter v-model:filter="notificationFilter" :items="notifications" />
      <PhoenixMessageInbox v-model:selected-id="selectedThread" v-model:query="query" :threads="threads" appearance="soft" />
      <PhoenixActivityFeed :items="activities" appearance="minimal" />
      <PhoenixCommentComposer v-model="comment" reply-to="王芳" :attachments="[{ id: 1, name: '需求说明.pdf', size: 268000 }]" />
      <PhoenixMentionInput v-model="mention" :suggestions="[{ id: 1, label: '王芳', handle: 'wangfang', description: '产品中心' }, { id: 2, label: '王明', handle: 'wangming', description: '技术中心' }]" appearance="soft" />
      <PhoenixDocumentPreview :document="{ id: 1, name: '组件接入说明.pdf', size: 368000, mimeType: 'application/pdf', pages: 18, owner: '产品中心', updatedAt: '今天 09:30', url: 'https://example.com/document.pdf' }" downloadable appearance="minimal" />
    </div>
    <PhoenixMediaGallery v-model:selected-id="selectedMedia" :items="media" />
    <div class="cg-content-media-row"><PhoenixImagePreview open :src="pixel" title="商品主图预览" caption="安全图片地址与受控缩放" has-next /><PhoenixFileDropzone accept="image/*,.pdf" :max-files="5" appearance="soft" /></div>
  </div>
</template>
