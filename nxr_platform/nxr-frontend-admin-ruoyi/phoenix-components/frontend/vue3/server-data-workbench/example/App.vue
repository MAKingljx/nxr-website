<template>
  <main class="demo-page">
    <h1>Server data workbench</h1>

    <PhoenixServerDataWorkbench
      v-model:page="page"
      v-model:page-size="pageSize"
      :loading="loading"
      :error="error"
      :empty="visibleRows.length === 0"
      :total="filteredRows.length"
      :page-size-options="[5, 10, 20]"
      aria-label="Records"
      @query="runQuery"
      @reset="resetFilters"
      @retry="loadPage"
      @page-change="loadPage"
    >
      <template #filters>
        <label>
          Search
          <input v-model="draftQuery" type="search" autocomplete="off" />
        </label>
        <label>
          State
          <select v-model="draftState">
            <option value="">All states</option>
            <option value="ready">Ready</option>
            <option value="paused">Paused</option>
          </select>
        </label>
      </template>

      <template #toolbar>
        <button type="button" @click="loadPage">Refresh current page</button>
      </template>

      <table>
        <thead>
          <tr>
            <th scope="col">ID</th>
            <th scope="col">Label</th>
            <th scope="col">State</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in visibleRows" :key="row.id">
            <td>{{ row.id }}</td>
            <td>{{ row.label }}</td>
            <td>{{ row.state }}</td>
            <td><button type="button" @click="lastAction = `Opened ${row.id}`">Open</button></td>
          </tr>
        </tbody>
      </table>

      <template #footer>
        <p v-if="lastAction" role="status">{{ lastAction }}</p>
      </template>
    </PhoenixServerDataWorkbench>
  </main>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { PhoenixServerDataWorkbench } from '../src'

interface DemoRow {
  id: number
  label: string
  state: 'ready' | 'paused'
}

const allRows: DemoRow[] = Array.from({ length: 37 }, (_, index) => ({
  id: index + 1,
  label: `Record ${index + 1}`,
  state: index % 4 === 0 ? 'paused' : 'ready',
}))

const page = ref(1)
const pageSize = ref(5)
const loading = ref(false)
const error = ref('')
const draftQuery = ref('')
const draftState = ref('')
const appliedQuery = ref('')
const appliedState = ref('')
const lastAction = ref('')

const filteredRows = computed(() => {
  const query = appliedQuery.value.trim().toLowerCase()
  return allRows.filter((row) => {
    const matchesQuery = !query || row.label.toLowerCase().includes(query)
    const matchesState = !appliedState.value || row.state === appliedState.value
    return matchesQuery && matchesState
  })
})

const visibleRows = computed(() => {
  const offset = (page.value - 1) * pageSize.value
  return filteredRows.value.slice(offset, offset + pageSize.value)
})

function runQuery(): void {
  appliedQuery.value = draftQuery.value
  appliedState.value = draftState.value
  page.value = 1
  loadPage()
}

function resetFilters(): void {
  draftQuery.value = ''
  draftState.value = ''
  runQuery()
}

function loadPage(): void {
  // A real host performs its server request here; the component only emits intent.
  error.value = ''
}
</script>

<style scoped>
:global(*) { box-sizing: border-box; }
:global(body) { margin: 0; color: #17202a; font-family: system-ui, sans-serif; background: #f3f6fa; }
button, input, select { min-height: 38px; font: inherit; }
.demo-page { width: min(980px, calc(100% - 32px)); margin: 0 auto; padding: 40px 0; }
h1 { margin: 0 0 20px; }
label { display: grid; min-width: 180px; gap: 5px; font-size: 13px; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 12px; text-align: left; border-bottom: 1px solid #e0e6ee; }
p { margin: 0; }
</style>
