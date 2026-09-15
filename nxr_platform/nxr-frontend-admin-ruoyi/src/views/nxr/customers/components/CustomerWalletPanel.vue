<script setup>
import { reactive, ref, watch } from 'vue'
import { tx } from '@/i18n'
import { getMerchantProfile, saveMerchantProfile } from '@/api/nxr/merchantWallet'
import EnterpriseCreditFinance from '@/components/EnterpriseCredit/FinancePanel.vue'
const props = defineProps({ customerId: { type: Number, required: true } })
const company = reactive({ companyName: '', contactName: '' }), loading=ref(false), saving=ref(false), error=ref(''), success=ref('')
let generation=0
async function load(){const current=++generation;loading.value=true;try{const result=await getMerchantProfile(props.customerId);if(current===generation)Object.assign(company,result.data)}catch(e){if(current===generation)error.value=e.message||tx('Unable to load company balances.')}finally{if(current===generation)loading.value=false}}
async function save(){if(!company.companyName.trim()||!company.contactName.trim())return;saving.value=true;error.value='';try{await saveMerchantProfile(props.customerId,{...company});success.value=tx('Company profile saved.')}catch(e){error.value=e.message||tx('The operation could not be completed. Please try again.')}finally{saving.value=false}}
watch(()=>props.customerId,load,{immediate:true})
</script>
<template><div v-loading="loading"><el-alert v-if="error" :title="error" :closable="false" type="error"/><el-alert v-if="success" :title="success" :closable="false" type="success"/><el-form label-position="top" :model="company" @submit.prevent="save"><el-form-item :label="$tx('Company name')"><el-input v-model="company.companyName" required maxlength="191"/></el-form-item><el-form-item :label="$tx('Contact person')"><el-input v-model="company.contactName" required maxlength="128"/></el-form-item><el-form-item><el-button v-hasPermi="['nxr:customer:manage']" type="primary" native-type="submit" :loading="saving">{{$tx('Save profile')}}</el-button></el-form-item></el-form><EnterpriseCreditFinance :key="customerId" :company-id="customerId"/></div></template>
