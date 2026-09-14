import request from '@/utils/request'
import { tx, localizeBackendMessage } from '@/i18n'

export type PartnerPage<T> = { items:T[]; total:number; page:number; pageSize:number }
export type PartnerSummary = {
  id:number;displayName:string;email:string;mobile:string|null;companyName:string;contactName:string;active:boolean;
  operatorCount:number;activeOperatorCount:number;operatorAccounts:string|null;clientCount:number;inventoryCount:number;pendingBatchCount:number;
  currencyCode:string;walletBalance:number|null;pendingRechargeCount:number|null;financeVisible:boolean;createdAt:string;
}
export type PartnerOperator = {sysUserId:number;userName:string;nickName:string;active:boolean;backendActive:boolean}
export type PartnerRecharge = {id:number;rechargeNo:string;currencyCode:string;amount:number;statusCode:string;payerReference:string;proofReference:string;reviewNote?:string;createdAt:string}
export type PartnerBatch = {id:number;batchNo:string;batchName:string;statusCode:string;totalRows:number;acceptedRows:number;createdAt:string}
export type PartnerDetail = {partner:PartnerSummary;operators:PartnerOperator[];wallets:Array<{currencyCode:string;balance:number}>;recharges:PartnerPage<PartnerRecharge>;batches:PartnerPage<PartnerBatch>;financeVisible:boolean}
export type PartnerProfileInput = Pick<PartnerSummary,'displayName'|'email'|'companyName'|'contactName'|'active'> & {mobile:string}
export type PartnerProvisionInput = {existingCustomerId?:number;email?:string;displayName?:string;mobile?:string;companyName?:string;contactName?:string;userName:string;nickName?:string;password:string;requestKey:string}

// Credential-bearing provisioning requests must bypass the generic sessionStorage duplicate-submit cache.
async function partnerRequest<T>(path:string,method='get',data?:object,params?:object):Promise<T>{
  try{return await request({url:`/api/admin/partners${path}`,method,data,params,headers:{repeatSubmit:false},suppressErrorMessage:true}) as T}
  catch(error:any){throw new Error(localizeBackendMessage(error?.response?.data?.message||error?.response?.data?.msg||error?.message)||tx('The operation could not be completed. Please try again.'))}
}
export const listPartners=(params:object)=>partnerRequest<PartnerPage<PartnerSummary>>('','get',undefined,params)
export const getPartner=(id:number)=>partnerRequest<PartnerDetail>(`/${id}`)
export const provisionPartner=(data:PartnerProvisionInput)=>partnerRequest<{customerId:number;sysUserId:number;replayed:boolean}>('','post',data)
export const updatePartner=(id:number,data:PartnerProfileInput)=>partnerRequest<PartnerSummary>(`/${id}`,'put',data)
export const requestPartnerRecharge=(id:number,data:object)=>partnerRequest<PartnerRecharge>(`/${id}/wallet-recharges`,'post',data)
export const setPartnerOperatorState=(customerId:number,sysUserId:number,active:boolean)=>request({url:`/api/admin/agent/operators/${sysUserId}`,method:'put',data:{merchantCustomerId:customerId,active},headers:{repeatSubmit:false},suppressErrorMessage:true})
