<template>
  <main class="nxr-workspace nxr-brands-workspace">
    <nxr-page-header
      :kicker="$tx('GRADING CATALOG')"
      :title="$tx('Brand Settings')"
      :summary="$tx('Maintain the brand names, aliases, and availability used by card entries')"
    >
      <template #actions>
        <el-button type="primary" plain icon="Plus" v-hasPermi="['nxr:brand:add']" @click="handleAdd">{{ $tx('New Brand') }}</el-button>
        <el-button icon="Refresh" plain @click="getList">{{ $tx('Refresh') }}</el-button>
      </template>
    </nxr-page-header>

    <div class="brand-search mb8">
      <el-input v-model="searchText" :placeholder="$tx('Search brand or alias')" clearable prefix-icon="Search" />
    </div>
    <el-table v-loading="loading" :data="filteredBrands">
      <el-table-column :label="$tx('Order')" width="100" align="center">
        <template #default="scope">{{ Number(scope.row.sortOrder) > 0 ? scope.row.sortOrder : $tx('Not specified') }}</template>
      </el-table-column>
      <el-table-column :label="$tx('Brand Name')" prop="name" min-width="160" />
      <el-table-column :label="$tx('Aliases (comma-separated)')" prop="aliases" min-width="280" show-overflow-tooltip />
      <el-table-column :label="$tx('Status')" width="100" align="center">
        <template #default="scope">
          <nxr-status-tag :code="scope.row.isActive ? 'active' : 'inactive'" />
        </template>
      </el-table-column>
      <el-table-column :label="$tx('Updated At')" width="190" show-overflow-tooltip>
        <template #default="scope">{{ formatUpdatedAt(scope.row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column :label="$tx('Actions')" width="110" align="center">
        <template #default="scope">
          <el-button link type="primary" icon="Edit" v-hasPermi="['nxr:brand:edit']" @click="handleEdit(scope.row)">{{ $tx('Edit') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="formTitle" v-model="open" width="560px" append-to-body>
      <el-form ref="brandRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item :label="$tx('Brand Name')" prop="name">
          <el-input v-model="form.name" :placeholder="$tx('English brand name, e.g. Pokemon')" />
        </el-form-item>
        <el-form-item :label="$tx('Aliases')" prop="aliases">
          <el-input v-model="form.aliases" type="textarea" :rows="3" :placeholder="$tx('Comma-separated, e.g. pokemon,poke')" />
        </el-form-item>
        <el-form-item :label="$tx('Order')" prop="sortOrder">
          <el-input-number v-model="form.sortOrder" :min="0" controls-position="right" />
        </el-form-item>
        <el-form-item :label="$tx('Status')">
          <el-switch v-model="form.isActive" active-text="Active" inactive-text="Inactive" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :loading="submitting" @click="submitForm">{{ $tx('Save') }}</el-button>
        <el-button @click="open = false">{{ $tx('Cancel') }}</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<script setup name="NxrBrands">
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import NxrStatusTag from '@/components/NxrWorkspace/StatusTag.vue'
import { fetchBrandSettings, createBrandSetting, updateBrandSetting } from '@/api/nxr/brands'
import { activeLocale } from '@/i18n'

const { proxy } = getCurrentInstance()

const brands = ref([])
const searchText = ref('')
const filteredBrands = computed(() => {
  const keyword = searchText.value.trim().toLocaleLowerCase()
  return brands.value
    .filter((brand) => !keyword || `${brand.name || ''} ${brand.aliases || ''}`.toLocaleLowerCase().includes(keyword))
    .sort((a, b) => {
      const first = Number(a.sortOrder) > 0 ? Number(a.sortOrder) : Number.POSITIVE_INFINITY
      const second = Number(b.sortOrder) > 0 ? Number(b.sortOrder) : Number.POSITIVE_INFINITY
      return first - second || String(a.name || '').localeCompare(String(b.name || ''), activeLocale())
    })
})
const loading = ref(false)
const open = ref(false)
const submitting = ref(false)
const editingId = ref(null)

const form = reactive({ name: '', aliases: '', sortOrder: 0, isActive: true })
const rules = {
  name: [{ required: true, message: tx('Brand name is required'), trigger: 'blur' }]
}

const formTitle = computed(() => (editingId.value ? tx('Edit Brand') : tx('New Brand')))

function formatUpdatedAt(value) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat(activeLocale(), {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  }).format(date)
}

function getList() {
  loading.value = true
  fetchBrandSettings()
    .then((res) => {
      brands.value = res.data || []
    })
    .finally(() => {
      loading.value = false
    })
}

function handleAdd() {
  editingId.value = null
  Object.assign(form, {
    name: '', aliases: '',
    sortOrder: Math.max(0, ...brands.value.map((brand) => Number(brand.sortOrder) || 0)) + 1,
    isActive: true
  })
  open.value = true
}

function handleEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    aliases: row.aliases,
    sortOrder: row.sortOrder,
    isActive: row.isActive
  })
  open.value = true
}

function submitForm() {
  proxy.$refs.brandRef.validate((valid) => {
    if (!valid) return
    submitting.value = true
    const action = editingId.value
      ? updateBrandSetting(editingId.value, { ...form })
      : createBrandSetting({ ...form })
    action
      .then(() => {
        proxy.$modal.msgSuccess(editingId.value ? tx('Brand saved') : tx('Brand created'))
        open.value = false
        getList()
      })
      .finally(() => {
        submitting.value = false
      })
  })
}

getList()
</script>

<style scoped>
.brand-search {
  max-width: 360px;
}
</style>
