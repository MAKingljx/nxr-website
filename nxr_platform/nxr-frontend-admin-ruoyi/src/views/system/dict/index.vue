<template>
   <div class="app-container">
      <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="68px">
         <el-form-item :label="$tx('Dictionary Name')" prop="dictName">
            <el-input
               v-model="queryParams.dictName"
               :placeholder="$tx('Enter dictionary name')"
               clearable
               style="width: 240px"
               @keyup.enter="handleQuery"
            />
         </el-form-item>
         <el-form-item :label="$tx('Dictionary Type')" prop="dictType">
            <el-input
               v-model="queryParams.dictType"
               :placeholder="$tx('Enter dictionary type')"
               clearable
               style="width: 240px"
               @keyup.enter="handleQuery"
            />
         </el-form-item>
         <el-form-item :label="$tx('Status')" prop="status">
            <el-select
               v-model="queryParams.status"
               :placeholder="$tx('All statuses')"
               clearable
               style="width: 240px"
            >
               <el-option
                  v-for="option in statusOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
               />
            </el-select>
         </el-form-item>
         <el-form-item :label="$tx('Created')" style="width: 308px">
            <el-date-picker
               v-model="dateRange"
               value-format="YYYY-MM-DD"
               type="daterange"
               range-separator="-"
               :start-placeholder="$tx('Start date')"
               :end-placeholder="$tx('End date')"
            ></el-date-picker>
         </el-form-item>
         <el-form-item>
            <el-button type="primary" icon="Search" @click="handleQuery">{{ $tx('Search') }}</el-button>
            <el-button icon="Refresh" @click="resetQuery">{{ $tx('Reset') }}</el-button>
         </el-form-item>
      </el-form>

      <el-row :gutter="10" class="mb8">
         <el-col :span="1.5">
            <el-button
               type="primary"
               plain
               icon="Plus"
               @click="handleAdd"
               v-hasPermi="['system:dict:add']"
            >{{ $tx('Add') }}</el-button>
         </el-col>
         <el-col :span="1.5">
            <el-button
               type="success"
               plain
               icon="Edit"
               :disabled="single"
               @click="handleUpdate"
               v-hasPermi="['system:dict:edit']"
            >{{ $tx('Edit') }}</el-button>
         </el-col>
         <el-col :span="1.5">
            <el-button
               type="danger"
               plain
               icon="Delete"
               :disabled="multiple"
               @click="handleDelete"
               v-hasPermi="['system:dict:remove']"
            >{{ $tx('Delete') }}</el-button>
         </el-col>
         <el-col :span="1.5">
            <el-button
               type="warning"
               plain
               icon="Download"
               @click="handleExport"
               v-hasPermi="['system:dict:export']"
            >{{ $tx('Export') }}</el-button>
         </el-col>
         <el-col :span="1.5">
            <el-button
               type="danger"
               plain
               icon="Refresh"
               @click="handleRefreshCache"
               v-hasPermi="['system:dict:remove']"
            >{{ $tx('Refresh Cache') }}</el-button>
         </el-col>
         <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
      </el-row>

      <el-table v-loading="loading" :data="typeList" @selection-change="handleSelectionChange">
         <el-table-column type="selection" width="55" align="center" />
         <el-table-column :label="$tx('Dictionary ID')" align="center" prop="dictId" />
         <el-table-column :label="$tx('Dictionary Name')" align="center" :show-overflow-tooltip="true">
            <template #default="scope">{{ displayDictionaryName(scope.row) }}</template>
         </el-table-column>
         <el-table-column :label="$tx('Dictionary Type')" align="center" :show-overflow-tooltip="true">
            <template #default="scope">
               <a class="link-type" style="cursor:pointer" @click="handleViewData(scope.row)">{{ scope.row.dictType }}</a>
            </template>
         </el-table-column>
         <el-table-column :label="$tx('Status')" align="center" prop="status">
            <template #default="scope">
               <dict-tag :options="statusOptions" :value="scope.row.status" />
            </template>
         </el-table-column>
         <el-table-column :label="$tx('Notes')" align="center" prop="remark" :show-overflow-tooltip="true" />
         <el-table-column :label="$tx('Created At')" align="center" prop="createTime" width="180">
            <template #default="scope">
               <span>{{ parseTime(scope.row.createTime) }}</span>
            </template>
         </el-table-column>
         <el-table-column :label="$tx('Actions')" align="center" width="280" class-name="small-padding fixed-width">
            <template #default="scope">
               <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['system:dict:edit']">{{ $tx('Edit') }}</el-button>
               <el-button link type="primary" icon="Operation" @click="handleDataList(scope.row)" v-hasPermi="['system:dict:edit']">{{ $tx('Data') }}</el-button>
               <el-button link type="primary" icon="Delete" @click="handleDelete(scope.row)" v-hasPermi="['system:dict:remove']">{{ $tx('Delete') }}</el-button>
            </template>
         </el-table-column>
      </el-table>

      <pagination
         v-show="total > 0"
         :total="total"
         v-model:page="queryParams.pageNum"
         v-model:limit="queryParams.pageSize"
         @pagination="getList"
      />

      <!-- 添加或修改参数配置对话框 -->
      <el-dialog :title="title" v-model="open" width="500px" append-to-body>
         <el-form ref="dictRef" :model="form" :rules="rules" label-width="100px">
            <el-form-item :label="$tx('Dictionary Name')" prop="dictName">
               <el-input v-model="form.dictName" :placeholder="$tx('Enter dictionary name')" />
            </el-form-item>
            <el-form-item prop="dictType">
               <el-input v-model="form.dictType" :placeholder="$tx('Enter dictionary type')" />
               <template #label>
                 <span>
                   <el-tooltip :content="$tx('Storage key, for example: sys_user_sex')" placement="top">
                     <el-icon><question-filled /></el-icon>
                   </el-tooltip> {{ $tx('Dictionary Type') }} </span>
               </template>
            </el-form-item>
            <el-form-item :label="$tx('Status')" prop="status">
               <el-radio-group v-model="form.status">
                  <el-radio
                     v-for="option in statusOptions"
                     :key="option.value"
                     :value="option.value"
                  >{{ option.label }}</el-radio>
               </el-radio-group>
            </el-form-item>
            <el-form-item :label="$tx('Notes')" prop="remark">
               <el-input v-model="form.remark" type="textarea" :placeholder="$tx('Enter notes')"></el-input>
            </el-form-item>
         </el-form>
         <template #footer>
            <div class="dialog-footer">
               <el-button type="primary" @click="submitForm">{{ $tx('Save') }}</el-button>
               <el-button @click="cancel">{{ $tx('Cancel') }}</el-button>
            </div>
         </template>
      </el-dialog>

      <dict-data-drawer v-model:visible="drawerVisible" :row="drawerRow" />
   </div>
</template>

<script setup name="Dict">
import DictDataDrawer from './detail'
import useDictStore from '@/store/modules/dict'
import { listType, getType, delType, addType, updateType, refreshCache } from "@/api/system/dict/type"

const { proxy } = getCurrentInstance()
const statusOptions = [
  { value: '0', label: tx('Active'), elTagType: 'success' },
  { value: '1', label: tx('Disabled'), elTagType: 'danger' }
]
const dictionaryNameMap = {
  nxr_sports_type: 'Sports Type',
  nxr_card_category: 'Card Category',
  nxr_product_type: 'Product Type',
  nxr_vintage_classification: 'Vintage Classification',
  nxr_language: 'Card Language'
}

function displayDictionaryName(row) {
  return tx(dictionaryNameMap[row?.dictType] || row?.dictName || '-')
}

const typeList = ref([])
const open = ref(false)
const loading = ref(true)
const showSearch = ref(true)
const ids = ref([])
const single = ref(true)
const multiple = ref(true)
const total = ref(0)
const title = ref("")
const dateRange = ref([])
const drawerVisible = ref(false)
const drawerRow = ref({})

const data = reactive({
  form: {},
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    dictName: undefined,
    dictType: undefined,
    status: undefined
  },
  rules: {
    dictName: [{ required: true, message: tx('Dictionary name is required'), trigger: "blur" }],
    dictType: [{ required: true, message: tx('Dictionary type is required'), trigger: "blur" }]
  },
})

const { queryParams, form, rules } = toRefs(data)

/** 查询字典类型列表 */
function getList() {
  loading.value = true
  listType(proxy.addDateRange(queryParams.value, dateRange.value)).then(response => {
    typeList.value = response.rows
    total.value = response.total
    loading.value = false
  })
}

/** 取消按钮 */
function cancel() {
  open.value = false
  reset()
}

/** 表单重置 */
function reset() {
  form.value = {
    dictId: undefined,
    dictName: undefined,
    dictType: undefined,
    status: "0",
    remark: undefined
  }
  proxy.resetForm("dictRef")
}

/** 搜索按钮操作 */
function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

/** 重置按钮操作 */
function resetQuery() {
  dateRange.value = []
  proxy.resetForm("queryRef")
  handleQuery()
}

/** 新增按钮操作 */
function handleAdd() {
  reset()
  open.value = true
  title.value = tx('Add Dictionary')
}

/** 多选框选中数据 */
function handleSelectionChange(selection) {
  ids.value = selection.map(item => item.dictId)
  single.value = selection.length != 1
  multiple.value = !selection.length
}

/** 字典数据抽屉 */
function handleViewData(row) {
  drawerRow.value = row
  drawerVisible.value = true
}

/** 字典数据列表页面 */
function handleDataList(row) {
  proxy.$tab.openPage("Dictionary Data", '/system/dict-data/index/' + row.dictId)
}

/** 修改按钮操作 */
function handleUpdate(row) {
  reset()
  const dictId = row.dictId || ids.value
  getType(dictId).then(response => {
    form.value = response.data
    open.value = true
    title.value = tx('Edit Dictionary')
  })
}

/** 提交按钮 */
function submitForm() {
  proxy.$refs["dictRef"].validate(valid => {
    if (valid) {
      if (form.value.dictId != undefined) {
        updateType(form.value).then(response => {
          proxy.$modal.msgSuccess(tx('Dictionary updated'))
          open.value = false
          getList()
        })
      } else {
        addType(form.value).then(response => {
          proxy.$modal.msgSuccess(tx('Dictionary created'))
          open.value = false
          getList()
        })
      }
    }
  })
}

/** 删除按钮操作 */
function handleDelete(row) {
  const dictIds = row.dictId || ids.value
  proxy.$modal.confirm('Delete dictionary ID(s) "' + dictIds + '"?').then(function() {
    return delType(dictIds)
  }).then(() => {
    getList()
    proxy.$modal.msgSuccess(tx('Dictionary deleted'))
  }).catch(() => {})
}

/** 导出按钮操作 */
function handleExport() {
  proxy.download("system/dict/type/export", {
    ...queryParams.value
  }, `dict_${new Date().getTime()}.xlsx`)
}

/** 刷新缓存按钮操作 */
function handleRefreshCache() {
  refreshCache().then(() => {
    proxy.$modal.msgSuccess(tx('Dictionary cache refreshed'))
    useDictStore().cleanDict()
  })
}

getList()
</script>
