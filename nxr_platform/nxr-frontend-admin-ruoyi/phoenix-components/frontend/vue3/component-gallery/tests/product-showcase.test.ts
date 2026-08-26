import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ProductShowcasePage from '../src/products/ProductShowcasePage.vue'
import { productCatalog } from '../src/products/catalog'
import { toSafeProductUrl } from '../src/products/safety'
import type { CatalogItem } from '../src/products/types'

const internalProduct: CatalogItem = {
  id: 'internal-tool',
  name: '内测工具',
  type: '软件',
  stage: '内测',
  version: '0.1.0',
  techStack: ['Vue 3'],
  capabilities: ['流程编排'],
  summary: '用于验证多阶段筛选的测试条目。',
  sourcePath: 'frontend/vue3/internal-tool',
  updatedAt: '2026-08-11',
}

function mountPage(props: Record<string, unknown> = {}) {
  return mount(ProductShowcasePage, { props })
}

describe('ProductShowcasePage', () => {
  it('ships only the two repository-backed trial products', () => {
    expect(productCatalog).toHaveLength(2)
    expect(productCatalog.map((item) => item.name)).toEqual([
      'Phoenix组件展厅',
      'Phoenix资源管理后台',
    ])
    expect(productCatalog.every((item) => item.stage === '试用')).toBe(true)
  })

  it('uses stable product IDs and verified source paths', () => {
    expect(productCatalog.map(({ id, sourcePath }) => ({ id, sourcePath }))).toEqual([
      { id: 'phoenix-component-gallery', sourcePath: 'frontend/vue3/component-gallery' },
      { id: 'phoenix-resource-admin-app', sourcePath: 'frontend/vue3/resource-admin-app' },
    ])
  })

  it('renders the Chinese product page and structured metadata', () => {
    const wrapper = mountPage()
    expect(wrapper.get('h1').text()).toBe('Phoenix 成品展厅')
    expect(wrapper.findAll('.px-products__card')).toHaveLength(2)
    expect(wrapper.text()).toContain('Vue 3')
    expect(wrapper.text()).toContain('信息更新')
    expect(wrapper.text()).toContain('2026年08月11日')
  })

  it('states that trial entries are not production ready', () => {
    expect(mountPage().text()).toContain('不代表生产就绪')
  })

  it('emits controlled keyword updates without mutating the input prop', async () => {
    const wrapper = mountPage({ query: '' })
    await wrapper.get('input[type="search"]').setValue('管理')
    expect(wrapper.emitted('update:query')?.[0]?.[0]).toBe('管理')
    expect(wrapper.props('query')).toBe('')
    expect(wrapper.findAll('.px-products__card')).toHaveLength(2)
  })

  it('emits controlled type updates', async () => {
    const wrapper = mountPage()
    await wrapper.findAll('select')[0].setValue('软件')
    expect(wrapper.emitted('update:type')?.[0]?.[0]).toBe('软件')
  })

  it('emits controlled stage updates', async () => {
    const wrapper = mountPage({ items: [...productCatalog, internalProduct] })
    await wrapper.findAll('select')[1].setValue('内测')
    expect(wrapper.emitted('update:stage')?.[0]?.[0]).toBe('内测')
  })

  it('filters by a case-insensitive technology keyword', () => {
    const wrapper = mountPage({ query: 'VITEST' })
    expect(wrapper.findAll('.px-products__card')).toHaveLength(2)
  })

  it('searches product capabilities', () => {
    const wrapper = mountPage({ query: '账户安全' })
    expect(wrapper.findAll('.px-products__card')).toHaveLength(1)
    expect(wrapper.text()).toContain('Phoenix资源管理后台')
  })

  it('filters by product type', () => {
    const wrapper = mountPage({ type: '系统' })
    expect(wrapper.findAll('.px-products__card')).toHaveLength(1)
    expect(wrapper.text()).toContain('Phoenix组件展厅')
  })

  it('filters by product stage', () => {
    const wrapper = mountPage({ items: [...productCatalog, internalProduct], stage: '内测' })
    expect(wrapper.findAll('.px-products__card')).toHaveLength(1)
    expect(wrapper.text()).toContain('内测工具')
  })

  it('combines keyword, type, and stage filters', () => {
    const wrapper = mountPage({
      items: [...productCatalog, internalProduct],
      query: '流程编排',
      type: '软件',
      stage: '内测',
    })
    expect(wrapper.findAll('.px-products__card')).toHaveLength(1)
    expect(wrapper.get('.px-products__card').attributes('data-product-id')).toBe('internal-tool')
  })

  it('shows an empty state for unmatched filters', () => {
    const wrapper = mountPage({ query: '不存在的成品' })
    expect(wrapper.find('.px-products__empty').exists()).toBe(true)
    expect(wrapper.text()).toContain('没有找到匹配的成品')
  })

  it('requests all controlled filters to clear', async () => {
    const wrapper = mountPage({ query: '管理', type: '软件', stage: '试用' })
    await wrapper.get('.px-products__clear').trigger('click')
    expect(wrapper.emitted('update:query')?.[0]?.[0]).toBe('')
    expect(wrapper.emitted('update:type')?.[0]?.[0]).toBe('')
    expect(wrapper.emitted('update:stage')?.[0]?.[0]).toBe('')
    expect(wrapper.emitted('clear')).toHaveLength(1)
  })

  it('emits controlled selection without changing the selected card', async () => {
    const wrapper = mountPage({ selectedId: '' })
    await wrapper.findAll('.px-products__actions button')[0].trigger('click')
    expect(wrapper.emitted('update:selectedId')?.[0]?.[0]).toBe('phoenix-component-gallery')
    expect((wrapper.emitted('select')?.[0]?.[0] as CatalogItem).id).toBe('phoenix-component-gallery')
    expect(wrapper.find('.px-products__card.is-selected').exists()).toBe(false)
  })

  it('marks only the externally selected product', () => {
    const wrapper = mountPage({ selectedId: 'phoenix-resource-admin-app' })
    expect(wrapper.get('.px-products__card.is-selected').attributes('data-product-id')).toBe('phoenix-resource-admin-app')
  })

  it('accepts absolute HTTP and HTTPS URLs', () => {
    expect(toSafeProductUrl('http://127.0.0.1:4173/products')).toBe('http://127.0.0.1:4173/products')
    expect(toSafeProductUrl('https://example.com/demo')).toBe('https://example.com/demo')
  })

  it('rejects javascript and data URLs', () => {
    expect(toSafeProductUrl('javascript:alert(1)')).toBeNull()
    expect(toSafeProductUrl('data:text/html,unsafe')).toBeNull()
  })

  it('rejects embedded URL user information', () => {
    expect(toSafeProductUrl('https://user:secret@example.com/demo')).toBeNull()
    expect(toSafeProductUrl('http://user@example.com/demo')).toBeNull()
  })

  it('renders only safe URLs with external-link protection', async () => {
    const item = { ...internalProduct, url: 'https://example.com/demo' }
    const wrapper = mountPage({ items: [item] })
    const link = wrapper.get('.px-products__actions a')
    expect(link.attributes('href')).toBe('https://example.com/demo')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    await link.trigger('click')
    expect(wrapper.emitted('open')?.[0]).toEqual([item, 'https://example.com/demo'])
  })

  it('omits unsafe links supplied by a host', () => {
    const item = { ...internalProduct, url: 'javascript:alert(1)' }
    const wrapper = mountPage({ items: [item] })
    expect(wrapper.find('.px-products__actions a').exists()).toBe(false)
  })

  it('contains no HTML injection, network, storage, or timer behavior', () => {
    const sourcePath = resolve(process.cwd(), 'src/products/ProductShowcasePage.vue')
    const source = readFileSync(sourcePath, 'utf8')
    expect(source).not.toContain('v-html')
    expect(source).not.toMatch(/\b(fetch|XMLHttpRequest|localStorage|sessionStorage|setTimeout|setInterval)\b/)
  })
})
