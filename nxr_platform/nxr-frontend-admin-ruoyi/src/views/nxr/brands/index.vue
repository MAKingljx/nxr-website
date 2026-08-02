<template>
  <div class="app-container">
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="Plus" v-hasPermi="['nxr:brand:add']" @click="handleAdd">新增品牌</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button icon="Refresh" plain @click="getList">刷新</el-button>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="brands">
      <el-table-column label="排序" prop="sortOrder" width="80" align="center" />
      <el-table-column label="品牌名称" prop="name" min-width="160" />
      <el-table-column label="别名（逗号分隔，用于录入规范化）" prop="aliases" min-width="280" show-overflow-tooltip />
      <el-table-column label="状态" width="100" align="center">
        <template #default="scope">
          <el-tag :type="scope.row.isActive ? 'success' : 'info'">{{ scope.row.isActive ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" prop="updatedAt" width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="110" align="center">
        <template #default="scope">
          <el-button link type="primary" icon="Edit" v-hasPermi="['nxr:brand:edit']" @click="handleEdit(scope.row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="formTitle" v-model="open" width="560px" append-to-body>
      <el-form ref="brandRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="品牌名称" prop="name">
          <el-input v-model="form.name" placeholder="英文品牌名，如 Pokemon" />
        </el-form-item>
        <el-form-item label="别名" prop="aliases">
          <el-input v-model="form.aliases" type="textarea" :rows="3" placeholder="逗号分隔，如 pokemon,poke" />
        </el-form-item>
        <el-form-item label="排序" prop="sortOrder">
          <el-input-number v-model="form.sortOrder" :min="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.isActive" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :loading="submitting" @click="submitForm">确 定</el-button>
        <el-button @click="open = false">取 消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="NxrBrands">
import { fetchBrandSettings, createBrandSetting, updateBrandSetting } from '@/api/nxr/brands'

const { proxy } = getCurrentInstance()

const brands = ref([])
const loading = ref(false)
const open = ref(false)
const submitting = ref(false)
const editingId = ref(null)

const form = reactive({ name: '', aliases: '', sortOrder: 0, isActive: true })
const rules = {
  name: [{ required: true, message: '品牌名称不能为空', trigger: 'blur' }]
}

const formTitle = computed(() => (editingId.value ? '编辑品牌' : '新增品牌'))

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
  Object.assign(form, { name: '', aliases: '', sortOrder: brands.value.length + 1, isActive: true })
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
        proxy.$modal.msgSuccess(editingId.value ? '保存成功' : '新增成功')
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
