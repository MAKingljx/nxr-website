<script setup lang="ts">
const props = withDefaults(defineProps<{
  title?: string
  step?: number
  totalSteps?: number
  canContinue?: boolean
  submitting?: boolean
}>(), {
  title: '预约确认',
  step: 1,
  totalSteps: 3,
  canContinue: true,
  submitting: false,
})

const emit = defineEmits<{
  'update:step': [step: number]
  next: [step: number]
  previous: [step: number]
  submit: []
  cancel: []
}>()

function previous() {
  if (props.step <= 1) return
  const nextStep = props.step - 1
  emit('update:step', nextStep)
  emit('previous', nextStep)
}

function next() {
  if (!props.canContinue || props.submitting) return
  if (props.step >= props.totalSteps) {
    emit('submit')
    return
  }
  const nextStep = props.step + 1
  emit('update:step', nextStep)
  emit('next', nextStep)
}
</script>

<template>
  <main class="px-page-pattern px-booking-page" :aria-label="title" :aria-busy="submitting">
    <header class="px-page-pattern__header">
      <h1><slot name="title">{{ title }}</slot></h1>
      <button type="button" class="px-page-pattern__text-button" @click="emit('cancel')">取消预约</button>
    </header>

    <nav class="px-booking-page__steps" aria-label="预约步骤">
      <slot name="steps" :step="step" :total="totalSteps">
        <ol>
          <li v-for="index in totalSteps" :key="index" :class="{ 'is-current': index === step, 'is-complete': index < step }" :aria-current="index === step ? 'step' : undefined">
            <span>{{ index }}</span>
          </li>
        </ol>
      </slot>
    </nav>

    <section class="px-page-pattern__surface px-booking-page__content">
      <slot :step="step" />
    </section>
    <aside v-if="$slots.summary" class="px-page-pattern__surface px-booking-page__summary" aria-label="预约汇总">
      <slot name="summary" />
    </aside>

    <footer class="px-booking-page__actions">
      <slot name="actions" :previous="previous" :next="next" :step="step">
        <button type="button" class="px-page-pattern__button" :disabled="step <= 1" @click="previous">上一步</button>
        <button
          type="button"
          class="px-page-pattern__button px-page-pattern__button--primary"
          :disabled="!canContinue || submitting"
          @click="next"
        >
          {{ submitting ? '提交中' : step >= totalSteps ? '提交预约' : '下一步' }}
        </button>
      </slot>
    </footer>
  </main>
</template>
