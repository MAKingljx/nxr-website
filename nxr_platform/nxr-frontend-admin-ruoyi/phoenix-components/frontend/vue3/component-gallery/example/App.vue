<script setup lang="ts">
import catalog from '../src/data/components.json'
import ComponentGallery from '../src/ComponentGallery.vue'
import type { CatalogItem } from '../src/types'

const items = catalog as unknown as CatalogItem[]
</script>

<template>
  <ComponentGallery :items="items" />
</template>

<style>
* { box-sizing: border-box; }
body { margin: 0; min-width: 320px; }
button, input, select { font: inherit; }
</style>
