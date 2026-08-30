<template>
  <el-drawer :title="$tx('User Details')" v-model="visible" direction="rtl" size="68%" append-to-body :before-close="handleClose" class="detail-drawer">
    <div v-loading="loading" class="drawer-content">
      <!-- 基本信息 -->
      <h4 class="section-header">{{ $tx('Basic Information') }}</h4>
      <el-row :gutter="20" class="mb8">
        <el-col :span="24">
          <div class="info-item">
            <label class="info-label">{{ $tx('Display Name:') }}</label>
            <span class="info-value plaintext">{{ info.nickName }}</span>
          </div>
        </el-col>
      </el-row>
      <el-row :gutter="20" class="mb8">
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Phone:') }}</label>
            <span class="info-value plaintext">{{ info.phonenumber }}</span>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Email:') }}</label>
            <span class="info-value plaintext">{{ info.email }}</span>
          </div>
        </el-col>
      </el-row>
      <el-row :gutter="20" class="mb8">
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Username:') }}</label>
            <span class="info-value plaintext">{{ info.userName }}</span>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Status:') }}</label>
            <span class="info-value plaintext">
              <el-tag size="small" :type="info.status === '0' ? 'success' : 'danger'">{{ info.status === '0' ? 'Active' : 'Disabled' }}</el-tag>
            </span>
          </div>
        </el-col>
      </el-row>
      <el-row :gutter="20" class="mb8">
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Positions:') }}</label>
            <span class="info-value plaintext">{{ postNames || 'None' }}</span>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Gender:') }}</label>
            <span class="info-value plaintext">{{ sexLabel }}</span>
          </div>
        </el-col>
      </el-row>
      <el-row :gutter="20" class="mb8">
        <el-col :span="24">
          <div class="info-item full-width">
            <label class="info-label">{{ $tx('Roles:') }}</label>
            <span class="info-value plaintext">{{ roleNames || 'None' }}</span>
          </div>
        </el-col>
      </el-row>
      <!-- 其他信息 -->
      <h4 class="section-header">{{ $tx('Additional Information') }}</h4>
      <el-row :gutter="20" class="mb8">
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Created By:') }}</label>
            <span class="info-value plaintext">{{ info.createBy }}</span>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Created At:') }}</label>
            <span class="info-value plaintext">{{ info.createTime }}</span>
          </div>
        </el-col>
      </el-row>
      <el-row :gutter="20" class="mb8">
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Updated By:') }}</label>
            <span class="info-value plaintext">{{ info.updateBy }}</span>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Updated At:') }}</label>
            <span class="info-value plaintext">{{ info.updateTime }}</span>
          </div>
        </el-col>
      </el-row>
      <el-row :gutter="20" class="mb8">
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Last Login IP:') }}</label>
            <span class="info-value plaintext">{{ info.loginIp }}</span>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="info-item">
            <label class="info-label">{{ $tx('Last Login At:') }}</label>
            <span class="info-value plaintext">{{ info.loginDate }}</span>
          </div>
        </el-col>
      </el-row>
      <el-row :gutter="20" class="mb8">
        <el-col :span="24">
          <div class="info-item full-width">
            <label class="info-label">{{ $tx('Notes:') }}</label>
            <span class="info-value plaintext">{{ info.remark }}</span>
          </div>
        </el-col>
      </el-row>
    </div>
  </el-drawer>
</template>

<script setup>
import { getUser } from '@/api/system/user'
import { localizePostName, localizeRoleName } from '@/i18n/dataLabels'

const visible = ref(false)
const loading = ref(false)
const info = reactive({})
const postOptions = ref([])
const roleOptions = ref([])

const sexLabel = computed(() => ({
  '0': 'Male',
  '1': 'Female',
  '2': 'Not specified'
}[info.sex] || '-'))

const postNames = computed(() => {
  if (!postOptions.value.length || !info.postIds) return ''
  return postOptions.value.filter(p => info.postIds?.includes(p.postId)).map(p => localizePostName(p.postName)).join(', ') || ''
})

const roleNames = computed(() => {
  if (!roleOptions.value.length || !info.roleIds) return ''
  return roleOptions.value.filter(r => info.roleIds?.includes(r.roleId)).map(r => localizeRoleName(r.roleName)).join(', ') || ''
})

const open = async (userId) => {
  visible.value = true
  loading.value = true
  try {
    const res = await getUser(userId)
    Object.assign(info, res.data || {})
    postOptions.value = res.posts || []
    roleOptions.value = res.roles || []
    info.postIds = res.postIds || []
    info.roleIds = res.roleIds || []
  } catch (error) {
    console.error('Failed to load user information:', error)
  } finally {
    loading.value = false
  }
}

function handleClose() {
  visible.value = false
}

defineExpose({
  open
})
</script>
