<script setup lang="ts">
import { computed, ref } from 'vue'
import { customerRequest } from '../lib/customer'
import { removeUnusedOrderPhoto } from '../lib/orderApplication'
import PrivateOrderPhoto from './PrivateOrderPhoto.vue'
const props = defineProps<{ currentIds: number[] }>()
const photos = ref<Array<{ id: number; originalFilename: string; byteSize: number }>>([]), error = ref(''), loading = ref(false)
const available = computed(() => photos.value.filter(photo => !props.currentIds.includes(photo.id)))
async function load(event?: Event) {
  if (event && !(event.target as HTMLDetailsElement).open) return
  loading.value = true; error.value = ''
  try { photos.value = await customerRequest('/api/customer/order-photos') }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to load unused images.' }
  finally { loading.value = false }
}
async function remove(id: number) {
  try { await removeUnusedOrderPhoto(id); photos.value = photos.value.filter(photo => photo.id !== id) }
  catch (e) { error.value = e instanceof Error ? e.message : 'Unable to remove this image.' }
}
</script>
<template><details class="form-section unused-images" @toggle="load"><summary>Manage images from unfinished applications</summary><p class="muted-copy">Only images that have not been submitted with an order can be removed here.</p><p v-if="error" class="form-error">{{ error }}</p><p v-if="loading">Loading…</p><div v-else class="unused-list"><article v-for="photo in available" :key="photo.id"><PrivateOrderPhoto :photo-id="photo.id" :label="photo.originalFilename" /><button type="button" class="text-button" @click="remove(photo.id)">Remove unused image</button></article><p v-if="!available.length" class="muted-copy">No unused images outside the current application.</p></div></details></template>
<style scoped>.unused-images summary{cursor:pointer}.unused-list{display:flex;gap:16px;flex-wrap:wrap}.unused-list article{max-width:180px;display:flex;flex-direction:column}.unused-list article button{font-size:12px}</style>
