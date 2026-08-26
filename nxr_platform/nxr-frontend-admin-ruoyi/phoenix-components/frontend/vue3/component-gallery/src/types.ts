export type CatalogCategory = 'frontend' | 'backend' | 'desktop' | 'common' | 'templates' | 'service'
export type CatalogStatus = 'template' | 'experimental' | 'stable' | 'deprecated'

export interface CatalogDependencies {
  runtime: string[]
  peer: string[]
}

export interface CatalogItem {
  id: string
  name: string
  version: string
  category: CatalogCategory
  stack: string
  status: CatalogStatus
  kind: string
  capabilities: string[]
  owner: string
  keywords: string[]
  summary: string
  path: string
  compatibility: Record<string, string>
  dependencies: CatalogDependencies
}

export interface ComponentGalleryLabels {
  all: string
  frontend: string
  backend: string
  desktop: string
  common: string
  templates: string
  service: string
  search: string
  stack: string
  status: string
  kind: string
  clear: string
  results: string
  preview: string
  capabilities: string
  compatibility: string
  dependencies: string
  path: string
  copy: string
  copied: string
  noResults: string
  noPreview: string
}
