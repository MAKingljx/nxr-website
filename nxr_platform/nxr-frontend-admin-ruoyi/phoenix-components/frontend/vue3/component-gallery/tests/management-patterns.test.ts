import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import {
  PhoenixAuditLogPage,
  PhoenixBookingManagementPage,
  PhoenixDepartmentManagementPage,
  PhoenixDictionaryConfigurationPage,
  PhoenixInventoryManagementPage,
  PhoenixManagementPageShell,
  PhoenixOrderManagementPage,
  PhoenixProductManagementPage,
  PhoenixRolePermissionPage,
  PhoenixUserManagementPage,
} from '../src/patterns/management'

describe('Phoenix 通用管理页面框架', () => {
  it('页面壳直接渲染统计、筛选、侧栏、内容和详情', () => {
    const wrapper = mount(PhoenixManagementPageShell, {
      props: { title: '管理页面', stats: [{ label: '全部', value: 12, tone: 'primary' }] },
      slots: { filters: '查询条件', sidebar: '分类树', default: '数据列表', detail: '数据详情' },
    })
    expect(wrapper.text()).toContain('管理页面')
    expect(wrapper.text()).toContain('查询条件')
    expect(wrapper.text()).toContain('分类树')
    expect(wrapper.text()).toContain('数据列表')
    expect(wrapper.text()).toContain('数据详情')
    expect(wrapper.get('[data-tone="primary"]').text()).toContain('12')
  })

  it('页面壳的操作保持受控且忙碌时禁用', async () => {
    const wrapper = mount(PhoenixManagementPageShell, { props: { title: '管理页', actions: [{ id: 'create', label: '新增', variant: 'primary' }] } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('action')?.[0]).toEqual(['create'])
    await wrapper.setProps({ busy: true })
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  })

  it('用户管理页提供导入导出新增事件', async () => {
    const wrapper = mount(PhoenixUserManagementPage)
    for (const button of wrapper.findAll('button')) await button.trigger('click')
    expect(wrapper.emitted('import')).toHaveLength(1)
    expect(wrapper.emitted('export')).toHaveLength(1)
    expect(wrapper.emitted('create')).toHaveLength(1)
  })

  it('角色权限页组合角色和权限插槽', async () => {
    const wrapper = mount(PhoenixRolePermissionPage, { slots: { roles: '角色列表', permissions: '权限矩阵' } })
    expect(wrapper.text()).toContain('角色列表')
    expect(wrapper.text()).toContain('权限矩阵')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('save')).toHaveLength(1)
  })

  it('部门管理页组合组织树和成员列表', async () => {
    const wrapper = mount(PhoenixDepartmentManagementPage, { slots: { tree: '组织架构', members: '部门成员' } })
    expect(wrapper.text()).toContain('组织架构')
    expect(wrapper.text()).toContain('部门成员')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('create')).toHaveLength(1)
  })

  it('字典配置页提供分类条目和导入新增事件', async () => {
    const wrapper = mount(PhoenixDictionaryConfigurationPage, { slots: { categories: '字典分类', entries: '字典条目' } })
    expect(wrapper.text()).toContain('字典分类')
    expect(wrapper.text()).toContain('字典条目')
    await wrapper.findAll('button')[0].trigger('click')
    expect(wrapper.emitted('import')).toHaveLength(1)
  })

  it('审计日志页提供筛选日志详情和导出', async () => {
    const wrapper = mount(PhoenixAuditLogPage, { slots: { filters: '日志筛选', default: '日志列表', detail: '日志详情' } })
    expect(wrapper.text()).toContain('日志筛选')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('export')).toHaveLength(1)
  })

  it('商品管理页提供列表编辑和新增', async () => {
    const wrapper = mount(PhoenixProductManagementPage, { slots: { default: '商品列表', editor: '商品编辑' } })
    expect(wrapper.text()).toContain('商品编辑')
    await wrapper.findAll('button')[2].trigger('click')
    expect(wrapper.emitted('create')).toHaveLength(1)
  })

  it('订单管理页提供批量和导出事件', async () => {
    const wrapper = mount(PhoenixOrderManagementPage)
    await wrapper.findAll('button')[0].trigger('click')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('batch')).toHaveLength(1)
    expect(wrapper.emitted('export')).toHaveLength(1)
  })

  it('库存管理页组合库存预警并发起盘点', async () => {
    const wrapper = mount(PhoenixInventoryManagementPage, { slots: { alerts: '库存预警' } })
    expect(wrapper.text()).toContain('库存预警')
    await wrapper.findAll('button')[0].trigger('click')
    expect(wrapper.emitted('stocktake')).toHaveLength(1)
  })

  it('预约管理页组合日历并提供新增预约', async () => {
    const wrapper = mount(PhoenixBookingManagementPage, { slots: { calendar: '预约日历' } })
    expect(wrapper.text()).toContain('预约日历')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('create')).toHaveLength(1)
  })
})
