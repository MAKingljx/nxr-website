<script setup lang="ts">
import { PhoenixButton, PhoenixCard, PhoenixTag, PhoenixThemeProvider } from '../primitives'
import type { PhoenixTheme } from '../primitives'

const themes: Array<{ id: PhoenixTheme; label: string }> = [
  { id: 'modern', label: '现代主题' },
  { id: 'business', label: '商务主题' },
  { id: 'minimal', label: '极简主题' },
  { id: 'festive', label: '节庆主题' },
]
</script>

<template>
  <div class="cg-theme-showcase">
    <PhoenixThemeProvider v-for="theme in themes" :key="theme.id" :theme="theme.id" :label="theme.label" class="cg-theme-preview">
      <header><strong>{{ theme.label }}</strong><PhoenixTag variant="primary">运行中</PhoenixTag></header>
      <PhoenixCard title="项目概览" elevated>
        <div class="cg-component-row"><PhoenixButton size="small">主要操作</PhoenixButton><PhoenixButton size="small" variant="secondary">次要操作</PhoenixButton></div>
      </PhoenixCard>
    </PhoenixThemeProvider>
  </div>
</template>
