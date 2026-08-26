<script setup lang="ts">
withDefaults(defineProps<{
  title?: string
  menuLabel?: string
  showMenu?: boolean
}>(), {
  title: '工作台',
  menuLabel: '打开导航',
  showMenu: true,
})

defineEmits<{ menu: [] }>()
</script>

<template>
  <div class="px-top-bar">
    <button v-if="showMenu" type="button" class="px-top-bar__menu" :aria-label="menuLabel" @click="$emit('menu')"><span aria-hidden="true">☰</span></button>
    <div class="px-top-bar__brand"><slot name="brand"><strong>{{ title }}</strong></slot></div>
    <div class="px-top-bar__center"><slot /></div>
    <div class="px-top-bar__actions"><slot name="actions" /></div>
  </div>
</template>
