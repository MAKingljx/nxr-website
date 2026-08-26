<script setup lang="ts">
type LiveRoomStatus = 'offline' | 'connecting' | 'live' | 'ended'

withDefaults(defineProps<{
  title?: string
  status?: LiveRoomStatus
  muted?: boolean
  handRaised?: boolean
  participantCount?: number
  panel?: 'chat' | 'participants'
  controlsDisabled?: boolean
}>(), {
  title: '直播间',
  status: 'offline',
  muted: false,
  handRaised: false,
  participantCount: 0,
  panel: 'chat',
  controlsDisabled: false,
})

const emit = defineEmits<{
  'update:muted': [muted: boolean]
  'update:handRaised': [raised: boolean]
  'update:panel': [panel: 'chat' | 'participants']
  reconnect: []
  leave: []
}>()

const statusText: Record<LiveRoomStatus, string> = {
  offline: '未连接',
  connecting: '连接中',
  live: '直播中',
  ended: '已结束',
}
</script>

<template>
  <main class="px-page-pattern px-live-room-page" :aria-label="title" :aria-busy="status === 'connecting'">
    <header class="px-page-pattern__header px-live-room-page__header">
      <h1><slot name="title">{{ title }}</slot></h1>
      <div class="px-live-room-page__status" :class="`is-${status}`" role="status">
        <span></span>{{ statusText[status] }}
      </div>
      <slot name="header-actions" />
    </header>

    <div class="px-live-room-page__layout">
      <section class="px-live-room-page__stage" aria-label="直播画面">
        <slot name="stage">
          <div class="px-page-pattern__state">{{ statusText[status] }}</div>
        </slot>
        <div v-if="$slots.notice" class="px-live-room-page__notice" aria-live="polite"><slot name="notice" /></div>
      </section>

      <aside class="px-page-pattern__surface px-live-room-page__panel">
        <div class="px-live-room-page__tabs" role="tablist" aria-label="直播间面板">
          <button
            type="button"
            role="tab"
            :aria-selected="panel === 'chat'"
            :class="{ 'is-active': panel === 'chat' }"
            @click="emit('update:panel', 'chat')"
          >
            互动
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="panel === 'participants'"
            :class="{ 'is-active': panel === 'participants' }"
            @click="emit('update:panel', 'participants')"
          >
            参与者 {{ participantCount }}
          </button>
        </div>
        <div role="tabpanel">
          <slot v-if="panel === 'chat'" name="chat" />
          <slot v-else name="participants" />
        </div>
      </aside>
    </div>

    <footer class="px-live-room-page__controls" aria-label="直播间控制">
      <slot name="controls" :muted="muted" :hand-raised="handRaised">
        <button
          type="button"
          class="px-page-pattern__button"
          :aria-pressed="muted"
          :disabled="controlsDisabled"
          @click="emit('update:muted', !muted)"
        >
          {{ muted ? '打开麦克风' : '静音' }}
        </button>
        <button
          type="button"
          class="px-page-pattern__button"
          :aria-pressed="handRaised"
          :disabled="controlsDisabled"
          @click="emit('update:handRaised', !handRaised)"
        >
          {{ handRaised ? '放下手' : '举手' }}
        </button>
        <button v-if="status === 'offline'" type="button" class="px-page-pattern__button" @click="emit('reconnect')">重新连接</button>
        <button type="button" class="px-page-pattern__button px-page-pattern__button--danger" @click="emit('leave')">离开</button>
      </slot>
    </footer>
  </main>
</template>
