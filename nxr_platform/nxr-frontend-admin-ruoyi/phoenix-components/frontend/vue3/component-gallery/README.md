# Vue 3 Component Gallery

`frontend.vue3.component-gallery` 是一个全中文的现代化组件展厅。左侧导航仅用于定位
按钮与操作、导航、输入与搜索、CRUD 基础、常用交互、高级组件等分类，不需要先选择
单个组件；163 个可交互组件和页面框架会在页面中直接展示。左侧将组件、模板系统、成品系统和组件需求分开。

组件包导出 163 个 Vue 3 通用组件与页面框架，并提供成品系统目录和组件需求清单：

- 基础界面：按钮、搜索、分割线、标签页、面包屑、分页、开关、标签、提示和卡片。
- 主题与外观：通过一个局部提供器统一现代、商务、极简和节庆主题，不修改文档根节点。
- CRUD 基础：表格、输入、选择、表单项、日期、对话框、抽屉、消息、空状态和骨架加载。
- 常用交互：复选、单选、文件选择、树、级联、步骤、描述、头像、徽标、提示和通知。
- 高级组件：虚拟列表、正文编辑、图表、媒体、实时动态、地图和文件管理容器。
- 数据可视化：指标、趋势、柱状、折线、环形、漏斗、排行、摘要、筛选和图例。
- 高频表单：多行文本、数字、多选、日期范围、时间、补全、滑块、评分、颜色、穿梭、分段和验证码。
- 认证与权限：登录、用户菜单、权限守卫和角色权限矩阵。
- 平台组件：应用框架、侧边菜单、顶栏、页头、下拉、气泡、折叠、时间线、进度、结果、日历和看板。
- 业务组合：资源、价格、购物车、订单状态、支付状态、预约、学习、聊天、成员、直播状态和推荐。
- 营销与社区：抽奖、好友助力砍价、社区评论和商品卡片，提供现代、节庆和极简外观。
- 内容与消息：通知、消息、公告、动态、评论、成员提及、媒体、图片、文档和文件拖放。
- 系统后台核心：高级表格、查询、批量、导入导出、树形组织、审计、审批、附件和账户安全。
- 商城与预约：SKU、库存、地址、优惠券、支付方式、退款、物流、时段、座位、评价、评分和收藏。
- 直播业务：直播控制台、商品货架、弹幕、审核队列、成员管理、回放和数据指标。
- 页面框架：工作台、资源管理、结算、预约、学习中心和直播间。
- 通用管理页：用户、角色权限、部门、字典、审计、商品、订单、库存和预约管理。
- 工作台页面：登录后工作台、消息中心、文件中心、个人设置和系统设置。
- 成品页面组合：后台数据管理、经营数据看板、内容消息工作台和账户安全，通过少量配置直接形成页面。

## 开发效率基准

固定“用户管理页”能力契约下，Phoenix 复用版与纯 Vue 自写版均包含标题、3 个指标、
查询、批量操作、用户表格、新增、导入和导出。确定性静态源码测量结果：

| 指标 | 纯 Vue 自写版 | Phoenix 复用版 | 减少 |
| --- | ---: | ---: | ---: |
| 非空源码行 | 182 | 94 | 48.4% |
| 模板行 | 68 | 27 | 60.3% |
| 页面样式行 | 55 | 0 | 100% |
| UTF-8 字节 | 8357 | 3184 | 61.9% |

运行 `node scripts/measure-development-efficiency.mjs` 可复现结果。该基准只说明固定场景
的消费者自写代码规模变化，不等同于真实开发人时，也不估算精确 Token 节省。

## 作为系统运行

```bash
npm ci
npm run verify
npm run dev
```

`npm run build` 会同时生成可安装的 `dist` 组件包和可部署的 `site-dist` 展厅站点；
`npm run preview` 可在本地预览生产站点。

示例应用使用组件目录自己的全中文 `src/data/components.json` 确定性快照，因此运行时
不依赖仓库根或兄弟组件。`catalog.zh-CN.json` 必须覆盖根索引的全部组件，新增工程
组件时若缺少中文名称或说明会直接校验失败。仓库维护者在完整工作区更新根索引后运行：

```bash
npm run catalog:sync
npm run catalog:check
```

## 作为组件使用

```vue
<PhoenixThemeProvider theme="business">
  <ComponentGallery :items="catalog" />
</PhoenixThemeProvider>

<PhoenixButton variant="primary">保存</PhoenixButton>
<PhoenixSearch v-model="keyword" placeholder="搜索资源" />
<PhoenixDivider text="更多信息" />
<PhoenixProductCard title="商品" :price="99" :inventory="10" appearance="festive" />
<PhoenixAdvancedTable :columns="columns" :rows="rows" />
<PhoenixMetricCard label="成交金额" :value="26890" kind="currency" />
<PhoenixNotificationCenter :items="notifications" />
```

`PhoenixThemeProvider` 只在自身容器内设置 Phoenix 设计令牌，可嵌套使用；支持
`modern`、`business`、`minimal` 和 `festive`，不会写入 `documentElement` 或浏览器存储。

组件不会执行清单里的命令，也不会导入兄弟组件源码。展厅展示的是同一组件包实际导出的
基础组件；底部工程组件目录只提供中文说明和精确拉取命令，不把目录卡片当作运行兼容证据。

## 公开 API

- Props：`items`、`title`、`subtitle`。
- Emits：`copy`。
- 基础组件的 Props、事件和插槽以各组件 TypeScript 声明为准。
- 抽奖结果、砍价金额、支付选择、退款、直播控制和审核均由业务端处理；组件只展示受控状态并发出请求事件。
- 工程组件与模板系统分开展示，模板系统内部再按前端、后端、通用和桌面端分类。

当前状态为 `experimental`。
