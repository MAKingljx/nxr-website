<template>
  <el-form ref="pwdRef" :model="user" :rules="rules" label-width="80px">
    <el-form-item :label="$tx('Current Password')" prop="oldPassword">
      <el-input v-model="user.oldPassword" :placeholder="$tx('Enter current password')" type="password" show-password />
    </el-form-item>
    <el-form-item :label="$tx('New Password')" prop="newPassword" :rules="infoPwdValidator">
      <el-input v-model="user.newPassword" :placeholder="$tx('Enter new password')" type="password" show-password />
    </el-form-item>
    <el-form-item :label="$tx('Confirm Password')" prop="confirmPassword">
      <el-input v-model="user.confirmPassword" :placeholder="$tx('Confirm new password')" type="password" show-password />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="submit">{{ $tx('Save') }}</el-button>
      <el-button type="danger" @click="close">{{ $tx('Close') }}</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup>
import { usePasswordRule } from "@/utils/passwordRule"
import { updateUserPwd } from "@/api/system/user"

const { proxy } = getCurrentInstance()
const { infoPwdValidator } = usePasswordRule()

const user = reactive({
  oldPassword: undefined,
  newPassword: undefined,
  confirmPassword: undefined
})

const equalToPassword = (rule, value, callback) => {
  if (user.newPassword !== value) {
    callback(new Error(tx('Passwords do not match')))
  } else {
    callback()
  }
}

const rules = ref({
  oldPassword: [{ required: true, message: tx('Current password is required'), trigger: "blur" }],
  confirmPassword: [{ required: true, message: tx('Confirm your new password'), trigger: "blur" }, { required: true, validator: equalToPassword, trigger: "blur" }]
})

/** 提交按钮 */
function submit() {
  proxy.$refs.pwdRef.validate(valid => {
    if (valid) {
      updateUserPwd(user.oldPassword, user.newPassword).then(() => {
        proxy.$modal.msgSuccess(tx('Password updated'))
      })
    }
  })
}

/** 关闭按钮 */
function close() {
  proxy.$tab.closePage()
}
</script>
