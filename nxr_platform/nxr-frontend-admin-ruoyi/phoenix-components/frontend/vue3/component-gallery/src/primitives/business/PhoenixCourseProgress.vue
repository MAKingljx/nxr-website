<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  title: string
  completed?: number
  total?: number
  currentLesson?: string
  actionLabel?: string
  disabled?: boolean
}>(), {
  completed: 0, total: 0, currentLesson: '', actionLabel: '继续学习', disabled: false,
})

const emit = defineEmits<{ continue: [] }>()
const safeTotal = computed(() => Number.isFinite(props.total) ? Math.max(0, Math.trunc(props.total)) : 0)
const safeCompleted = computed(() => Number.isFinite(props.completed) ? Math.min(safeTotal.value, Math.max(0, Math.trunc(props.completed))) : 0)
const percentage = computed(() => safeTotal.value ? Math.round(safeCompleted.value / safeTotal.value * 100) : 0)
</script>

<template>
  <article class="px-course-progress" :aria-label="`${title} 学习进度`">
    <header><h3>{{ title }}</h3><strong>{{ percentage }}%</strong></header>
    <div class="px-course-progress__track" role="progressbar" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="percentage"><span :style="{ width: `${percentage}%` }"></span></div>
    <p v-if="safeTotal">已完成 {{ safeCompleted }} / {{ safeTotal }} 节</p>
    <p v-if="currentLesson">当前：{{ currentLesson }}</p>
    <button type="button" :disabled="disabled || safeTotal === 0 || safeCompleted >= safeTotal" @click="emit('continue')">{{ safeCompleted >= safeTotal && safeTotal > 0 ? '已完成' : actionLabel }}</button>
  </article>
</template>
