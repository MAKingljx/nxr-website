<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  permissions?: string[]
  grantedPermissions?: string[]
  match?: 'all' | 'any'
}>(), {
  permissions: () => [],
  grantedPermissions: () => [],
  match: 'all',
})

const required = computed(() => [...new Set(props.permissions.filter(Boolean))])
const granted = computed(() => new Set(props.grantedPermissions.filter(Boolean)))
const missingPermissions = computed(() => required.value.filter((permission) => !granted.value.has(permission)))
const allowed = computed(() => {
  if (!required.value.length) return true
  return props.match === 'any'
    ? required.value.some((permission) => granted.value.has(permission))
    : missingPermissions.value.length === 0
})
</script>

<template>
  <slot v-if="allowed" :permissions="required"></slot>
  <slot v-else name="denied" :missing-permissions="missingPermissions"></slot>
</template>
