<template>
   <el-form ref="userRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item :label="$tx('Display Name')" prop="nickName">
         <el-input v-model="form.nickName" maxlength="30" />
      </el-form-item>
      <el-form-item :label="$tx('Phone')" prop="phonenumber">
         <el-input v-model="form.phonenumber" maxlength="11" />
      </el-form-item>
      <el-form-item :label="$tx('Email')" prop="email">
         <el-input v-model="form.email" maxlength="50" />
      </el-form-item>
      <el-form-item :label="$tx('Gender')">
         <el-radio-group v-model="form.sex">
            <el-radio value="0">{{ $tx('Male') }}</el-radio>
            <el-radio value="1">{{ $tx('Female') }}</el-radio>
         </el-radio-group>
      </el-form-item>
      <el-form-item>
      <el-button type="primary" @click="submit">{{ $tx('Save') }}</el-button>
      <el-button type="danger" @click="close">{{ $tx('Close') }}</el-button>
      </el-form-item>
   </el-form>
</template>

<script setup>
import { updateUserProfile } from "@/api/system/user"

const props = defineProps({
  user: {
    type: Object
  }
})

const { proxy } = getCurrentInstance()

const form = ref({})
const rules = ref({
  nickName: [{ required: true, message: tx('Display name is required'), trigger: "blur" }],
  email: [{ required: true, message: tx('Email address is required'), trigger: "blur" }, { type: "email", message: tx('Enter a valid email address'), trigger: ["blur", "change"] }],
  phonenumber: [{ required: true, message: tx('Phone number is required'), trigger: "blur" }, { pattern: /^1[3|4|5|6|7|8|9][0-9]\d{8}$/, message: tx('Enter a valid phone number'), trigger: "blur" }],
})

/** 提交按钮 */
function submit() {
  proxy.$refs.userRef.validate(valid => {
    if (valid) {
      updateUserProfile(form.value).then(() => {
        proxy.$modal.msgSuccess(tx('Profile updated'))
        props.user.phonenumber = form.value.phonenumber
        props.user.email = form.value.email
      })
    }
  })
}

/** 关闭按钮 */
function close() {
  proxy.$tab.closePage()
}

// 回显当前登录用户信息
watch(() => props.user, user => {
  if (user) {
    form.value = { nickName: user.nickName, phonenumber: user.phonenumber, email: user.email, sex: user.sex }
  }
},{ immediate: true })
</script>
