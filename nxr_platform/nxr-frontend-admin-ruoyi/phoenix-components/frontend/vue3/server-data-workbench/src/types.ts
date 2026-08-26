export interface ServerDataWorkbenchLabels {
  workbench: string
  filters: string
  query: string
  reset: string
  loadingTitle: string
  loadingDescription: string
  errorTitle: string
  retry: string
  emptyTitle: string
  emptyDescription: string
  pagination: string
  previousPage: string
  nextPage: string
  pageSize: string
  page: string
  of: string
  total: string
  items: string
}

export interface ServerDataQueryContext {
  page: number
  pageSize: number
}

export interface ServerDataWorkbenchPaginationContext extends ServerDataQueryContext {
  total: number
  totalPages: number
  pageSizeOptions: number[]
  goToPage: (page: number) => void
  setPageSize: (pageSize: number) => void
}
