<template>
  <div class="app-container tree-sidebar-manage-wrap">
    <div class="tree-sidebar-content">
      <div class="content-inner">
        <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="68px">
          <el-form-item :label="$tx('Username')" prop="userName">
            <el-input v-model="queryParams.userName" :placeholder="$tx('Enter username')" clearable style="width: 240px" @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item :label="$tx('Phone')" prop="phonenumber">
            <el-input v-model="queryParams.phonenumber" :placeholder="$tx('Enter phone number')" clearable style="width: 240px" @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item :label="$tx('Status')" prop="status">
            <el-select v-model="queryParams.status" :placeholder="$tx('All statuses')" clearable style="width: 240px">
              <el-option v-for="option in accountStatusOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item :label="$tx('Created')" style="width: 308px">
            <el-date-picker v-model="dateRange" value-format="YYYY-MM-DD" type="daterange" range-separator="-" :start-placeholder="$tx('Start date')" :end-placeholder="$tx('End date')"></el-date-picker>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="Search" @click="handleQuery">{{ $tx('Search') }}</el-button>
            <el-button icon="Refresh" @click="resetQuery">{{ $tx('Reset') }}</el-button>
          </el-form-item>
        </el-form>

        <el-row :gutter="10" class="mb8">
          <el-col :span="1.5">
            <el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['system:user:add']">{{ $tx('Add') }}</el-button>
          </el-col>
          <el-col :span="1.5">
            <el-button type="success" plain icon="Edit" :disabled="single" @click="handleUpdate" v-hasPermi="['system:user:edit']">{{ $tx('Edit') }}</el-button>
          </el-col>
          <el-col :span="1.5">
            <el-button type="danger" plain icon="Delete" :disabled="multiple" @click="handleDelete" v-hasPermi="['system:user:remove']">{{ $tx('Delete') }}</el-button>
          </el-col>
          <el-col :span="1.5">
            <el-button type="info" plain icon="Upload" @click="handleImport" v-hasPermi="['system:user:import']">{{ $tx('Import') }}</el-button>
          </el-col>
          <el-col :span="1.5">
            <el-button type="warning" plain icon="Download" @click="handleExport" v-hasPermi="['system:user:export']">{{ $tx('Export') }}</el-button>
          </el-col>
          <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" :columns="columns" storageKey="xxxxxxxx"></right-toolbar>
        </el-row>

        <el-table v-loading="loading" :data="userList" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="50" align="center" />
          <el-table-column :label="$tx('User ID')" align="center" key="userId" prop="userId" v-if="columns.userId.visible" />
          <el-table-column :label="$tx('Username')" align="center" key="userName" v-if="columns.userName.visible" :show-overflow-tooltip="true">
            <template #default="scope">
              <a class="link-type" style="cursor:pointer" @click="handleViewData(scope.row)">{{ scope.row.userName }}</a>
            </template>
         </el-table-column>
          <el-table-column :label="$tx('Display Name')" align="center" key="nickName" prop="nickName" v-if="columns.nickName.visible" :show-overflow-tooltip="true" />
          <el-table-column :label="$tx('Phone')" align="center" key="phonenumber" prop="phonenumber" v-if="columns.phonenumber.visible" width="120" />
          <el-table-column :label="$tx('Status')" align="center" key="status" v-if="columns.status.visible">
            <template #default="scope">
              <el-switch
                v-model="scope.row.status"
                active-value="0"
                inactive-value="1"
                @change="handleStatusChange(scope.row)"
              ></el-switch>
            </template>
          </el-table-column>
          <el-table-column :label="$tx('Created At')" align="center" prop="createTime" v-if="columns.createTime.visible" width="160">
            <template #default="scope">
              <span>{{ parseTime(scope.row.createTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="$tx('Actions')" align="center" width="150" class-name="small-padding fixed-width">
            <template #default="scope">
              <el-tooltip :content="$tx('Edit')" placement="top" v-if="scope.row.userId !== 1">
                <el-button link type="primary" icon="Edit" @click="handleUpdate(scope.row)" v-hasPermi="['system:user:edit']"></el-button>
              </el-tooltip>
              <el-tooltip :content="$tx('Delete')" placement="top" v-if="scope.row.userId !== 1">
                <el-button link type="primary" icon="Delete" @click="handleDelete(scope.row)" v-hasPermi="['system:user:remove']"></el-button>
              </el-tooltip>
              <el-tooltip :content="$tx('Reset Password')" placement="top" v-if="scope.row.userId !== 1">
                <el-button link type="primary" icon="Key" @click="handleResetPwd(scope.row)" v-hasPermi="['system:user:resetPwd']"></el-button>
              </el-tooltip>
              <el-tooltip :content="$tx('Assign Roles')" placement="top" v-if="scope.row.userId !== 1">
                <el-button link type="primary" icon="CircleCheck" @click="handleAuthRole(scope.row)" v-hasPermi="['system:user:edit']"></el-button>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
        <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
      </div>
    </div>

    <!-- 添加或修改用户配置对话框 -->
    <el-dialog :title="title" v-model="open" width="600px" append-to-body>
      <el-form :model="form" :rules="rules" ref="userRef" label-width="80px">
        <el-row>
          <el-col :span="24">
            <el-form-item :label="$tx('Display Name')" prop="nickName">
              <el-input v-model="form.nickName" :placeholder="$tx('Enter display name')" maxlength="30" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item :label="$tx('Phone')" prop="phonenumber">
              <el-input v-model="form.phonenumber" :placeholder="$tx('Enter phone number')" maxlength="11" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="$tx('Email')" prop="email">
              <el-input v-model="form.email" :placeholder="$tx('Enter email address')" maxlength="50" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item v-if="form.userId == undefined" :label="$tx('Username')" prop="userName">
              <el-input v-model="form.userName" :placeholder="$tx('Enter username')" maxlength="30" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item v-if="form.userId == undefined" :label="$tx('Password')" prop="password" :rules="pwdValidator">
              <el-input v-model="form.password" :placeholder="$tx('Enter password')" type="password" maxlength="20" show-password />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item :label="$tx('Gender')">
              <el-select v-model="form.sex" :placeholder="$tx('Select gender')">
                <el-option v-for="option in genderOptions" :key="option.value" :label="option.label" :value="option.value"></el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="$tx('Status')">
              <el-radio-group v-model="form.status">
                <el-radio v-for="option in accountStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item :label="$tx('Positions')">
              <el-select v-model="form.postIds" multiple :placeholder="$tx('Select positions')">
                <el-option v-for="item in postOptions" :key="item.postId" :label="localizePostName(item.postName)" :value="item.postId" :disabled="item.status == 1"></el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="$tx('Roles')">
              <el-select v-model="form.roleIds" multiple :placeholder="$tx('Select roles')">
                <el-option v-for="item in roleOptions" :key="item.roleId" :label="localizeRoleName(item.roleName)" :value="item.roleId" :disabled="item.status == 1"></el-option>
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="24">
            <el-form-item :label="$tx('Notes')">
              <el-input v-model="form.remark" type="textarea" :placeholder="$tx('Enter notes')"></el-input>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="submitForm">{{ $tx('Save') }}</el-button>
          <el-button @click="cancel">{{ $tx('Cancel') }}</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 用户详情抽屉 -->
    <user-view-drawer ref="userViewRef" />
    <!-- 用户导入对话框 -->
    <excel-import-dialog ref="importUserRef" :title="$tx('Import Users')" action="/system/user/importData" template-action="/system/user/importTemplate" template-file-name="user_template" :update-support-label="$tx('Update users that already exist')" @success="getList" />
  </div>
</template>

<script setup name="User">
import ExcelImportDialog from "@/components/ExcelImportDialog"
import UserViewDrawer from "./view"
import { usePasswordRule } from "@/utils/passwordRule"
import { changeUserStatus, listUser, resetUserPwd, delUser, getUser, updateUser, addUser } from "@/api/system/user"
import { localizePostName, localizeRoleName } from '@/i18n/dataLabels'

const router = useRouter()
const { proxy } = getCurrentInstance()
const { pwdValidator, pwdPromptValidator } = usePasswordRule()
const accountStatusOptions = [
  { value: '0', label: tx('Active') },
  { value: '1', label: tx('Disabled') }
]
const genderOptions = [
  { value: '0', label: tx('Male') },
  { value: '1', label: tx('Female') },
  { value: '2', label: tx('Not specified') }
]

const userList = ref([])
const open = ref(false)
const loading = ref(true)
const showSearch = ref(true)
const ids = ref([])
const single = ref(true)
const multiple = ref(true)
const total = ref(0)
const title = ref("")
const dateRange = ref([])
const initPassword = ref(undefined)
const postOptions = ref([])
const roleOptions = ref([])
// 列显隐信息
const columns = ref({
  userId: { label: tx('User ID'), visible: true },
  userName: { label: tx('Username'), visible: true },
  nickName: { label: tx('Display Name'), visible: true },
  phonenumber: { label: tx('Phone'), visible: true },
  status: { label: tx('Status'), visible: true },
  createTime: { label: tx('Created At'), visible: true }
})

const data = reactive({
  form: {},
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    userName: undefined,
    phonenumber: undefined,
    status: undefined
  },
  rules: {
    userName: [{ required: true, message: tx('Username is required'), trigger: "blur" }, { min: 2, max: 20, message: tx('Username must contain 2 to 20 characters'), trigger: "blur" }],
    nickName: [{ required: true, message: tx('Display name is required'), trigger: "blur" }],
    email: [{ type: "email", message: tx('Enter a valid email address'), trigger: ["blur", "change"] }],
    phonenumber: [{ pattern: /^1[3|4|5|6|7|8|9][0-9]\d{8}$/, message: tx('Enter a valid phone number'), trigger: "blur" }]
  }
})

const { queryParams, form, rules } = toRefs(data)

/** 查询用户列表 */
function getList() {
  loading.value = true
  listUser(proxy.addDateRange(queryParams.value, dateRange.value)).then(res => {
    loading.value = false
    userList.value = res.rows
    total.value = res.total
  })
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

/** 删除按钮操作 */
function handleDelete(row) {
  const userIds = row.userId || ids.value
  proxy.$modal.confirm(tx('Delete user ID(s) "{ids}"?', { ids: userIds })).then(function () {
    return delUser(userIds)
  }).then(() => {
    getList()
    proxy.$modal.msgSuccess(tx('User deleted'))
  }).catch(() => {})
}

/** 导出按钮操作 */
function handleExport() {
  proxy.download("system/user/export", {
    ...queryParams.value,
  },`user_${new Date().getTime()}.xlsx`)
}

/** 用户状态修改  */
function handleStatusChange(row) {
  const action = row.status === "0" ? 'enable' : 'disable'
  const result = row.status === "0" ? 'enabled' : 'disabled'
  proxy.$modal.confirm(tx('Are you sure you want to {action} user "{username}"?', { action: tx(action), username: row.userName })).then(function () {
    return changeUserStatus(row.userId, row.status)
  }).then(() => {
    proxy.$modal.msgSuccess(tx('User {action}', { action: tx(result) }))
  }).catch(function () {
    row.status = row.status === "0" ? "1" : "0"
  })
}

/** 更多操作 */
function handleCommand(command, row) {
  switch (command) {
    case "handleResetPwd":
      handleResetPwd(row)
      break
    case "handleAuthRole":
      handleAuthRole(row)
      break
    default:
      break
  }
}

/** 跳转角色分配 */
function handleAuthRole(row) {
  const userId = row.userId
  router.push("/system/user-auth/role/" + userId)
}

/** 重置密码按钮操作 */
function handleResetPwd(row) {
  proxy.$prompt(tx('Enter a new password for {username}', { username: row.userName }), tx('Reset Password'), {
    confirmButtonText: tx('Save'),
    cancelButtonText: tx('Cancel'),
    closeOnClickModal: false,
    inputValidator: pwdPromptValidator
  }).then(({ value }) => {
    resetUserPwd(row.userId, value).then(() => {
      proxy.$modal.msgSuccess(tx('Password updated. New password: {password}', { password: value }))
    })
  }).catch(() => {})
}

/** 选择条数  */
function handleSelectionChange(selection) {
  ids.value = selection.map(item => item.userId)
  single.value = selection.length != 1
  multiple.value = !selection.length
}

/** 详情按钮操作 */
function handleViewData(row) {
  proxy.$refs["userViewRef"].open(row.userId)
}

/** 导入按钮操作 */
function handleImport() {
  proxy.$refs["importUserRef"].open()
}

/** 重置操作表单 */
function reset() {
  form.value = {
    userId: undefined,
    deptId: undefined,
    userName: undefined,
    nickName: undefined,
    password: undefined,
    phonenumber: undefined,
    email: undefined,
    sex: undefined,
    status: "0",
    remark: undefined,
    postIds: [],
    roleIds: []
  }
  proxy.resetForm("userRef")
}

/** 取消按钮 */
function cancel() {
  open.value = false
  reset()
}

/** 新增按钮操作 */
function handleAdd() {
  reset()
  getUser().then(response => {
    postOptions.value = response.posts
    roleOptions.value = response.roles
    open.value = true
    title.value = tx('Add User')
    form.value.password = initPassword.value
  })
}

/** 修改按钮操作 */
function handleUpdate(row) {
  reset()
  const userId = row.userId || ids.value
  getUser(userId).then(response => {
    form.value = response.data
    postOptions.value = response.posts
    roleOptions.value = response.roles
    form.value.postIds = response.postIds
    form.value.roleIds = response.roleIds
    open.value = true
    title.value = tx('Edit User')
    form.value.password = ""
  })
}

/** 提交按钮 */
function submitForm() {
  proxy.$refs["userRef"].validate(valid => {
    if (valid) {
      if (form.value.userId != undefined) {
        updateUser(form.value).then(() => {
          proxy.$modal.msgSuccess(tx('User updated'))
          open.value = false
          getList()
        })
      } else {
        addUser(form.value).then(() => {
          proxy.$modal.msgSuccess(tx('User created'))
          open.value = false
          getList()
        })
      }
    }
  })
}

onMounted(() => {
  getList()
  proxy.getConfigKey("sys.user.initPassword").then(response => {
    initPassword.value = response.msg
  })
})
</script>
