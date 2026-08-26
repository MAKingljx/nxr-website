<script setup lang="ts">
import { computed } from 'vue'
import {
  PhoenixPasswordChange,
  PhoenixSessionManager,
} from '../../../primitives/admin'
import type {
  PhoenixPasswordChangeValue,
  PhoenixSession,
} from '../../../primitives/admin'
import '../../../admin-primitives.css'

export interface PhoenixAccountSession {
  id: string | number
  clientName: string
  createdAt: string
  lastSeenAt: string
  expiresAt: string
  current: boolean
}

export interface PhoenixAccountPasswordChange {
  currentPassword: string
  newPassword: string
}

const props = withDefaults(defineProps<{
  title?: string
  username?: string
  passwordValue?: PhoenixPasswordChangeValue
  passwordRevealed?: boolean
  sessions?: PhoenixAccountSession[]
  passwordSubmitting?: boolean
  sessionsLoading?: boolean
  disabled?: boolean
  passwordError?: string
  sessionsError?: string
}>(), {
  title: '账户安全',
  username: '',
  passwordValue: () => ({ currentPassword: '', newPassword: '', confirmPassword: '' }),
  passwordRevealed: false,
  sessions: () => [],
  passwordSubmitting: false,
  sessionsLoading: false,
  disabled: false,
  passwordError: '',
  sessionsError: '',
})

const emit = defineEmits<{
  'update:passwordValue': [value: PhoenixPasswordChangeValue]
  'update:passwordRevealed': [value: boolean]
  'change-password': [value: PhoenixAccountPasswordChange]
  'revoke-session': [session: PhoenixAccountSession]
  'revoke-other-sessions': []
  'refresh-sessions': []
}>()

const displaySessions = computed<PhoenixSession[]>(() => props.sessions.map((session) => ({
  id: session.id,
  device: session.clientName,
  lastActive: session.lastSeenAt,
  createdAt: session.createdAt,
  expiresAt: session.expiresAt,
  current: session.current,
})))

function changePassword(value: PhoenixPasswordChangeValue) {
  emit('change-password', {
    currentPassword: value.currentPassword,
    newPassword: value.newPassword,
  })
}

function revokeSession(session: PhoenixSession) {
  const source = props.sessions.find((item) => item.id === session.id)
  if (source) emit('revoke-session', source)
}
</script>

<template>
  <main class="px-account-security-page" :aria-busy="passwordSubmitting || sessionsLoading">
    <header><h1>{{ title }}</h1></header>
    <div class="px-account-security-page__grid">
      <section aria-label="修改密码">
        <p v-if="passwordError" class="px-account-security-page__error" role="alert">{{ passwordError }}</p>
        <PhoenixPasswordChange
          :model-value="passwordValue"
          :revealed="passwordRevealed"
          :username="username"
          :submitting="passwordSubmitting"
          :disabled="disabled"
          @update:model-value="emit('update:passwordValue', $event)"
          @update:revealed="emit('update:passwordRevealed', $event)"
          @submit="changePassword"
        />
      </section>
      <section aria-label="登录设备">
        <p v-if="sessionsError" class="px-account-security-page__error" role="alert">{{ sessionsError }}</p>
        <PhoenixSessionManager
          :sessions="displaySessions"
          :loading="sessionsLoading"
          :disabled="disabled"
          @refresh="emit('refresh-sessions')"
          @revoke="revokeSession"
          @revoke-others="emit('revoke-other-sessions')"
        />
      </section>
    </div>
  </main>
</template>

<style scoped>
.px-account-security-page {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  padding: clamp(16px, 3vw, 32px);
  color: var(--px-ink, #1b2032);
  background: var(--px-soft, #f5f6fa);
  font-family: var(--px-font, Inter, "PingFang SC", "Microsoft YaHei", ui-sans-serif, system-ui, sans-serif);
}
.px-account-security-page > header { margin: 0 0 20px; }
.px-account-security-page h1 { margin: 0; font-size: clamp(24px, 4vw, 36px); }
.px-account-security-page__grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.25fr); gap: 20px; align-items: start; }
.px-account-security-page__grid > section { min-width: 0; }
.px-account-security-page__error { margin: 0 0 10px; padding: 10px 12px; border-radius: 10px; color: #9f1239; background: #fff1f2; }
@media (max-width: 840px) { .px-account-security-page__grid { grid-template-columns: 1fr; } }
</style>
