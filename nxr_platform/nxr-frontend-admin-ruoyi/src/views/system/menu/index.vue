<template>
   <div class="app-container">
      <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch">
         <el-form-item :label="$tx('Menu Name')" prop="menuName">
            <el-input
               v-model="queryParams.menuName"
               :placeholder="$tx('Enter menu name')"
               clearable
               style="width: 200px"
               @keyup.enter="handleQuery"
            />
         </el-form-item>
         <el-form-item :label="$tx('Status')" prop="status">
            <el-select v-model="queryParams.status" :placeholder="$tx('Menu status')" clearable style="width: 200px">
               <el-option
                  v-for="dict in sys_normal_disable"
                  :key="dict.value"
                  :label="dict.label"
                  :value="dict.value"
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
               v-hasPermi="['system:menu:add']"
            >{{ $tx('Add') }}</el-button>
         </el-col>
         <el-col :span="1.5">
            <el-button
               type="warning"
               plain
               icon="Check"
               @click="handleSaveSort"
               v-hasPermi="['system:menu:edit']"
            >{{ $tx('Save Order') }}</el-button>
         </el-col>
         <el-col :span="1.5">
            <el-button
               type="info"
               plain
               icon="Sort"
               @click="toggleExpandAll"
            >{{ $tx('Expand/Collapse') }}</el-button>
         </el-col>
         <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
      </el-row>

      <el-table
         v-if="refreshTable"
         v-loading="loading"
         :data="menuList"
         row-key="menuId"
         :default-expand-all="isExpandAll"
         :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
      >
         <el-table-column prop="menuName" :label="$tx('Menu Name')" :show-overflow-tooltip="true" width="220">
            <template #default="scope">
               <svg-icon :icon-class="scope.row.icon" />
               <span class="ml5">{{ localizeMenuName(scope.row.menuName) }}</span>
            </template>
         </el-table-column>
         <el-table-column prop="menuName" :label="$tx('Type')" :show-overflow-tooltip="true" width="100">
            <template #default="scope">
               <el-tag v-if="scope.row.menuType === 'M' && scope.row.isFrame === '0'" type="danger" size="small">{{ $tx('External') }}</el-tag>
               <el-tag v-else-if="scope.row.menuType === 'M'" type="primary" size="small">{{ $tx('Directory') }}</el-tag>
               <el-tag v-else-if="scope.row.menuType === 'C' && scope.row.isFrame === '0'" type="danger" size="small">{{ $tx('External') }}</el-tag>
               <el-tag v-else-if="scope.row.menuType === 'C'" type="success" size="small">{{ $tx('Menu') }}</el-tag>
               <el-tag v-else-if="scope.row.menuType === 'F'" type="warning" size="small">{{ $tx('Button') }}</el-tag>
            </template>
         </el-table-column>
         <el-table-column prop="orderNum" :label="$tx('Order')" width="200">
            <template #default="scope">
               <el-input-number v-model="scope.row.orderNum" controls-position="right" :min="0" style="width: 88px" />
            </template>
         </el-table-column>
         <el-table-column prop="perms" :label="$tx('Permission')" :show-overflow-tooltip="true" />
         <el-table-column prop="component" :label="$tx('Component Path')" :show-overflow-tooltip="true" />
         <el-table-column prop="status" :label="$tx('Status')" width="80">
            <template #default="scope">
               <dict-tag :options="sys_normal_disable" :value="scope.row.status" />
            </template>
         </el-table-column>
         <el-table-column :label="$tx('Actions')" align="center" width="210" class-name="small-padding fixed-width">
            <template #default="scope">
               <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['system:menu:edit']">{{ $tx('Edit') }}</el-button>
               <el-button link type="primary" icon="Plus" @click="handleAdd(scope.row)" v-hasPermi="['system:menu:add']">{{ $tx('Add') }}</el-button>
               <el-button link type="primary" icon="Delete" @click="handleDelete(scope.row)" v-hasPermi="['system:menu:remove']">{{ $tx('Delete') }}</el-button>
            </template>
         </el-table-column>
      </el-table>

      <!-- 添加或修改菜单对话框 -->
      <el-dialog :title="title" v-model="open" width="680px" append-to-body>
         <el-form ref="menuRef" :model="form" :rules="rules" label-width="100px">
            <el-row>
               <el-col :span="24">
                  <el-form-item :label="$tx('Parent Menu')">
                     <el-tree-select
                        v-model="form.parentId"
                        :data="menuOptions"
                        :props="{ value: 'menuId', label: 'displayName', children: 'children' }"
                        value-key="menuId"
                        :placeholder="$tx('Select parent menu')"
                        check-strictly
                     />
                  </el-form-item>
               </el-col>
               <el-col :span="24">
                  <el-form-item :label="$tx('Menu Type')" prop="menuType">
                     <el-radio-group v-model="form.menuType">
                        <el-radio value="M">{{ $tx('Directory') }}</el-radio>
                        <el-radio value="C">{{ $tx('Menu') }}</el-radio>
                        <el-radio value="F">{{ $tx('Button') }}</el-radio>
                     </el-radio-group>
                  </el-form-item>
               </el-col>
               <el-col :span="12" v-if="form.menuType != 'F'">
                  <el-form-item :label="$tx('Menu Icon')" prop="icon">
                     <el-popover
                        placement="bottom-start"
                        :width="540"
                        trigger="click"
                     >
                        <template #reference>
                           <el-input v-model="form.icon" :placeholder="$tx('Select an icon')" @blur="showSelectIcon" readonly>
                              <template #prefix>
                                 <svg-icon
                                    v-if="form.icon"
                                    :icon-class="form.icon"
                                    class="el-input__icon"
                                    style="height: 32px;width: 16px;"
                                 />
                                 <el-icon v-else style="height: 32px;width: 16px;"><search /></el-icon>
                              </template>
                           </el-input>
                        </template>
                        <icon-select ref="iconSelectRef" @selected="selected" :active-icon="form.icon" />
                     </el-popover>
                  </el-form-item>
               </el-col>
               <el-col :span="12">
                  <el-form-item :label="$tx('Display Order')" prop="orderNum">
                     <el-input-number v-model="form.orderNum" controls-position="right" :min="0" />
                  </el-form-item>
               </el-col>
               <el-col :span="12">
                  <el-form-item :label="$tx('Menu Name')" prop="menuName">
                     <el-input v-model="form.menuName" :placeholder="$tx('Enter menu name')" />
                  </el-form-item>
               </el-col>
               <el-col :span="12" v-if="form.menuType == 'C'">
                  <el-form-item prop="routeName">
                     <template #label>
                        <span>
                           <el-tooltip :content="$tx('Defaults to the route path when empty. Use a unique custom name when routes could conflict.')" placement="top">
                              <el-icon><question-filled /></el-icon>
                           </el-tooltip> {{ $tx('Route Name') }} </span>
                     </template>
                     <el-input v-model="form.routeName" :placeholder="$tx('Enter route name')" />
                  </el-form-item>
               </el-col>
               <el-col :span="12" v-if="form.menuType != 'F'">
                  <el-form-item>
                     <template #label>
                        <span>
                           <el-tooltip :content="$tx('External routes must start with http:// or https://')" placement="top">
                              <el-icon><question-filled /></el-icon>
                           </el-tooltip>{{ $tx('External Link') }} </span>
                     </template>
                     <el-radio-group v-model="form.isFrame">
                        <el-radio value="0">{{ $tx('Yes') }}</el-radio>
                        <el-radio value="1">{{ $tx('No') }}</el-radio>
                     </el-radio-group>
                  </el-form-item>
               </el-col>
               <el-col :span="12" v-if="form.menuType != 'F'">
                  <el-form-item prop="path">
                     <template #label>
                        <span>
                           <el-tooltip :content="$tx('Route path, for example user. Internal links to external URLs must start with http:// or https://')" placement="top">
                              <el-icon><question-filled /></el-icon>
                           </el-tooltip> {{ $tx('Route Path') }} </span>
                     </template>
                     <el-input v-model="form.path" :placeholder="$tx('Enter route path')" />
                  </el-form-item>
               </el-col>
               <el-col :span="12" v-if="form.menuType == 'C'">
                  <el-form-item prop="component">
                     <template #label>
                        <span>
                           <el-tooltip :content="$tx('Component path under views, for example system/user/index')" placement="top">
                              <el-icon><question-filled /></el-icon>
                           </el-tooltip> {{ $tx('Component Path') }} </span>
                     </template>
                     <el-input v-model="form.component" :placeholder="$tx('Enter component path')" />
                  </el-form-item>
               </el-col>
               <el-col :span="12" v-if="form.menuType != 'M'">
                  <el-form-item>
                     <el-input v-model="form.perms" :placeholder="$tx('Enter permission identifier')" maxlength="100" />
                     <template #label>
                        <span>
                           <el-tooltip :content="$tx('Permission used by controller authorization, for example: @PreAuthorize(`@ss.hasPermi(\'system:user:list\')`)')" placement="top">
                              <el-icon><question-filled /></el-icon>
                           </el-tooltip> {{ $tx('Permission') }} </span>
                     </template>
                  </el-form-item>
               </el-col>
               <el-col :span="12" v-if="form.menuType == 'C'">
                  <el-form-item>
                     <el-input v-model="form.query" :placeholder="$tx('Enter route parameters')" maxlength="255" />
                     <template #label>
                        <span>
                           <el-tooltip :content="$tx('Default route parameters, for example: `{&quot;id&quot;: 1, &quot;name&quot;: &quot;ry&quot;}`')" placement="top">
                              <el-icon><question-filled /></el-icon>
                           </el-tooltip> {{ $tx('Route Parameters') }} </span>
                     </template>
                  </el-form-item>
               </el-col>
               <el-col :span="12" v-if="form.menuType == 'C'">
                  <el-form-item>
                     <template #label>
                        <span>
                           <el-tooltip :content="$tx('Cached routes require the component name to match the route')" placement="top">
                              <el-icon><question-filled /></el-icon>
                           </el-tooltip> {{ $tx('Cache Route') }} </span>
                     </template>
                     <el-radio-group v-model="form.isCache">
                        <el-radio value="0">{{ $tx('Cache') }}</el-radio>
                        <el-radio value="1">{{ $tx('Do Not Cache') }}</el-radio>
                     </el-radio-group>
                  </el-form-item>
               </el-col>
               <el-col :span="12" v-if="form.menuType != 'F'">
                  <el-form-item>
                     <template #label>
                        <span>
                           <el-tooltip :content="$tx('Hidden routes remain accessible but do not appear in the sidebar')" placement="top">
                              <el-icon><question-filled /></el-icon>
                           </el-tooltip> {{ $tx('Visibility') }} </span>
                     </template>
                     <el-radio-group v-model="form.visible">
                        <el-radio
                           v-for="dict in sys_show_hide"
                           :key="dict.value"
                           :value="dict.value"
                        >{{ dict.label }}</el-radio>
                     </el-radio-group>
                  </el-form-item>
               </el-col>
               <el-col :span="12">
                  <el-form-item>
                     <template #label>
                        <span>
                           <el-tooltip :content="$tx('Disabled routes are hidden and cannot be accessed')" placement="top">
                              <el-icon><question-filled /></el-icon>
                           </el-tooltip> {{ $tx('Menu Status') }} </span>
                     </template>
                     <el-radio-group v-model="form.status">
                        <el-radio
                           v-for="dict in sys_normal_disable"
                           :key="dict.value"
                           :value="dict.value"
                        >{{ dict.label }}</el-radio>
                     </el-radio-group>
                  </el-form-item>
               </el-col>
            </el-row>
         </el-form>
         <template #footer>
            <div class="dialog-footer">
               <el-button type="primary" @click="submitForm">{{ $tx('Confirm') }}</el-button>
               <el-button @click="cancel">{{ $tx('Cancel') }}</el-button>
            </div>
         </template>
      </el-dialog>
   </div>
</template>

<script setup name="Menu">
import { addMenu, delMenu, getMenu, listMenu, updateMenu, updateMenuSort } from "@/api/system/menu"
import SvgIcon from "@/components/SvgIcon"
import IconSelect from "@/components/IconSelect"
import { localizeMenuName, localizeMenuTree } from '@/i18n/dataLabels'

const { proxy } = getCurrentInstance()
const { sys_show_hide, sys_normal_disable } = useDict("sys_show_hide", "sys_normal_disable")

const menuList = ref([])
const open = ref(false)
const loading = ref(true)
const showSearch = ref(true)
const title = ref("")
const menuOptions = ref([])
const isExpandAll = ref(false)
const refreshTable = ref(true)
const iconSelectRef = ref(null)
const originalOrders = ref({})

const data = reactive({
  form: {},
  queryParams: {
    menuName: undefined,
    visible: undefined
  },
  rules: {
    menuName: [{ required: true, message: tx('Menu name is required'), trigger: "blur" }],
    orderNum: [{ required: true, message: tx('Display order is required'), trigger: "blur" }],
    path: [{ required: true, message: tx('Route path is required'), trigger: "blur" }]
  },
})

const { queryParams, form, rules } = toRefs(data)

/** 查询菜单列表 */
function getList() {
  loading.value = true
  listMenu(queryParams.value).then(response => {
    menuList.value = proxy.handleTree(response.data, "menuId")
    recordOriginalOrders(menuList.value)
    loading.value = false
  })
}

/** 查询菜单下拉树结构 */
function getTreeselect() {
  menuOptions.value = []
  listMenu().then(response => {
    const menu = { menuId: 0, menuName: tx('Root'), displayName: tx('Root'), children: [] }
    menu.children = localizeMenuTree(proxy.handleTree(response.data, "menuId"), 'menuName', 'displayName')
    menuOptions.value.push(menu)
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
    menuId: undefined,
    parentId: 0,
    menuName: undefined,
    icon: undefined,
    menuType: "M",
    orderNum: undefined,
    isFrame: "1",
    isCache: "0",
    visible: "0",
    status: "0"
  }
  proxy.resetForm("menuRef")
}

/** 展示下拉图标 */
function showSelectIcon() {
  iconSelectRef.value.reset()
}

/** 选择图标 */
function selected(name) {
  form.value.icon = name
}

/** 搜索按钮操作 */
function handleQuery() {
  getList()
}

/** 重置按钮操作 */
function resetQuery() {
  proxy.resetForm("queryRef")
  handleQuery()
}

/** 新增按钮操作 */
function handleAdd(row) {
  reset()
  getTreeselect()
  if (row != null && row.menuId) {
    form.value.parentId = row.menuId
  } else {
    form.value.parentId = 0
  }
  open.value = true
  title.value = tx('Add Menu')
}

/** 展开/折叠操作 */
function toggleExpandAll() {
  refreshTable.value = false
  isExpandAll.value = !isExpandAll.value
  nextTick(() => {
    refreshTable.value = true
  })
}

/** 修改按钮操作 */
async function handleUpdate(row) {
  reset()
  await getTreeselect()
  getMenu(row.menuId).then(response => {
    form.value = response.data
    open.value = true
    title.value = tx('Edit Menu')
  })
}

/** 提交按钮 */
function submitForm() {
  proxy.$refs["menuRef"].validate(valid => {
    if (valid) {
      if (form.value.menuId != undefined) {
        updateMenu(form.value).then(response => {
          proxy.$modal.msgSuccess(tx('Updated successfully'))
          open.value = false
          getList()
        })
      } else {
        addMenu(form.value).then(response => {
          proxy.$modal.msgSuccess(tx('Added successfully'))
          open.value = false
          getList()
        })
      }
    }
  })
}


/** 递归记录原始排序 */
function recordOriginalOrders(list) {
  list.forEach(item => {
    originalOrders.value[item.menuId] = item.orderNum
    if (item.children && item.children.length) {
      recordOriginalOrders(item.children)
    }
  })
}

/** 保存排序 */
function handleSaveSort() {
  const changedMenuIds = []
  const changedOrderNums = []
  const collectChanged = (list) => {
    list.forEach(item => {
      if (String(originalOrders.value[item.menuId]) !== String(item.orderNum)) {
        changedMenuIds.push(item.menuId)
        changedOrderNums.push(item.orderNum)
      }
      if (item.children && item.children.length) {
        collectChanged(item.children)
      }
    })
  }
  collectChanged(menuList.value)
  if (changedMenuIds.length === 0) {
   proxy.$modal.msgWarning(tx('No order changes detected'))
    return
  }
  updateMenuSort({ menuIds: changedMenuIds.join(","), orderNums: changedOrderNums.join(",") }).then(() => {
   proxy.$modal.msgSuccess(tx('Order saved'))
    recordOriginalOrders(menuList.value)
  })
}

/** 删除按钮操作 */
function handleDelete(row) {
  proxy.$modal.confirm(tx('Delete menu "') + localizeMenuName(row.menuName) + '"?').then(function() {
    return delMenu(row.menuId)
  }).then(() => {
    getList()
    proxy.$modal.msgSuccess(tx('Deleted successfully'))
  }).catch(() => {})
}

getList()
</script>
