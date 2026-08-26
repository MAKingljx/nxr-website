import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import catalog from '../src/data/components.json'
import requestRegistry from '../src/data/component-requests.json'
import ComponentGallery from '../src/ComponentGallery.vue'
import * as componentLibrary from '../src'
import type { CatalogItem } from '../src/types'

const items: CatalogItem[] = [
  { id: 'frontend.vue3.workbench', name: 'Vue 3 业务工作台', version: '0.1.0', category: 'frontend', stack: 'vue3', status: 'experimental', kind: 'ui', capabilities: ['dashboard', 'search-filter'], owner: 'phoenix', keywords: ['table'], summary: '用于统计、搜索、筛选和处理业务资源。', path: 'frontend/vue3/workbench', compatibility: { vue: '3.5' }, dependencies: { runtime: [], peer: ['vue'] } },
  { id: 'frontend.vue3.schema-form', name: 'Vue 3 配置表单', version: '0.1.0', category: 'frontend', stack: 'vue3', status: 'experimental', kind: 'ui', capabilities: ['schema-form'], owner: 'phoenix', keywords: ['form'], summary: '通过配置生成响应式业务表单。', path: 'frontend/vue3/schema-form', compatibility: { vue: '3.5' }, dependencies: { runtime: [], peer: ['vue'] } },
  { id: 'frontend.vue3.api-client', name: 'Vue 3 通用接口客户端', version: '0.1.0', category: 'frontend', stack: 'vue3', status: 'experimental', kind: 'sdk', capabilities: ['typed-client', 'authorization'], owner: 'phoenix', keywords: ['api'], summary: '统一会话和资源接口。', path: 'frontend/vue3/api-client', compatibility: { typescript: '6' }, dependencies: { runtime: [], peer: [] } },
  { id: 'backend.fastapi.core', name: 'FastAPI 业务内核', version: '0.1.0', category: 'backend', stack: 'fastapi', status: 'experimental', kind: 'domain', capabilities: ['authorization'], owner: 'phoenix', keywords: ['rbac'], summary: '提供通用权限和业务能力。', path: 'backend/fastapi/core', compatibility: { python: '3.12' }, dependencies: { runtime: ['fastapi'], peer: [] } },
  { id: 'backend.flask.base-project', name: 'Flask SQLite 基础项目', version: '0.2.0', category: 'backend', stack: 'flask', status: 'experimental', kind: 'project-template', capabilities: ['authentication', 'rbac'], owner: 'phoenix', keywords: ['sqlite'], summary: '可直接开发的 Flask 基础项目。', path: 'backend/flask/base-project', compatibility: { python: '3.12' }, dependencies: { runtime: ['flask'], peer: [] } },
  { id: 'backend.spring-boot.base-project', name: 'Spring Boot SQLite 基础项目', version: '0.1.0', category: 'backend', stack: 'spring-boot', status: 'experimental', kind: 'project-template', capabilities: ['sqlite-persistence'], owner: 'phoenix', keywords: ['sqlite'], summary: '可直接开发的 Spring Boot 基础项目。', path: 'backend/spring-boot/base-project', compatibility: { java: '17' }, dependencies: { runtime: ['spring-boot'], peer: [] } },
  { id: 'templates.agnostic.project-template', name: '完整项目模板', version: '0.0.0', category: 'templates', stack: 'agnostic', status: 'template', kind: 'template', capabilities: [], owner: '', keywords: [], summary: '用于创建完整项目的复制模板。', path: 'templates/agnostic/project-template', compatibility: {}, dependencies: { runtime: [], peer: [] } },
]

function mountGallery() {
  return mount(ComponentGallery, { props: { items } })
}

async function showSection(wrapper: ReturnType<typeof mountGallery>, sectionId: string) {
  await wrapper.get('#gallery-category-jump').setValue(sectionId)
  await flushPromises()
}

describe('ComponentGallery', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/')
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } })
  })

  it('公共入口导出的真实组件数量与展厅和文档一致', () => {
    const reusableComponents = Object.entries(componentLibrary).filter(([name, value]) => (
      !['ComponentGallery', 'productCatalog'].includes(name) && typeof value === 'object' && value !== null
    ))
    expect(reusableComponents).toHaveLength(163)
  })

  it('uses a left category navigation while rendering every component section directly', () => {
    const wrapper = mountGallery()
    expect(wrapper.find('.cg-sidebar').exists()).toBe(false)
    expect(wrapper.find('[data-testid="detail-panel"]').exists()).toBe(false)
    expect(wrapper.find('.cg-body').exists()).toBe(true)
    expect(wrapper.findAll('.cg-category-nav__group')).toHaveLength(4)
    expect(wrapper.findAll('.cg-category-nav a')).toHaveLength(29)
    expect(wrapper.findAll('[data-navigation-disclosure]')).toHaveLength(5)
    expect(wrapper.findAll('.cg-category-nav__mobile option')).toHaveLength(29)
    expect(wrapper.find('.cg-category-nav small').exists()).toBe(false)
    expect(wrapper.findAll('.cg-section')).toHaveLength(1)
    expect(wrapper.get('.cg-section').attributes('id')).toBe('actions')
    for (const label of ['按钮与操作', '导航组件', '输入与搜索', '分隔与布局', '主题与外观', '数据展示', '反馈状态', 'CRUD 基础', '常用交互', '高级组件', '数据可视化', '高频表单', '认证与权限', '平台与布局', '业务组合', '营销与社区', '内容与消息', '系统后台核心', '商城与预约', '直播业务', '页面框架', '通用管理页', '工作台页面', '成品页面组合', '工程组件', '模板系统']) {
      expect(wrapper.text()).toContain(label)
    }
    expect(wrapper.get('#component-navigation-title').text()).toBe('组件')
    expect(wrapper.get('#template-navigation-title').text()).toBe('模板系统')
    expect(wrapper.get('#template-navigation-title').text()).toBe('模板系统')
    expect(wrapper.get('#product-navigation-title').text()).toBe('成品系统')
    expect(wrapper.get('#request-navigation-title').text()).toBe('组件需求')
    expect(wrapper.text()).toContain('使用反馈')
  })

  it('groups the desktop navigation and keeps one compact disclosure open', async () => {
    const wrapper = mountGallery()
    const disclosures = wrapper.findAll('[data-navigation-disclosure]')
    expect(disclosures.map((button) => button.text())).toEqual([
      '常用基础⌄',
      '数据与表单⌄',
      '业务场景⌄',
      '页面与工作台⌄',
      '工程目录⌄',
    ])
    expect(disclosures.map((button) => button.attributes('aria-expanded'))).toEqual([
      'true',
      'false',
      'false',
      'false',
      'false',
    ])

    await disclosures[1]?.trigger('click')
    expect(disclosures[0]?.attributes('aria-expanded')).toBe('false')
    expect(disclosures[1]?.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('#navigation-group-data').isVisible()).toBe(true)
    expect(wrapper.get('#navigation-group-foundation').isVisible()).toBe(false)

    await wrapper.get('#navigation-group-data a[href="#auth"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.cg-section')).toHaveLength(1)
    expect(wrapper.get('.cg-section').attributes('id')).toBe('auth')
  })

  it('uses one compact category jump control on small screens', async () => {
    const wrapper = mountGallery()
    const jump = wrapper.get('#gallery-category-jump')
    expect(jump.findAll('option')).toHaveLength(29)

    await jump.setValue('commerce')
    await flushPromises()
    expect(window.location.hash).toBe('#commerce')
    expect((jump.element as HTMLSelectElement).value).toBe('commerce')
    expect(wrapper.findAll('.cg-section')).toHaveLength(1)
    expect(wrapper.get('.cg-section').attributes('id')).toBe('commerce')
  })

  it('将成品系统和组件需求作为独立页面显示', async () => {
    const wrapper = mountGallery()
    await showSection(wrapper, 'products')
    expect(wrapper.findAll('.cg-section')).toHaveLength(1)
    expect(wrapper.get('.cg-section').attributes('id')).toBe('products')
    expect(wrapper.findAll('.px-products__card')).toHaveLength(2)

    await showSection(wrapper, 'requests')
    expect(wrapper.findAll('.cg-section')).toHaveLength(1)
    expect(wrapper.get('.cg-section').attributes('id')).toBe('requests')
    expect(requestRegistry.requests.length).toBeGreaterThan(0)
    expect(wrapper.findAll('[data-request-id]')).toHaveLength(requestRegistry.requests.length)

    await showSection(wrapper, 'usage-feedback')
    expect(wrapper.findAll('.cg-section')).toHaveLength(1)
    expect(wrapper.get('.cg-section').attributes('id')).toBe('usage-feedback')
    expect(wrapper.text()).toContain('提交使用反馈')
  })

  it('shows real components when their module is selected', async () => {
    const wrapper = mountGallery()
    expect(wrapper.findAll('.px-button').length).toBeGreaterThanOrEqual(10)
    expect(wrapper.findAll('.px-tag').length).toBeGreaterThanOrEqual(6)

    await showSection(wrapper, 'navigation')
    expect(wrapper.find('.px-breadcrumb').exists()).toBe(true)
    expect(wrapper.find('.px-tabs').exists()).toBe(true)
    expect(wrapper.find('.px-pagination').exists()).toBe(true)

    await showSection(wrapper, 'inputs')
    expect(wrapper.findAll('.px-search')).toHaveLength(3)
    expect(wrapper.findAll('.px-switch')).toHaveLength(3)

    await showSection(wrapper, 'layout')
    expect(wrapper.findAll('.px-divider').length).toBeGreaterThanOrEqual(5)

    await showSection(wrapper, 'feedback')
    expect(wrapper.findAll('.px-alert')).toHaveLength(4)

    await showSection(wrapper, 'crud')
    expect(wrapper.find('.px-data-table').exists()).toBe(true)

    await showSection(wrapper, 'interaction')
    expect(wrapper.find('.px-file-upload').exists()).toBe(true)

    await showSection(wrapper, 'advanced')
    expect(wrapper.find('.px-virtual-list').exists()).toBe(true)

    await showSection(wrapper, 'forms')
    expect(wrapper.find('.px-textarea').exists()).toBe(true)

    await showSection(wrapper, 'platform')
    expect(wrapper.find('.px-app-shell').exists()).toBe(true)

    await showSection(wrapper, 'business')
    expect(wrapper.find('.px-cart-summary').exists()).toBe(true)

    await showSection(wrapper, 'auth')
    expect(wrapper.find('.px-login-panel').exists()).toBe(true)
    expect(wrapper.find('.px-user-menu').exists()).toBe(true)
    expect(wrapper.find('.px-role-permission-matrix').exists()).toBe(true)

    await showSection(wrapper, 'marketing')
    expect(wrapper.findAll('.px-lucky-draw')).toHaveLength(3)
    expect(wrapper.findAll('.px-product-card')).toHaveLength(3)

    await showSection(wrapper, 'admin')
    expect(wrapper.find('.px-advanced-table').exists()).toBe(true)

    await showSection(wrapper, 'commerce')
    expect(wrapper.find('.px-sku-editor').exists()).toBe(true)

    await showSection(wrapper, 'live')
    expect(wrapper.find('.px-live-console').exists()).toBe(true)

    await showSection(wrapper, 'content')
    expect(wrapper.find('.px-notification-center').exists()).toBe(true)

    await showSection(wrapper, 'analytics')
    expect(wrapper.findAll('.px-metric-card')).toHaveLength(3)
    expect(wrapper.find('.px-trend-chart').exists()).toBe(true)

    await showSection(wrapper, 'management')
    expect(wrapper.findAll('.px-management-page')).toHaveLength(9)

    await showSection(wrapper, 'workspace')
    expect(wrapper.findAll('.px-workspace-page')).toHaveLength(5)

    await showSection(wrapper, 'solutions')
    expect(wrapper.find('.px-admin-workspace').exists()).toBe(true)
    expect(wrapper.find('.px-analytics-dashboard-page').exists()).toBe(true)
    expect(wrapper.find('.px-content-workspace').exists()).toBe(true)

    await showSection(wrapper, 'patterns')
    expect(wrapper.findAll('.px-page-pattern')).toHaveLength(6)
  })

  it('keeps component demos interactive on the same page', async () => {
    const wrapper = mountGallery()
    await showSection(wrapper, 'inputs')
    await wrapper.get('.px-search input:not(:disabled)').setValue('导航组件')
    expect(wrapper.text()).toContain('当前输入：导航组件')

    const switchButton = wrapper.findAll('[role="switch"]')[0]
    await switchButton.trigger('click')
    expect(switchButton.attributes('aria-checked')).toBe('false')

    await showSection(wrapper, 'navigation')
    const pendingTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes('待处理'))
    await pendingTab?.trigger('click')
    expect(wrapper.text()).toContain('当前栏目：待处理')
  })

  it('switches the whole gallery theme without secondary copy', async () => {
    const wrapper = mountGallery()
    expect(wrapper.get('#top').attributes('data-theme')).toBe('modern')
    const themeButtons = wrapper.findAll('.cg-theme-picker [role="radio"]')
    expect(themeButtons).toHaveLength(4)
    expect(wrapper.find('.cg-theme-picker small').exists()).toBe(false)
    await themeButtons[1].trigger('click')
    expect(wrapper.get('#top').attributes('data-theme')).toBe('business')
    expect(themeButtons[1].attributes('aria-checked')).toBe('true')
    await showSection(wrapper, 'themes')
    expect(wrapper.get('#themes').findAll('.cg-theme-preview')).toHaveLength(4)
  })

  it('renders engineering components and templates in separate selected modules', async () => {
    const wrapper = mountGallery()
    await showSection(wrapper, 'engineering')
    expect(wrapper.get('#engineering').findAll('.cg-engineering-group')).toHaveLength(2)
    expect(wrapper.text()).toContain('前端组件')
    expect(wrapper.text()).toContain('后端组件')
    expect(wrapper.get('#engineering').find('[data-component-id="frontend.vue3.workbench"]').exists()).toBe(true)
    expect(wrapper.get('#engineering').find('[data-component-id="backend.flask.base-project"]').exists()).toBe(false)

    await showSection(wrapper, 'templates')
    expect(wrapper.get('#templates').findAll('.cg-engineering-group')).toHaveLength(2)
    expect(wrapper.text()).toContain('后端模板')
    expect(wrapper.text()).toContain('工程模板')
    expect(wrapper.get('#templates').find('[data-component-id="backend.flask.base-project"]').exists()).toBe(true)
    expect(wrapper.get('#templates').find('[data-component-id="backend.spring-boot.base-project"]').exists()).toBe(true)
    expect(wrapper.get('#templates').find('[data-component-id="templates.agnostic.project-template"]').exists()).toBe(true)
  })

  it('copies the precise pull command from each directly visible engineering card', async () => {
    const wrapper = mountGallery()
    await showSection(wrapper, 'engineering')
    await wrapper.get('[data-component-id="frontend.vue3.schema-form"] .cg-copy-command').trigger('click')
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('python3 src/pcl.py pull frontend.vue3.schema-form')
    expect(wrapper.emitted('copy')?.[0]?.[0]).toBe('python3 src/pcl.py pull frontend.vue3.schema-form')
    expect(wrapper.get('[data-component-id="frontend.vue3.schema-form"]').text()).toContain('已复制命令')
  })

  it('uses Chinese labels for capabilities and lifecycle states', async () => {
    const wrapper = mountGallery()
    await showSection(wrapper, 'engineering')
    expect(wrapper.get('[data-component-id="frontend.vue3.workbench"]').text()).toContain('数据看板')
    expect(wrapper.get('[data-component-id="frontend.vue3.workbench"]').text()).toContain('搜索筛选')
    expect(wrapper.get('[data-component-id="frontend.vue3.api-client"]').text()).toContain('类型化客户端')
    expect(wrapper.get('[data-component-id="frontend.vue3.workbench"]').text()).toContain('试用')

    await showSection(wrapper, 'templates')
    expect(wrapper.get('[data-component-id="backend.flask.base-project"]').text()).toContain('身份认证')
    expect(wrapper.get('[data-component-id="backend.flask.base-project"]').text()).toContain('角色权限')
    expect(wrapper.get('[data-component-id="templates.agnostic.project-template"]').text()).toContain('模板')
  })

  it('ships a complete Chinese name and summary for every catalog entry', () => {
    const localizedItems = catalog as unknown as CatalogItem[]
    expect(localizedItems).toHaveLength(44)
    for (const item of localizedItems) {
      expect(item.name).toMatch(/[\u3400-\u9fff]/)
      expect(item.summary).toMatch(/[\u3400-\u9fff]/)
    }
  })

  it('keeps ordinary interface copy in Chinese while retaining technical identifiers', async () => {
    const wrapper = mountGallery()
    await showSection(wrapper, 'engineering')
    const text = wrapper.text()
    for (const oldCopy of ['Component OS', 'Catalog healthy', 'Experimental / Stable', 'Auto saved', 'active']) {
      expect(text).not.toContain(oldCopy)
    }
    expect(text).toContain('PHOENIX 设计系统')
    expect(text).toContain('组件 ID')
    expect(text).not.toContain('不需要先选择目录')
    expect(wrapper.find('.cg-switch-list small').exists()).toBe(false)
  })
})
