import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import {
  PhoenixBargainCampaign,
  PhoenixCommentThread,
  PhoenixLuckyDraw,
  PhoenixProductCard,
} from '../src/primitives/marketing'

describe('Phoenix 营销与社区组件', () => {
  it('抽奖组件直接展示奖项和服务端选定结果', () => {
    const wrapper = mount(PhoenixLuckyDraw, {
      props: { items: [{ id: 1, label: '优惠券' }, { id: 2, label: '积分' }], selectedId: 2 },
    })
    expect(wrapper.findAll('li')).toHaveLength(2)
    expect(wrapper.text()).toContain('已中奖：积分')
    expect(wrapper.findAll('li')[1].classes()).toContain('is-selected')
  })

  it('抽奖操作只发请求事件而不在客户端随机选奖', async () => {
    const wrapper = mount(PhoenixLuckyDraw, { props: { items: [{ id: 1, label: '奖品' }] } })
    await wrapper.get('.px-lucky-draw__action').trigger('click')
    expect(wrapper.emitted('start')).toHaveLength(1)
    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.text()).not.toContain('已中奖')
  })

  it('抽奖奖项点击受控且禁用奖项不响应', async () => {
    const item = { id: 'gift', label: '礼物' }
    const disabled = { id: 'none', label: '暂不可用', disabled: true }
    const wrapper = mount(PhoenixLuckyDraw, { props: { items: [item, disabled], layout: 'grid' } })
    await wrapper.findAll('li button')[0].trigger('click')
    await wrapper.findAll('li button')[1].trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([item, 0])
    expect(wrapper.emitted('select')).toHaveLength(1)
  })

  it('抽奖组件拒绝脚本和 SVG data 图片', () => {
    const wrapper = mount(PhoenixLuckyDraw, {
      props: { items: [{ id: 1, label: '脚本', image: 'javascript:alert(1)' }, { id: 2, label: 'SVG', image: 'data:image/svg+xml;base64,PHN2Zz4=' }] },
    })
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('抽奖支持现代节庆极简外观', async () => {
    const wrapper = mount(PhoenixLuckyDraw, { props: { items: [{ id: 1, label: '奖品' }], appearance: 'modern' } })
    expect(wrapper.attributes('data-appearance')).toBe('modern')
    await wrapper.setProps({ appearance: 'festive' })
    expect(wrapper.attributes('data-appearance')).toBe('festive')
    await wrapper.setProps({ appearance: 'minimal' })
    expect(wrapper.attributes('data-appearance')).toBe('minimal')
  })

  it('砍价组件计算进度、目标价和倒计时', () => {
    const wrapper = mount(PhoenixBargainCampaign, {
      props: { title: '旅行套装', originalPrice: 100, currentPrice: 60, targetPrice: 20, remainingSeconds: 3661 },
    })
    expect(wrapper.text()).toContain('已完成 50%')
    expect(wrapper.text()).toContain('01:01:01')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('50')
  })

  it('砍价组件收敛异常价格和人数', () => {
    const wrapper = mount(PhoenixBargainCampaign, {
      props: { title: '异常商品', originalPrice: 50, currentPrice: Number.NaN, targetPrice: 80, participants: [] },
    })
    expect(wrapper.text()).not.toContain('NaN')
    expect(wrapper.text()).toContain('0 人已助力')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('100')
  })

  it('砍价和邀请只发业务请求事件', async () => {
    const wrapper = mount(PhoenixBargainCampaign, { props: { title: '商品', originalPrice: 100, currentPrice: 80, targetPrice: 20 } })
    await wrapper.findAll('button')[0].trigger('click')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('bargain')).toHaveLength(1)
    expect(wrapper.emitted('share')).toHaveLength(1)
  })

  it('已结束砍价禁用砍价但保留分享', async () => {
    const wrapper = mount(PhoenixBargainCampaign, { props: { title: '商品', originalPrice: 100, currentPrice: 80, targetPrice: 20, status: 'expired' } })
    expect(wrapper.findAll('button')[0].attributes('disabled')).toBeDefined()
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('share')).toHaveLength(1)
  })

  it('砍价参与者头像拒绝不安全地址', () => {
    const wrapper = mount(PhoenixBargainCampaign, {
      props: { title: '商品', originalPrice: 100, currentPrice: 80, targetPrice: 20, participants: [{ id: 1, name: '小李', avatar: 'javascript:alert(1)' }] },
    })
    expect(wrapper.find('.px-bargain-campaign__people img').exists()).toBe(false)
    expect(wrapper.text()).toContain('1 人已助力')
  })

  it('社区评论纯文本渲染并展示回复', () => {
    const wrapper = mount(PhoenixCommentThread, {
      props: { comments: [{ id: 1, author: '用户', content: '<img src=x onerror=alert(1)>', replies: [{ id: 2, author: '店主', content: '感谢反馈' }] }] },
    })
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.text()).toContain('感谢反馈')
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('社区评论输入和提交保持受控', async () => {
    const wrapper = mount(PhoenixCommentThread, { props: { comments: [], modelValue: '' } })
    await wrapper.get('textarea').setValue('  新评论  ')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['  新评论  '])
    await wrapper.setProps({ modelValue: '  新评论  ' })
    await wrapper.get('.px-comment-thread__composer button').trigger('click')
    expect(wrapper.emitted('submit')?.[0]).toEqual(['新评论'])
  })

  it('空评论和加载状态提供中文反馈并禁用提交', () => {
    const wrapper = mount(PhoenixCommentThread, { props: { comments: [], modelValue: '内容', loading: true } })
    expect(wrapper.text()).toContain('还没有评论')
    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.px-comment-thread__composer button').attributes('disabled')).toBeDefined()
  })

  it('评论点赞回复删除举报只发出对应事件', async () => {
    const comment = { id: 1, author: '用户', content: '内容', likes: -3, canDelete: true, canReport: true }
    const wrapper = mount(PhoenixCommentThread, { props: { comments: [comment] } })
    const buttons = wrapper.findAll('.px-comment-thread__comment footer button')
    for (const button of buttons) await button.trigger('click')
    expect(wrapper.emitted('like')?.[0]).toEqual([comment, true])
    expect(wrapper.emitted('reply')?.[0]).toEqual([comment])
    expect(wrapper.emitted('delete')?.[0]).toEqual([comment])
    expect(wrapper.emitted('report')?.[0]).toEqual([comment])
    expect(wrapper.text()).toContain('点赞 0')
  })

  it('评论长度按受控上限截断', async () => {
    const wrapper = mount(PhoenixCommentThread, { props: { comments: [], modelValue: '123456', maxLength: 4 } })
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('1234')
    await wrapper.get('textarea').setValue('abcdef')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['abcd'])
  })

  it('商品卡片展示价格评分销量库存', () => {
    const wrapper = mount(PhoenixProductCard, { props: { title: '城市旅行箱', price: 299, originalPrice: 399, rating: 8, sales: 120, inventory: 20 } })
    expect(wrapper.text()).toContain('¥299.00')
    expect(wrapper.text()).toContain('¥399.00')
    expect(wrapper.text()).toContain('★ 5.0')
    expect(wrapper.text()).toContain('已售 120')
  })

  it('商品卡片支持竖向横向紧凑布局和三种外观', async () => {
    const wrapper = mount(PhoenixProductCard, { props: { title: '商品', price: 10, inventory: 1, layout: 'vertical', appearance: 'modern' } })
    expect(wrapper.classes()).toContain('px-product-card--vertical')
    await wrapper.setProps({ layout: 'horizontal', appearance: 'festive' })
    expect(wrapper.classes()).toContain('px-product-card--horizontal')
    expect(wrapper.attributes('data-appearance')).toBe('festive')
    await wrapper.setProps({ layout: 'compact', appearance: 'minimal' })
    expect(wrapper.classes()).toContain('px-product-card--compact')
  })

  it('商品选择收藏加购事件互不重复', async () => {
    const wrapper = mount(PhoenixProductCard, { props: { title: '商品', price: 10, inventory: 2 } })
    await wrapper.trigger('click')
    await wrapper.get('.px-product-card__media button').trigger('click')
    await wrapper.get('.px-product-card__body > button').trigger('click')
    expect(wrapper.emitted('select')).toHaveLength(1)
    expect(wrapper.emitted('update:favorited')?.[0]).toEqual([true])
    expect(wrapper.emitted('favorite')?.[0]).toEqual([true])
    expect(wrapper.emitted('add-cart')).toHaveLength(1)
  })

  it('商品售罄时禁用加购并收敛异常数字', async () => {
    const wrapper = mount(PhoenixProductCard, { props: { title: '商品', price: Number.NaN, rating: -2, sales: -4, inventory: -1 } })
    expect(wrapper.text()).not.toContain('NaN')
    expect(wrapper.text()).toContain('已售罄')
    expect(wrapper.get('.px-product-card__body > button').attributes('disabled')).toBeDefined()
    await wrapper.get('.px-product-card__body > button').trigger('click')
    expect(wrapper.emitted('add-cart')).toBeUndefined()
  })

  it('商品卡片拒绝脚本和 SVG data 图片', async () => {
    const wrapper = mount(PhoenixProductCard, { props: { title: '商品', price: 10, inventory: 1, image: 'javascript:alert(1)' } })
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.setProps({ image: 'data:image/svg+xml;base64,PHN2Zz4=' })
    expect(wrapper.find('img').exists()).toBe(false)
  })
})
