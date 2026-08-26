<script setup lang="ts">
import { ref } from 'vue'
import {
  PhoenixLoginPanel,
  PhoenixPermissionGuard,
  PhoenixRolePermissionMatrix,
  PhoenixUserMenu,
} from '../primitives/auth'

const username = ref('')
const password = ref('')
const remember = ref(false)
const userMenuOpen = ref(false)
const permissionValues = ref<Record<string, string[]>>({
  admin: ['resource.read', 'resource.write', 'user.manage'],
  editor: ['resource.read', 'resource.write'],
})

const roles = [
  { key: 'admin', label: '管理员' },
  { key: 'editor', label: '编辑人员' },
]
const permissions = [
  { key: 'resource.read', label: '查看资源' },
  { key: 'resource.write', label: '编辑资源' },
  { key: 'user.manage', label: '管理用户' },
]
</script>

<template>
  <div class="cg-auth-showcase">
    <article class="cg-auth-preview">
      <h3>登录面板</h3>
      <PhoenixLoginPanel
        v-model:username="username"
        v-model:password="password"
        v-model:remember="remember"
      />
    </article>

    <article class="cg-auth-preview">
      <h3>用户与权限</h3>
      <div class="cg-auth-preview__stack">
        <PhoenixUserMenu v-model:open="userMenuOpen" name="张三" :roles="['管理员']" />
        <PhoenixPermissionGuard :permissions="['resource.write']" :granted-permissions="['resource.read', 'resource.write']">
          <button type="button" class="cg-auth-action">允许编辑资源</button>
          <template #denied><span>无编辑权限</span></template>
        </PhoenixPermissionGuard>
      </div>
    </article>

    <article class="cg-auth-preview cg-auth-preview--wide">
      <h3>角色权限矩阵</h3>
      <PhoenixRolePermissionMatrix v-model="permissionValues" :roles="roles" :permissions="permissions" />
    </article>
  </div>
</template>
