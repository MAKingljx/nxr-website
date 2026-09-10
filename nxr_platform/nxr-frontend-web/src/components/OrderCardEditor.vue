<script setup lang="ts">
import { ref } from 'vue'
import { emptyApplicationCard, removeUnusedOrderPhoto, uploadOrderPhoto, type ApplicationCard } from '../lib/orderApplication'
import PrivateOrderPhoto from './PrivateOrderPhoto.vue'
const props = defineProps<{ modelValue: ApplicationCard[]; maxCards: number }>()
const emit = defineEmits<{ 'update:modelValue': [ApplicationCard[]]; busy: [boolean] }>()
const categoryNames: Record<string, string> = { trading_card: 'Trading card', sports_card: 'Sports card', celebrity_card: 'Celebrity card', film: 'Film', sticker: 'Sticker' }
function changeCategory(item: ApplicationCard, event: Event) {
  const value = (event.target as HTMLInputElement).value
  item.category = Object.keys(categoryNames).find(code => categoryNames[code]?.toLowerCase() === value.trim().toLowerCase()) || value
}
const addCount = ref(1), bulkNames = ref(''), error = ref(''), uploads = ref(new Set<string>())
function addCards() {
  const count = Math.min(props.maxCards - props.modelValue.length, Math.max(1, Math.floor(Number(addCount.value) || 1)))
  emit('update:modelValue', [...props.modelValue, ...Array.from({ length: count }, emptyApplicationCard)])
}
function addNames() {
  const names = bulkNames.value.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  if (names.length + props.modelValue.length > props.maxCards) { error.value = `At most ${props.maxCards} cards per order.`; return }
  emit('update:modelValue', [...props.modelValue, ...names.map(cardName => ({ ...emptyApplicationCard(), cardName }))])
  bulkNames.value = ''; error.value = ''
}
async function remove(index: number) {
  const item = props.modelValue[index]
  if (!item) return
  try {
    for (const side of ['frontPhotoId', 'backPhotoId'] as const) {
      const id = item[side]
      if (id) { await removeUnusedOrderPhoto(id); item[side] = null }
    }
    emit('update:modelValue', props.modelValue.filter((_, i) => i !== index))
  } catch (e) { error.value = e instanceof Error ? e.message : 'Unable to remove this card.' }
}
async function upload(item: ApplicationCard, side: 'frontPhotoId' | 'backPhotoId', event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (file.size > 15 * 1024 * 1024) { error.value = 'Each image must be 15 MB or smaller.'; input.value = ''; return }
  const key = `${item.localId}:${side}`
  uploads.value.add(key); emit('busy', true); error.value = ''
  try {
    const next = await uploadOrderPhoto(file)
    const old = item[side]; item[side] = next.id
    if (old) await removeUnusedOrderPhoto(old)
  } catch (e) { error.value = e instanceof Error ? e.message : 'Image upload failed.' }
  finally { uploads.value.delete(key); emit('busy', uploads.value.size > 0); input.value = '' }
}
</script>
<template>
  <section class="form-section card-editor" @invalid.capture="($event.target as HTMLElement).closest('details')?.setAttribute('open', '')">
    <h2>Your cards</h2><p class="muted-copy">Describe each physical card. A catalog match is not required; our team reviews eligibility before requesting payment. Photos are optional and visible to you and authorized NXR staff.</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <details v-for="(item, index) in modelValue" :key="item.localId" class="card-entry" :open="modelValue.length <= 3">
      <summary>Card {{ index + 1 }} · {{ item.cardName || 'Add card details' }} <small>{{ item.languageCode }}</small></summary>
      <div class="form-grid card-fields">
        <label class="form-wide">Card name<input v-model="item.cardName" required maxlength="255" placeholder="Enter the printed name, or your best description" /></label>
        <label>Year<input v-model="item.year" maxlength="32" placeholder="e.g. 2024" /></label>
        <label>Rarity / version<input v-model="item.rarity" maxlength="128" placeholder="e.g. rare, parallel, first edition" /></label>
        <label>Product type<select v-model="item.productType"><option value="graded_card">Card</option><option value="vintage_product">Vintage card</option><option value="merch_product">Sticker / collectible</option></select></label>
        <label>Category<input :value="categoryNames[item.category] || item.category" @input="changeCategory(item, $event)" list="card-categories" maxlength="64" placeholder="Trading card, sports, film…" /></label>
        <label>Language<input v-model="item.languageCode" required maxlength="32" placeholder="EN, JA, ZH…" /></label>
        <label>Brand<input v-model="item.brandName" maxlength="128" /></label>
        <label>Set / series<input v-model="item.setName" maxlength="255" /></label>
        <label>Card number<input v-model="item.cardNumber" maxlength="64" /></label>
        <label class="form-wide">Card note<input v-model="item.itemNote" maxlength="2000" /></label>
        <label>Front photo<input type="file" accept="image/jpeg,image/png" :disabled="uploads.size > 0" @change="upload(item, 'frontPhotoId', $event)" /><PrivateOrderPhoto :photo-id="item.frontPhotoId" label="Front" /></label>
        <label>Back photo<input type="file" accept="image/jpeg,image/png" :disabled="uploads.size > 0" @change="upload(item, 'backPhotoId', $event)" /><PrivateOrderPhoto :photo-id="item.backPhotoId" label="Back" /></label>
      </div>
      <button v-if="modelValue.length > 1" type="button" class="text-button" :disabled="uploads.size > 0" @click="remove(index)">Remove card {{ index + 1 }}</button>
    </details>
    <datalist id="card-categories"><option v-for="label in categoryNames" :key="label" :value="label" /></datalist>
    <div v-if="modelValue.length < maxCards" class="card-add-row"><label>Cards to add<input v-model.number="addCount" type="number" min="1" :max="maxCards - modelValue.length" /></label><button type="button" class="btn-secondary" :disabled="uploads.size > 0" @click="addCards">Add cards</button></div>
    <details v-if="modelValue.length < maxCards" class="bulk-names"><summary>Quick entry: paste a list of card names</summary><label>One physical card per line<textarea v-model="bulkNames" rows="5" placeholder="Pikachu&#10;Charizard" /></label><button type="button" class="btn-secondary" @click="addNames">Add from list</button></details>
    <p class="muted-copy">{{ modelValue.length }} / {{ maxCards }} cards · JPEG or PNG, up to 15 MB per image.</p>
  </section>
</template>
<style scoped>.card-entry{border:1px solid var(--border,#ddd);border-radius:12px;padding:16px;margin:14px 0}.card-entry summary{cursor:pointer;font-weight:600}.card-entry summary small{margin-left:8px;font-weight:400}.card-fields{padding-top:18px}.card-add-row{display:flex;gap:16px;align-items:end;margin:16px 0}.card-add-row input{max-width:130px}.bulk-names summary{cursor:pointer;margin:16px 0}.bulk-names label{display:grid;gap:8px}.bulk-names button{margin-top:12px}</style>
