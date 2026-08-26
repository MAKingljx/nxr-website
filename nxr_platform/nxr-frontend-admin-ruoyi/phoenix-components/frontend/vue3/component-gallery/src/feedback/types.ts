import type { ComponentRequestPriority, ComponentRequestStatus } from '../requests/types'

export type FetchClient = (input: string, init?: RequestInit) => Promise<Response>

export interface EditableRequestRecord {
  id: string
  title: string
  description: string
  capabilityArea: string
  useCase: string
  expectedOutcome: string
  targetStacks: string[]
  priority: 'low' | 'medium' | 'high' | ComponentRequestPriority
  referenceUrl?: string | null
  status: ComponentRequestStatus | string
  createdAt?: string
  updatedAt?: string
}

export interface EditableFeedbackRecord {
  id: string
  componentId: string
  componentVersion?: string
  releaseTag?: string | null
  commit?: string | null
  consumerLockDigest?: string | null
  title: string
  useCase: string
  problem: string
  impact: string
  keyImprovement: string
  acceptanceCriteria: string
  status: string
  createdAt?: string
  updatedAt?: string
  componentBinding?: 'verified' | 'legacy' | string
}

export interface CreatedRecord<T> {
  record: T
  editToken?: string
  replayed?: boolean
}

export interface RecordResponse<T> {
  record: T
}

export interface RecordListResponse<T> {
  records: T[]
  page?: number
  limit?: number
  total?: number
}

export interface ApiErrorBody {
  error?: {
    code?: string
    message?: string
  }
  message?: string
}
