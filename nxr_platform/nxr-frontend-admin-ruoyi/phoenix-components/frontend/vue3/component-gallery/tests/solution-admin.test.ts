import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PhoenixAdminWorkspacePage from '../src/patterns/solutions/admin/PhoenixAdminWorkspacePage.vue'

const columns = [
  { key: 'name', label: '姓名', sortable: true, filterable: true, editable: true },
  { key: 'department', label: '部门' },
]
const rows = [{ id: 1, name: '李明', department: '产品中心' }, { id: 2, name: '王芳', department: '技术中心' }]
const queryFields = [{ key: 'keyword', label: '姓名', type: 'search' as const }]

function mountPage(props = {}) {
  return mount(PhoenixAdminWorkspacePage, { props: { columns, rows, queryFields, batchActions: [{ key: 'enable', label: '批量启用' }], ...props } })
}

describe('PhoenixAdminWorkspacePage', () => {
  it('renders a complete controlled management workspace', () => {
    const wrapper = mountPage()
    expect(wrapper.text()).toContain('系统数据管理')
    expect(wrapper.find('.px-query-panel').exists()).toBe(true)
    expect(wrapper.find('.px-batch-action-bar').exists()).toBe(true)
    expect(wrapper.find('.px-advanced-table').exists()).toBe(true)
    expect(wrapper.find('.px-import-export-panel').exists()).toBe(true)
  })

  it('renders supplied metrics and title', () => {
    const wrapper = mountPage({ title: '用户管理', stats: [{ label: '全部用户', value: 1286 }] })
    expect(wrapper.text()).toContain('用户管理')
    expect(wrapper.text()).toContain('1286')
  })

  it('emits create without mutating data', async () => {
    const wrapper = mountPage()
    await wrapper.get('.px-management-page__header button').trigger('click')
    expect(wrapper.emitted('create')).toHaveLength(1)
  })

  it('forwards controlled query updates and submit', async () => {
    const wrapper = mountPage({ query: { keyword: '' } })
    await wrapper.get('.px-query-panel input').setValue('王芳')
    await wrapper.get('.px-query-panel').trigger('submit')
    expect(wrapper.emitted('update:query')?.[0]?.[0]).toEqual({ keyword: '王芳' })
    expect(wrapper.emitted('query')?.[0]?.[0]).toEqual({ keyword: '' })
  })

  it('forwards reset requests', async () => {
    const wrapper = mountPage()
    const reset = wrapper.findAll('.px-query-panel button').find((item) => item.text() === '重置')
    await reset?.trigger('click')
    expect(wrapper.emitted('reset')).toHaveLength(1)
  })

  it('forwards table selection as controlled keys', async () => {
    const wrapper = mountPage()
    await wrapper.findAll('.px-advanced-table tbody input')[0].setValue(true)
    expect(wrapper.emitted('update:selectedKeys')?.[0]?.[0]).toEqual([1])
  })

  it('forwards server sorting', async () => {
    const wrapper = mountPage()
    await wrapper.get('[aria-label="按姓名排序"]').trigger('click')
    expect(wrapper.emitted('sortChange')?.[0]?.[0]).toEqual({ key: 'name', direction: 'asc' })
  })

  it('forwards column filters', async () => {
    const wrapper = mountPage()
    await wrapper.get('[aria-label="筛选姓名"]').setValue('李')
    expect(wrapper.emitted('filterChange')?.[0]?.[0]).toEqual({ name: '李' })
  })

  it('enables batch actions only with selected rows', async () => {
    const wrapper = mountPage({ selectedKeys: [1] })
    await wrapper.findAll('.px-batch-action-bar button')[0].trigger('click')
    expect(wrapper.emitted('batchAction')?.[0]?.[0]).toMatchObject({ key: 'enable' })
  })

  it('forwards clear selection', async () => {
    const wrapper = mountPage({ selectedKeys: [1] })
    await wrapper.findAll('.px-batch-action-bar button').at(-1)?.trigger('click')
    expect(wrapper.emitted('clearSelection')).toHaveLength(1)
  })

  it('forwards export format requests', async () => {
    const wrapper = mountPage()
    await wrapper.findAll('.px-import-export-panel button').at(-1)?.trigger('click')
    expect(wrapper.emitted('exportRequest')?.[0]?.[0]).toBe('xlsx')
  })

  it('can hide import and export for read-only pages', () => {
    const wrapper = mountPage({ showImportExport: false })
    expect(wrapper.find('.px-import-export-panel').exists()).toBe(false)
  })

  it('disables page actions while loading', () => {
    const wrapper = mountPage({ loading: true })
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.px-management-page__header button').attributes('disabled')).toBeDefined()
  })
})
