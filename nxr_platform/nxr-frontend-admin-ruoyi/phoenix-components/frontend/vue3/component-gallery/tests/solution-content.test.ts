import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PhoenixContentWorkspacePage from '../src/patterns/solutions/content/PhoenixContentWorkspacePage.vue'

const notifications = [
  { id: 'n1', title: '审批待处理', description: '你有一项新任务', read: false, actionLabel: '处理', dismissible: true },
  { id: 'n2', title: '发布成功', read: true },
]

const threads = [
  { id: 't1', sender: '王敏', subject: '项目周报', preview: '本周进展已整理', unread: true, starred: true },
  { id: 't2', sender: '李华', subject: '历史消息', unread: true, archived: true },
]

const activities = [
  { id: 'a1', actor: '产品组', action: '更新了', target: '内容计划', description: '新增三个交付节点', actionLabel: '查看' },
]

const announcement = {
  title: '系统维护安排',
  description: '今晚 22:00 进行例行维护',
  actionLabel: '查看安排',
  dismissible: true,
  tone: 'warning' as const,
}

function mountWorkspace(extra: Record<string, unknown> = {}) {
  return mount(PhoenixContentWorkspacePage, {
    props: { notifications, threads, activities, announcement, ...extra },
  })
}

describe('PhoenixContentWorkspacePage', () => {
  it('以中文默认内容组合四个内容组件', () => {
    const wrapper = mountWorkspace()
    expect(wrapper.get('h1').text()).toBe('内容工作台')
    expect(wrapper.text()).toContain('集中处理消息、通知与团队动态')
    expect(wrapper.find('.px-message-inbox').exists()).toBe(true)
    expect(wrapper.find('.px-notification-center').exists()).toBe(true)
    expect(wrapper.find('.px-activity-feed').exists()).toBe(true)
    expect(wrapper.find('.px-announcement-banner').exists()).toBe(true)
  })

  it('自动汇总未读消息、未读通知和动态数量', () => {
    const wrapper = mountWorkspace()
    const values = wrapper.findAll('.px-content-workspace__stats dd').map((item) => item.text())
    expect(values).toEqual(['1', '1', '1'])
  })

  it('统一把 appearance 透传给所有组合组件', () => {
    const wrapper = mountWorkspace({ appearance: 'soft' })
    expect(wrapper.attributes('data-appearance')).toBe('soft')
    expect(wrapper.get('.px-message-inbox').attributes('data-appearance')).toBe('soft')
    expect(wrapper.get('.px-notification-center').attributes('data-appearance')).toBe('soft')
    expect(wrapper.get('.px-activity-feed').attributes('data-appearance')).toBe('soft')
    expect(wrapper.get('.px-announcement-banner').attributes('data-appearance')).toBe('soft')
  })

  it('异常运行时外观值收敛为 modern', () => {
    const wrapper = mountWorkspace({ appearance: 'glass' })
    expect(wrapper.attributes('data-appearance')).toBe('modern')
  })

  it('窄屏面板选择是受控值并发出更新事件', async () => {
    const wrapper = mountWorkspace({ activePanel: 'inbox' })
    const tabs = wrapper.findAll('[aria-label="内容工作台视图"] button')
    await tabs[1].trigger('click')
    expect(wrapper.emitted('update:activePanel')?.[0]).toEqual(['notifications'])
    expect(wrapper.get('.px-content-workspace__panel--inbox').classes()).toContain('is-active')
    expect(wrapper.get('.px-content-workspace__panel--notifications').classes()).not.toContain('is-active')
  })

  it('转发受控通知筛选值', async () => {
    const wrapper = mountWorkspace({ notificationFilter: 'unread' })
    const panel = wrapper.get('.px-content-workspace__panel--notifications')
    expect(panel.text()).not.toContain('发布成功')
    await panel.findAll('[aria-label="通知筛选"] button')[0].trigger('click')
    expect(wrapper.emitted('update:notificationFilter')?.[0]).toEqual(['all'])
  })

  it('通知选择统一为带 kind 的 select 事件', async () => {
    const wrapper = mountWorkspace()
    await wrapper.get('.px-notification-center__main').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([{ kind: 'notification', item: notifications[0] }])
  })

  it('已读、全部已读、通知操作和移除直接转发', async () => {
    const wrapper = mountWorkspace()
    const panel = wrapper.get('.px-content-workspace__panel--notifications')
    await panel.get('[aria-label="标记已读：审批待处理"]').trigger('click')
    await panel.get('.px-content-header button').trigger('click')
    await panel.get('[aria-label="移除通知：审批待处理"]').trigger('click')
    const actionButton = panel.findAll('.px-notification-center__item .px-content-actions button').find((button) => button.text() === '处理')
    await actionButton!.trigger('click')
    expect(wrapper.emitted('read')?.[0]).toEqual([notifications[0]])
    expect(wrapper.emitted('read-all')).toHaveLength(1)
    expect(wrapper.emitted('dismiss')?.[0]).toEqual([{ kind: 'notification', item: notifications[0] }])
    expect(wrapper.emitted('action')?.[0]).toEqual([{ kind: 'notification', item: notifications[0] }])
  })

  it('会话选择同时转发 selectedThreadId 并统一 select 载荷', async () => {
    const wrapper = mountWorkspace()
    await wrapper.get('.px-message-inbox__main').trigger('click')
    expect(wrapper.emitted('update:selectedThreadId')?.[0]).toEqual(['t1'])
    expect(wrapper.emitted('select')?.[0]).toEqual([{ kind: 'thread', item: threads[0] }])
  })

  it('消息查询与文件夹保持受控转发', async () => {
    const wrapper = mountWorkspace()
    await wrapper.get('.px-message-inbox__search input').setValue('周报')
    const folders = wrapper.findAll('[aria-label="消息文件夹"] button')
    await folders[1].trigger('click')
    expect(wrapper.emitted('update:messageQuery')?.[0]).toEqual(['周报'])
    expect(wrapper.emitted('update:inboxFolder')?.[0]).toEqual(['starred'])
  })

  it('页头和收件箱的写消息入口统一发出 compose', async () => {
    const wrapper = mountWorkspace()
    await wrapper.get('.px-content-workspace__compose').trigger('click')
    await wrapper.get('.px-message-inbox .px-content-header button').trigger('click')
    expect(wrapper.emitted('compose')).toHaveLength(2)
  })

  it('归档和标星事件保留完整会话及目标状态', async () => {
    const wrapper = mountWorkspace()
    await wrapper.get('[aria-label="归档：项目周报"]').trigger('click')
    await wrapper.get('[aria-label="取消标星：项目周报"]').trigger('click')
    expect(wrapper.emitted('archive')?.[0]).toEqual([threads[0]])
    expect(wrapper.emitted('star')?.[0]).toEqual([threads[0], false])
  })

  it('动态选择、操作和加载更多无需消费者手动接线子组件', async () => {
    const wrapper = mountWorkspace({ activityHasMore: true })
    const panel = wrapper.get('.px-content-workspace__panel--activity')
    await panel.get('article').trigger('keydown', { key: 'Enter' })
    await panel.get('.px-activity-feed__list > li > button').trigger('click')
    await panel.get('.px-content-footer button').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([{ kind: 'activity', item: activities[0] }])
    expect(wrapper.emitted('action')?.[0]).toEqual([{ kind: 'activity', item: activities[0] }])
    expect(wrapper.emitted('load-more')).toHaveLength(1)
  })

  it('公告操作、关闭和可见性更新向页面消费者转发', async () => {
    const wrapper = mountWorkspace()
    const banner = wrapper.get('.px-announcement-banner')
    await banner.findAll('button')[0].trigger('click')
    await banner.get('[aria-label="关闭公告"]').trigger('click')
    expect(wrapper.emitted('action')?.[0]).toEqual([{ kind: 'announcement', item: announcement }])
    expect(wrapper.emitted('update:announcementVisible')?.[0]).toEqual([false])
    expect(wrapper.emitted('dismiss')?.[0]).toEqual([{ kind: 'announcement', item: announcement }])
    expect(wrapper.find('.px-announcement-banner').exists()).toBe(true)
  })

  it('总加载态与禁用态统一下发到子面板', async () => {
    const wrapper = mountWorkspace({ loading: true, disabled: true })
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.px-message-inbox').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.px-notification-center').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.px-activity-feed').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.px-content-workspace__compose').attributes('disabled')).toBeDefined()
    await wrapper.get('.px-content-workspace__compose').trigger('click')
    expect(wrapper.emitted('compose')).toBeUndefined()
  })

  it('外部内容保持纯文本，不执行 HTML', () => {
    const wrapper = mountWorkspace({
      notifications: [{ id: 1, title: '<script>bad()</script>' }],
      threads: [{ id: 1, sender: '<img src=x>', subject: '<b>消息</b>' }],
      activities: [{ id: 1, actor: '<svg onload=bad()>', action: '更新' }],
      announcement: { title: '<iframe src=x></iframe>' },
    })
    expect(wrapper.text()).toContain('<script>bad()</script>')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.find('svg').exists()).toBe(false)
  })

  it('无数据时一次提供三个中文空状态', () => {
    const wrapper = mount(PhoenixContentWorkspacePage)
    expect(wrapper.text()).toContain('暂无消息')
    expect(wrapper.text()).toContain('暂无通知')
    expect(wrapper.text()).toContain('暂无动态')
    expect(wrapper.find('.px-announcement-banner').exists()).toBe(false)
  })
})
