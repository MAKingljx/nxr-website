import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import {
  BookingPage,
  CheckoutPage,
  DashboardPage,
  LearningPage,
  LiveRoomPage,
  ResourceManagementPage,
} from '../src/patterns'

describe('通用页面组合框架', () => {
  describe('DashboardPage', () => {
    it('展示中文标题和可组合的区域', () => {
      const wrapper = mount(DashboardPage, {
        slots: {
          navigation: '主导航',
          metrics: '关键指标',
          default: '趋势内容',
          aside: '待办事项',
        },
      })
      expect(wrapper.get('main').attributes('aria-label')).toBe('管理工作台')
      expect(wrapper.get('h1').text()).toBe('管理工作台')
      expect(wrapper.get('aside[aria-label="工作台导航"]').text()).toBe('主导航')
      expect(wrapper.get('[aria-label="关键指标"]').text()).toBe('关键指标')
      expect(wrapper.get('[aria-label="主要内容"]').text()).toBe('趋势内容')
      expect(wrapper.get('[aria-label="辅助信息"]').text()).toBe('待办事项')
    })

    it('导航打开状态受控且可用 Escape 请求关闭', async () => {
      const wrapper = mount(DashboardPage, { props: { sidebarOpen: true } })
      await wrapper.get('[aria-label="打开导航"]').trigger('click')
      await wrapper.get('.px-dashboard-page').trigger('keydown', { key: 'Escape' })
      expect(wrapper.emitted('update:sidebarOpen')).toEqual([[false], [false]])
    })

    it('刷新只发出事件且处理中禁用', async () => {
      const wrapper = mount(DashboardPage)
      await wrapper.get('.px-page-pattern__button').trigger('click')
      expect(wrapper.emitted('refresh')).toHaveLength(1)
      await wrapper.setProps({ refreshing: true })
      expect(wrapper.get('.px-page-pattern__button').attributes('disabled')).toBeDefined()
      expect(wrapper.get('.px-page-pattern__button').text()).toBe('刷新中')
    })
  })

  describe('ResourceManagementPage', () => {
    it('搜索提交当前受控关键词', async () => {
      const wrapper = mount(ResourceManagementPage, { props: { query: '  课程  ' } })
      await wrapper.get('form').trigger('submit')
      expect(wrapper.emitted('search')?.[0]).toEqual(['课程'])
      await wrapper.get('input').setValue('图书')
      expect(wrapper.emitted('update:query')?.[0]).toEqual(['图书'])
    })

    it('视图切换只请求更新状态', async () => {
      const wrapper = mount(ResourceManagementPage, { props: { view: 'table' } })
      expect(wrapper.get('[aria-label="表格视图"]').attributes('aria-pressed')).toBe('true')
      await wrapper.get('[aria-label="网格视图"]').trigger('click')
      expect(wrapper.emitted('update:view')?.[0]).toEqual(['grid'])
      expect(wrapper.classes()).not.toContain('is-grid')
    })

    it('选中状态、空状态和新建操作保持明确语义', async () => {
      const wrapper = mount(ResourceManagementPage, { props: { selectedCount: 2, empty: true } })
      expect(wrapper.get('.px-resource-page__selection').text()).toContain('已选 2 项')
      expect(wrapper.get('.px-page-pattern__state').text()).toBe('暂无资源')
      await wrapper.get('.px-page-pattern__text-button').trigger('click')
      await wrapper.get('.px-page-pattern__button--primary').trigger('click')
      expect(wrapper.emitted('clearSelection')).toHaveLength(1)
      expect(wrapper.emitted('create')).toHaveLength(1)
    })
  })

  describe('CheckoutPage', () => {
    it('订单内容与汇总通过插槽组合', () => {
      const wrapper = mount(CheckoutPage, {
        slots: { items: '商品清单', summary: '合计金额', payment: '支付选择' },
      })
      expect(wrapper.get('[aria-label="订单内容"]').text()).toBe('商品清单')
      expect(wrapper.get('[aria-label="订单汇总"]').text()).toContain('合计金额')
      expect(wrapper.get('[aria-label="支付方式"]').text()).toBe('支付选择')
    })

    it('提交和取消仅输出页面事件', async () => {
      const wrapper = mount(CheckoutPage)
      const buttons = wrapper.findAll('.px-checkout-page__actions button')
      await buttons[0].trigger('click')
      await buttons[1].trigger('click')
      expect(wrapper.emitted('cancel')).toHaveLength(1)
      expect(wrapper.emitted('submit')).toHaveLength(1)
    })

    it('处理中阻止重复提交并呈现状态', async () => {
      const wrapper = mount(CheckoutPage, { props: { status: 'processing' } })
      expect(wrapper.get('[role="status"]').text()).toBe('处理中')
      const submit = wrapper.findAll('.px-checkout-page__actions button')[1]
      expect(submit.attributes('disabled')).toBeDefined()
      await submit.trigger('click')
      expect(wrapper.emitted('submit')).toBeUndefined()
    })
  })

  describe('BookingPage', () => {
    it('下一步请求更新受控步骤', async () => {
      const wrapper = mount(BookingPage, { props: { step: 1, totalSteps: 3 } })
      await wrapper.findAll('.px-booking-page__actions button')[1].trigger('click')
      expect(wrapper.emitted('update:step')?.[0]).toEqual([2])
      expect(wrapper.emitted('next')?.[0]).toEqual([2])
      expect(wrapper.props('step')).toBe(1)
    })

    it('最后一步只发出提交事件', async () => {
      const wrapper = mount(BookingPage, { props: { step: 3, totalSteps: 3 } })
      await wrapper.findAll('.px-booking-page__actions button')[1].trigger('click')
      expect(wrapper.emitted('submit')).toHaveLength(1)
      expect(wrapper.emitted('update:step')).toBeUndefined()
    })

    it('起始步禁用返回且可取消预约', async () => {
      const wrapper = mount(BookingPage, { props: { step: 1 } })
      expect(wrapper.findAll('.px-booking-page__actions button')[0].attributes('disabled')).toBeDefined()
      await wrapper.get('.px-page-pattern__text-button').trigger('click')
      expect(wrapper.emitted('cancel')).toHaveLength(1)
    })
  })

  describe('LearningPage', () => {
    it('学习进度限制在有效范围', async () => {
      const wrapper = mount(LearningPage, { props: { progress: 120 } })
      expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('100')
      await wrapper.setProps({ progress: -10 })
      expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('0')
    })

    it('前后切换仅更新当前课时契约', async () => {
      const wrapper = mount(LearningPage, { props: { currentLesson: 2, totalLessons: 4 } })
      const buttons = wrapper.findAll('.px-learning-page__actions button')
      await buttons[0].trigger('click')
      await buttons[1].trigger('click')
      expect(wrapper.emitted('previous')?.[0]).toEqual([1])
      expect(wrapper.emitted('continue')?.[0]).toEqual([3])
      expect(wrapper.emitted('update:currentLesson')).toEqual([[1], [3]])
    })

    it('最后一节发出完成事件但不自行持久化', async () => {
      const wrapper = mount(LearningPage, { props: { currentLesson: 2, totalLessons: 2 } })
      await wrapper.findAll('.px-learning-page__actions button')[1].trigger('click')
      expect(wrapper.emitted('complete')).toHaveLength(1)
      expect(wrapper.emitted('update:currentLesson')).toBeUndefined()
    })
  })

  describe('LiveRoomPage', () => {
    it('面板切换受控并保留参与者数量', async () => {
      const wrapper = mount(LiveRoomPage, {
        props: { panel: 'chat', participantCount: 12 },
        slots: { chat: '互动内容', participants: '参与者列表' },
      })
      expect(wrapper.get('[role="tabpanel"]').text()).toBe('互动内容')
      expect(wrapper.findAll('[role="tab"]')[1].text()).toContain('12')
      await wrapper.findAll('[role="tab"]')[1].trigger('click')
      expect(wrapper.emitted('update:panel')?.[0]).toEqual(['participants'])
      expect(wrapper.get('[role="tabpanel"]').text()).toBe('互动内容')
    })

    it('麦克风和举手按钮只发出状态事件', async () => {
      const wrapper = mount(LiveRoomPage, { props: { status: 'live', muted: false, handRaised: false } })
      const controls = wrapper.findAll('.px-live-room-page__controls button')
      await controls[0].trigger('click')
      await controls[1].trigger('click')
      expect(wrapper.emitted('update:muted')?.[0]).toEqual([true])
      expect(wrapper.emitted('update:handRaised')?.[0]).toEqual([true])
    })

    it('离线状态提供重连请求与离开事件', async () => {
      const wrapper = mount(LiveRoomPage, { props: { status: 'offline' } })
      expect(wrapper.get('.px-live-room-page__status').text()).toBe('未连接')
      const controls = wrapper.findAll('.px-live-room-page__controls button')
      await controls[2].trigger('click')
      await controls[3].trigger('click')
      expect(wrapper.emitted('reconnect')).toHaveLength(1)
      expect(wrapper.emitted('leave')).toHaveLength(1)
    })
  })
})
