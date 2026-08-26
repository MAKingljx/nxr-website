<script setup lang="ts">
import { ref } from 'vue'
import PhoenixCard from '../primitives/PhoenixCard.vue'
import {
  PhoenixChartContainer,
  PhoenixFileManager,
  PhoenixMapContainer,
  PhoenixMediaPlayer,
  PhoenixRealtimeFeed,
  PhoenixRichTextEditor,
  PhoenixVirtualList,
} from '../primitives/advanced'

const article = ref('## 项目说明\n请输入正文内容。')
const selectedFiles = ref<Array<string | number>>(['f-1'])
const listItems = Array.from({ length: 120 }, (_, index) => ({ id: index + 1, label: `通用数据项 ${index + 1}` }))
const feedItems = [
  { id: 1, title: '资源更新', message: '课程资料已发布', actor: '系统', timestamp: '刚刚', status: 'success' as const },
  { id: 2, title: '库存提醒', message: '可用数量低于阈值', actor: '运营中心', timestamp: '10 分钟前', status: 'warning' as const },
]
const managedFiles = [
  { id: 'f-1', name: '项目说明.pdf', size: 248000, type: 'PDF', downloadable: true, deletable: true },
  { id: 'f-2', name: '数据模板.xlsx', size: 86000, type: 'Excel', downloadable: true, deletable: true },
]
</script>

<template>
  <div class="cg-advanced-showcase">
    <PhoenixCard title="虚拟列表" padding="large" class="cg-demo-card">
      <PhoenixVirtualList :items="listItems" :height="240" :item-height="42">
        <template #default="slotProps"><div class="cg-virtual-row">{{ slotProps.item.label }}</div></template>
      </PhoenixVirtualList>
    </PhoenixCard>

    <PhoenixCard title="正文编辑" padding="large" class="cg-demo-card">
      <PhoenixRichTextEditor v-model="article" :rows="8" />
    </PhoenixCard>

    <PhoenixCard title="图表容器" padding="large" class="cg-demo-card">
      <PhoenixChartContainer title="月度完成情况">
        <div class="cg-chart-demo" aria-label="示例柱状图"><i style="height: 42%" /><i style="height: 68%" /><i style="height: 84%" /><i style="height: 56%" /><i style="height: 92%" /></div>
      </PhoenixChartContainer>
    </PhoenixCard>

    <PhoenixCard title="媒体播放器" padding="large" class="cg-demo-card">
      <PhoenixMediaPlayer title="媒体播放器" />
    </PhoenixCard>

    <PhoenixCard title="实时动态" padding="large" class="cg-demo-card">
      <PhoenixRealtimeFeed :items="feedItems" show-refresh />
    </PhoenixCard>

    <PhoenixCard title="地图容器" padding="large" class="cg-demo-card">
      <PhoenixMapContainer title="位置分布" :height="240">
        <div class="cg-map-demo" aria-label="地图适配器示例"><span>北京</span><span>上海</span><span>广州</span></div>
      </PhoenixMapContainer>
    </PhoenixCard>

    <PhoenixCard title="文件管理" padding="large" class="cg-demo-card cg-demo-card--wide">
      <PhoenixFileManager v-model:selected-ids="selectedFiles" :files="managedFiles" />
    </PhoenixCard>
  </div>
</template>
