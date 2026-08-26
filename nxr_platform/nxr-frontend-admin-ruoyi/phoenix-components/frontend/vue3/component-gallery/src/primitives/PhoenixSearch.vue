<script setup lang="ts">
withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    ariaLabel?: string
    clearable?: boolean
    disabled?: boolean
    loading?: boolean
  }>(),
  {
    placeholder: '请输入关键词搜索',
    ariaLabel: '搜索',
    clearable: true,
    disabled: false,
    loading: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  search: [value: string]
  clear: []
  focus: [event: FocusEvent]
  blur: [event: FocusEvent]
}>()

function update(event: Event) {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}

function clear() {
  emit('update:modelValue', '')
  emit('clear')
}
</script>

<template>
  <label class="px-search" :class="{ 'is-disabled': disabled }">
    <span class="px-search__icon" aria-hidden="true">
      <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4 4" /></svg>
    </span>
    <span class="px-sr-only">{{ ariaLabel }}</span>
    <input
      type="search"
      :value="modelValue"
      :placeholder="placeholder"
      :aria-label="ariaLabel"
      :disabled="disabled"
      @input="update"
      @keydown.enter="emit('search', modelValue)"
      @focus="emit('focus', $event)"
      @blur="emit('blur', $event)"
    />
    <span v-if="loading" class="px-search__spinner" aria-label="正在搜索"></span>
    <button
      v-else-if="clearable && modelValue"
      type="button"
      class="px-search__clear"
      aria-label="清除搜索内容"
      :disabled="disabled"
      @click="clear"
    >×</button>
  </label>
</template>
