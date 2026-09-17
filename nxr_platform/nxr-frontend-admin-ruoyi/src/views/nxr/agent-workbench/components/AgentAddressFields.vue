<script setup lang="ts">
import type { AgentAddress } from '../lib/agentWorkbench'
import { computed } from 'vue'
import { addressCities, addressCountries, addressRegions, withCurrentOption } from '../lib/addressCatalog'
const address = defineModel<AgentAddress>({ required: true })
withDefaults(defineProps<{ required?: boolean }>(), { required: true })
const countryOptions = computed(() => withCurrentOption(addressCountries, address.value.country))
const regionOptions = computed(() => withCurrentOption(addressRegions(address.value.country), address.value.region))
const cityOptions = computed(() => withCurrentOption(addressCities(address.value.country, address.value.region), address.value.city))
const knownCountry = computed(() => addressRegions(address.value.country).length > 0)
const knownRegion = computed(() => addressCities(address.value.country, address.value.region).length > 0)
function countryChanged() { address.value.region = ''; address.value.city = '' }
function regionChanged() { address.value.city = '' }
</script>
<template><div class="form-grid"><label>{{ $tx('Recipient') }}<input v-model="address.contactName" maxlength="128" :required="required" autocomplete="off" /></label><label>{{ $tx('Phone number') }}<input v-model="address.phone" maxlength="64" :required="required" type="tel" /></label><label class="form-wide">{{ $tx('Address') }}<input v-model="address.addressLine1" maxlength="255" :required="required" /></label><label class="form-wide">{{ $tx('Address line 2') }}<input v-model="address.addressLine2" maxlength="255" /></label><label>{{ $tx('Country / region') }}<select v-model="address.country" :required="required" @change="countryChanged"><option value="">{{ $tx('Choose country / region') }}</option><option v-for="option in countryOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label>{{ $tx('State / province') }}<select v-if="knownCountry" v-model="address.region" :required="knownCountry" @change="regionChanged"><option value="">{{ $tx('Choose region / state') }}</option><option v-for="option in regionOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select><input v-else v-model="address.region" maxlength="128" /></label><label>{{ $tx('City') }}<select v-if="knownRegion" v-model="address.city" required><option value="">{{ $tx('Choose city') }}</option><option v-for="option in cityOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select><input v-else v-model="address.city" maxlength="128" :required="required" /></label><label>{{ $tx('Postal code') }}<input v-model="address.postalCode" maxlength="64" :required="required" /></label></div></template>
