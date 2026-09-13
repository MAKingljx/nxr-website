<script setup lang="ts">
import type { AgentAddress } from '../../lib/agentWorkbench'
const address = defineModel<AgentAddress>({ required: true })
withDefaults(defineProps<{ required?: boolean }>(), { required: true })
</script>
<template><div class="form-grid"><label>收件人<input v-model="address.contactName" maxlength="128" :required="required" autocomplete="off" /></label><label>联系电话<input v-model="address.phone" maxlength="64" :required="required" type="tel" /></label><label class="form-wide">地址<input v-model="address.addressLine1" maxlength="255" :required="required" /></label><label class="form-wide">补充地址<input v-model="address.addressLine2" maxlength="255" /></label><label>城市<input v-model="address.city" maxlength="128" :required="required" /></label><label>省 / 州<input v-model="address.region" maxlength="128" /></label><label>邮编<input v-model="address.postalCode" maxlength="64" :required="required" /></label><label>国家 / 地区<input v-model="address.country" maxlength="128" :required="required" placeholder="例如 CN、US、HK" /></label></div></template>
