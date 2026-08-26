<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { CatalogItem } from './types'
import AdvancedShowcase from './showcase/AdvancedShowcase.vue'
import AdminShowcase from './showcase/AdminShowcase.vue'
import AnalyticsShowcase from './showcase/AnalyticsShowcase.vue'
import AuthShowcase from './showcase/AuthShowcase.vue'
import BusinessShowcase from './showcase/BusinessShowcase.vue'
import CommerceShowcase from './showcase/CommerceShowcase.vue'
import ContentShowcase from './showcase/ContentShowcase.vue'
import CrudShowcase from './showcase/CrudShowcase.vue'
import FormShowcase from './showcase/FormShowcase.vue'
import InteractionShowcase from './showcase/InteractionShowcase.vue'
import LiveShowcase from './showcase/LiveShowcase.vue'
import ManagementShowcase from './showcase/ManagementShowcase.vue'
import MarketingShowcase from './showcase/MarketingShowcase.vue'
import PatternShowcase from './showcase/PatternShowcase.vue'
import PlatformShowcase from './showcase/PlatformShowcase.vue'
import SolutionShowcase from './showcase/SolutionShowcase.vue'
import ThemeShowcase from './showcase/ThemeShowcase.vue'
import WorkspaceShowcase from './showcase/WorkspaceShowcase.vue'
import { ProductShowcasePage } from './products'
import { ComponentRequestShowcase } from './requests'
import { FeedbackShowcase } from './feedback'
import {
  PhoenixAlert,
  PhoenixBreadcrumb,
  PhoenixButton,
  PhoenixCard,
  PhoenixDivider,
  PhoenixPagination,
  PhoenixSearch,
  PhoenixSwitch,
  PhoenixTabs,
  PhoenixTag,
  PhoenixThemeProvider,
} from './primitives'
import type { PhoenixTheme } from './primitives'

const props = withDefaults(
  defineProps<{
    items: CatalogItem[]
    title?: string
    subtitle?: string
  }>(),
  {
    title: 'Phoenix 组件展厅',
    subtitle: '打开页面即可查看和体验所有前端组件',
  },
)

const emit = defineEmits<{
  copy: [command: string, item: CatalogItem]
}>()

const searchValue = ref('')
const activeTab = ref('overview')
const currentPage = ref(3)
const notificationEnabled = ref(true)
const compactMode = ref(false)
const copiedId = ref('')
const selectedTheme = ref<PhoenixTheme>('modern')

const themes: Array<{ id: PhoenixTheme; label: string }> = [
  { id: 'modern', label: '现代' },
  { id: 'business', label: '商务' },
  { id: 'minimal', label: '极简' },
  { id: 'festive', label: '节庆' },
]

const componentNavigationGroups = [
  {
    id: 'foundation',
    label: '常用基础',
    items: [
      { id: 'actions', label: '按钮与操作' },
      { id: 'navigation', label: '导航组件' },
      { id: 'inputs', label: '输入与搜索' },
      { id: 'layout', label: '分隔与布局' },
      { id: 'themes', label: '主题与外观' },
      { id: 'display', label: '数据展示' },
      { id: 'feedback', label: '反馈状态' },
    ],
  },
  {
    id: 'data',
    label: '数据与表单',
    items: [
      { id: 'crud', label: 'CRUD 基础' },
      { id: 'interaction', label: '常用交互' },
      { id: 'advanced', label: '高级组件' },
      { id: 'analytics', label: '数据可视化' },
      { id: 'forms', label: '高频表单' },
      { id: 'auth', label: '认证与权限' },
      { id: 'platform', label: '平台与布局' },
    ],
  },
  {
    id: 'business',
    label: '业务场景',
    items: [
      { id: 'business', label: '业务组合' },
      { id: 'marketing', label: '营销与社区' },
      { id: 'content', label: '内容与消息' },
      { id: 'admin', label: '系统后台核心' },
      { id: 'commerce', label: '商城与预约' },
      { id: 'live', label: '直播业务' },
    ],
  },
  {
    id: 'pages',
    label: '页面与工作台',
    items: [
      { id: 'patterns', label: '页面框架' },
      { id: 'management', label: '通用管理页' },
      { id: 'workspace', label: '工作台页面' },
      { id: 'solutions', label: '成品页面组合' },
    ],
  },
  {
    id: 'engineering',
    label: '工程目录',
    items: [{ id: 'engineering', label: '工程组件' }],
  },
]
const componentNavigation = componentNavigationGroups.flatMap((group) => group.items)
const templateNavigation = [{ id: 'templates', label: '全部模板' }]
const productNavigation = [{ id: 'products', label: '成品系统与软件' }]
const requestNavigation = [
  { id: 'requests', label: '组件需求清单' },
  { id: 'usage-feedback', label: '使用反馈' },
]
const showcaseGroups = [
  ...componentNavigation,
  ...templateNavigation,
  ...productNavigation,
  ...requestNavigation,
]
const navigationIds = new Set(showcaseGroups.map((item) => item.id))
const initialNavigation = typeof window === 'undefined'
  ? 'actions'
  : window.location.hash.replace(/^#/, '')
const activeNavigation = ref(navigationIds.has(initialNavigation) ? initialNavigation : 'actions')
const openNavigationGroup = ref(
  componentNavigationGroups.find((group) => (
    group.items.some((item) => item.id === activeNavigation.value)
  ))?.id ?? 'foundation',
)

const tabs = [
  { label: '概览', value: 'overview' },
  { label: '待处理', value: 'pending', badge: 8 },
  { label: '已完成', value: 'completed' },
  { label: '已归档', value: 'archived', disabled: true },
]

const breadcrumbs = [
  { label: '首页', href: '#top' },
  { label: '组件库', href: '#actions' },
  { label: '导航组件' },
]

const categoryLabels: Record<string, string> = {
  frontend: '前端组件',
  backend: '后端组件',
  desktop: '桌面端组件',
  common: '通用能力',
  templates: '工程模板',
  service: '服务接口',
}

const templateCategoryLabels: Record<string, string> = {
  frontend: '前端模板',
  backend: '后端模板',
  desktop: '桌面端模板',
  common: '通用模板',
  templates: '工程模板',
}

const statusLabels: Record<string, string> = {
  stable: '稳定',
  experimental: '试用',
  template: '模板',
  deprecated: '已弃用',
}

const capabilityLabels: Record<string, string> = {
  authentication: '身份认证',
  authorization: '权限控制',
  rbac: '角色权限',
  'bearer-sessions': '会话管理',
  'admin-bootstrap': '管理员初始化',
  'password-hashing': '口令哈希',
  'typed-client': '类型化客户端',
  audit: '操作审计',
  'resource-catalog': '资源目录',
  'resource-management': '资源管理',
  inventory: '库存与配额',
  order: '订单管理',
  idempotency: '幂等处理',
  notification: '消息通知',
  'payment-provider': '支付服务接口',
  'recommendation-provider': '推荐服务接口',
  'live-provider': '直播服务接口',
  'application-blueprint': '应用蓝图',
  'capability-composition': '能力组合',
  'provider-boundary': '服务边界',
  dashboard: '数据看板',
  'responsive-list': '响应式列表',
  'search-filter': '搜索筛选',
  'component-catalog': '组件目录',
  'component-discovery': '组件发现',
  'component-preview': '组件预览',
  'schema-form': '配置表单',
  'form-validation': '表单校验',
  'responsive-form': '响应式表单',
  button: '按钮',
  navigation: '导航',
  'search-input': '搜索框',
  divider: '分割线',
  tabs: '标签页',
  pagination: '分页',
  'form-control': '表单控件',
  'data-display': '数据展示',
  'status-feedback': '状态反馈',
  'data-table': '数据表格',
  'text-input': '输入框',
  'select-control': '下拉选择',
  'form-item': '表单项',
  'date-picker': '日期选择',
  dialog: '对话框',
  drawer: '抽屉',
  toast: '消息提示',
  'empty-state': '空状态',
  'skeleton-loading': '骨架加载',
  checkbox: '复选框',
  radio: '单选框',
  'file-upload-control': '文件选择',
  tree: '树形选择',
  cascader: '级联选择',
  steps: '步骤流程',
  descriptions: '描述列表',
  avatar: '头像',
  badge: '徽标',
  tooltip: '文字提示',
  'notification-ui': '通知',
  'virtual-list': '虚拟列表',
  'rich-text-editor': '正文编辑',
  'chart-container': '图表容器',
  'media-player': '媒体播放器',
  'realtime-feed': '实时动态',
  'map-container': '地图容器',
  'file-manager': '文件管理',
  textarea: '多行文本',
  'number-input': '数字输入',
  'multi-select': '多项选择',
  'time-picker': '时间选择',
  'date-range-picker': '日期范围',
  autocomplete: '自动补全',
  slider: '滑块',
  rate: '评分',
  'color-picker': '颜色选择',
  transfer: '穿梭选择',
  'segmented-control': '分段选择',
  'otp-input': '验证码输入',
  'app-shell': '应用框架',
  'side-menu': '侧边菜单',
  'top-bar': '顶部导航',
  'page-header': '页面标题',
  dropdown: '下拉菜单',
  popover: '气泡内容',
  collapse: '折叠面板',
  timeline: '时间线',
  progress: '进度展示',
  result: '结果页面',
  calendar: '日历',
  kanban: '任务看板',
  'resource-card': '资源卡片',
  'price-display': '价格展示',
  'quantity-stepper': '数量选择',
  'cart-summary': '购物车汇总',
  'order-timeline': '订单进度',
  'payment-status': '支付状态',
  'booking-summary': '预约摘要',
  'course-progress': '学习进度',
  'chat-panel': '聊天面板',
  'participant-list': '成员列表',
  'stream-status': '直播状态',
  'recommendation-list': '推荐列表',
  'dashboard-page': '工作台页面',
  'resource-management-page': '资源管理页面',
  'checkout-page': '结算页面',
  'booking-page': '预约页面',
  'learning-page': '学习页面',
  'live-room-page': '直播间页面',
  'login-panel': '登录面板',
  'user-menu': '用户菜单',
  'permission-guard': '权限守卫',
  'role-permission-matrix': '角色权限矩阵',
  'lucky-draw': '抽奖',
  'bargain-campaign': '好友助力砍价',
  'community-comments': '社区评论',
  'product-card': '商品卡片',
  'advanced-table': '高级表格',
  'query-panel': '查询条件',
  'batch-actions': '批量操作',
  'data-import-export': '导入导出',
  'tree-select': '树形选择',
  'organization-tree': '组织架构',
  'audit-log-viewer': '审计日志查看',
  'approval-panel': '审批操作',
  'attachment-preview': '附件预览',
  'user-profile': '用户资料',
  'password-change': '修改密码',
  'session-management': '会话管理',
  'sku-editor': '商品规格',
  'inventory-table': '库存管理表',
  'address-selector': '地址选择',
  'address-form': '地址表单',
  'coupon-selector': '优惠券选择',
  'payment-method-selector': '支付方式选择',
  'refund-panel': '退款申请',
  'logistics-tracker': '物流跟踪',
  'time-slot-picker': '预约时段',
  'seat-room-selector': '座位房间选择',
  'review-composer': '评价编辑',
  'rating-control': '评分',
  'favorite-control': '收藏',
  'live-console': '直播控制台',
  'live-product-shelf': '直播商品货架',
  'danmaku-layer': '弹幕层',
  'moderation-queue': '消息审核队列',
  'member-management': '成员管理',
  'replay-list': '回放列表',
  'live-metrics': '直播指标',
  'user-management-page': '用户管理页',
  'role-permission-page': '角色权限管理页',
  'department-management-page': '部门管理页',
  'dictionary-configuration-page': '字典配置页',
  'audit-log-page': '审计日志页',
  'product-management-page': '商品管理页',
  'order-management-page': '订单管理页',
  'inventory-management-page': '库存管理页',
  'booking-management-page': '预约管理页',
  'notification-center': '通知中心',
  'message-inbox': '消息收件箱',
  'announcement-banner': '公告栏',
  'activity-feed': '动态列表',
  'comment-composer': '评论输入',
  'mention-input': '成员提及',
  'media-gallery': '媒体图库',
  'image-preview': '图片预览',
  'document-preview': '文档预览',
  'file-dropzone': '文件拖放',
  'metric-card': '指标卡片',
  'trend-chart': '趋势图',
  'bar-chart': '柱状图',
  'line-chart': '折线图',
  'donut-chart': '环形图',
  'funnel-chart': '漏斗图',
  'ranking-list': '排行列表',
  'data-summary': '数据摘要',
  'dashboard-filter': '看板筛选',
  'chart-legend': '图表图例',
  'workspace-home-page': '工作台首页',
  'message-center-page': '消息中心页',
  'file-center-page': '文件中心页',
  'profile-settings-page': '个人设置页',
  'system-settings-page': '系统设置页',
  'admin-workspace-page': '后台管理成品页',
  'analytics-dashboard-page': '数据看板成品页',
  'content-workspace-page': '内容工作台成品页',
  'app-factory': '应用工厂',
  'health-check': '健康检查',
  'resource-crud': '资源增删改查',
  'sqlite-persistence': 'SQLite 持久化',
  'sql-migrations': 'SQL 迁移',
  'layered-architecture': '分层架构',
  'api-error-contract': '接口错误规范',
}

function groupedEngineeringItems(items: CatalogItem[], labels = categoryLabels) {
  const order = ['frontend', 'backend', 'common', 'service', 'desktop', 'templates']
  return order
    .map((category) => ({
      category,
      label: labels[category],
      items: items.filter((item) => item.category === category),
    }))
    .filter((group) => group.items.length)
}

function isTemplateSystem(item: CatalogItem) {
  return item.status === 'template' || item.kind === 'project-template'
}

const engineeringGroups = computed(() => groupedEngineeringItems(props.items.filter((item) => !isTemplateSystem(item))))
const templateSystemGroups = computed(() => groupedEngineeringItems(props.items.filter(isTemplateSystem), templateCategoryLabels))

const uniqueCapabilities = computed(() => new Set(props.items.flatMap((item) => item.capabilities)).size)

function pullCommand(item: CatalogItem) {
  return `python3 src/pcl.py pull ${item.id}`
}

async function copyCommand(item: CatalogItem) {
  const command = pullCommand(item)
  try {
    await navigator.clipboard?.writeText(command)
    copiedId.value = item.id
  } catch {
    copiedId.value = ''
  }
  emit('copy', command, item)
}

function capabilityLabel(value: string) {
  return capabilityLabels[value] ?? value
}

function toggleNavigationGroup(groupId: string) {
  openNavigationGroup.value = openNavigationGroup.value === groupId ? '' : groupId
}

function activateNavigation(sectionId: string) {
  activeNavigation.value = sectionId
  const group = componentNavigationGroups.find((item) => (
    item.items.some((navigation) => navigation.id === sectionId)
  ))
  if (group) openNavigationGroup.value = group.id
}

async function showNavigation(sectionId: string) {
  activateNavigation(sectionId)
  window.history.replaceState(null, '', `#${sectionId}`)
  await nextTick()
  const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
  document.getElementById(sectionId)?.scrollIntoView?.({
    behavior: reduceMotion ? 'auto' : 'smooth',
    block: 'start',
  })
}

function jumpToNavigation(event: Event) {
  const sectionId = (event.target as HTMLSelectElement).value
  if (!sectionId) return
  void showNavigation(sectionId)
}

function syncNavigationFromHash() {
  const sectionId = window.location.hash.replace(/^#/, '')
  if (navigationIds.has(sectionId)) activateNavigation(sectionId)
}

onMounted(() => window.addEventListener('hashchange', syncNavigationFromHash))
onBeforeUnmount(() => window.removeEventListener('hashchange', syncNavigationFromHash))
</script>

<template>
  <PhoenixThemeProvider id="top" class="cg-page" :theme="selectedTheme">
    <header class="cg-hero">
      <div class="cg-hero__bar">
        <a class="cg-brand" href="#top" aria-label="返回组件展厅顶部">
          <span class="cg-brand__mark" aria-hidden="true"><i /><i /><i /></span>
          <span><strong>{{ title }}</strong><small>PHOENIX 设计系统</small></span>
        </a>
        <div class="cg-hero__actions">
          <PhoenixTag variant="primary" rounded>Vue 3 通用组件</PhoenixTag>
          <div class="cg-theme-picker" role="radiogroup" aria-label="页面主题">
            <button v-for="theme in themes" :key="theme.id" type="button" role="radio" :aria-checked="selectedTheme === theme.id" @click="selectedTheme = theme.id">{{ theme.label }}</button>
          </div>
        </div>
      </div>
      <div class="cg-hero__content">
        <div>
          <p class="cg-eyebrow">现代化 · 可复用 · 可单独拉取</p>
          <h1>{{ subtitle }}</h1>
        </div>
        <div class="cg-hero__stats" aria-label="组件统计">
          <span><strong>163</strong><small>前端组件</small></span>
          <span><strong>{{ showcaseGroups.length }}</strong><small>展示分类</small></span>
          <span><strong>{{ items.length }}</strong><small>工程项目</small></span>
          <span><strong>{{ uniqueCapabilities }}</strong><small>通用能力</small></span>
        </div>
      </div>
    </header>

    <div class="cg-body">
      <nav class="cg-category-nav" aria-label="展厅分类导航">
        <div class="cg-category-nav__desktop">
          <section class="cg-category-nav__group" aria-labelledby="component-navigation-title">
            <h2 id="component-navigation-title">组件</h2>
            <div
              v-for="group in componentNavigationGroups"
              :key="group.id"
              class="cg-navigation-disclosure"
            >
              <button
                type="button"
                class="cg-navigation-disclosure__toggle"
                :aria-expanded="openNavigationGroup === group.id"
                :aria-controls="`navigation-group-${group.id}`"
                data-navigation-disclosure
                @click="toggleNavigationGroup(group.id)"
              >
                <strong>{{ group.label }}</strong>
                <span aria-hidden="true">⌄</span>
              </button>
              <div
                v-show="openNavigationGroup === group.id"
                :id="`navigation-group-${group.id}`"
                class="cg-navigation-disclosure__panel"
              >
                <a
                  v-for="item in group.items"
                  :key="item.id"
                  :href="`#${item.id}`"
                  :class="{ 'is-active': activeNavigation === item.id }"
                  :aria-current="activeNavigation === item.id ? 'location' : undefined"
                  @click.prevent="showNavigation(item.id)"
                >
                  <strong>{{ item.label }}</strong>
                </a>
              </div>
            </div>
          </section>
          <section class="cg-category-nav__group" aria-labelledby="template-navigation-title">
            <h2 id="template-navigation-title">模板系统</h2>
            <a
              v-for="group in templateNavigation"
              :key="group.id"
              :href="`#${group.id}`"
              :class="{ 'is-active': activeNavigation === group.id }"
              :aria-current="activeNavigation === group.id ? 'location' : undefined"
              @click.prevent="showNavigation(group.id)"
            >
              <strong>{{ group.label }}</strong>
            </a>
          </section>
          <section class="cg-category-nav__group" aria-labelledby="product-navigation-title">
            <h2 id="product-navigation-title">成品系统</h2>
            <a
              v-for="item in productNavigation"
              :key="item.id"
              :href="`#${item.id}`"
              :class="{ 'is-active': activeNavigation === item.id }"
              :aria-current="activeNavigation === item.id ? 'location' : undefined"
              @click.prevent="showNavigation(item.id)"
            ><strong>{{ item.label }}</strong></a>
          </section>
          <section class="cg-category-nav__group" aria-labelledby="request-navigation-title">
            <h2 id="request-navigation-title">组件需求</h2>
            <a
              v-for="item in requestNavigation"
              :key="item.id"
              :href="`#${item.id}`"
              :class="{ 'is-active': activeNavigation === item.id }"
              :aria-current="activeNavigation === item.id ? 'location' : undefined"
              @click.prevent="showNavigation(item.id)"
            ><strong>{{ item.label }}</strong></a>
          </section>
        </div>

        <div class="cg-category-nav__mobile">
          <label for="gallery-category-jump">分类</label>
          <select
            id="gallery-category-jump"
            :value="activeNavigation"
            aria-label="跳转到组件分类"
            @change="jumpToNavigation"
          >
            <optgroup
              v-for="group in componentNavigationGroups"
              :key="group.id"
              :label="group.label"
            >
              <option v-for="item in group.items" :key="item.id" :value="item.id">
                {{ item.label }}
              </option>
            </optgroup>
            <optgroup label="模板系统">
              <option v-for="item in templateNavigation" :key="item.id" :value="item.id">
                {{ item.label }}
              </option>
            </optgroup>
            <optgroup label="成品系统">
              <option v-for="item in productNavigation" :key="item.id" :value="item.id">
                {{ item.label }}
              </option>
            </optgroup>
            <optgroup label="组件需求">
              <option v-for="item in requestNavigation" :key="item.id" :value="item.id">
                {{ item.label }}
              </option>
            </optgroup>
          </select>
        </div>
      </nav>

      <main class="cg-content">
        <section v-if="activeNavigation === 'actions'" id="actions" class="cg-section" data-group="按钮与操作">
          <header class="cg-section__header"><span>01</span><div><h2>按钮与操作</h2><p>覆盖主要操作、次要操作、危险操作、尺寸、禁用和加载状态。</p></div></header>
          <div class="cg-showcase-grid">
            <PhoenixCard title="按钮类型" subtitle="不同业务层级使用清晰的视觉优先级" padding="large" class="cg-demo-card cg-demo-card--wide">
              <div class="cg-component-row">
                <PhoenixButton>主要操作</PhoenixButton>
                <PhoenixButton variant="secondary">次要操作</PhoenixButton>
                <PhoenixButton variant="outline">描边按钮</PhoenixButton>
                <PhoenixButton variant="ghost">文字按钮</PhoenixButton>
                <PhoenixButton variant="danger">危险操作</PhoenixButton>
              </div>
              <PhoenixDivider text="尺寸与状态" />
              <div class="cg-component-row cg-component-row--end">
                <PhoenixButton size="small">小型按钮</PhoenixButton>
                <PhoenixButton>中型按钮</PhoenixButton>
                <PhoenixButton size="large">大型按钮</PhoenixButton>
                <PhoenixButton disabled>不可操作</PhoenixButton>
                <PhoenixButton loading>提交中</PhoenixButton>
              </div>
            </PhoenixCard>
            <PhoenixCard title="状态标签" subtitle="用于分类、状态和轻量操作" padding="large" class="cg-demo-card">
              <div class="cg-component-row">
                <PhoenixTag variant="primary">主要</PhoenixTag>
                <PhoenixTag variant="success">已完成</PhoenixTag>
                <PhoenixTag variant="warning">待处理</PhoenixTag>
                <PhoenixTag variant="danger">有风险</PhoenixTag>
                <PhoenixTag>普通标签</PhoenixTag>
                <PhoenixTag closable>可关闭</PhoenixTag>
              </div>
            </PhoenixCard>
          </div>
        </section>

        <section v-if="activeNavigation === 'navigation'" id="navigation" class="cg-section" data-group="导航组件">
          <header class="cg-section__header"><span>02</span><div><h2>导航组件</h2><p>直接展示面包屑、标签页和分页，适配管理后台与内容型页面。</p></div></header>
          <div class="cg-showcase-grid">
            <PhoenixCard title="面包屑导航" subtitle="呈现当前位置与返回路径" padding="large" class="cg-demo-card">
              <PhoenixBreadcrumb :items="breadcrumbs" />
            </PhoenixCard>
            <PhoenixCard title="内容标签页" subtitle="支持徽标、禁用和键盘切换" padding="large" class="cg-demo-card">
              <PhoenixTabs v-model="activeTab" :items="tabs" />
              <p class="cg-demo-result">当前栏目：{{ tabs.find((item) => item.value === activeTab)?.label }}</p>
            </PhoenixCard>
            <PhoenixCard title="分页导航" subtitle="根据数据总量自动生成页码" padding="large" class="cg-demo-card cg-demo-card--wide">
              <PhoenixPagination v-model="currentPage" :total="128" :page-size="10" />
            </PhoenixCard>
          </div>
        </section>

        <section v-if="activeNavigation === 'inputs'" id="inputs" class="cg-section" data-group="输入与搜索">
          <header class="cg-section__header"><span>03</span><div><h2>输入与搜索</h2><p>适合资源列表、业务查询和功能配置的高频输入控件。</p></div></header>
          <div class="cg-showcase-grid">
            <PhoenixCard title="搜索框" subtitle="支持双向绑定、清空、回车搜索和加载状态" padding="large" class="cg-demo-card cg-demo-card--wide">
              <div class="cg-search-examples">
                <div><small>默认状态</small><PhoenixSearch v-model="searchValue" placeholder="搜索组件名称或通用能力" /></div>
                <div><small>加载状态</small><PhoenixSearch model-value="正在查找相关组件" loading /></div>
                <div><small>禁用状态</small><PhoenixSearch model-value="当前不可搜索" disabled /></div>
              </div>
              <p class="cg-demo-result">当前输入：{{ searchValue || '暂无内容' }}</p>
            </PhoenixCard>
            <PhoenixCard title="开关控件" padding="large" class="cg-demo-card">
              <div class="cg-switch-list">
                <div><strong>状态通知</strong><PhoenixSwitch v-model="notificationEnabled" label="状态通知" active-text="已开启" inactive-text="已关闭" /></div>
                <div><strong>紧凑模式</strong><PhoenixSwitch v-model="compactMode" label="紧凑模式" active-text="已开启" inactive-text="已关闭" /></div>
                <div><strong>系统设置</strong><PhoenixSwitch :model-value="true" label="系统设置" disabled /></div>
              </div>
            </PhoenixCard>
          </div>
        </section>

        <section v-if="activeNavigation === 'layout'" id="layout" class="cg-section" data-group="分隔与布局">
          <header class="cg-section__header"><span>04</span><div><h2>分隔与布局</h2><p>通过分割线和内容卡片建立清晰、稳定的页面信息层级。</p></div></header>
          <div class="cg-showcase-grid">
            <PhoenixCard title="分割线" subtitle="普通、带文字、虚线和垂直分隔" padding="large" class="cg-demo-card">
              <p class="cg-sample-copy">第一组内容</p>
              <PhoenixDivider />
              <p class="cg-sample-copy">第二组内容</p>
              <PhoenixDivider text="或者" />
              <p class="cg-sample-copy">第三组内容</p>
              <PhoenixDivider dashed text="补充信息" content-position="left" />
              <div class="cg-vertical-divider-demo"><span>概览</span><PhoenixDivider direction="vertical" /><span>配置</span><PhoenixDivider direction="vertical" /><span>记录</span></div>
            </PhoenixCard>
            <PhoenixCard title="内容卡片" subtitle="支持标题、扩展操作、页脚和悬浮层级" elevated padding="large" class="cg-demo-card">
              <template #extra><PhoenixTag variant="success" size="small">运行正常</PhoenixTag></template>
              <p class="cg-sample-copy">卡片用于承载一组相对独立的业务信息，可组合任意表单、列表或统计内容。</p>
              <template #footer><div class="cg-card-footer"><span>刚刚更新</span><PhoenixButton variant="ghost" size="small">查看详情</PhoenixButton></div></template>
            </PhoenixCard>
          </div>
        </section>

        <section v-if="activeNavigation === 'themes'" id="themes" class="cg-section" data-group="主题与外观">
          <header class="cg-section__header"><span>05</span><div><h2>主题与外观</h2><p>一处设置即可统一组件的品牌色、表面、边框、文字和状态色。</p></div></header>
          <ThemeShowcase />
        </section>

        <section v-if="activeNavigation === 'display'" id="display" class="cg-section" data-group="数据展示">
          <header class="cg-section__header"><span>06</span><div><h2>数据展示</h2><p>将关键指标、趋势和业务状态以可扫描的方式直接呈现。</p></div></header>
          <div class="cg-metric-grid">
            <PhoenixCard title="全部资源" subtitle="较上周" elevated><strong class="cg-metric">1,286</strong><PhoenixTag variant="success" size="small">增长 12.6%</PhoenixTag></PhoenixCard>
            <PhoenixCard title="待处理事项" subtitle="需要今日完成" elevated><strong class="cg-metric">24</strong><PhoenixTag variant="warning" size="small">需关注</PhoenixTag></PhoenixCard>
            <PhoenixCard title="完成率" subtitle="本月平均值" elevated><strong class="cg-metric">96.8%</strong><PhoenixTag variant="primary" size="small">表现良好</PhoenixTag></PhoenixCard>
            <PhoenixCard title="风险记录" subtitle="最近 24 小时" elevated><strong class="cg-metric">3</strong><PhoenixTag variant="danger" size="small">待判定</PhoenixTag></PhoenixCard>
          </div>
        </section>

        <section v-if="activeNavigation === 'feedback'" id="feedback" class="cg-section" data-group="反馈状态">
          <header class="cg-section__header"><span>07</span><div><h2>反馈状态</h2><p>使用统一的成功、信息、提醒、错误、空状态和加载状态反馈。</p></div></header>
          <div class="cg-showcase-grid">
            <PhoenixCard title="消息提示" subtitle="支持四种语义和关闭操作" padding="large" class="cg-demo-card cg-demo-card--wide">
              <div class="cg-alert-stack">
                <PhoenixAlert variant="success" title="保存成功" description="最新配置已经生效。" />
                <PhoenixAlert variant="info" title="版本更新" description="组件目录已经同步到最新版本。" />
                <PhoenixAlert variant="warning" title="需要确认" description="部分操作会影响已有数据，请核对后继续。" />
                <PhoenixAlert variant="error" title="提交失败" description="网络连接异常，请稍后重试。" closable />
              </div>
            </PhoenixCard>
            <PhoenixCard title="空状态" subtitle="没有数据时给出明确下一步" padding="large" class="cg-demo-card">
              <div class="cg-empty-state"><span aria-hidden="true">◇</span><strong>暂无组件记录</strong><p>可以调整筛选条件，或者创建第一个组件。</p><PhoenixButton size="small">创建组件</PhoenixButton></div>
            </PhoenixCard>
            <PhoenixCard title="加载状态" subtitle="内容加载时保持页面结构稳定" padding="large" class="cg-demo-card">
              <div class="cg-skeleton" aria-label="内容加载中"><i /><i /><i /><i /></div>
            </PhoenixCard>
          </div>
        </section>

        <section v-if="activeNavigation === 'crud'" id="crud" class="cg-section" data-group="CRUD 基础">
          <header class="cg-section__header"><span>08</span><div><h2>CRUD 基础</h2><p>覆盖数据查询、编辑、选择、表格、弹层和操作反馈。</p></div></header>
          <CrudShowcase />
        </section>

        <section v-if="activeNavigation === 'interaction'" id="interaction" class="cg-section" data-group="常用交互">
          <header class="cg-section__header"><span>09</span><div><h2>常用交互</h2><p>覆盖上传、树形选择、步骤流程、身份标记和通知。</p></div></header>
          <InteractionShowcase />
        </section>

        <section v-if="activeNavigation === 'advanced'" id="advanced" class="cg-section" data-group="高级组件">
          <header class="cg-section__header"><span>10</span><div><h2>高级组件</h2><p>提供虚拟列表、编辑、图表、媒体、实时动态、地图和文件管理容器。</p></div></header>
          <AdvancedShowcase />
        </section>

        <section v-if="activeNavigation === 'analytics'" id="analytics" class="cg-section" data-group="数据可视化">
          <header class="cg-section__header"><span>11</span><div><h2>数据可视化</h2><p>指标、趋势、分类、占比、漏斗和排行组件直接组合业务看板。</p></div></header>
          <AnalyticsShowcase />
        </section>

        <section v-if="activeNavigation === 'forms'" id="forms" class="cg-section" data-group="高频表单">
          <header class="cg-section__header"><span>12</span><div><h2>高频表单</h2><p>覆盖复杂录入、选择、日期、评分、权限分配和验证码。</p></div></header>
          <FormShowcase />
        </section>

        <section v-if="activeNavigation === 'auth'" id="auth" class="cg-section" data-group="认证与权限">
          <header class="cg-section__header"><span>13</span><div><h2>认证与权限</h2><p>覆盖登录、用户菜单、权限守卫和角色权限配置。</p></div></header>
          <AuthShowcase />
        </section>

        <section v-if="activeNavigation === 'platform'" id="platform" class="cg-section" data-group="平台与布局">
          <header class="cg-section__header"><span>14</span><div><h2>平台与布局</h2><p>覆盖后台框架、菜单、日历、时间线、进度、结果和看板。</p></div></header>
          <PlatformShowcase />
        </section>

        <section v-if="activeNavigation === 'business'" id="business" class="cg-section" data-group="业务组合">
          <header class="cg-section__header"><span>15</span><div><h2>业务组合</h2><p>覆盖商城、预约、教学、推荐和直播互动的通用界面。</p></div></header>
          <BusinessShowcase />
        </section>

        <section v-if="activeNavigation === 'marketing'" id="marketing" class="cg-section" data-group="营销与社区">
          <header class="cg-section__header"><span>16</span><div><h2>营销与社区</h2><p>抽奖、好友助力、评论和商品卡片提供现代、节庆与极简外观。</p></div></header>
          <MarketingShowcase />
        </section>

        <section v-if="activeNavigation === 'content'" id="content" class="cg-section" data-group="内容与消息">
          <header class="cg-section__header"><span>17</span><div><h2>内容与消息</h2><p>通知、消息、公告、动态、评论、媒体和文件选择覆盖内容型产品。</p></div></header>
          <ContentShowcase />
        </section>

        <section v-if="activeNavigation === 'admin'" id="admin" class="cg-section" data-group="系统后台核心">
          <header class="cg-section__header"><span>18</span><div><h2>系统后台核心</h2><p>覆盖表格查询、批量操作、组织审计、审批附件和账户安全。</p></div></header>
          <AdminShowcase />
        </section>

        <section v-if="activeNavigation === 'commerce'" id="commerce" class="cg-section" data-group="商城与预约">
          <header class="cg-section__header"><span>19</span><div><h2>商城与预约</h2><p>覆盖规格库存、地址优惠、退款物流、时段座位和评价收藏。</p></div></header>
          <CommerceShowcase />
        </section>

        <section v-if="activeNavigation === 'live'" id="live" class="cg-section" data-group="直播业务">
          <header class="cg-section__header"><span>20</span><div><h2>直播业务</h2><p>覆盖直播控制、商品、弹幕、审核、成员、回放和数据指标。</p></div></header>
          <LiveShowcase />
        </section>

        <section v-if="activeNavigation === 'patterns'" id="patterns" class="cg-section" data-group="页面框架">
          <header class="cg-section__header"><span>21</span><div><h2>页面框架</h2><p>可直接组合成管理、结算、预约、学习和直播页面。</p></div></header>
          <PatternShowcase />
        </section>

        <section v-if="activeNavigation === 'management'" id="management" class="cg-section" data-group="通用管理页">
          <header class="cg-section__header"><span>22</span><div><h2>通用管理页</h2><p>用户、角色、部门、字典、审计、商品、订单、库存和预约管理直接展开。</p></div></header>
          <ManagementShowcase />
        </section>

        <section v-if="activeNavigation === 'workspace'" id="workspace" class="cg-section" data-group="工作台页面">
          <header class="cg-section__header"><span>23</span><div><h2>工作台页面</h2><p>工作台、消息、文件、个人设置和系统设置可以直接作为产品页面骨架。</p></div></header>
          <WorkspaceShowcase />
        </section>

        <section v-if="activeNavigation === 'solutions'" id="solutions" class="cg-section" data-group="成品页面组合">
          <header class="cg-section__header"><span>24</span><div><h2>成品页面组合</h2><p>后台管理、数据看板、内容工作台和账户安全可以直接组成产品页面。</p></div></header>
          <SolutionShowcase />
        </section>

        <section v-if="activeNavigation === 'engineering'" id="engineering" class="cg-section" data-group="工程组件">
          <header class="cg-section__header"><span>25</span><div><h2>工程组件</h2><p>可单独拉取的前端、后端和通用组件。</p></div></header>
          <div v-for="group in engineeringGroups" :key="group.category" class="cg-engineering-group">
            <div class="cg-engineering-group__title"><h3>{{ group.label }}</h3><span>{{ group.items.length }} 个组件</span></div>
            <div class="cg-engineering-grid">
              <article v-for="item in group.items" :key="item.id" class="cg-engineering-card" :data-component-id="item.id">
                <div class="cg-engineering-card__top"><PhoenixTag :variant="item.status === 'experimental' ? 'primary' : item.status === 'stable' ? 'success' : 'neutral'" size="small">{{ statusLabels[item.status] ?? item.status }}</PhoenixTag><span>{{ item.stack }} · v{{ item.version }}</span></div>
                <h4>{{ item.name }}</h4>
                <p>{{ item.summary }}</p>
                <small class="cg-engineering-card__id">组件 ID：{{ item.id }}</small>
                <div class="cg-engineering-card__tags"><span v-for="capability in item.capabilities.slice(0, 4)" :key="capability">{{ capabilityLabel(capability) }}</span><span v-if="!item.capabilities.length">待扩展能力</span></div>
                <button type="button" class="cg-copy-command" @click="copyCommand(item)"><span>{{ copiedId === item.id ? '已复制命令' : '复制拉取命令' }}</span><code>{{ item.id }}</code></button>
              </article>
            </div>
          </div>
        </section>

        <section v-if="activeNavigation === 'templates'" id="templates" class="cg-section" data-group="模板系统">
          <header class="cg-section__header"><span>26</span><div><h2>模板系统</h2><p>基础项目和技术栈模板按类别直接展开。</p></div></header>
          <div v-for="group in templateSystemGroups" :key="group.category" class="cg-engineering-group">
            <div class="cg-engineering-group__title"><h3>{{ group.label }}</h3><span>{{ group.items.length }} 个模板</span></div>
            <div class="cg-engineering-grid">
              <article v-for="item in group.items" :key="item.id" class="cg-engineering-card" :data-component-id="item.id">
                <div class="cg-engineering-card__top"><PhoenixTag :variant="item.status === 'experimental' ? 'primary' : item.status === 'stable' ? 'success' : 'neutral'" size="small">{{ statusLabels[item.status] ?? item.status }}</PhoenixTag><span>{{ item.stack }} · v{{ item.version }}</span></div>
                <h4>{{ item.name }}</h4>
                <p>{{ item.summary }}</p>
                <small class="cg-engineering-card__id">模板 ID：{{ item.id }}</small>
                <div class="cg-engineering-card__tags"><span v-for="capability in item.capabilities.slice(0, 4)" :key="capability">{{ capabilityLabel(capability) }}</span><span v-if="!item.capabilities.length">基础模板</span></div>
                <button type="button" class="cg-copy-command" @click="copyCommand(item)"><span>{{ copiedId === item.id ? '已复制命令' : '复制拉取命令' }}</span><code>{{ item.id }}</code></button>
              </article>
            </div>
          </div>
        </section>

        <section v-if="activeNavigation === 'products'" id="products" class="cg-section cg-section--standalone" data-group="成品系统">
          <ProductShowcasePage />
        </section>

        <section v-if="activeNavigation === 'requests'" id="requests" class="cg-section cg-section--standalone" data-group="组件需求清单">
          <ComponentRequestShowcase />
        </section>

        <section v-if="activeNavigation === 'usage-feedback'" id="usage-feedback" class="cg-section cg-section--standalone" data-group="使用反馈">
          <FeedbackShowcase />
        </section>
      </main>
    </div>
  </PhoenixThemeProvider>
</template>
