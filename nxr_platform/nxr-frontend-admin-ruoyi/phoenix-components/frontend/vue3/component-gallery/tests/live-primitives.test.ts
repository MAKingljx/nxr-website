import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import {
  PhoenixDanmakuLayer,
  PhoenixLiveConsole,
  PhoenixLiveMetrics,
  PhoenixLiveProductShelf,
  PhoenixMemberManager,
  PhoenixModerationQueue,
  PhoenixReplayList,
} from '../src/primitives/live'

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find((item) => item.text() === text)
  if (!button) throw new Error(`未找到按钮：${text}`)
  return button
}

describe('Phoenix 直播通用组件', () => {
  describe('PhoenixLiveConsole', () => {
    it('展示受控状态并收敛异常人数且不创建流媒体连接节点', () => {
      const wrapper = mount(PhoenixLiveConsole, { props: { status: 'live', viewers: -20, likes: Number.POSITIVE_INFINITY, durationLabel: '01:20:00' } })
      expect(wrapper.text()).toContain('直播中')
      expect(wrapper.text()).toContain('0 人')
      expect(wrapper.text()).not.toContain('Infinity')
      expect(wrapper.find('video').exists()).toBe(false)
      expect(wrapper.find('iframe').exists()).toBe(false)
    })

    it('直播操作和刷新只发出业务请求事件', async () => {
      const wrapper = mount(PhoenixLiveConsole, { props: { status: 'live', actions: ['pause', 'end'] } })
      await buttonByText(wrapper, '暂停直播').trigger('click')
      await buttonByText(wrapper, '结束直播').trigger('click')
      await buttonByText(wrapper, '刷新').trigger('click')
      expect(wrapper.emitted('request-action')).toEqual([['pause'], ['end']])
      expect(wrapper.emitted('refresh')).toHaveLength(1)
    })

    it('忙碌状态禁用所有操作', async () => {
      const wrapper = mount(PhoenixLiveConsole, { props: { busy: true, actions: ['start'] } })
      expect(wrapper.attributes('aria-busy')).toBe('true')
      await buttonByText(wrapper, '开始直播').trigger('click')
      expect(wrapper.emitted('request-action')).toBeUndefined()
    })
  })

  describe('PhoenixLiveProductShelf', () => {
    const product = { id: 'p1', title: '精选课程', price: 99, stock: 8, sales: 120 }

    it('展示安全商品图片与价格并拒绝脚本和 SVG data 图片', async () => {
      const wrapper = mount(PhoenixLiveProductShelf, { props: { products: [{ ...product, image: 'javascript:alert(1)' }] } })
      expect(wrapper.find('img').exists()).toBe(false)
      await wrapper.setProps({ products: [{ ...product, image: 'data:image/svg+xml;base64,PHN2Zz4=' }] })
      expect(wrapper.find('img').exists()).toBe(false)
      await wrapper.setProps({ products: [{ ...product, image: 'https://cdn.example.com/product.webp' }] })
      expect(wrapper.get('img').attributes('src')).toBe('https://cdn.example.com/product.webp')
      expect(wrapper.text()).toContain('¥99.00')
    })

    it('选择、加购和讲解操作保持受控并只发事件', async () => {
      const wrapper = mount(PhoenixLiveProductShelf, { props: { products: [product] } })
      await wrapper.get('.px-live-product-shelf__select').trigger('click')
      await buttonByText(wrapper, '加入购物车').trigger('click')
      await buttonByText(wrapper, '设为讲解').trigger('click')
      expect(wrapper.emitted('update:selectedId')?.[0]).toEqual(['p1'])
      expect(wrapper.emitted('select')?.[0]).toEqual([product])
      expect(wrapper.emitted('request-add')?.[0]).toEqual([product])
      expect(wrapper.emitted('request-feature')?.[0]).toEqual([product, true])
    })

    it('异常金额和库存归零且售罄商品不能请求加购', async () => {
      const invalid = { ...product, price: Number.NaN, stock: -2, sales: Number.POSITIVE_INFINITY }
      const wrapper = mount(PhoenixLiveProductShelf, { props: { products: [invalid] } })
      expect(wrapper.text()).not.toMatch(/NaN|Infinity/)
      expect(wrapper.text()).toContain('库存 0')
      expect(buttonByText(wrapper, '加入购物车').attributes('disabled')).toBeDefined()
      await buttonByText(wrapper, '加入购物车').trigger('click')
      expect(wrapper.emitted('request-add')).toBeUndefined()
    })
  })

  describe('PhoenixDanmakuLayer', () => {
    it('弹幕只按纯文本渲染并限制可见条数', () => {
      const messages = [
        { id: 1, sender: '用户甲', content: '较早消息' },
        { id: 2, sender: '用户乙', content: '<img src=x onerror=alert(1)>' },
      ]
      const wrapper = mount(PhoenixDanmakuLayer, { props: { messages, maxItems: 1 } })
      expect(wrapper.text()).not.toContain('较早消息')
      expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
      expect(wrapper.find('img').exists()).toBe(false)
      expect(wrapper.find('script').exists()).toBe(false)
      expect(wrapper.get('[role="log"]').attributes('aria-live')).toBe('polite')
    })

    it('选择和举报只发出对应业务请求', async () => {
      const message = { id: 'm1', sender: '用户甲', content: '需要复核' }
      const wrapper = mount(PhoenixDanmakuLayer, { props: { messages: [message], reportable: true } })
      await wrapper.get('.px-danmaku-layer__viewport article > button').trigger('click')
      await buttonByText(wrapper, '举报').trigger('click')
      expect(wrapper.emitted('select')?.[0]).toEqual([message])
      expect(wrapper.emitted('request-report')?.[0]).toEqual([message])
    })

    it('显示和暂停均由外部受控', async () => {
      const wrapper = mount(PhoenixDanmakuLayer, { props: { messages: [], visible: false, paused: true } })
      expect(wrapper.text()).toContain('弹幕已隐藏')
      await buttonByText(wrapper, '显示弹幕').trigger('click')
      await buttonByText(wrapper, '继续显示').trigger('click')
      expect(wrapper.emitted('update:visible')?.[0]).toEqual([true])
      expect(wrapper.emitted('update:paused')?.[0]).toEqual([false])
    })
  })

  describe('PhoenixModerationQueue', () => {
    const item = { id: 'q1', sender: '访客', content: '<script>alert(1)</script>', risk: 'high' as const }

    it('审核内容使用纯文本并提供风险语义', () => {
      const wrapper = mount(PhoenixModerationQueue, { props: { items: [item] } })
      expect(wrapper.text()).toContain('<script>alert(1)</script>')
      expect(wrapper.find('script').exists()).toBe(false)
      expect(wrapper.text()).toContain('高风险')
      expect(wrapper.get('li').classes()).toContain('is-high')
    })

    it('审核判定和禁言只发出业务请求事件', async () => {
      const wrapper = mount(PhoenixModerationQueue, { props: { items: [item] } })
      await buttonByText(wrapper, '通过').trigger('click')
      await buttonByText(wrapper, '拦截').trigger('click')
      await buttonByText(wrapper, '请求禁言').trigger('click')
      expect(wrapper.emitted('request-decision')).toEqual([[item, 'approve'], [item, 'reject']])
      expect(wrapper.emitted('request-mute')?.[0]).toEqual([item])
    })

    it('处理中项目禁止重复请求', async () => {
      const wrapper = mount(PhoenixModerationQueue, { props: { items: [item], processingId: 'q1' } })
      expect(wrapper.get('li').attributes('aria-busy')).toBe('true')
      await buttonByText(wrapper, '拦截').trigger('click')
      expect(wrapper.emitted('request-decision')).toBeUndefined()
    })
  })

  describe('PhoenixMemberManager', () => {
    const member = { id: 'u1', name: '直播助理', role: 'assistant', online: true, muted: false }

    it('选择、角色、禁言和移出均保持受控', async () => {
      const wrapper = mount(PhoenixMemberManager, { props: { members: [member], roles: [{ value: 'assistant', label: '助理' }, { value: 'host', label: '主播' }] } })
      await wrapper.get('.px-member-manager__identity').trigger('click')
      await wrapper.get('select').setValue('host')
      await buttonByText(wrapper, '请求禁言').trigger('click')
      await buttonByText(wrapper, '请求移出').trigger('click')
      expect(wrapper.emitted('update:selectedId')?.[0]).toEqual(['u1'])
      expect(wrapper.emitted('request-role-change')?.[0]).toEqual([member, 'host'])
      expect(wrapper.emitted('request-mute')?.[0]).toEqual([member, true])
      expect(wrapper.emitted('request-remove')?.[0]).toEqual([member])
    })

    it('拒绝危险头像并收敛异常总人数', async () => {
      const wrapper = mount(PhoenixMemberManager, { props: { members: [{ ...member, avatar: 'data:image/svg+xml;base64,PHN2Zz4=' }], total: Number.POSITIVE_INFINITY } })
      expect(wrapper.find('img').exists()).toBe(false)
      expect(wrapper.text()).toContain('1 人')
      await wrapper.setProps({ total: 9_999_999_999 })
      expect(wrapper.text()).toContain('999999999 人')
    })

    it('禁用成员不产生任何管理请求', async () => {
      const disabledMember = { ...member, disabled: true }
      const wrapper = mount(PhoenixMemberManager, { props: { members: [disabledMember] } })
      await buttonByText(wrapper, '请求禁言').trigger('click')
      expect(wrapper.emitted('request-mute')).toBeUndefined()
    })
  })

  describe('PhoenixReplayList', () => {
    it('仅接受白名单回放和图片地址且不创建执行型节点', async () => {
      const unsafe = { id: 1, title: '异常回放', url: 'javascript:alert(1)', thumbnail: 'data:image/svg+xml;base64,PHN2Zz4=' }
      const wrapper = mount(PhoenixReplayList, { props: { items: [unsafe] } })
      expect(wrapper.find('img').exists()).toBe(false)
      expect(wrapper.find('iframe').exists()).toBe(false)
      expect(wrapper.find('script').exists()).toBe(false)
      expect(wrapper.find('video').exists()).toBe(false)
      expect(buttonByText(wrapper, '播放回放').attributes('disabled')).toBeDefined()
      await buttonByText(wrapper, '播放回放').trigger('click')
      expect(wrapper.emitted('request-play')).toBeUndefined()
    })

    it('安全回放只通过事件交给业务层播放', async () => {
      const replay = { id: 'r1', title: '夏日专场', url: 'https://media.example.com/replay.mp4', thumbnail: '/images/replay.webp', views: 88 }
      const wrapper = mount(PhoenixReplayList, { props: { items: [replay] } })
      expect(wrapper.get('img').attributes('src')).toBe('/images/replay.webp')
      await buttonByText(wrapper, '播放回放').trigger('click')
      expect(wrapper.emitted('request-play')?.[0]).toEqual([{ replay, url: replay.url }])
      expect(wrapper.find('a').exists()).toBe(false)
    })

    it('处理中的回放不能发起播放且异常观看数归零', async () => {
      const replay = { id: 2, title: '生成中', url: 'https://media.example.com/replay.mp4', status: 'processing' as const, views: Number.NaN }
      const wrapper = mount(PhoenixReplayList, { props: { items: [replay] } })
      expect(wrapper.text()).toContain('0 次观看')
      expect(wrapper.text()).not.toContain('NaN')
      await buttonByText(wrapper, '播放回放').trigger('click')
      expect(wrapper.emitted('request-play')).toBeUndefined()
    })
  })

  describe('PhoenixLiveMetrics', () => {
    it('按类型格式化指标并收敛异常人数、金额与百分比', () => {
      const wrapper = mount(PhoenixLiveMetrics, { props: { metrics: [
        { key: 'viewers', label: '在线人数', value: Number.POSITIVE_INFINITY, kind: 'count' },
        { key: 'revenue', label: '成交金额', value: -99, kind: 'currency' },
        { key: 'conversion', label: '转化率', value: 180, kind: 'percent' },
      ] } })
      expect(wrapper.text()).not.toMatch(/Infinity|NaN/)
      expect(wrapper.text()).toContain('在线人数0')
      expect(wrapper.text()).toContain('成交金额¥0.00')
      expect(wrapper.text()).toContain('100.0%')
    })

    it('指标选择和刷新只发出事件', async () => {
      const metric = { key: 'orders', label: '订单数', value: 25, kind: 'count' as const }
      const wrapper = mount(PhoenixLiveMetrics, { props: { metrics: [metric], showRefresh: true } })
      await wrapper.get('.px-live-metrics__grid button').trigger('click')
      await buttonByText(wrapper, '刷新').trigger('click')
      expect(wrapper.emitted('select')?.[0]).toEqual([metric])
      expect(wrapper.emitted('refresh')).toHaveLength(1)
    })

    it('去除重复指标并收敛异常趋势', () => {
      const wrapper = mount(PhoenixLiveMetrics, { props: { metrics: [
        { key: 'orders', label: '订单数', value: 1, trend: 9_999 },
        { key: 'orders', label: '重复订单数', value: 2 },
      ] } })
      expect(wrapper.findAll('.px-live-metrics__grid > div')).toHaveLength(1)
      expect(wrapper.text()).toContain('999.0%')
      expect(wrapper.text()).not.toContain('重复订单数')
    })
  })
})
