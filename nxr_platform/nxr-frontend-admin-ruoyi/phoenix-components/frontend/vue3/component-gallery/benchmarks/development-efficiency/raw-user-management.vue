<!-- benchmark-capabilities: title,metrics,query,batch,table,create,import,export -->
<!-- benchmark-props: rows,stats,queryModel,selectedKeys,busy -->
<!-- benchmark-events: update:queryModel,update:selectedKeys,query,reset,batch,clearSelection,create,import,export -->
<script setup lang="ts">
interface UserMetric {
  label: string
  value: string | number
  tone?: 'neutral' | 'primary' | 'success' | 'warning' | 'danger'
}

interface UserRow {
  id: string | number
  name: string
  department: string
  role: string
  status: string
}

type QueryModel = Record<string, string>

const props = withDefaults(defineProps<{
  rows?: UserRow[]
  stats?: UserMetric[]
  queryModel?: QueryModel
  selectedKeys?: Array<string | number>
  busy?: boolean
}>(), {
  rows: () => [],
  stats: () => [
    { label: '用户总数', value: 128, tone: 'primary' },
    { label: '正常用户', value: 116, tone: 'success' },
    { label: '待处理', value: 12, tone: 'neutral' },
  ],
  queryModel: () => ({ name: '', status: '' }),
  selectedKeys: () => [],
  busy: false,
})

const emit = defineEmits<{
  'update:queryModel': [value: QueryModel]
  'update:selectedKeys': [value: Array<string | number>]
  query: [value: QueryModel]
  reset: []
  batch: [action: string]
  clearSelection: []
  create: []
  import: []
  export: []
}>()

function updateQuery(key: string, value: string) {
  emit('update:queryModel', { ...props.queryModel, [key]: value })
}

function toggleUser(id: string | number, checked: boolean) {
  const next = checked
    ? [...new Set([...props.selectedKeys, id])]
    : props.selectedKeys.filter((key) => key !== id)
  emit('update:selectedKeys', next)
}
</script>

<template>
  <section class="raw-user-page" aria-label="用户管理" :aria-busy="busy">
    <header class="raw-user-page__header">
      <h1>用户管理</h1>
      <nav aria-label="页面操作">
        <button type="button" :disabled="busy" @click="emit('import')">导入用户</button>
        <button type="button" :disabled="busy" @click="emit('export')">导出用户</button>
        <button type="button" class="is-primary" :disabled="busy" @click="emit('create')">新增用户</button>
      </nav>
    </header>

    <div class="raw-user-page__metrics" aria-label="用户指标">
      <article v-for="metric in stats" :key="metric.label" :data-tone="metric.tone ?? 'neutral'">
        <span>{{ metric.label }}</span>
        <strong>{{ metric.value }}</strong>
      </article>
    </div>

    <form class="raw-user-page__query" aria-label="查询条件" @submit.prevent="emit('query', queryModel)">
      <label>
        <span>用户名称</span>
        <input type="search" :value="queryModel.name" placeholder="请输入用户名称" :disabled="busy" @input="updateQuery('name', ($event.target as HTMLInputElement).value)">
      </label>
      <label>
        <span>用户状态</span>
        <select :value="queryModel.status" :disabled="busy" @change="updateQuery('status', ($event.target as HTMLSelectElement).value)">
          <option value="">全部</option>
          <option value="enabled">正常</option>
          <option value="disabled">停用</option>
        </select>
      </label>
      <div class="raw-user-page__query-actions">
        <button type="button" :disabled="busy" @click="emit('reset')">重置</button>
        <button type="submit" class="is-primary" :disabled="busy">查询</button>
      </div>
    </form>

    <aside class="raw-user-page__batch" aria-label="批量操作">
      <strong>已选择 {{ selectedKeys.length }} 项</strong>
      <div>
        <button type="button" :disabled="busy || !selectedKeys.length" @click="emit('batch', 'disable')">批量停用</button>
        <button type="button" :disabled="busy || !selectedKeys.length" @click="emit('clearSelection')">取消选择</button>
      </div>
    </aside>

    <div class="raw-user-page__table" tabindex="0">
      <table>
        <caption>用户列表</caption>
        <thead>
          <tr>
            <th scope="col">选择</th>
            <th scope="col">用户名称</th>
            <th scope="col">部门</th>
            <th scope="col">角色</th>
            <th scope="col">状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in rows" :key="user.id">
            <td><input type="checkbox" :aria-label="`选择${user.name}`" :checked="selectedKeys.includes(user.id)" :disabled="busy" @change="toggleUser(user.id, ($event.target as HTMLInputElement).checked)"></td>
            <td>{{ user.name }}</td>
            <td>{{ user.department }}</td>
            <td>{{ user.role }}</td>
            <td>{{ user.status }}</td>
          </tr>
          <tr v-if="!busy && !rows.length">
            <td colspan="5" class="raw-user-page__state">暂无用户</td>
          </tr>
        </tbody>
      </table>
      <p v-if="busy" class="raw-user-page__state" role="status">数据加载中</p>
    </div>
  </section>
</template>

<style scoped>
.raw-user-page {
  box-sizing: border-box;
  width: 100%;
  overflow: hidden;
  border: 1px solid #dfe2eb;
  border-radius: 22px;
  color: #1c2133;
  background: #f6f7fb;
  box-shadow: 0 18px 50px rgb(31 35 64 / 9%);
  font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif;
}

.raw-user-page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid #dfe2eb;
  padding: 18px 20px;
  background: #fff;
}

.raw-user-page__header h1 { margin: 0; font-size: 20px; }
.raw-user-page__header nav,
.raw-user-page__batch div,
.raw-user-page__query-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.raw-user-page button { min-height: 38px; border: 1px solid #dfe2eb; border-radius: 10px; padding: 0 14px; color: #4e556a; background: #fff; font-weight: 700; cursor: pointer; }
.raw-user-page button.is-primary { border-color: #635bff; color: #fff; background: #635bff; }
.raw-user-page button:disabled { opacity: .45; cursor: not-allowed; }
.raw-user-page__metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; padding: 16px 20px 0; }
.raw-user-page__metrics article { display: grid; gap: 8px; border: 1px solid #dfe2eb; border-radius: 14px; padding: 14px; background: #fff; }
.raw-user-page__metrics span { color: #70778e; font-size: 13px; }
.raw-user-page__metrics strong { font-size: 24px; }
.raw-user-page__metrics [data-tone="primary"] strong { color: #635bff; }
.raw-user-page__metrics [data-tone="success"] strong { color: #138a62; }
.raw-user-page__query { display: grid; grid-template-columns: repeat(2, minmax(150px, 1fr)) auto; align-items: end; gap: 12px; margin: 16px 20px; border: 1px solid #dfe2eb; border-radius: 14px; padding: 14px; background: #fff; }
.raw-user-page__query label { display: grid; gap: 7px; color: #52596c; font-size: 14px; }
.raw-user-page__query input,
.raw-user-page__query select { box-sizing: border-box; min-height: 40px; border: 1px solid #cfd4df; border-radius: 9px; padding: 0 11px; background: #fff; font: inherit; }
.raw-user-page__batch { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 20px 12px; border: 1px solid #d9d6ff; border-radius: 12px; padding: 11px 14px; background: #f1f0ff; }
.raw-user-page__table { margin: 0 20px 20px; overflow-x: auto; border: 1px solid #dfe2eb; border-radius: 14px; background: #fff; }
.raw-user-page table { width: 100%; min-width: 680px; border-collapse: collapse; }
.raw-user-page caption { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
.raw-user-page th,
.raw-user-page td { border-bottom: 1px solid #e9ebf1; padding: 13px 14px; text-align: left; }
.raw-user-page th { color: #5d6478; background: #fafbfc; font-size: 13px; }
.raw-user-page__state { padding: 30px; color: #70778e; text-align: center; }
.raw-user-page button:focus-visible,
.raw-user-page input:focus-visible,
.raw-user-page select:focus-visible { outline: 3px solid rgb(99 91 255 / 25%); outline-offset: 2px; }

@media (max-width: 720px) {
  .raw-user-page__header,
  .raw-user-page__batch { align-items: stretch; flex-direction: column; }
  .raw-user-page__query { grid-template-columns: 1fr; }
  .raw-user-page__query-actions { justify-content: flex-end; }
  .raw-user-page__metrics { grid-template-columns: 1fr; }
}
</style>
