import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import {
  PhoenixBookingSummary,
  PhoenixCartSummary,
  PhoenixChatPanel,
  PhoenixCourseProgress,
  PhoenixOrderTimeline,
  PhoenixParticipantList,
  PhoenixPaymentStatus,
  PhoenixPriceDisplay,
  PhoenixQuantityStepper,
  PhoenixRecommendationList,
  PhoenixResourceCard,
  PhoenixStreamStatus,
} from '../src/primitives/business'

describe('Phoenix 通用业务组合组件', () => {
  it('资源卡展示通用信息并发出选择事件', async () => {
    const wrapper = mount(PhoenixResourceCard, { props: { title: '城市图书馆', category: '公共服务', location: '和平区', tags: ['亲子', '自习'] } })
    expect(wrapper.text()).toContain('城市图书馆')
    expect(wrapper.text()).toContain('亲子')
    await wrapper.trigger('click')
    await wrapper.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('select')).toHaveLength(2)
  })

  it('资源卡操作按钮不重复触发卡片选择', async () => {
    const wrapper = mount(PhoenixResourceCard, { props: { title: '景区门票' } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('资源卡禁用交互并拒绝脚本与 SVG data 图片', async () => {
    const wrapper = mount(PhoenixResourceCard, { props: { title: '安全资源', image: 'javascript:alert(1)', disabled: true } })
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
    await wrapper.setProps({ image: 'data:image/svg+xml;base64,PHN2Zz4=' })
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('价格组件格式化人民币和原价', () => {
    const wrapper = mount(PhoenixPriceDisplay, { props: { value: 89, originalPrice: 109, suffix: '/人' } })
    expect(wrapper.text()).toContain('¥89.00')
    expect(wrapper.get('del').text()).toContain('109.00')
    expect(wrapper.text()).toContain('/人')
  })

  it('价格组件只在原价更高时展示划线价', () => {
    const wrapper = mount(PhoenixPriceDisplay, { props: { value: 100, originalPrice: 80 } })
    expect(wrapper.find('del').exists()).toBe(false)
    expect(wrapper.attributes('aria-label')).toContain('价格')
  })

  it('价格组件收敛无效值与货币配置', async () => {
    const wrapper = mount(PhoenixPriceDisplay, { props: { value: Number.NaN } })
    expect(wrapper.text()).toBe('暂无价格')
    await wrapper.setProps({ value: 5, currency: 'not-a-currency' })
    expect(wrapper.text()).toContain('not-a-currency 5.00')
  })

  it('数量步进器按边界增加并发出受控值', async () => {
    const wrapper = mount(PhoenixQuantityStepper, { props: { modelValue: 2, min: 1, max: 3 } })
    await wrapper.get('[aria-label="增加数量"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([3])
    expect(wrapper.emitted('change')?.[0]).toEqual([3])
  })

  it('数量步进器限制输入并修正反向边界', async () => {
    const wrapper = mount(PhoenixQuantityStepper, { props: { modelValue: 3, min: 5, max: 2 } })
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('5')
    await wrapper.get('input').setValue('999')
    await wrapper.get('input').trigger('change')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([5])
  })

  it('数量步进器只读时不产生变化', async () => {
    const wrapper = mount(PhoenixQuantityStepper, { props: { modelValue: 2, readonly: true } })
    expect(wrapper.get('[aria-label="增加数量"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="减少数量"]').trigger('click')
    expect(wrapper.emitted('change')).toBeUndefined()
  })

  it('购物车计算小计、配送、优惠和非负合计', () => {
    const wrapper = mount(PhoenixCartSummary, { props: { items: [{ id: 1, title: '课程', quantity: 2, unitPrice: 50 }], shipping: 10, discount: 20 } })
    expect(wrapper.text()).toContain('¥100.00')
    expect(wrapper.text()).toContain('¥90.00')
    expect(wrapper.text()).toContain('2')
  })

  it('购物车操作只发出业务请求事件', async () => {
    const item = { id: 'book', title: '图书', quantity: 1, unitPrice: 39 }
    const wrapper = mount(PhoenixCartSummary, { props: { items: [item] } })
    await wrapper.get('[aria-label="增加 图书 数量"]').trigger('click')
    await wrapper.get('[aria-label="移除 图书"]').trigger('click')
    await wrapper.get('.px-business-primary').trigger('click')
    expect(wrapper.emitted('change-quantity')?.[0]).toEqual([item, 2])
    expect(wrapper.emitted('remove')?.[0]).toEqual([item])
    expect(wrapper.emitted('checkout')?.[0]).toEqual([{ subtotal: 39, total: 39 }])
  })

  it('购物车空状态禁用提交且异常数字不污染合计', () => {
    const empty = mount(PhoenixCartSummary, { props: { items: [] } })
    expect(empty.text()).toContain('购物车为空')
    expect(empty.get('.px-business-primary').attributes('disabled')).toBeDefined()
    const invalid = mount(PhoenixCartSummary, { props: { items: [{ id: 1, title: '异常项', quantity: -2, unitPrice: Number.NaN }], shipping: -1, discount: 3 } })
    expect(invalid.text()).toContain('¥0.00')
  })

  it('订单时间线展示当前和异常节点', () => {
    const wrapper = mount(PhoenixOrderTimeline, { props: { orderNumber: '订单 A100', items: [{ id: 1, title: '已下单', status: 'complete' }, { id: 2, title: '配送异常', status: 'error', time: '10:20' }] } })
    expect(wrapper.text()).toContain('订单 A100')
    expect(wrapper.findAll('li')[1].classes()).toContain('is-error')
    expect(wrapper.get('time').text()).toBe('10:20')
  })

  it('订单时间线提供中文空状态', () => {
    const wrapper = mount(PhoenixOrderTimeline, { props: { items: [] } })
    expect(wrapper.get('[role="status"]').text()).toBe('暂无订单记录')
  })

  it('支付状态展示状态与金额但不创建支付链接', () => {
    const wrapper = mount(PhoenixPaymentStatus, { props: { status: 'paid', amount: 299, reference: '流水号 P100' } })
    expect(wrapper.text()).toContain('已支付')
    expect(wrapper.text()).toContain('¥299.00')
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('支付状态按钮只发出当前状态事件', async () => {
    const wrapper = mount(PhoenixPaymentStatus, { props: { status: 'failed', actionLabel: '重新尝试' } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('action')?.[0]).toEqual(['failed'])
  })

  it('支付状态支持未知状态和无效币种回退', () => {
    const wrapper = mount(PhoenixPaymentStatus, { props: { status: 'unknown', amount: 12, currency: 'not-a-currency' } })
    expect(wrapper.text()).toContain('状态未知')
    expect(wrapper.text()).toContain('not-a-currency 12.00')
  })

  it('预约摘要展示日期地点和人数', () => {
    const wrapper = mount(PhoenixBookingSummary, { props: { title: '博物馆讲解', date: '2026-08-12', time: '10:00', location: '一号厅', participants: 3, status: 'confirmed' } })
    expect(wrapper.text()).toContain('已确认')
    expect(wrapper.text()).toContain('一号厅')
    expect(wrapper.text()).toContain('3 人')
  })

  it('预约摘要人数至少为一并按配置显示操作', async () => {
    const wrapper = mount(PhoenixBookingSummary, { props: { title: '预约', date: '今天', participants: -2, editable: true, cancellable: true } })
    expect(wrapper.text()).toContain('1 人')
    await wrapper.get('button').trigger('click')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('edit')).toHaveLength(1)
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('课程进度限制完成数并提供进度语义', () => {
    const wrapper = mount(PhoenixCourseProgress, { props: { title: 'Vue 入门', completed: 12, total: 10 } })
    expect(wrapper.text()).toContain('100%')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('100')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  })

  it('课程进度继续按钮只发出继续事件', async () => {
    const wrapper = mount(PhoenixCourseProgress, { props: { title: '课程', completed: 2, total: 10, currentLesson: '第三节' } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('continue')).toHaveLength(1)
    expect(wrapper.text()).toContain('当前：第三节')
  })

  it('课程进度零课时安全归零并禁用操作', () => {
    const wrapper = mount(PhoenixCourseProgress, { props: { title: '待开课', completed: Number.NaN, total: Number.NaN } })
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('0')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  })

  it('聊天面板纯文本展示消息而不执行 HTML', () => {
    const wrapper = mount(PhoenixChatPanel, { props: { messages: [{ id: 1, sender: '游客', content: '<img src=x onerror=alert(1)>' }] } })
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('[role="log"]').attributes('aria-live')).toBe('polite')
  })

  it('聊天输入受控更新且回车只请求发送', async () => {
    const wrapper = mount(PhoenixChatPanel, { props: { messages: [], modelValue: '你好' } })
    await wrapper.get('textarea').setValue('新消息')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['新消息'])
    await wrapper.setProps({ modelValue: '新消息' })
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')?.[0]).toEqual(['新消息'])
  })

  it('聊天发送中禁用输入并支持失败消息重试事件', async () => {
    const message = { id: 1, sender: '我', content: '消息', status: 'failed' as const }
    const wrapper = mount(PhoenixChatPanel, { props: { messages: [message], sending: true } })
    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined()
    await wrapper.get('.px-chat-panel__messages button').trigger('click')
    expect(wrapper.emitted('retry')?.[0]).toEqual([message])
  })

  it('参与者列表展示在线状态和截断人数', () => {
    const participants = [{ id: 1, name: '李明', status: 'online' as const }, { id: 2, name: '王芳' }]
    const wrapper = mount(PhoenixParticipantList, { props: { participants, maxVisible: 1 } })
    expect(wrapper.text()).toContain('李明')
    expect(wrapper.text()).toContain('另有 1 人')
    expect(wrapper.get('i').attributes('aria-label')).toBe('在线')
  })

  it('参与者列表受控选择并发出邀请事件', async () => {
    const participant = { id: 'u1', name: '主持人' }
    const wrapper = mount(PhoenixParticipantList, { props: { participants: [participant], showInvite: true } })
    await wrapper.get('li button').trigger('click')
    await wrapper.get('footer button').trigger('click')
    expect(wrapper.emitted('update:selectedId')?.[0]).toEqual(['u1'])
    expect(wrapper.emitted('select')?.[0]).toEqual([participant])
    expect(wrapper.emitted('invite')).toHaveLength(1)
  })

  it('参与者列表忽略禁用成员和脚本头像', async () => {
    const wrapper = mount(PhoenixParticipantList, { props: { participants: [{ id: 1, name: '禁用成员', avatar: 'javascript:alert(1)', disabled: true }] } })
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.get('li button').trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('直播状态展示人数且不创建流媒体节点', () => {
    const wrapper = mount(PhoenixStreamStatus, { props: { status: 'live', title: '公开课', viewers: 128, startedAt: '19:30' } })
    expect(wrapper.text()).toContain('直播中')
    expect(wrapper.text()).toContain('128 人观看')
    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.find('iframe').exists()).toBe(false)
  })

  it('直播状态操作只发出当前状态事件', async () => {
    const wrapper = mount(PhoenixStreamStatus, { props: { status: 'scheduled', scheduledAt: '明日 20:00', actionLabel: '进入直播间' } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('action')?.[0]).toEqual(['scheduled'])
    expect(wrapper.text()).toContain('明日 20:00')
  })

  it('直播状态收敛异常观看人数', () => {
    const wrapper = mount(PhoenixStreamStatus, { props: { status: 'live', viewers: -10 } })
    expect(wrapper.text()).toContain('0 人观看')
  })

  it('推荐列表限制条目并展示安全评分', () => {
    const items = [{ id: 1, title: '路线一', score: 8 }, { id: 2, title: '路线二' }]
    const wrapper = mount(PhoenixRecommendationList, { props: { items, maxItems: 1 } })
    expect(wrapper.findAll('li')).toHaveLength(1)
    expect(wrapper.text()).toContain('★ 5.0')
  })

  it('推荐列表选择与刷新只发出事件', async () => {
    const item = { id: 'route', title: '城市漫步' }
    const wrapper = mount(PhoenixRecommendationList, { props: { items: [item], showRefresh: true } })
    await wrapper.get('li button').trigger('click')
    await wrapper.get('header button').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([item, 0])
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('推荐列表空状态和图片协议安全', async () => {
    const wrapper = mount(PhoenixRecommendationList, { props: { items: [] } })
    expect(wrapper.get('[role="status"]').text()).toBe('暂无推荐内容')
    await wrapper.setProps({ items: [{ id: 1, title: '安全内容', image: 'data:image/svg+xml;base64,PHN2Zz4=' }] })
    expect(wrapper.find('img').exists()).toBe(false)
  })
})
