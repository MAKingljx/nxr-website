<script setup lang="ts">
import { computed, useId } from 'vue'

export interface PhoenixRoleOption {
  key: string
  label: string
  disabled?: boolean
}

export interface PhoenixPermissionOption {
  key: string
  label: string
  disabled?: boolean
}

export interface PhoenixRolePermissionChange {
  roleKey: string
  permissionKey: string
  checked: boolean
  value: Record<string, string[]>
}

const props = withDefaults(defineProps<{
  modelValue?: Record<string, string[]>
  roles?: PhoenixRoleOption[]
  permissions?: PhoenixPermissionOption[]
  title?: string
  disabled?: boolean
  readonly?: boolean
}>(), {
  modelValue: () => ({}),
  roles: () => [],
  permissions: () => [],
  title: '角色权限配置',
  disabled: false,
  readonly: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, string[]>]
  change: [change: PhoenixRolePermissionChange]
}>()

const uid = useId()
const uniqueRoles = computed(() => deduplicate(props.roles))
const uniquePermissions = computed(() => deduplicate(props.permissions))

function deduplicate<T extends { key: string }>(items: T[]) {
  const seen = new Set<string>()
  return items.filter((item) => {
    if (!item.key || seen.has(item.key)) return false
    seen.add(item.key)
    return true
  })
}

function isChecked(roleKey: string, permissionKey: string) {
  return (props.modelValue[roleKey] ?? []).includes(permissionKey)
}

function isDisabled(role: PhoenixRoleOption, permission: PhoenixPermissionOption) {
  return props.disabled || props.readonly || Boolean(role.disabled) || Boolean(permission.disabled)
}

function fieldName(roleIndex: number, permissionIndex: number) {
  return `phoenix-permission-${uid}-${roleIndex}-${permissionIndex}`
}

function toggle(roleKey: string, permissionKey: string, checked: boolean) {
  const next = Object.fromEntries(
    Object.entries(props.modelValue).map(([key, values]) => [key, [...new Set(values)]])
  )
  const selected = new Set(next[roleKey] ?? [])
  if (checked) selected.add(permissionKey)
  else selected.delete(permissionKey)
  next[roleKey] = [...selected]
  emit('update:modelValue', next)
  emit('change', { roleKey, permissionKey, checked, value: next })
}
</script>

<template>
  <div class="px-role-permission-matrix">
    <div v-if="!uniqueRoles.length || !uniquePermissions.length" class="px-role-permission-matrix__empty" role="status">暂无可配置的角色或权限</div>
    <div v-else class="px-role-permission-matrix__scroller" tabindex="0" :aria-label="title">
      <table>
        <caption>{{ title }}</caption>
        <thead>
          <tr>
            <th scope="col">角色</th>
            <th v-for="permission in uniquePermissions" :key="permission.key" scope="col">{{ permission.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(role, roleIndex) in uniqueRoles" :key="role.key">
            <th scope="row">{{ role.label }}</th>
            <td v-for="(permission, permissionIndex) in uniquePermissions" :key="permission.key">
              <label :for="fieldName(roleIndex, permissionIndex)">
                <input
                  :id="fieldName(roleIndex, permissionIndex)"
                  type="checkbox"
                  :name="fieldName(roleIndex, permissionIndex)"
                  :checked="isChecked(role.key, permission.key)"
                  :disabled="isDisabled(role, permission)"
                  :aria-label="`${role.label}：${permission.label}`"
                  @change="toggle(role.key, permission.key, ($event.target as HTMLInputElement).checked)"
                />
                <span aria-hidden="true"></span>
              </label>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
