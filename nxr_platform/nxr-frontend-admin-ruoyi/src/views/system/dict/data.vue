<template>
   <div class="app-container">
      <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch">
         <el-form-item :label="$tx('Dictionary Name')" prop="dictType">
            <el-select v-model="queryParams.dictType" style="width: 200px">
               <el-option
                  v-for="item in typeOptions"
                  :key="item.dictId"
                  :label="displayDictionaryName(item)"
                  :value="item.dictType"
               />
            </el-select>
         </el-form-item>
         <el-form-item :label="$tx('Data Label')" prop="dictLabel">
            <el-input
               v-model="queryParams.dictLabel"
               :placeholder="$tx('Enter data label')"
               clearable
               style="width: 200px"
               @keyup.enter="handleQuery"
            />
         </el-form-item>
         <el-form-item :label="$tx('Status')" prop="status">
            <el-select v-model="queryParams.status" :placeholder="$tx('All statuses')" clearable style="width: 200px">
               <el-option
                  v-for="option in statusOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
               />
            </el-select>
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
               type="warning"
               plain
               icon="Close"
               @click="handleClose"
            >{{ $tx('Close') }}</el-button>
         </el-col>
         <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
      </el-row>

      <el-table v-loading="loading" :data="dataList" @selection-change="handleSelectionChange">
         <el-table-column type="selection" width="55" align="center" />
         <el-table-column :label="$tx('Data ID')" align="center" prop="dictCode" />
         <el-table-column :label="$tx('Data Label')" align="center" prop="dictLabel">
            <template #default="scope">
               <span v-if="(scope.row.listClass == '' || scope.row.listClass == 'default') && (scope.row.cssClass == '' || scope.row.cssClass == null)">{{ scope.row.dictLabel }}</span>
               <el-tag v-else :type="scope.row.listClass == 'primary' ? '' : scope.row.listClass" :class="scope.row.cssClass">{{ scope.row.dictLabel }}</el-tag>
            </template>
         </el-table-column>
         <el-table-column :label="$tx('Data Value')" align="center" prop="dictValue" />
         <el-table-column :label="$tx('Sort Order')" align="center" prop="dictSort" />
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
         <el-table-column :label="$tx('Actions')" align="center" width="160" class-name="small-padding fixed-width">
            <template #default="scope">
               <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['system:dict:edit']">{{ $tx('Edit') }}</el-button>
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
         <el-form ref="dataRef" :model="form" :rules="rules" label-width="80px">
            <el-form-item :label="$tx('Dictionary Type')">
               <el-input v-model="form.dictType" :disabled="true" />
            </el-form-item>
            <el-form-item :label="$tx('Data Label')" prop="dictLabel">
               <el-input v-model="form.dictLabel" :placeholder="$tx('Enter data label')" />
            </el-form-item>
            <el-form-item :label="$tx('Data Value')" prop="dictValue">
               <el-input v-model="form.dictValue" :placeholder="$tx('Enter data value')" />
            </el-form-item>
            <el-form-item :label="$tx('CSS Class')" prop="cssClass">
               <el-input v-model="form.cssClass" :placeholder="$tx('Optional CSS class')" />
            </el-form-item>
            <el-form-item :label="$tx('Sort Order')" prop="dictSort">
               <el-input-number v-model="form.dictSort" controls-position="right" :min="0" />
            </el-form-item>
            <el-form-item :label="$tx('Tag Style')" prop="listClass">
               <el-select v-model="form.listClass">
                  <el-option
                     v-for="item in listClassOptions"
                     :key="item.value"
                     :label="item.label + '(' + item.value + ')'"
                     :value="item.value"
                  ></el-option>
               </el-select>
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
   </div>
</template>

<script setup name="Data">
import useDictStore from '@/store/modules/dict'
import { optionselect as getDictOptionselect, getType } from "@/api/system/dict/type"
import { listData, getData, delData, addData, updateData } from "@/api/system/dict/data"

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

function displayDictionaryName(item) {
  return tx(dictionaryNameMap[item?.dictType] || item?.dictName || '-')
}

const dataList = ref([])
const open = ref(false)
const loading = ref(true)
const showSearch = ref(true)
const ids = ref([])
const single = ref(true)
const multiple = ref(true)
const total = ref(0)
const title = ref("")
const defaultDictType = ref("")
const typeOptions = ref([])
const route = useRoute()
// 数据标签回显样式
const listClassOptions = ref([
  { value: "default", label: tx('Default') },
  { value: "primary", label: tx('Primary') },
  { value: "success", label: tx('Success') },
  { value: "info", label: tx('Info') },
  { value: "warning", label: tx('Warning') },
  { value: "danger", label: tx('Danger') }
])

const data = reactive({
  form: {},
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    dictType: undefined,
    dictLabel: undefined,
    status: undefined
  },
  rules: {
    dictLabel: [{ required: true, message: tx('Data label is required'), trigger: "blur" }],
    dictValue: [{ required: true, message: tx('Data value is required'), trigger: "blur" }],
    dictSort: [{ required: true, message: tx('Sort order is required'), trigger: "blur" }]
  }
})

const { queryParams, form, rules } = toRefs(data)

/** 查询字典类型详细 */
function getTypes(dictId) {
  getType(dictId).then(response => {
    queryParams.value.dictType = response.data.dictType
    defaultDictType.value = response.data.dictType
    getList()
  })
}

/** 查询字典类型列表 */
function getTypeList() {
  getDictOptionselect().then(response => {
    typeOptions.value = response.data
  })
}

/** 查询字典数据列表 */
function getList() {
  loading.value = true
  listData(queryParams.value).then(response => {
    dataList.value = response.rows
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
    dictCode: undefined,
    dictLabel: undefined,
    dictValue: undefined,
    cssClass: undefined,
    listClass: "default",
    dictSort: 0,
    status: "0",
    remark: undefined
  }
  proxy.resetForm("dataRef")
}

/** 搜索按钮操作 */
function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

/** 返回按钮操作 */
function handleClose() {
  const obj = { path: "/system/dict" }
  proxy.$tab.closeOpenPage(obj)
}

/** 重置按钮操作 */
function resetQuery() {
  proxy.resetForm("queryRef")
  queryParams.value.dictType = defaultDictType.value
  handleQuery()
}

/** 新增按钮操作 */
function handleAdd() {
  reset()
  open.value = true
  title.value = tx('Add Dictionary Data')
  form.value.dictType = queryParams.value.dictType
}

/** 多选框选中数据 */
function handleSelectionChange(selection) {
  ids.value = selection.map(item => item.dictCode)
  single.value = selection.length != 1
  multiple.value = !selection.length
}

/** 修改按钮操作 */
function handleUpdate(row) {
  reset()
  const dictCode = row.dictCode || ids.value
  getData(dictCode).then(response => {
    form.value = response.data
    open.value = true
    title.value = tx('Edit Dictionary Data')
  })
}

/** 提交按钮 */
function submitForm() {
  proxy.$refs["dataRef"].validate(valid => {
    if (valid) {
      if (form.value.dictCode != undefined) {
        updateData(form.value).then(response => {
          useDictStore().removeDict(queryParams.value.dictType)
          proxy.$modal.msgSuccess(tx('Dictionary data updated'))
          open.value = false
          getList()
        })
      } else {
        addData(form.value).then(response => {
          useDictStore().removeDict(queryParams.value.dictType)
          proxy.$modal.msgSuccess(tx('Dictionary data created'))
          open.value = false
          getList()
        })
      }
    }
  })
}

/** 删除按钮操作 */
function handleDelete(row) {
  const dictCodes = row.dictCode || ids.value
  proxy.$modal.confirm('Delete dictionary data ID(s) "' + dictCodes + '"?').then(function() {
    return delData(dictCodes)
  }).then(() => {
    getList()
    proxy.$modal.msgSuccess(tx('Dictionary data deleted'))
    useDictStore().removeDict(queryParams.value.dictType)
  }).catch(() => {})
}

/** 导出按钮操作 */
function handleExport() {
  proxy.download("system/dict/data/export", {
    ...queryParams.value
  }, `dict_data_${new Date().getTime()}.xlsx`)
}

getTypes(route.params && route.params.dictId)
getTypeList()
</script>
