# Server Data Workbench

`PhoenixServerDataWorkbench` 是一个领域无关的 Vue 3 工作台外壳，用于组合宿主拥有的
服务端查询、受控分页、数据内容和行级操作。组件只依赖 Vue，不发送网络请求、不读取
业务字段、不判断权限，也不维护宿主的筛选值或远端数据状态。

当前版本为 `experimental`，公开 API 在进入 stable 前仍可能调整。

## 能力边界

- 原生 `<form>` 查询区，输入控件按浏览器标准支持 Enter 提交。
- 查询、重置、重试、页码和每页数量均通过事件把意图交给宿主。
- 默认提供可访问的加载、错误、空态与服务端分页控件。
- 所有内容、筛选控件、工具栏、表格或列表、行操作都由插槽提供。
- `pagination` 插槽可以完整替换默认分页，不残留默认页码或每页数量控件。
- 对 `total`、`page`、`pageSize` 和 `pageSizeOptions` 做有限数、整数、非负、去重和
  越界归一化；归一化只影响渲染和用户事件，不会因 props 更新自动发出请求。

## 安装与运行

要求宿主提供 Vue `>=3.5.0 <4`。在组件目录中可独立运行：

```bash
npm ci
npm run dev
```

入口为 `src/index.ts`。构建后的 ESM、样式和类型入口分别为
`dist/server-data-workbench.js`、`dist/server-data-workbench.css` 与 `dist/index.d.ts`。

## 使用

```vue
<template>
  <PhoenixServerDataWorkbench
    v-model:page="page"
    v-model:page-size="pageSize"
    :loading="loading"
    :error="error"
    :empty="rows.length === 0"
    :total="total"
    @query="queryServer"
    @reset="resetFilters"
    @retry="queryServer"
    @page-change="queryServer"
  >
    <template #filters>
      <label>Keyword <input v-model="keyword" type="search" /></label>
    </template>

    <table>
      <tbody>
        <tr v-for="row in rows" :key="row.id">
          <td>{{ row.label }}</td>
          <td><button type="button" @click="open(row)">Open</button></td>
        </tr>
      </tbody>
    </table>
  </PhoenixServerDataWorkbench>
</template>

<script setup lang="ts">
import { PhoenixServerDataWorkbench } from '@phoenix-components/vue3-server-data-workbench'
import '@phoenix-components/vue3-server-data-workbench/style.css'
</script>
```

`queryServer` 属于宿主；组件不会调用它之外的请求客户端，也不会把 `pageChange` 自动
转换为 `query`。宿主可分别监听两个事件，或让同一个函数处理它们。

## Props

| Prop | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `loading` | `boolean` | `false` | 显示加载态并设置 `aria-busy` |
| `error` | `string` | `''` | 非空错误文案；优先于空态 |
| `empty` | `boolean` | `false` | 由宿主明确控制空态 |
| `emptyTitle` | `string` | 标签默认值 | 覆盖默认空态标题 |
| `emptyDescription` | `string` | 标签默认值 | 覆盖默认空态说明 |
| `total` | `number` | `0` | 服务端结果总数；归一化为非负整数 |
| `page` | `number` | `1` | 受控页码；渲染时限制在有效范围 |
| `pageSize` | `number` | `10` | 受控每页数量；必须为正整数 |
| `pageSizeOptions` | `number[]` | `[10, 20, 50]` | 有效正整数会去重，并保证包含当前值 |
| `showReset` | `boolean` | `true` | 控制默认查询区和空态的重置按钮 |
| `labels` | `Partial<ServerDataWorkbenchLabels>` | `{}` | 覆盖全部内置可见或可访问文案 |
| `ariaLabel` | `string` | 标签默认值 | 工作台区域的可访问名称 |

状态优先级固定为 `loading -> error -> empty -> default`。组件不根据 `total` 推断
`empty`，避免服务端总数、当前页暂空或占位内容之间出现隐式业务判断。

## Events

| Event | 参数 | 触发时机 |
| --- | --- | --- |
| `query` | `{ page, pageSize }` | 查询表单提交，包括浏览器标准 Enter 提交 |
| `reset` | 无 | 默认或插槽提供的重置动作 |
| `retry` | 无 | 默认错误态重试动作 |
| `update:page` | `page` | 用户切页，或变更每页数量后请求回到第 1 页 |
| `update:pageSize` | `pageSize` | 用户选择新的每页数量 |
| `pageChange` | `page, pageSize` | 用户切页或变更每页数量 |

页码或每页数量 props 变化、越界归一化及状态 props 变化均不会触发上述事件。变更
每页数量会发出 `update:pageSize`、`update:page(1)` 和 `pageChange(1, pageSize)`，但
不会发出 `query`。

## Slots

| Slot | Scope | 用途 |
| --- | --- | --- |
| `filters` | `{ query, reset }` | 宿主筛选控件，位于原生查询表单内 |
| `filter-actions` | `{ query, reset }` | 完整替换默认查询与重置按钮 |
| `toolbar` | `{ page, pageSize, total, query }` | 导出、刷新或批量操作 |
| `default` | `{ page, pageSize, total, totalPages }` | 表格、列表、卡片和宿主行操作 |
| `loading` | `{ title, description }` | 完整替换默认加载态 |
| `error` | `{ error, retry }` | 完整替换默认错误态 |
| `empty` | `{ title, description, reset }` | 完整替换默认空态 |
| `pagination` | `{ page, pageSize, total, totalPages, pageSizeOptions, goToPage, setPageSize }` | 完整替换默认分页 |
| `footer` | `{ page, pageSize, total, totalPages }` | 分页后的补充状态或动作 |

自定义状态插槽和自定义分页由宿主负责其最终可访问语义。

## Labels

`ServerDataWorkbenchLabels` 包含 `workbench`、`filters`、`query`、`reset`、
`loadingTitle`、`loadingDescription`、`errorTitle`、`retry`、`emptyTitle`、
`emptyDescription`、`pagination`、`previousPage`、`nextPage`、`pageSize`、`page`、
`of`、`total` 和 `items`。

## 主题

可在组件或祖先元素覆盖 `--psdw-color-surface`、`--psdw-color-subtle`、
`--psdw-color-text`、`--psdw-color-muted`、`--psdw-color-border`、
`--psdw-color-primary`、`--psdw-color-primary-soft`、`--psdw-color-danger`、
`--psdw-color-danger-soft`、`--psdw-radius` 与 `--psdw-font-family`。

## 验证

```bash
npm ci
npm run verify
python3 ../../../src/pcl.py validate --strict frontend/vue3/server-data-workbench
```

兼容声明、原生示例/测试路径、锁文件及真实命令结果见
`evidence/VERIFICATION.md`、`evidence/build.json` 与 `evidence/test.json`。

## 已知边界

- 不提供请求、取消请求、缓存、轮询、权限、选择、排序、虚拟滚动或业务状态判断。
- 不读取默认插槽中的行数据，也不规定表格、卡片或行操作字段。
- 自动化证据覆盖 Vue DOM、事件、归一化、插槽和构建；未声明真实浏览器视觉回归或
  辅助技术人工验收。
