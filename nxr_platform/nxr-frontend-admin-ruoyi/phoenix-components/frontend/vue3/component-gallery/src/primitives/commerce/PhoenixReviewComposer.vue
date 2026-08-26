<script setup lang="ts">
import { computed } from 'vue'
import { clampInteger, safeImageUrl } from './utils'

export interface PhoenixReviewDraft {
  rating: number
  content: string
  images: string[]
}
const props = withDefaults(defineProps<{
  rating?: number
  content?: string
  images?: string[]
  maxLength?: number
  maxImages?: number
  disabled?: boolean
  submitting?: boolean
  title?: string
}>(), { rating: 0, content: '', images: () => [], maxLength: 500, maxImages: 6, disabled: false, submitting: false, title: '发表评价' })
const emit = defineEmits<{
  'update:rating': [rating: number]
  'update:content': [content: string]
  'update:images': [images: string[]]
  submit: [draft: PhoenixReviewDraft]
  cancel: []
}>()
const lengthLimit = computed(() => clampInteger(props.maxLength, 1, 5000))
const imageLimit = computed(() => clampInteger(props.maxImages, 0, 20))
const safeRating = computed(() => clampInteger(props.rating, 0, 5))
const safeContent = computed(() => String(props.content ?? '').slice(0, lengthLimit.value))
const safeImages = computed(() => props.images.map(safeImageUrl).filter(Boolean).slice(0, imageLimit.value))
function chooseRating(value: number) { if (!props.disabled && !props.submitting) emit('update:rating', clampInteger(value, 1, 5)) }
function removeImage(index: number) { if (!props.disabled && !props.submitting) emit('update:images', safeImages.value.filter((_, itemIndex) => itemIndex !== index)) }
function submit() { if (!props.disabled && !props.submitting && safeRating.value > 0) emit('submit', { rating: safeRating.value, content: safeContent.value, images: safeImages.value }) }
</script>

<template>
  <form class="px-review-composer" :aria-label="title" @submit.prevent="submit">
    <h3>{{ title }}</h3>
    <div class="px-review-composer__rating" role="radiogroup" aria-label="评价星级"><button v-for="star in 5" :key="star" type="button" role="radio" :aria-checked="star === safeRating" :aria-label="`${star} 分`" :disabled="disabled || submitting" :class="{ 'is-active': star <= safeRating }" @click="chooseRating(star)">★</button></div>
    <label><span class="px-commerce-sr-only">评价内容</span><textarea :value="safeContent" :maxlength="lengthLimit" placeholder="请分享真实体验" :disabled="disabled || submitting" @input="emit('update:content', ($event.target as HTMLTextAreaElement).value.slice(0, lengthLimit))"></textarea><small>{{ safeContent.length }}/{{ lengthLimit }}</small></label>
    <div v-if="safeImages.length" class="px-review-composer__images" aria-label="评价图片"><figure v-for="(image, index) in safeImages" :key="`${image}-${index}`"><img :src="image" alt="评价图片" loading="lazy"><button type="button" :aria-label="`移除第 ${index + 1} 张图片`" :disabled="disabled || submitting" @click="removeImage(index)">×</button></figure></div>
    <p class="px-commerce-hint">图片仅用于预览，组件不会上传或保存内容</p>
    <footer><button type="button" :disabled="disabled || submitting" @click="emit('cancel')">取消</button><button class="is-primary" type="submit" :disabled="disabled || submitting || safeRating <= 0">{{ submitting ? '提交中' : '提交评价' }}</button></footer>
  </form>
</template>
