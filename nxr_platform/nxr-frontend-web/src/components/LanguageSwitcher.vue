<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { persistLocale, supportedLocales, type SupportedLocale } from '../i18n'

const { locale, t } = useI18n()

function switchLocale(nextLocale: SupportedLocale) {
  locale.value = nextLocale
  persistLocale(nextLocale)
}
</script>

<template>
  <div class="language-switcher" :aria-label="t('common.language')">
    <button
      v-for="item in supportedLocales"
      :key="item.code"
      type="button"
      :class="{ active: locale === item.code }"
      :aria-pressed="locale === item.code"
      :title="item.label"
      @click="switchLocale(item.code)"
    >
      {{ item.label }}
    </button>
  </div>
</template>

<style scoped>
.language-switcher {
  display: inline-flex;
  gap: 6px;
  padding: 4px;
  border-radius: 999px;
  border: 1px solid rgba(17, 21, 31, 0.12);
  background: rgba(255, 255, 255, 0.72);
}

.language-switcher button {
  min-width: 72px;
  height: 34px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.language-switcher button.active {
  background: #1f304f;
  color: #fff;
  font-weight: 700;
}
</style>
