<template>
  <el-dropdown trigger="click" @command="switchLocale">
    <button class="language-switcher" type="button" :aria-label="t('common.language')">
      <svg-icon icon-class="language" />
      <span>{{ currentLabel }}</span>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="item in supportedLocales"
          :key="item.code"
          :command="item.code"
          :disabled="item.code === locale"
        >
          {{ item.label }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
import { persistLocale, supportedLocales } from '@/i18n'

const { locale, t } = useI18n()
const currentLabel = computed(() => supportedLocales.find((item) => item.code === locale.value)?.label || 'English')

function switchLocale(nextLocale) {
  if (nextLocale === locale.value) return
  locale.value = nextLocale
  persistLocale(nextLocale)
  window.location.reload()
}
</script>

<style scoped>
.language-switcher {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-regular);
  cursor: pointer;
}

.language-switcher:hover {
  background: rgba(0, 0, 0, 0.04);
}

.language-switcher .svg-icon {
  width: 16px;
  height: 16px;
}
</style>
