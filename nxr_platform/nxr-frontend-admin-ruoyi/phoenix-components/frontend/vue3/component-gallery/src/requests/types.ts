export const REQUEST_STATUSES = ['proposed', 'accepted', 'in_progress', 'done', 'rejected'] as const
export const REQUEST_PRIORITIES = ['P0', 'P1', 'P2', 'P3'] as const

export type ComponentRequestStatus = (typeof REQUEST_STATUSES)[number]
export type ComponentRequestPriority = (typeof REQUEST_PRIORITIES)[number]

export interface ComponentRequestItem {
  id: string
  title: string
  category: string
  stack: string
  kind: string
  priority: ComponentRequestPriority
  status: ComponentRequestStatus
  scenario: string
  capabilities: string[]
  acceptanceCriteria: string[]
  reuseCandidates: string[]
  targetComponentId: string | null
  requestedBy: string
  createdAt: string
  updatedAt: string
}

export interface ComponentRequestRegistry {
  schemaVersion: 1
  requests: ComponentRequestItem[]
}
