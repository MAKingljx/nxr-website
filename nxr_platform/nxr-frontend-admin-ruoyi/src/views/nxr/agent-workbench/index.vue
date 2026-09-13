<script setup lang="ts">
import { nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import AgentWorkspace from './components/AgentWorkspace.vue'
import AgentOperatorsPanel from './components/AgentOperatorsPanel.vue'
import { createAgentApi, type AgentApi, type Company } from './lib/agentWorkbench'
defineOptions({ name:'NxrAgentWorkbench' })
const route=useRoute(),router=useRouter(), management=shallowRef(createAgentApi()), activeApi=shallowRef<AgentApi|null>(null)
const company=ref<Company|null>(null), platformManager=ref(false), companies=ref<Company[]>([]),selectedId=ref<number|undefined>(),loading=ref(true),searching=ref(false),error=ref(''),operatorsOpen=ref(false)
let generation=0,searchGeneration=0,initialization=0,initializing=false,visible=true
async function searchCompanies(query=''){const current=++searchGeneration;searching.value=true;try{const result=await management.value.fetchCompanies({query,page:1,pageSize:50});if(current===searchGeneration)companies.value=result.items}catch(e){if(current===searchGeneration)error.value=e instanceof Error?e.message:'企业加载失败。'}finally{if(current===searchGeneration)searching.value=false}}
async function selectCompany(id?:number,writeRoute=true){
  const current=++generation
  activeApi.value?.dispose();activeApi.value=null;company.value=null;selectedId.value=id;error.value='';loading.value=true
  await nextTick()
  if(current!==generation||!visible)return
  if(!id){loading.value=false;if(writeRoute)await router.replace({path:route.path,query:{}});return}
  const candidate=createAgentApi(id)
  try{const context=await candidate.fetchContext();if(current!==generation){candidate.dispose();return}if(!context.company||context.company.id!==id)throw new Error('无法确认该企业的操作权限。');company.value=context.company;activeApi.value=candidate;if(writeRoute)await router.replace({path:'/nxr/agent-workbench',query:{company:String(id),tab:'clients'}})}catch(e){candidate.dispose();if(current===generation)error.value=e instanceof Error?e.message:'企业工作台加载失败。'}finally{if(current===generation)loading.value=false}
}
async function initialize(){
  if(initializing)return
  const current=++initialization
  initializing=true;loading.value=true;error.value=''
  if(!management.value.isActive())management.value=createAgentApi()
  try{const context=await management.value.fetchContext();if(current!==initialization||!visible)return;platformManager.value=context.platformManager;const requested=Number(route.query.company);const id=context.platformManager&&Number.isSafeInteger(requested)&&requested>0?requested:context.company?.id;if(context.platformManager)await searchCompanies();if(current!==initialization||!visible)return;await selectCompany(id,false)}catch(e){if(current===initialization&&visible){error.value=e instanceof Error?e.message:'代理工作台加载失败。';loading.value=false}}finally{if(current===initialization)initializing=false}
}
function dispose(){generation++;searchGeneration++;initialization++;initializing=false;activeApi.value?.dispose();activeApi.value=null;company.value=null;management.value.dispose();operatorsOpen.value=false}
watch(()=>route.query.company,raw=>{if(!platformManager.value||initializing||loading.value||!visible)return;const id=Number(raw);if(Number.isSafeInteger(id)&&id>0){if(id!==company.value?.id)void selectCompany(id,false)}else if(company.value)void selectCompany(undefined,false)})
onMounted(initialize)
onActivated(()=>{visible=true;if(!management.value.isActive())void initialize()})
onDeactivated(()=>{visible=false;dispose()})
onBeforeUnmount(dispose)
</script>
<template><main class="nxr-workspace agent-admin-page" data-testid="agent-admin-workbench"><NxrPageHeader :title="$t('nav.agentWorkbench')" :summary="company ? company.companyName || company.displayName : ''"><template #actions><el-button v-if="platformManager" data-testid="agent-manage-operators" @click="operatorsOpen=true">代理账号绑定</el-button></template></NxrPageHeader><el-card v-if="platformManager" shadow="never" class="agent-company-card"><el-form inline><el-form-item label="当前代理企业"><el-select v-model="selectedId" filterable remote clearable :remote-method="searchCompanies" :loading="searching" :disabled="activeApi?.busy.value||loading" placeholder="搜索并选择代理企业" style="width:360px;max-width:100%" data-testid="agent-company-select" @change="selectCompany"><el-option v-if="company&&!companies.some(item=>item.id===company?.id)" :value="company.id" :label="company.companyName||company.displayName" /><el-option v-for="item in companies" :key="item.id" :value="item.id" :label="`${item.companyName||item.displayName} · ${item.email}`" /></el-select></el-form-item></el-form></el-card><el-alert v-if="error" :title="error" type="error" :closable="false" class="agent-context-error" /><el-button v-if="error" @click="initialize">重试</el-button><p v-if="loading" class="agent-loading" role="status">加载代理工作台…</p><AgentWorkspace v-else-if="activeApi&&company" :key="`${company.id}-${generation}`" :api="activeApi" /><el-empty v-else-if="!error" :description="platformManager?'请选择代理企业':'此后台账号尚未绑定代理企业，请联系平台管理员。'" data-testid="agent-binding-required" /><el-dialog v-if="platformManager" v-model="operatorsOpen" title="代理账号绑定" width="min(1000px,94vw)" destroy-on-close><AgentOperatorsPanel v-if="operatorsOpen" :api="management" /></el-dialog></main></template>
<style scoped>.agent-admin-page{overflow-x:hidden}.agent-company-card{margin-bottom:20px}.agent-company-card :deep(.el-form-item){margin-bottom:0}.agent-context-error{margin-bottom:12px}.agent-loading{padding:30px;color:var(--el-text-color-secondary)}</style>
