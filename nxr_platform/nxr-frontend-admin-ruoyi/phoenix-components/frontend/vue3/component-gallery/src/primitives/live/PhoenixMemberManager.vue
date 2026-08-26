<script setup lang="ts">
import { computed } from 'vue'
import { safeCount, safeImageUrl } from './safety'

export interface PhoenixLiveRoleOption {
  value: string
  label: string
  disabled?: boolean
}

export interface PhoenixLiveMember {
  id: string | number
  name: string
  avatar?: string
  role?: string
  online?: boolean
  muted?: boolean
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  members: PhoenixLiveMember[]
  roles?: PhoenixLiveRoleOption[]
  selectedId?: string | number | null
  total?: number
  title?: string
  disabled?: boolean
  emptyText?: string
}>(), {
  roles: () => [],
  selectedId: null,
  total: 0,
  title: '直播成员',
  disabled: false,
  emptyText: '暂无成员',
})

const emit = defineEmits<{
  'update:selectedId': [id: string | number]
  select: [member: PhoenixLiveMember]
  'request-mute': [member: PhoenixLiveMember, muted: boolean]
  'request-remove': [member: PhoenixLiveMember]
  'request-role-change': [member: PhoenixLiveMember, role: string]
}>()

const safeTotal = computed(() => Math.max(props.members.length, safeCount(props.total)))
const uniqueRoles = computed(() => {
  const seen = new Set<string>()
  return props.roles.filter((role) => role.value && !seen.has(role.value) && Boolean(seen.add(role.value)))
})

function unavailable(member: PhoenixLiveMember) {
  return props.disabled || member.disabled
}

function select(member: PhoenixLiveMember) {
  if (unavailable(member)) return
  emit('update:selectedId', member.id)
  emit('select', member)
}

function changeRole(member: PhoenixLiveMember, event: Event) {
  if (!unavailable(member)) emit('request-role-change', member, (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <section class="px-member-manager" :aria-label="title">
    <header><h3>{{ title }}</h3><strong>{{ safeTotal }} 人</strong></header>
    <p v-if="!members.length" class="px-live-state" role="status">{{ emptyText }}</p>
    <ul v-else>
      <li v-for="member in members" :key="member.id" :class="{ 'is-selected': selectedId === member.id }">
        <button class="px-member-manager__identity" type="button" :disabled="unavailable(member)" :aria-pressed="selectedId === member.id" @click="select(member)">
          <span class="px-member-manager__avatar">
            <img v-if="safeImageUrl(member.avatar)" :src="safeImageUrl(member.avatar)" :alt="member.name" loading="lazy">
            <span v-else aria-hidden="true">{{ member.name.trim().slice(0, 1) || '员' }}</span>
            <i :class="{ 'is-online': member.online }" :aria-label="member.online ? '在线' : '离线'"></i>
          </span>
          <span><strong>{{ member.name }}</strong><small>{{ member.role || '普通成员' }}<template v-if="member.muted"> · 已禁言</template></small></span>
        </button>
        <select v-if="uniqueRoles.length" :value="member.role" :disabled="unavailable(member)" :aria-label="`设置${member.name}的角色`" @change="changeRole(member, $event)">
          <option value="" disabled>选择角色</option>
          <option v-for="role in uniqueRoles" :key="role.value" :value="role.value" :disabled="role.disabled">{{ role.label }}</option>
        </select>
        <div class="px-member-manager__actions">
          <button type="button" :disabled="unavailable(member)" @click="emit('request-mute', member, !member.muted)">{{ member.muted ? '请求解除禁言' : '请求禁言' }}</button>
          <button type="button" :disabled="unavailable(member)" @click="emit('request-remove', member)">请求移出</button>
        </div>
      </li>
    </ul>
  </section>
</template>
