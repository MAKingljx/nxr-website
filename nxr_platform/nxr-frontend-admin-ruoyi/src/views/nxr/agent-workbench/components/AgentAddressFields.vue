<script setup lang="ts">
import type { AgentAddress } from '../lib/agentWorkbench'
import { computed, useId } from 'vue'
import { addressCities, addressCountries, addressRegions, withCurrentOption } from '../lib/addressCatalog'
const address = defineModel<AgentAddress>({ required: true })
withDefaults(defineProps<{ required?: boolean; includeContact?: boolean; postalRequired?: boolean; regionRequired?: boolean }>(), { required: true, includeContact: true, postalRequired: true, regionRequired: true })
const countryOptions = computed(() => withCurrentOption(addressCountries, address.value.country))
const regionOptions = computed(() => withCurrentOption(addressRegions(address.value.country), address.value.region))
const cityOptions = computed(() => withCurrentOption(addressCities(address.value.country, address.value.region), address.value.city))
const knownCountry = computed(() => addressRegions(address.value.country).length > 0)
const regionListId = useId(), cityListId = useId()
function countryChanged() { address.value.region = ''; address.value.city = '' }
</script>
<template>
  <div class="form-grid">
    <label v-if="includeContact"><span>{{ $tx('Recipient') }} <b v-if="required" class="required" aria-hidden="true">*</b></span><input v-model="address.contactName" maxlength="128" :required="required" autocomplete="off" /></label>
    <label v-if="includeContact"><span>{{ $tx('Phone number') }} <b v-if="required" class="required" aria-hidden="true">*</b></span><input v-model="address.phone" maxlength="64" :required="required" type="tel" /></label>
    <label class="form-wide"><span>{{ $tx('Address') }} <b v-if="required" class="required" aria-hidden="true">*</b></span><input v-model="address.addressLine1" maxlength="255" :required="required" /></label>
    <label class="form-wide">{{ $tx('Address line 2') }}<input v-model="address.addressLine2" maxlength="255" /></label>
    <label><span>{{ $tx('Country / region') }} <b v-if="required" class="required" aria-hidden="true">*</b></span><select v-model="address.country" :required="required" @change="countryChanged"><option value="">{{ $tx('Choose country / region') }}</option><option v-for="option in countryOptions" :key="option.value" :value="option.value">{{ $tx(option.label) }}</option></select></label>
    <label><span>{{ $tx('State / province') }} <b v-if="required && regionRequired && knownCountry" class="required" aria-hidden="true">*</b></span><input v-model="address.region" :list="regionListId" maxlength="128" :required="required && regionRequired && knownCountry" /><datalist :id="regionListId"><option v-for="option in regionOptions" :key="option.value" :value="option.value">{{ $tx(option.label) }}</option></datalist></label>
    <label><span>{{ $tx('City') }} <b v-if="required" class="required" aria-hidden="true">*</b></span><input v-model="address.city" :list="cityListId" maxlength="128" :required="required" /><datalist :id="cityListId"><option v-for="option in cityOptions" :key="option.value" :value="option.value">{{ $tx(option.label) }}</option></datalist></label>
    <label><span>{{ $tx('Postal code') }} <b v-if="required && postalRequired" class="required" aria-hidden="true">*</b></span><input v-model="address.postalCode" maxlength="64" :required="required && postalRequired" /></label>
  </div>
</template>
<style scoped>.required{color:var(--el-color-danger);margin-left:3px;font-weight:700}</style>
