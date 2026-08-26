import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ComponentRequestShowcase from '../src/requests/ComponentRequestShowcase.vue'
import type { ComponentRequestItem } from '../src/requests/types'

const requests: ComponentRequestItem[] = [
  {
    id: 'REQ-20260811-PRODUCT-SHOWCASE',
    title: '成品系统与软件展示',
    category: 'frontend',
    stack: 'vue3',
    kind: 'ui',
    priority: 'P1',
    status: 'in_progress',
    scenario: '集中展示可以运行的成品系统和软件。',
    capabilities: ['product-catalog', 'product-filter'],
    acceptanceCriteria: ['页面使用中文', '支持关键词筛选'],
    reuseCandidates: ['frontend.vue3.component-gallery'],
    targetComponentId: 'frontend.vue3.component-gallery',
    requestedBy: 'user',
    createdAt: '2026-08-11',
    updatedAt: '2026-08-11',
  },
  {
    id: 'REQ-20260811-TABLE',
    title: '高级数据表格',
    category: 'frontend',
    stack: 'vue3',
    kind: 'ui',
    priority: 'P0',
    status: 'done',
    scenario: '后台列表需要排序和批量选择。',
    capabilities: ['data-table'],
    acceptanceCriteria: ['支持服务端排序'],
    reuseCandidates: [],
    targetComponentId: null,
    requestedBy: 'another-ai',
    createdAt: '2026-08-10',
    updatedAt: '2026-08-11',
  },
]

function mountPage() {
  return mount(ComponentRequestShowcase, { props: { items: requests } })
}

describe('ComponentRequestShowcase', () => {
  it('直接显示全部需求与统计', () => {
    const wrapper = mountPage()
    expect(wrapper.findAll('[data-request-id]')).toHaveLength(2)
    expect(wrapper.text()).toContain('组件需求清单')
    expect(wrapper.text()).toContain('待推进')
    expect(wrapper.text()).toContain('成品系统与软件展示')
  })

  it('显示优先级、状态、技术方向和来源', () => {
    const wrapper = mountPage()
    const first = wrapper.get('[data-request-id="REQ-20260811-PRODUCT-SHOWCASE"]')
    expect(first.text()).toContain('P1')
    expect(first.text()).toContain('开发中')
    expect(first.text()).toContain('frontend / vue3 / ui')
    expect(first.text()).toContain('user')
  })

  it('展示能力、验收标准、复用候选和目标组件', () => {
    const wrapper = mountPage()
    const first = wrapper.get('[data-request-id="REQ-20260811-PRODUCT-SHOWCASE"]')
    expect(first.text()).toContain('product-catalog')
    expect(first.text()).toContain('页面使用中文')
    expect(first.text()).toContain('优先复用')
    expect(first.text()).toContain('frontend.vue3.component-gallery')
  })

  it('可按关键词过滤', async () => {
    const wrapper = mountPage()
    await wrapper.get('input[type="search"]').setValue('数据表格')
    expect(wrapper.findAll('[data-request-id]')).toHaveLength(1)
    expect(wrapper.text()).toContain('高级数据表格')
  })

  it('关键词可匹配能力和验收标准', async () => {
    const wrapper = mountPage()
    await wrapper.get('input[type="search"]').setValue('product-filter')
    expect(wrapper.findAll('[data-request-id]')).toHaveLength(1)
    await wrapper.get('input[type="search"]').setValue('服务端排序')
    expect(wrapper.findAll('[data-request-id]')).toHaveLength(1)
    expect(wrapper.text()).toContain('高级数据表格')
  })

  it('可按状态过滤', async () => {
    const wrapper = mountPage()
    await wrapper.findAll('select')[0]?.setValue('done')
    expect(wrapper.findAll('[data-request-id]')).toHaveLength(1)
    expect(wrapper.text()).toContain('高级数据表格')
  })

  it('可按优先级组合过滤', async () => {
    const wrapper = mountPage()
    await wrapper.findAll('select')[0]?.setValue('in_progress')
    await wrapper.findAll('select')[1]?.setValue('P1')
    expect(wrapper.findAll('[data-request-id]')).toHaveLength(1)
    expect(wrapper.text()).toContain('成品系统与软件展示')
  })

  it('无结果时显示中文空状态并可清空', async () => {
    const wrapper = mountPage()
    await wrapper.get('input[type="search"]').setValue('不存在的需求')
    expect(wrapper.find('[data-request-id]').exists()).toBe(false)
    expect(wrapper.text()).toContain('没有匹配的组件需求')
    await wrapper.get('.px-requests__empty button').trigger('click')
    expect(wrapper.findAll('[data-request-id]')).toHaveLength(2)
  })

  it('没有筛选条件时清除按钮不可用', () => {
    const wrapper = mountPage()
    expect(wrapper.get('.px-requests__filters button').attributes('disabled')).toBeDefined()
  })

  it('使用本地数据且源码不包含运行时副作用', async () => {
    const source = await import('../src/requests/ComponentRequestShowcase.vue?raw')
    expect(source.default).not.toMatch(/fetch\(|axios|localStorage|sessionStorage|setTimeout|setInterval|v-html/)
  })

  it('接口可用时额外展示最新匿名需求且不覆盖静态清单', async () => {
    const fetchClient = vi.fn().mockResolvedValue(new Response(JSON.stringify({ records: [{ id: 'REQ-PUBLIC', title: '公开需求', description: '内容', capabilityArea: '通用能力', useCase: '跨主题项目', expectedOutcome: '可以组合复用', targetStacks: [], priority: 'medium', status: 'proposed' }] }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const wrapper = mount(ComponentRequestShowcase, { props: { items: requests, fetchClient } })
    await flushPromises()
    expect(wrapper.get('[data-submission-id="REQ-PUBLIC"]').text()).toContain('公开需求')
    expect(wrapper.findAll('[data-request-id]')).toHaveLength(2)
  })
})
