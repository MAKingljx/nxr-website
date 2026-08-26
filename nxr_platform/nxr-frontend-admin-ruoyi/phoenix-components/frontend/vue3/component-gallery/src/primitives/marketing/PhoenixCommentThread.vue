<script setup lang="ts">
import { computed } from 'vue'
import type { PhoenixMarketingAppearance } from './PhoenixLuckyDraw.vue'

export interface PhoenixCommentItem {
  id: string | number
  author: string
  avatar?: string
  content: string
  createdAt?: string
  likes?: number
  liked?: boolean
  canDelete?: boolean
  canReport?: boolean
  replies?: PhoenixCommentItem[]
}

const props = withDefaults(defineProps<{
  comments: PhoenixCommentItem[]
  modelValue?: string
  appearance?: PhoenixMarketingAppearance
  title?: string
  placeholder?: string
  submitLabel?: string
  emptyText?: string
  loading?: boolean
  disabled?: boolean
  maxLength?: number
}>(), {
  modelValue: '',
  appearance: 'modern',
  title: '社区评论',
  placeholder: '说说你的看法',
  submitLabel: '发表评论',
  emptyText: '还没有评论',
  loading: false,
  disabled: false,
  maxLength: 500,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: [content: string]
  reply: [comment: PhoenixCommentItem]
  like: [comment: PhoenixCommentItem, liked: boolean]
  delete: [comment: PhoenixCommentItem]
  report: [comment: PhoenixCommentItem]
}>()

const limit = computed(() => Math.max(1, Math.trunc(Number.isFinite(props.maxLength) ? props.maxLength : 500)))
const value = computed(() => props.modelValue.slice(0, limit.value))
const canSubmit = computed(() => !props.disabled && !props.loading && value.value.trim().length > 0)

function safeImage(value?: string) {
  if (!value) return ''
  const normalized = value.trim()
  return /^(https?:\/\/|\/|\.\/|\.\.\/|blob:|data:image\/(?!svg\+xml)[a-z0-9.+-]+;base64,)/i.test(normalized)
    ? normalized
    : ''
}

function update(event: Event) {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value.slice(0, limit.value))
}

function submit() {
  if (canSubmit.value) emit('submit', value.value.trim())
}

function likeCount(comment: PhoenixCommentItem) {
  return Math.max(0, Math.trunc(Number.isFinite(comment.likes) ? comment.likes ?? 0 : 0))
}
</script>

<template>
  <section class="px-comment-thread" :data-appearance="appearance" :aria-busy="loading">
    <header><h3>{{ title }}</h3><strong>{{ comments.length }} 条</strong></header>
    <div class="px-comment-thread__composer">
      <textarea :value="value" :maxlength="limit" :placeholder="placeholder" :disabled="disabled || loading" aria-label="评论内容" @input="update" @keydown.ctrl.enter.prevent="submit" />
      <footer><span>{{ value.length }}/{{ limit }}</span><button type="button" :disabled="!canSubmit" @click="submit">{{ loading ? '提交中' : submitLabel }}</button></footer>
    </div>
    <p v-if="!comments.length" class="px-marketing-empty" role="status">{{ emptyText }}</p>
    <ol v-else class="px-comment-thread__list">
      <li v-for="comment in comments" :key="comment.id">
        <article class="px-comment-thread__comment">
          <span class="px-comment-thread__avatar"><img v-if="safeImage(comment.avatar)" :src="safeImage(comment.avatar)" :alt="comment.author"><strong v-else aria-hidden="true">{{ comment.author.slice(0, 1) }}</strong></span>
          <div>
            <header><strong>{{ comment.author }}</strong><time v-if="comment.createdAt">{{ comment.createdAt }}</time></header>
            <p>{{ comment.content }}</p>
            <footer>
              <button type="button" :aria-pressed="comment.liked" @click="emit('like', comment, !comment.liked)">{{ comment.liked ? '已赞' : '点赞' }} {{ likeCount(comment) }}</button>
              <button type="button" @click="emit('reply', comment)">回复</button>
              <button v-if="comment.canDelete" type="button" @click="emit('delete', comment)">删除</button>
              <button v-if="comment.canReport" type="button" @click="emit('report', comment)">举报</button>
            </footer>
          </div>
        </article>
        <ol v-if="comment.replies?.length" class="px-comment-thread__replies" aria-label="回复列表">
          <li v-for="replyItem in comment.replies" :key="replyItem.id">
            <strong>{{ replyItem.author }}</strong><p>{{ replyItem.content }}</p>
          </li>
        </ol>
      </li>
    </ol>
  </section>
</template>
