import rawCatalog from '../data/products.zh-CN.json'
import { PRODUCT_STAGES, PRODUCT_TYPES } from './types'
import type { CatalogItem, ProductStage, ProductType } from './types'
import { toSafeProductUrl } from './safety'

interface RawCatalogItem {
  id: string
  name: string
  type: string
  stage: string
  version: string
  techStack: string[]
  capabilities: string[]
  summary: string
  sourcePath: string
  url?: string
  updatedAt: string
}

function isProductType(value: string): value is ProductType {
  return PRODUCT_TYPES.some((item) => item === value)
}

function isProductStage(value: string): value is ProductStage {
  return PRODUCT_STAGES.some((item) => item === value)
}

function parseCatalogItem(raw: RawCatalogItem): CatalogItem {
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(raw.id)) {
    throw new TypeError(`Invalid product ID: ${raw.id}`)
  }
  if (!isProductType(raw.type)) {
    throw new TypeError(`Invalid product type: ${raw.type}`)
  }
  if (!isProductStage(raw.stage)) {
    throw new TypeError(`Invalid product stage: ${raw.stage}`)
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(raw.updatedAt)) {
    throw new TypeError(`Invalid product update date: ${raw.updatedAt}`)
  }

  const safeUrl = toSafeProductUrl(raw.url)
  return {
    id: raw.id,
    name: raw.name,
    type: raw.type,
    stage: raw.stage,
    version: raw.version,
    techStack: [...raw.techStack],
    capabilities: [...raw.capabilities],
    summary: raw.summary,
    sourcePath: raw.sourcePath,
    ...(safeUrl ? { url: safeUrl } : {}),
    updatedAt: raw.updatedAt,
  }
}

const parsedCatalog = (rawCatalog as RawCatalogItem[]).map(parseCatalogItem)
const productIds = new Set(parsedCatalog.map((item) => item.id))

if (productIds.size !== parsedCatalog.length) {
  throw new TypeError('Product IDs must be unique')
}

export const productCatalog: readonly CatalogItem[] = parsedCatalog
