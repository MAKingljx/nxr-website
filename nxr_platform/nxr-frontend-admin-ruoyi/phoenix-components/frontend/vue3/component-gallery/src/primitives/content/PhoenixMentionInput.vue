<script setup lang="ts">
import { computed, useId } from 'vue'
import { finiteInteger, normalizeAppearance, safeImageUrl } from './safety'
import type { PhoenixContentAppearance } from './safety'

export interface PhoenixMentionSuggestion {
  id: string | number
  label: string
  handle?: string
  description?: string
  avatar?: string
  disabled?: boolean
}

const props = withDefaults(defineProps<{
  modelValue?: string
  suggestions?: PhoenixMentionSuggestion[]
  activeSuggestionId?: string | number
  appearance?: PhoenixContentAppearance
  placeholder?: string
  ariaLabel?: string
  maxLength?: number
  disabled?: boolean
  emptyText?: string
}>(), {
  modelValue: '',
  suggestions: () => [],
  activeSuggestionId: undefined,
  appearance: 'modern',
  placeholder: '输入 @ 提及成员',
  ariaLabel: '提及输入',
  maxLength: 500,
  disabled: false,
  emptyText: '没有匹配的成员',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'update:activeSuggestionId': [id: string | number | undefined]
  search: [query: string]
  mention: [suggestion: PhoenixMentionSuggestion]
  dismiss: []
}>()

const appearanceValue = computed(() => normalizeAppearance(props.appearance))
const listboxId = `px-mention-options-${useId()}`
const limit = computed(() => finiteInteger(props.maxLength, 500, 1))
const value = computed(() => props.modelValue.slice(0, limit.value))
const mentionQuery = computed(() => {
  const match = value.value.match(/(?:^|\s)@([^\s@]*)$/u)
  return match ? match[1] : null
})
const filteredSuggestions = computed(() => {
  if (mentionQuery.value === null) return []
  const query = mentionQuery.value.toLocaleLowerCase('zh-CN')
  return props.suggestions.filter((item) => `${item.label} ${item.handle ?? ''}`.toLocaleLowerCase('zh-CN').includes(query))
})
const activeIndex = computed(() => {
  const matched = filteredSuggestions.value.findIndex((item) => item.id === props.activeSuggestionId && !item.disabled)
  if (matched >= 0) return matched
  return filteredSuggestions.value.findIndex((item) => !item.disabled)
})
const activeOptionId = computed(() => activeIndex.value >= 0 ? `${listboxId}-option-${activeIndex.value}` : undefined)

function update(event: Event) {
  const next = (event.target as HTMLTextAreaElement).value.slice(0, limit.value)
  emit('update:modelValue', next)
  const match = next.match(/(?:^|\s)@([^\s@]*)$/u)
  if (match) emit('search', match[1])
}

function choose(suggestion: PhoenixMentionSuggestion) {
  if (props.disabled || suggestion.disabled || mentionQuery.value === null) return
  const handle = (suggestion.handle || suggestion.label).replace(/^@+/, '')
  const next = value.value.replace(/@[^\s@]*$/u, `@${handle} `).slice(0, limit.value)
  emit('update:modelValue', next)
  emit('mention', suggestion)
}

function move(direction: 1 | -1) {
  const enabled = filteredSuggestions.value.filter((item) => !item.disabled)
  if (!enabled.length) return
  const current = enabled.findIndex((item) => item.id === props.activeSuggestionId)
  const next = current < 0 ? (direction > 0 ? 0 : enabled.length - 1) : (current + direction + enabled.length) % enabled.length
  emit('update:activeSuggestionId', enabled[next].id)
}

function onKeydown(event: KeyboardEvent) {
  if (mentionQuery.value === null) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    move(event.key === 'ArrowDown' ? 1 : -1)
  } else if (event.key === 'Enter' && activeIndex.value >= 0) {
    event.preventDefault()
    choose(filteredSuggestions.value[activeIndex.value])
  } else if (event.key === 'Escape') {
    event.preventDefault()
    emit('dismiss')
  }
}
</script>

<template>
  <div class="px-mention-input" :data-appearance="appearanceValue">
    <textarea :value="value" :maxlength="limit" :placeholder="placeholder" :aria-label="ariaLabel" :disabled="disabled" role="combobox" :aria-expanded="mentionQuery !== null" aria-autocomplete="list" aria-haspopup="listbox" :aria-controls="mentionQuery !== null ? listboxId : undefined" :aria-activedescendant="mentionQuery !== null ? activeOptionId : undefined" @input="update" @keydown="onKeydown"></textarea>
    <div class="px-mention-input__count">{{ value.length }}/{{ limit }}</div>
    <div v-if="mentionQuery !== null" class="px-mention-input__popover">
      <ul :id="listboxId" role="listbox" aria-label="提及建议">
        <li v-if="!filteredSuggestions.length" role="presentation"><p class="px-content-empty" role="status">{{ emptyText }}</p></li>
        <template v-else>
          <li v-for="(suggestion, index) in filteredSuggestions" :id="`${listboxId}-option-${index}`" :key="suggestion.id" role="option" :aria-selected="index === activeIndex" :aria-disabled="suggestion.disabled">
            <button type="button" :disabled="disabled || suggestion.disabled" @mousedown.prevent @click="choose(suggestion)">
              <span class="px-content-avatar"><img v-if="safeImageUrl(suggestion.avatar)" :src="safeImageUrl(suggestion.avatar)" :alt="suggestion.label" /><strong v-else aria-hidden="true">{{ suggestion.label.slice(0, 1) }}</strong></span>
              <span><strong>{{ suggestion.label }}</strong><small v-if="suggestion.description">{{ suggestion.description }}</small></span><b>@{{ (suggestion.handle || suggestion.label).replace(/^@+/, '') }}</b>
            </button>
          </li>
        </template>
      </ul>
    </div>
  </div>
</template>
