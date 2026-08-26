<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  title: string
  image?: string
  category?: string
  location?: string
  tags?: string[]
  selected?: boolean
  disabled?: boolean
  actionLabel?: string
  ariaLabel?: string
}>(), {
  image: '', category: '', location: '', tags: () => [], selected: false, disabled: false,
  actionLabel: '查看详情', ariaLabel: '',
})

const emit = defineEmits<{
  select: []
  action: []
}>()

const safeImage = computed(() => {
  const value = props.image.trim()
  return /^(https?:\/\/|\/|\.\/|\.\.\/|blob:|data:image\/(?!svg\+xml)[a-z0-9.+-]+;base64,)/i.test(value) ? value : ''
})

function select() {
  if (!props.disabled) emit('select')
}

function onKeydown(event: KeyboardEvent) {
  if (!['Enter', ' '].includes(event.key) || props.disabled) return
  event.preventDefault()
  select()
}

function action(event: Event) {
  event.stopPropagation()
  if (!props.disabled) emit('action')
}
</script>

<template>
  <article
    class="px-resource-card"
    :class="{ 'is-selected': selected, 'is-disabled': disabled }"
    :tabindex="disabled ? -1 : 0"
    :aria-label="ariaLabel || title"
    :aria-disabled="disabled || undefined"
    :aria-selected="selected"
    @click="select"
    @keydown="onKeydown"
  >
    <div class="px-resource-card__media">
      <img v-if="safeImage" :src="safeImage" :alt="title" loading="lazy">
      <span v-else aria-hidden="true">{{ title.slice(0, 1) }}</span>
      <strong v-if="category">{{ category }}</strong>
    </div>
    <div class="px-resource-card__body">
      <h3>{{ title }}</h3>
      <p v-if="location">⌖ {{ location }}</p>
      <div v-if="tags.length" class="px-resource-card__tags" aria-label="标签">
        <span v-for="tag in tags" :key="tag">{{ tag }}</span>
      </div>
      <slot />
      <button type="button" :disabled="disabled" @click="action">{{ actionLabel }}</button>
    </div>
  </article>
</template>
