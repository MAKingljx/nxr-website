import request from '@/utils/request'
import type { AgentPage } from './agentWorkbench'

export type AgentOverviewRow = {
  id: number; companyId: number; companyName: string; companyActive: boolean;
  reference: string; title: string; clientName: string | null; statusCode: string | null;
  cardCount: number | null; amount: number | string | null; currencyCode: string | null;
  detail: string | null; updatedAt: string | null;
}

export function fetchAgentOverview(view: string, params: { query: string; page: number; pageSize: number }, signal: AbortSignal) {
  return request({ url:`/api/admin/agent/overview/${view}`, method:'get', params, signal, suppressErrorMessage:true }) as unknown as Promise<AgentPage<AgentOverviewRow>>
}
