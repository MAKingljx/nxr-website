<script setup lang="ts">
import { tx } from '@/i18n'
import { nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import AgentWorkspace from './components/AgentWorkspace.vue'
import { createAgentApi, type AgentApi, type Company } from './lib/agentWorkbench'
defineOptions({ name:'NxrAgentWorkbench' })
const route=useRoute(),router=useRouter(), management=shallowRef(createAgentApi()), activeApi=shallowRef<AgentApi|null>(null)
const company=ref<Company|null>(null), platformManager=ref(false), companies=ref<Company[]>([]),selectedId=ref<number|undefined>(),loading=ref(true),searching=ref(false),error=ref('')
let generation=0,searchGeneration=0,initialization=0,initializing=false,visible=true
async function searchCompanies(query=''){const current=++searchGeneration;searching.value=true;try{const result=await management.value.fetchCompanies({query,page:1,pageSize:50});if(current===searchGeneration)companies.value=result.items}catch(e){if(current===searchGeneration)error.value=e instanceof Error?e.message:tx('Unable to load companies.')}finally{if(current===searchGeneration)searching.value=false}}
async function selectCompany(id?:number,writeRoute=true){
  const current=++generation
  activeApi.value?.dispose();activeApi.value=null;company.value=null;selectedId.value=id;error.value='';loading.value=true
  await nextTick()
  if(current!==generation||!visible)return
  if(!id){loading.value=false;if(writeRoute)await router.replace({path:route.path,query:{}});return}
  const candidate=createAgentApi(id)
  try{const context=await candidate.fetchContext();if(current!==generation){candidate.dispose();return}if(!context.company||context.company.id!==id)throw new Error(tx('Unable to verify access to this company.'));company.value=context.company;activeApi.value=candidate;if(writeRoute)await router.replace({path:'/nxr/submission-workbench',query:{company:String(id),tab:'clients'}})}catch(e){candidate.dispose();if(current===generation)error.value=e instanceof Error?e.message:tx('Unable to load the company workspace.')}finally{if(current===generation)loading.value=false}
}
async function initialize(){
  if(initializing)return
  const current=++initialization
  initializing=true;loading.value=true;error.value=''
  if(!management.value.isActive())management.value=createAgentApi()
  try{const context=await management.value.fetchContext();if(current!==initialization||!visible)return;platformManager.value=context.platformManager;const requested=Number(route.query.company);const id=context.platformManager&&Number.isSafeInteger(requested)&&requested>0?requested:context.company?.id;if(context.platformManager)await searchCompanies();if(current!==initialization||!visible)return;await selectCompany(id,false)}catch(e){if(current===initialization&&visible){error.value=e instanceof Error?e.message:tx('Unable to load Submission Workspace.');loading.value=false}}finally{if(current===initialization)initializing=false}
}
function dispose(){generation++;searchGeneration++;initialization++;initializing=false;activeApi.value?.dispose();activeApi.value=null;company.value=null;management.value.dispose()}
watch(()=>route.query.company,raw=>{if(!platformManager.value||initializing||loading.value||!visible)return;const id=Number(raw);if(Number.isSafeInteger(id)&&id>0){if(id!==company.value?.id)void selectCompany(id,false)}else if(company.value)void selectCompany(undefined,false)})
onMounted(initialize)
onActivated(()=>{visible=true;if(!management.value.isActive())void initialize()})
onDeactivated(()=>{visible=false;dispose()})
onBeforeUnmount(dispose)
</script>
<template><main class="nxr-workspace agent-admin-page" data-testid="agent-admin-workbench"><NxrPageHeader :title="$t('nav.submissionWorkspace')" :summary="company ? company.companyName || company.displayName : ''"><template #actions><router-link v-if="platformManager" to="/nxr/partners"><el-button data-testid="submission-open-partners">{{ $tx('Sub-agent management') }}</el-button></router-link></template></NxrPageHeader><el-card v-if="platformManager" shadow="never" class="agent-company-card"><el-form inline><el-form-item :label="$tx('Current sub-agent company')"><el-select v-model="selectedId" filterable remote clearable :remote-method="searchCompanies" :loading="searching" :disabled="activeApi?.busy.value||loading" :placeholder="$tx('Search and select a sub-agent company')" style="width:360px;max-width:100%" data-testid="agent-company-select" @change="selectCompany"><el-option v-if="company&&!companies.some(item=>item.id===company?.id)" :value="company.id" :label="company.companyName||company.displayName" /><el-option v-for="item in companies" :key="item.id" :value="item.id" :label="`${item.companyName||item.displayName} · ${item.email}`" /></el-select></el-form-item></el-form></el-card><el-alert v-if="error" :title="error" type="error" :closable="false" class="agent-context-error" /><el-button v-if="error" @click="initialize">{{ $tx('Retry') }}</el-button><p v-if="loading" class="agent-loading" role="status">{{ $tx('Loading Submission Workspace…') }}</p><AgentWorkspace v-else-if="activeApi&&company" :key="`${company.id}-${generation}`" :api="activeApi" /><el-empty v-else-if="!error" :description="platformManager?$tx('Select a sub-agent company'):$tx('This account is not linked to a sub-agent company. Please contact the platform administrator.')" data-testid="agent-binding-required" /></main></template>
<style scoped>.agent-admin-page{overflow-x:hidden}.agent-company-card{margin-bottom:20px}.agent-company-card :deep(.el-form-item){margin-bottom:0}.agent-context-error{margin-bottom:12px}.agent-loading{padding:30px;color:var(--el-text-color-secondary)}</style>
