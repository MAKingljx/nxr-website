<script setup lang="ts">
const props = withDefaults(defineProps<{
  title?: string
  currentLesson?: number
  totalLessons?: number
  progress?: number
  completed?: boolean
  canContinue?: boolean
}>(), {
  title: '学习中心',
  currentLesson: 1,
  totalLessons: 1,
  progress: 0,
  completed: false,
  canContinue: true,
})

const emit = defineEmits<{
  'update:currentLesson': [lesson: number]
  previous: [lesson: number]
  continue: [lesson: number]
  complete: []
}>()

function previous() {
  if (props.currentLesson <= 1) return
  const lesson = props.currentLesson - 1
  emit('update:currentLesson', lesson)
  emit('previous', lesson)
}

function proceed() {
  if (!props.canContinue || props.completed) return
  if (props.currentLesson >= props.totalLessons) {
    emit('complete')
    return
  }
  const lesson = props.currentLesson + 1
  emit('update:currentLesson', lesson)
  emit('continue', lesson)
}

function normalizedProgress() {
  return Math.min(100, Math.max(0, props.progress))
}
</script>

<template>
  <main class="px-page-pattern px-learning-page" :aria-label="title">
    <header class="px-page-pattern__header px-learning-page__header">
      <div>
        <h1><slot name="title">{{ title }}</slot></h1>
        <div
          class="px-learning-page__progress"
          role="progressbar"
          aria-label="学习进度"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuenow="normalizedProgress()"
        >
          <span :style="{ width: `${normalizedProgress()}%` }"></span>
        </div>
      </div>
      <slot name="header-actions" />
    </header>

    <div class="px-learning-page__layout">
      <aside class="px-page-pattern__surface px-learning-page__outline" aria-label="课程目录">
        <slot name="outline" :current-lesson="currentLesson" />
      </aside>
      <section class="px-page-pattern__surface px-learning-page__content" aria-label="学习内容">
        <slot :current-lesson="currentLesson" />
        <div v-if="$slots.resources" class="px-learning-page__resources"><slot name="resources" /></div>
      </section>
      <aside v-if="$slots.discussion" class="px-page-pattern__surface px-learning-page__discussion" aria-label="课程讨论">
        <slot name="discussion" />
      </aside>
    </div>

    <footer class="px-learning-page__actions">
      <slot name="actions" :previous="previous" :proceed="proceed" :lesson="currentLesson">
        <button type="button" class="px-page-pattern__button" :disabled="currentLesson <= 1" @click="previous">上一节</button>
        <button
          type="button"
          class="px-page-pattern__button px-page-pattern__button--primary"
          :disabled="!canContinue || completed"
          @click="proceed"
        >
          {{ completed ? '已完成' : currentLesson >= totalLessons ? '完成学习' : '下一节' }}
        </button>
      </slot>
    </footer>
  </main>
</template>
