<script setup lang="ts">
import { computed } from 'vue'
import { safeCount } from './safety'

export interface PhoenixDanmakuMessage {
  id: string | number
  sender: string
  content: string
  time?: string
  kind?: 'normal' | 'system' | 'highlight'
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  messages: PhoenixDanmakuMessage[]
  visible?: boolean
  paused?: boolean
  title?: string
  maxItems?: number
  reportable?: boolean
  disabled?: boolean
  emptyText?: string
}>(), {
  visible: true,
  paused: false,
  title: '直播弹幕',
  maxItems: 30,
  reportable: false,
  disabled: false,
  emptyText: '暂无弹幕',
})

const emit = defineEmits<{
  'update:visible': [visible: boolean]
  'update:paused': [paused: boolean]
  select: [message: PhoenixDanmakuMessage]
  'request-report': [message: PhoenixDanmakuMessage]
}>()

const limit = computed(() => Math.max(1, safeCount(props.maxItems, 100)))
const visibleMessages = computed(() => props.messages.slice(-limit.value))

function select(message: PhoenixDanmakuMessage) {
  if (!props.disabled && !message.disabled) emit('select', message)
}

function requestReport(message: PhoenixDanmakuMessage) {
  if (!props.disabled && !message.disabled) emit('request-report', message)
}
</script>

<template>
  <section class="px-danmaku-layer" :aria-label="title">
    <header>
      <h3>{{ title }}</h3>
      <div>
        <button type="button" :disabled="disabled" :aria-pressed="paused" @click="emit('update:paused', !paused)">{{ paused ? '继续显示' : '暂停显示' }}</button>
        <button type="button" :disabled="disabled" :aria-pressed="visible" @click="emit('update:visible', !visible)">{{ visible ? '隐藏弹幕' : '显示弹幕' }}</button>
      </div>
    </header>

    <p v-if="!visible" class="px-live-state" role="status">弹幕已隐藏</p>
    <div v-else class="px-danmaku-layer__viewport" role="log" :aria-live="paused ? 'off' : 'polite'" :aria-label="`${title}消息列表`">
      <p v-if="!visibleMessages.length" class="px-live-state" role="status">{{ emptyText }}</p>
      <article v-for="message in visibleMessages" v-else :key="message.id" :class="`is-${message.kind || 'normal'}`">
        <button type="button" :disabled="disabled || message.disabled" @click="select(message)">
          <strong>{{ message.sender }}</strong><span>{{ message.content }}</span><time v-if="message.time">{{ message.time }}</time>
        </button>
        <button v-if="reportable" type="button" :disabled="disabled || message.disabled" :aria-label="`举报${message.sender}的弹幕`" @click="requestReport(message)">举报</button>
      </article>
    </div>
  </section>
</template>
