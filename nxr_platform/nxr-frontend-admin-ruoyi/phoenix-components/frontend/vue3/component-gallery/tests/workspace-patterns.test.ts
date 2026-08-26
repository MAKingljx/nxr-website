import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import {
  PhoenixFileCenterPage,
  PhoenixMessageCenterPage,
  PhoenixProfileSettingsPage,
  PhoenixSystemSettingsPage,
  PhoenixWorkspaceHomePage,
  PhoenixWorkspacePageShell,
} from '../src/patterns/workspace'

describe('workspace page patterns', () => {
  it('renders navigation and emits controlled shell actions', async () => {
    const wrapper = mount(PhoenixWorkspacePageShell, { props: { title: '工作台', activeNavigation: 'home', navigation: [{ id: 'home', label: '首页' }, { id: 'files', label: '文件' }], actions: [{ id: 'create', label: '新建', primary: true }] } })
    expect(wrapper.get('h1').text()).toBe('工作台')
    expect(wrapper.get('[aria-current="page"]').text()).toContain('首页')
    await wrapper.findAll('.px-workspace-page__navigation button')[1].trigger('click')
    await wrapper.get('.px-workspace-page__actions button').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([['files']])
    expect(wrapper.emitted('action')).toEqual([['create']])
  })

  it('disables controls while busy', () => {
    const wrapper = mount(PhoenixWorkspacePageShell, { props: { title: '忙碌', busy: true, navigation: [{ id: 'home', label: '首页' }], actions: [{ id: 'save', label: '保存' }] } })
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.findAll('button').every((item) => item.attributes('disabled') !== undefined)).toBe(true)
  })

  it('renders optional brand, topbar, aside, and footer regions', () => {
    const wrapper = mount(PhoenixWorkspacePageShell, { props: { title: '工作台' }, slots: { brand: '品牌', topbar: '顶部工具', aside: '辅助信息', footer: '页脚信息' } })
    for (const copy of ['品牌', '顶部工具', '辅助信息', '页脚信息']) expect(wrapper.text()).toContain(copy)
  })

  it('does not render optional regions when slots are absent', () => {
    const wrapper = mount(PhoenixWorkspacePageShell, { props: { title: '工作台' } })
    expect(wrapper.find('.px-workspace-page__aside').exists()).toBe(false)
    expect(wrapper.find('.px-workspace-page__footer').exists()).toBe(false)
  })

  it('does not emit navigation from a disabled item', async () => {
    const wrapper = mount(PhoenixWorkspacePageShell, { props: { title: '工作台', navigation: [{ id: 'locked', label: '不可访问', disabled: true }] } })
    await wrapper.get('.px-workspace-page__navigation button').trigger('click')
    expect(wrapper.emitted('navigate')).toBeUndefined()
  })

  it('renders the workspace home slots and create event', async () => {
    const wrapper = mount(PhoenixWorkspaceHomePage, { slots: { metrics: '<strong>128</strong>', tasks: '待办列表', activity: '最近动态', aside: '快捷入口' } })
    expect(wrapper.text()).toContain('待办列表')
    expect(wrapper.text()).toContain('最近动态')
    expect(wrapper.text()).toContain('快捷入口')
    await wrapper.get('.px-workspace-page__actions button').trigger('click')
    expect(wrapper.emitted('create')).toHaveLength(1)
  })

  it('clamps unread counts and emits message actions', async () => {
    const wrapper = mount(PhoenixMessageCenterPage, { props: { unread: -3, activeNavigation: 'unread' }, slots: { list: '消息列表', detail: '消息详情' } })
    expect(wrapper.text()).toContain('未读消息0')
    expect(wrapper.get('[aria-current="page"]').text()).toContain('未读消息')
    const actions = wrapper.findAll('.px-workspace-page__actions button')
    await actions[0].trigger('click')
    await actions[1].trigger('click')
    expect(wrapper.emitted('markAllRead')).toHaveLength(1)
    expect(wrapper.emitted('compose')).toHaveLength(1)
  })

  it('renders file areas and emits file actions', async () => {
    const wrapper = mount(PhoenixFileCenterPage, { props: { activeNavigation: 'shared' }, slots: { folders: '文件夹', files: '文件列表', preview: '文件预览' } })
    expect(wrapper.text()).toContain('文件预览')
    expect(wrapper.get('[aria-current="page"]').text()).toContain('共享给我')
    const actions = wrapper.findAll('.px-workspace-page__actions button')
    await actions[0].trigger('click')
    await actions[1].trigger('click')
    expect(wrapper.emitted('createFolder')).toHaveLength(1)
    expect(wrapper.emitted('upload')).toHaveLength(1)
  })

  it('emits profile navigation and save without persisting data', async () => {
    const wrapper = mount(PhoenixProfileSettingsPage, { slots: { default: '<form>资料表单</form>' } })
    await wrapper.findAll('.px-workspace-page__navigation button')[1].trigger('click')
    await wrapper.get('.px-workspace-page__actions button').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([['security']])
    expect(wrapper.emitted('save')).toHaveLength(1)
  })

  it('emits system reset and save separately', async () => {
    const wrapper = mount(PhoenixSystemSettingsPage, { slots: { default: '配置表单', audit: '变更记录' } })
    const actions = wrapper.findAll('.px-workspace-page__actions button')
    await actions[0].trigger('click')
    await actions[1].trigger('click')
    expect(wrapper.emitted('reset')).toHaveLength(1)
    expect(wrapper.emitted('save')).toHaveLength(1)
    expect(wrapper.text()).toContain('变更记录')
  })

  it.each([
    [PhoenixWorkspaceHomePage, '工作台'],
    [PhoenixMessageCenterPage, '消息中心'],
    [PhoenixFileCenterPage, '文件中心'],
    [PhoenixProfileSettingsPage, '个人设置'],
    [PhoenixSystemSettingsPage, '系统设置'],
  ])('uses Chinese copy for %s', (component, title) => {
    expect(mount(component).text()).toContain(title)
  })
})
