<script setup lang="ts">
import { computed } from 'vue'

export interface PhoenixParticipant {
  id: string | number
  name: string
  role?: string
  avatar?: string
  status?: 'online' | 'away' | 'offline'
  muted?: boolean
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  participants: PhoenixParticipant[]
  selectedId?: string | number | null
  title?: string
  maxVisible?: number
  showInvite?: boolean
  emptyText?: string
}>(), {
  selectedId: null, title: '参与成员', maxVisible: 8, showInvite: false, emptyText: '暂无成员',
})

const emit = defineEmits<{
  'update:selectedId': [value: string | number]
  select: [participant: PhoenixParticipant]
  invite: []
}>()

const visible = computed(() => props.participants.slice(0, Math.max(0, Math.trunc(props.maxVisible))))
const remaining = computed(() => Math.max(0, props.participants.length - visible.value.length))

function safeAvatar(value?: string) {
  if (!value) return ''
  return /^(https?:\/\/|\/|\.\/|\.\.\/|blob:|data:image\/(?!svg\+xml)[a-z0-9.+-]+;base64,)/i.test(value.trim()) ? value.trim() : ''
}

function select(participant: PhoenixParticipant) {
  if (participant.disabled) return
  emit('update:selectedId', participant.id)
  emit('select', participant)
}
</script>

<template>
  <section class="px-participant-list" :aria-label="title">
    <header><h3>{{ title }}</h3><strong>{{ participants.length }} 人</strong></header>
    <p v-if="!participants.length" class="px-business-empty" role="status">{{ emptyText }}</p>
    <ul v-else>
      <li v-for="participant in visible" :key="participant.id">
        <button type="button" :class="{ 'is-selected': selectedId === participant.id }" :disabled="participant.disabled" @click="select(participant)">
          <span class="px-participant-list__avatar">
            <img v-if="safeAvatar(participant.avatar)" :src="safeAvatar(participant.avatar)" :alt="participant.name">
            <span v-else aria-hidden="true">{{ participant.name.slice(0, 1) }}</span>
            <i :class="`is-${participant.status || 'offline'}`" :aria-label="participant.status === 'online' ? '在线' : participant.status === 'away' ? '离开' : '离线'"></i>
          </span>
          <span><strong>{{ participant.name }}</strong><span v-if="participant.role">{{ participant.role }}</span></span>
          <span v-if="participant.muted" aria-label="已静音">静音</span>
        </button>
      </li>
    </ul>
    <footer v-if="remaining || showInvite"><span v-if="remaining">另有 {{ remaining }} 人</span><button v-if="showInvite" type="button" @click="emit('invite')">邀请成员</button></footer>
  </section>
</template>
