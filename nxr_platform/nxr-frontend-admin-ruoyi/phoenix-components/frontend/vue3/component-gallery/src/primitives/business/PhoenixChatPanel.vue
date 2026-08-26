<script setup lang="ts">
export interface PhoenixChatMessage {
  id: string | number
  sender: string
  content: string
  time?: string
  self?: boolean
  status?: 'sending' | 'sent' | 'failed'
}

const props = withDefaults(defineProps<{
  messages: PhoenixChatMessage[]
  modelValue?: string
  title?: string
  placeholder?: string
  emptyText?: string
  sendText?: string
  sending?: boolean
  disabled?: boolean
  maxLength?: number
}>(), {
  modelValue: '', title: '在线交流', placeholder: '请输入消息', emptyText: '暂无消息', sendText: '发送',
  sending: false, disabled: false, maxLength: 500,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  send: [content: string]
  retry: [message: PhoenixChatMessage]
}>()

function send() {
  const content = props.modelValue.trim()
  if (!content || props.disabled || props.sending) return
  emit('send', content)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  send()
}
</script>

<template>
  <section class="px-chat-panel" :aria-label="title" :aria-busy="sending">
    <header><h3>{{ title }}</h3><slot name="actions" /></header>
    <div class="px-chat-panel__messages" role="log" aria-live="polite">
      <p v-if="!messages.length" class="px-business-empty" role="status">{{ emptyText }}</p>
      <article v-for="message in messages" v-else :key="message.id" :class="{ 'is-self': message.self, 'is-failed': message.status === 'failed' }">
        <strong>{{ message.sender }}</strong>
        <p>{{ message.content }}</p>
        <footer><time v-if="message.time">{{ message.time }}</time><span v-if="message.status === 'sending'">发送中</span><span v-if="message.status === 'sent'">已发送</span><button v-if="message.status === 'failed'" type="button" @click="emit('retry', message)">重新发送</button></footer>
      </article>
    </div>
    <div class="px-chat-panel__composer">
      <textarea
        rows="2" :value="modelValue" :placeholder="placeholder" :maxlength="Math.max(1, maxLength)" :disabled="disabled || sending"
        aria-label="消息内容" @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)" @keydown="onKeydown"
      ></textarea>
      <button type="button" :disabled="disabled || sending || !modelValue.trim()" @click="send">{{ sending ? '发送中' : sendText }}</button>
    </div>
  </section>
</template>
