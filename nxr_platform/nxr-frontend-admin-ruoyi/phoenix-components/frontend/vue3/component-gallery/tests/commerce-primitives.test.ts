import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import {
  PhoenixAddressForm,
  PhoenixAddressSelector,
  PhoenixCouponSelector,
  PhoenixFavoriteButton,
  PhoenixInventoryTable,
  PhoenixLogisticsTracker,
  PhoenixPaymentMethodSelector,
  PhoenixRating,
  PhoenixRefundPanel,
  PhoenixReviewComposer,
  PhoenixSeatRoomSelector,
  PhoenixSkuEditor,
  PhoenixTimeSlotPicker,
} from '../src/primitives/commerce'

describe('Phoenix 商城与预约通用组件', () => {
  it('规格编辑器收敛异常价格库存并拒绝不安全图片', () => {
    const wrapper = mount(PhoenixSkuEditor, {
      props: { modelValue: [{ id: 1, name: '红色', code: 'R', price: Number.NaN, stock: -8, image: 'javascript:alert(1)' }] },
    })
    const numberInputs = wrapper.findAll('input[type="number"]')
    expect((numberInputs[0].element as HTMLInputElement).value).toBe('0')
    expect((numberInputs[1].element as HTMLInputElement).value).toBe('0')
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('规格编辑器以受控事件更新、添加和删除规格', async () => {
    const item = { id: 'sku', name: '标准版', code: 'STD', price: 20, stock: 2 }
    const wrapper = mount(PhoenixSkuEditor, { props: { modelValue: [item] } })
    await wrapper.findAll('input[type="number"]')[0].setValue('39')
    await wrapper.findAll('input[type="number"]')[0].trigger('change')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[expect.objectContaining({ price: 39 })]])
    await wrapper.get('[aria-label="删除 标准版"]').trigger('click')
    await wrapper.get('header button').trigger('click')
    expect(wrapper.emitted('remove')?.[0]).toEqual([item])
    expect(wrapper.emitted('add')).toHaveLength(1)
  })

  it('库存表将异常库存收敛为非负整数并标记库存不足', () => {
    const wrapper = mount(PhoenixInventoryTable, { props: { items: [{ id: 1, name: '门票', stock: -2, reserved: Number.NaN }] } })
    expect(wrapper.text()).toContain('库存不足')
    expect(wrapper.findAll('td')[0].text()).toBe('0')
    expect(wrapper.findAll('td')[1].text()).toBe('0')
  })

  it('库存表只通过事件请求调整且禁用项不可操作', async () => {
    const items = [{ id: 1, name: '可售商品', stock: 2 }, { id: 2, name: '停用商品', stock: 8, disabled: true }]
    const wrapper = mount(PhoenixInventoryTable, { props: { items } })
    await wrapper.get('[aria-label="增加 可售商品 库存"]').trigger('click')
    await wrapper.get('[aria-label="增加 停用商品 库存"]').trigger('click')
    expect(wrapper.emitted('adjust')).toHaveLength(1)
    expect(wrapper.emitted('adjust')?.[0]?.[1]).toBe(3)
  })

  it('地址选择器受控选择并发出编辑请求', async () => {
    const address = { id: 'a1', recipient: '李明', phone: '13800000000', region: '天津市', address: '和平路 1 号' }
    const wrapper = mount(PhoenixAddressSelector, { props: { addresses: [address] } })
    await wrapper.get('input[type="radio"]').trigger('change')
    await wrapper.get('label button').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['a1'])
    expect(wrapper.emitted('edit')?.[0]).toEqual([address])
  })

  it('地址选择器阻止禁用地址且不做本地持久化', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const wrapper = mount(PhoenixAddressSelector, { props: { addresses: [{ id: 1, recipient: '停用', phone: '10086', region: '天津', address: '地址', disabled: true }] } })
    await wrapper.get('input').trigger('change')
    expect(wrapper.emitted('change')).toBeUndefined()
    expect(setItem).not.toHaveBeenCalled()
    setItem.mockRestore()
  })

  it('地址表单对有效信息只发出提交事件', async () => {
    const value = { recipient: '王芳', phone: '13800000000', province: '天津市', city: '天津市', district: '和平区', address: '南京路 1 号' }
    const wrapper = mount(PhoenixAddressForm, { props: { modelValue: value } })
    await wrapper.trigger('submit')
    expect(wrapper.emitted('submit')?.[0]).toEqual([expect.objectContaining(value)])
  })

  it('地址表单校验电话并保持输入受控', async () => {
    const value = { recipient: '', phone: 'x', province: '', city: '', district: '', address: '' }
    const wrapper = mount(PhoenixAddressForm, { props: { modelValue: value } })
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.findAll('input')[0].setValue('新收货人')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([expect.objectContaining({ recipient: '新收货人' })])
    await wrapper.trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('优惠券按订单金额选择和清空', async () => {
    const coupon = { id: 'c1', title: '新人券', discount: 20, minSpend: 50 }
    const wrapper = mount(PhoenixCouponSelector, { props: { coupons: [coupon], orderAmount: 100, modelValue: null } })
    await wrapper.findAll('input')[1].trigger('change')
    expect(wrapper.emitted('change')?.[0]).toEqual([coupon])
    await wrapper.findAll('input')[0].trigger('change')
    expect(wrapper.emitted('update:modelValue')?.[1]).toEqual([null])
  })

  it('优惠券收敛异常金额并禁用未达门槛项目', async () => {
    const wrapper = mount(PhoenixCouponSelector, { props: { coupons: [{ id: 1, title: '满减券', discount: -10, minSpend: 200 }], orderAmount: Number.NaN } })
    expect(wrapper.text()).toContain('¥0.00')
    expect(wrapper.findAll('input')[1].attributes('disabled')).toBeDefined()
    await wrapper.findAll('input')[1].trigger('change')
    expect(wrapper.emitted('change')).toBeUndefined()
  })

  it('支付方式选择与确认只发出事件', async () => {
    const method = { code: 'mock-pay', label: '测试支付方式' }
    const wrapper = mount(PhoenixPaymentMethodSelector, { props: { methods: [method] } })
    await wrapper.get('input').trigger('change')
    expect(wrapper.emitted('change')?.[0]).toEqual([method])
    await wrapper.setProps({ modelValue: 'mock-pay' })
    await wrapper.get('.px-commerce-primary').trigger('click')
    expect(wrapper.emitted('confirm')?.[0]).toEqual([method])
  })

  it('支付方式组件拒绝不安全图标且无网络存储日志副作用', async () => {
    const fetchCall = vi.spyOn(globalThis, 'fetch')
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const log = vi.spyOn(console, 'log')
    const wrapper = mount(PhoenixPaymentMethodSelector, { props: { methods: [{ code: 'safe', label: '线下确认', icon: 'data:image/svg+xml;base64,PHN2Zz4=' }], modelValue: 'safe' } })
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.get('.px-commerce-primary').trigger('click')
    expect(fetchCall).not.toHaveBeenCalled(); expect(setItem).not.toHaveBeenCalled(); expect(log).not.toHaveBeenCalled()
    fetchCall.mockRestore(); setItem.mockRestore(); log.mockRestore()
  })

  it('退款面板收敛退款金额并提交请求数据', async () => {
    const wrapper = mount(PhoenixRefundPanel, { props: { amount: 500, maxAmount: 100, reasonCode: 'quality', note: '说明' } })
    expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('100')
    await wrapper.trigger('submit')
    expect(wrapper.emitted('submit')?.[0]).toEqual([{ amount: 100, reasonCode: 'quality', note: '说明' }])
  })

  it('退款面板拒绝无效请求且不发起网络操作', async () => {
    const fetchCall = vi.spyOn(globalThis, 'fetch')
    const wrapper = mount(PhoenixRefundPanel, { props: { amount: -8, maxAmount: 100, reasonCode: '' } })
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(fetchCall).not.toHaveBeenCalled()
    fetchCall.mockRestore()
  })

  it('物流跟踪器展示当前与异常节点', () => {
    const wrapper = mount(PhoenixLogisticsTracker, { props: { events: [{ id: 1, title: '已揽收', status: 'current' }, { id: 2, title: '配送异常', status: 'exception' }] } })
    expect(wrapper.findAll('li')[0].classes()).toContain('is-current')
    expect(wrapper.findAll('li')[1].classes()).toContain('is-exception')
  })

  it('物流跟踪器复制和刷新均只发出请求事件', async () => {
    const wrapper = mount(PhoenixLogisticsTracker, { props: { events: [], trackingNumber: 'SF100' } })
    await wrapper.get('[aria-label="请求复制运单号"]').trigger('click')
    await wrapper.get('header button').trigger('click')
    expect(wrapper.emitted('copy')?.[0]).toEqual(['SF100'])
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('预约时段选择器发出受控选择', async () => {
    const slot = { id: 'morning', label: '上午场', start: '09:00', end: '10:00', remaining: 2 }
    const wrapper = mount(PhoenixTimeSlotPicker, { props: { slots: [slot] } })
    await wrapper.get('input').trigger('change')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['morning'])
    expect(wrapper.emitted('change')?.[0]).toEqual([slot])
  })

  it('预约时段收敛异常余量并禁用约满时段', async () => {
    const wrapper = mount(PhoenixTimeSlotPicker, { props: { slots: [{ id: 1, label: '晚间场', remaining: -3 }] } })
    expect(wrapper.text()).toContain('已约满')
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    await wrapper.get('input').trigger('change')
    expect(wrapper.emitted('change')).toBeUndefined()
  })

  it('房间选择会清空座位并发出房间变更', async () => {
    const rooms = [{ id: 'a', name: 'A 厅', seats: [] }, { id: 'b', name: 'B 厅', seats: [] }]
    const wrapper = mount(PhoenixSeatRoomSelector, { props: { rooms, roomId: 'a', modelValue: ['old'] } })
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.emitted('update:roomId')?.[0]).toEqual(['b'])
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[]])
  })

  it('座位选择遵守占用状态和最大选择数', async () => {
    const rooms = [{ id: 'a', name: 'A 厅', seats: [{ id: 1, label: 'A1', price: -1 }, { id: 2, label: 'A2', status: 'reserved' as const }] }]
    const wrapper = mount(PhoenixSeatRoomSelector, { props: { rooms, roomId: 'a', modelValue: [], maxSelected: 1 } })
    const seats = wrapper.findAll('.px-seat-room__seats button')
    expect(wrapper.text()).toContain('¥0.00')
    await seats[0].trigger('click')
    await seats[1].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[1]])
    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
  })

  it('评价编辑器收敛评分内容并过滤不安全图片', () => {
    const wrapper = mount(PhoenixReviewComposer, { props: { rating: 99, content: 'abcdef', maxLength: 3, images: ['javascript:alert(1)', 'https://example.com/a.png'] } })
    expect(wrapper.findAll('.px-review-composer__rating button.is-active')).toHaveLength(5)
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('abc')
    expect(wrapper.findAll('img')).toHaveLength(1)
  })

  it('评价编辑器通过受控事件评分并只提交安全草稿', async () => {
    const wrapper = mount(PhoenixReviewComposer, { props: { rating: 4, content: '很好', images: ['/safe.png'] } })
    await wrapper.findAll('.px-review-composer__rating button')[4].trigger('click')
    expect(wrapper.emitted('update:rating')?.[0]).toEqual([5])
    await wrapper.trigger('submit')
    expect(wrapper.emitted('submit')?.[0]).toEqual([{ rating: 4, content: '很好', images: ['/safe.png'] }])
  })

  it('评分组件收敛评分和评价数量', () => {
    const wrapper = mount(PhoenixRating, { props: { modelValue: 99, max: 5, count: -4 } })
    expect(wrapper.text()).toContain('5.0')
    expect(wrapper.text()).toContain('0 条评价')
    expect(wrapper.findAll('button.is-active')).toHaveLength(5)
  })

  it('评分组件支持点击和方向键受控变更', async () => {
    const wrapper = mount(PhoenixRating, { props: { modelValue: 2 } })
    await wrapper.findAll('button')[3].trigger('click')
    await wrapper.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([4])
    expect(wrapper.emitted('update:modelValue')?.[1]).toEqual([3])
  })

  it('收藏按钮切换受控状态并收敛计数', async () => {
    const wrapper = mount(PhoenixFavoriteButton, { props: { modelValue: false, count: -20 } })
    expect(wrapper.text()).toContain('0')
    await wrapper.trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true])
    expect(wrapper.emitted('toggle')?.[0]).toEqual([true])
  })

  it('收藏按钮加载或禁用时不产生事件', async () => {
    const wrapper = mount(PhoenixFavoriteButton, { props: { loading: true } })
    expect(wrapper.attributes('disabled')).toBeDefined()
    await wrapper.trigger('click')
    expect(wrapper.emitted('toggle')).toBeUndefined()
  })
})
