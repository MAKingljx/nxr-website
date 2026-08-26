<script setup lang="ts">
import { ref } from 'vue'
import PhoenixButton from '../primitives/PhoenixButton.vue'
import PhoenixCard from '../primitives/PhoenixCard.vue'
import {
  PhoenixDataTable,
  PhoenixDatePicker,
  PhoenixDialog,
  PhoenixDrawer,
  PhoenixEmpty,
  PhoenixFormItem,
  PhoenixInput,
  PhoenixSelect,
  PhoenixSkeleton,
  PhoenixToast,
} from '../primitives/crud'
import type { PhoenixDataTableColumn, PhoenixTableKey } from '../primitives/crud'

const keyword = ref('')
const category = ref<string | number>()
const date = ref('2026-08-10')
const selectedRows = ref<PhoenixTableKey[]>([])
const sortBy = ref('')
const sortDirection = ref<'asc' | 'desc' | null>(null)
const dialogVisible = ref(false)
const drawerVisible = ref(false)
const toastVisible = ref(false)

const options = [
  { label: '全部分类', value: 'all' },
  { label: '教学资源', value: 'teaching' },
  { label: '图书资源', value: 'library' },
  { label: '旅游内容', value: 'travel' },
]

const columns: PhoenixDataTableColumn[] = [
  { key: 'name', label: '名称', sortable: true },
  { key: 'category', label: '分类' },
  { key: 'status', label: '状态', align: 'center' },
]

const rows = [
  { id: 1, name: '课程资料', category: '教学', status: '已发布' },
  { id: 2, name: '馆藏目录', category: '图书', status: '维护中' },
  { id: 3, name: '目的地指南', category: '旅游', status: '草稿' },
]
</script>

<template>
  <div class="cg-crud-showcase">
    <PhoenixCard title="查询与表单" padding="large" class="cg-demo-card cg-demo-card--wide">
      <div class="cg-form-demo">
        <PhoenixFormItem label="关键词" html-for="crud-keyword">
          <template #default="slotProps">
            <PhoenixInput id="crud-keyword" v-model="keyword" clearable :described-by="slotProps.describedBy" />
          </template>
        </PhoenixFormItem>
        <PhoenixFormItem label="分类" html-for="crud-category">
          <PhoenixSelect id="crud-category" v-model="category" :options="options" clearable />
        </PhoenixFormItem>
        <PhoenixFormItem label="日期" html-for="crud-date">
          <PhoenixDatePicker id="crud-date" v-model="date" clearable />
        </PhoenixFormItem>
      </div>
    </PhoenixCard>

    <PhoenixCard title="数据表格" padding="large" class="cg-demo-card cg-demo-card--wide">
      <PhoenixDataTable
        v-model="selectedRows"
        v-model:sort-by="sortBy"
        v-model:sort-direction="sortDirection"
        :rows="rows"
        :columns="columns"
        selectable
      />
    </PhoenixCard>

    <PhoenixCard title="弹层与消息" padding="large" class="cg-demo-card">
      <div class="cg-component-row">
        <PhoenixButton @click="dialogVisible = true">打开对话框</PhoenixButton>
        <PhoenixButton variant="outline" @click="drawerVisible = true">打开抽屉</PhoenixButton>
        <PhoenixButton variant="ghost" @click="toastVisible = true">显示消息</PhoenixButton>
      </div>
      <PhoenixDialog v-model="dialogVisible" title="确认操作">
        <p>确认后将保存当前配置。</p>
        <template #footer><PhoenixButton @click="dialogVisible = false">确认</PhoenixButton></template>
      </PhoenixDialog>
      <PhoenixDrawer v-model="drawerVisible" title="资源详情">
        <PhoenixFormItem label="资源名称"><PhoenixInput model-value="课程资料" readonly /></PhoenixFormItem>
      </PhoenixDrawer>
      <PhoenixToast v-model="toastVisible" type="success" message="配置已保存" :duration="0" position="bottom-right" />
    </PhoenixCard>

    <PhoenixCard title="空状态与加载" padding="large" class="cg-demo-card">
      <div class="cg-state-pair">
        <PhoenixEmpty compact title="暂无记录"><template #action><PhoenixButton size="small">新建记录</PhoenixButton></template></PhoenixEmpty>
        <PhoenixSkeleton :rows="3" avatar />
      </div>
    </PhoenixCard>
  </div>
</template>
