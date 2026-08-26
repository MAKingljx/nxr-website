export const PRODUCT_TYPES = ['系统', '软件'] as const
export const PRODUCT_STAGES = ['试用', '内测', '维护', '归档'] as const

export type ProductType = (typeof PRODUCT_TYPES)[number]
export type ProductStage = (typeof PRODUCT_STAGES)[number]
export type ProductTypeFilter = ProductType | ''
export type ProductStageFilter = ProductStage | ''

/**
 * A repository-backed system or software entry shown in the product catalog.
 * Product IDs remain stable across name and version changes.
 */
export interface CatalogItem {
  id: string
  name: string
  type: ProductType
  stage: ProductStage
  version: string
  techStack: string[]
  capabilities: string[]
  summary: string
  sourcePath: string
  url?: string
  updatedAt: string
}
