<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(defineProps<{
  name: string
  roles?: string[]
  detail?: string
  open?: boolean
  disabled?: boolean
  logoutText?: string
}>(), {
  roles: () => [],
  detail: '',
  open: false,
  disabled: false,
  logoutText: '退出登录',
})

const emit = defineEmits<{
  'update:open': [value: boolean]
  logout: []
}>()

const uid = useId()
const menuId = computed(() => `phoenix-user-menu-${uid}`)
const initials = computed(() => props.name.trim().slice(0, 2) || '用户')
const roleText = computed(() => props.roles.filter(Boolean).join('、') || '未设置角色')

function toggle() {
  if (!props.disabled) emit('update:open', !props.open)
}

function close() {
  if (props.open) emit('update:open', false)
}

function logout() {
  if (props.disabled) return
  emit('logout')
  close()
}
</script>

<template>
  <div class="px-user-menu" @keydown.esc="close">
    <button
      type="button"
      class="px-user-menu__trigger"
      aria-haspopup="menu"
      :aria-controls="menuId"
      :aria-expanded="open"
      :aria-label="`打开${name}的用户菜单`"
      :disabled="disabled"
      @click="toggle"
    >
      <span class="px-user-menu__avatar" aria-hidden="true">{{ initials }}</span>
      <span class="px-user-menu__identity"><strong>{{ name }}</strong></span>
      <span class="px-user-menu__chevron" aria-hidden="true">›</span>
    </button>

    <div v-if="open" :id="menuId" class="px-user-menu__panel" role="menu" :aria-label="`${name}的用户菜单`">
      <div class="px-user-menu__summary">
        <strong>{{ name }}</strong>
        <span>{{ roleText }}</span>
        <span v-if="detail">{{ detail }}</span>
      </div>
      <slot></slot>
      <button type="button" role="menuitem" class="px-user-menu__logout" :disabled="disabled" @click="logout">{{ logoutText }}</button>
    </div>
  </div>
</template>
