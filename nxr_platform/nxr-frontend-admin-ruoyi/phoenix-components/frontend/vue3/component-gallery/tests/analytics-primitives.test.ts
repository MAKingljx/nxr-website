import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import {
  PhoenixBarChart,
  PhoenixChartLegend,
  PhoenixDashboardFilter,
  PhoenixDataSummary,
  PhoenixDonutChart,
  PhoenixFunnelChart,
  PhoenixLineChart,
  PhoenixMetricCard,
  PhoenixRankingList,
  PhoenixTrendChart,
} from '../src/primitives/analytics'

describe('Phoenix 数据可视化组件', () => {
  it('指标卡格式化中文数值并生成完整 ARIA 文本', () => {
    const wrapper = mount(PhoenixMetricCard, {
      props: { label: '销售额', value: 12880.5, unit: '元', trend: 12.4, trendLabel: '同比' },
    })
    expect(wrapper.text()).toContain('12,880.5')
    expect(wrapper.attributes('aria-label')).toContain('销售额：12,880.5元')
    expect(wrapper.attributes('aria-label')).toContain('同比上升 12.4%')
  })

  it('指标卡把异常数值和负百分比收敛为零', async () => {
    const wrapper = mount(PhoenixMetricCard, { props: { value: Number.NaN } })
    expect(wrapper.text()).toContain('0')
    await wrapper.setProps({ value: -20, kind: 'percent' })
    expect(wrapper.text()).toContain('0%')
    await wrapper.setProps({ value: Number.POSITIVE_INFINITY, kind: 'number' })
    expect(wrapper.text()).toContain('0')
  })

  it('指标卡的选择行为完全受 props 控制', async () => {
    const wrapper = mount(PhoenixMetricCard, { props: { value: 88, interactive: true, selected: true } })
    const button = wrapper.get('button')
    expect(button.attributes('aria-pressed')).toBe('true')
    await button.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([88])
    expect(button.attributes('aria-pressed')).toBe('true')
  })

  it('禁用指标卡不发出选择事件并支持三种外观', async () => {
    const wrapper = mount(PhoenixMetricCard, { props: { interactive: true, disabled: true, appearance: 'soft' } })
    expect(wrapper.classes()).toContain('is-soft')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
    await wrapper.setProps({ appearance: 'minimal' })
    expect(wrapper.classes()).toContain('is-minimal')
  })

  it('趋势图提供中文空状态', () => {
    const wrapper = mount(PhoenixTrendChart, { props: { data: [] } })
    expect(wrapper.get('[role="status"]').text()).toBe('暂无趋势数据')
    expect(wrapper.find('svg').exists()).toBe(false)
  })

  it('趋势图保留业务上合理的负值且 SVG 可访问', () => {
    const wrapper = mount(PhoenixTrendChart, {
      props: { title: '利润走势', data: [{ key: 'july', label: '七月', value: -12 }, { key: 'aug', label: '八月', value: 20 }] },
    })
    expect(wrapper.get('svg').attributes('role')).toBe('group')
    expect(wrapper.get('svg title').text()).toBe('利润走势')
    expect(wrapper.get('svg desc').text()).toContain('七月 -12')
    expect(wrapper.get('path.px-trend-chart__line').attributes('d')).not.toMatch(/NaN|Infinity/)
  })

  it('趋势图点击和键盘选择均发出受控事件', async () => {
    const data = [{ key: 'q1', label: '第一季度', value: 10 }, { key: 'q2', label: '第二季度', value: 18 }]
    const wrapper = mount(PhoenixTrendChart, { props: { data, activeKey: 'q1' } })
    const points = wrapper.findAll('[role="button"]')
    expect(points[0].attributes('aria-pressed')).toBe('true')
    await points[1].trigger('click')
    await points[0].trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('update:activeKey')).toEqual([['q2'], ['q1']])
    expect(wrapper.emitted('select')?.[0]).toEqual([data[1], 1])
  })

  it('趋势图拒绝不安全颜色字符串', () => {
    const wrapper = mount(PhoenixTrendChart, { props: { data: [{ key: 'x', label: '指标', value: 1, color: 'url(javascript:alert(1))' }] } })
    expect(wrapper.get('.px-trend-chart__point circle:last-child').attributes('fill')).toBe('#5b5ce2')
    expect(wrapper.html()).not.toContain('javascript:')
  })

  it('柱状图把负值和非有限值收敛为零', () => {
    const wrapper = mount(PhoenixBarChart, {
      props: { data: [{ key: 'a', label: '退货', value: -4 }, { key: 'b', label: '异常', value: Number.NaN }] },
    })
    const bars = wrapper.findAll('.px-bar-chart__bar')
    expect(bars.every((bar) => (bar.attributes('style') ?? '').includes('width: 0%'))).toBe(true)
    expect(wrapper.text()).not.toMatch(/NaN|Infinity/)
  })

  it('柱状图点击数据项发出选中键和原始对象', async () => {
    const data = [{ key: 'north', label: '北区', value: 30 }, { key: 'south', label: '南区', value: 20 }]
    const wrapper = mount(PhoenixBarChart, { props: { data, activeKey: 'south' } })
    const buttons = wrapper.findAll('button')
    expect(buttons[1].attributes('aria-pressed')).toBe('true')
    await buttons[0].trigger('click')
    expect(wrapper.emitted('update:activeKey')?.[0]).toEqual(['north'])
    expect(wrapper.emitted('select')?.[0]).toEqual([data[0], 0])
  })

  it('柱状图数据列表具备列表与逐项 ARIA 标签', () => {
    const wrapper = mount(PhoenixBarChart, { props: { data: [{ key: 'a', label: '新增用户', value: 42 }] } })
    expect(wrapper.get('[role="list"]').attributes('aria-label')).toBe('分类对比数据')
    expect(wrapper.get('[role="listitem"] button').attributes('aria-label')).toBe('新增用户：42')
  })

  it('折线图在全部系列隐藏时展示空状态', () => {
    const wrapper = mount(PhoenixLineChart, {
      props: { series: [{ key: 'sales', label: '销售', points: [{ key: 'jan', label: '一月', value: 2 }] }], hiddenKeys: ['sales'] },
    })
    expect(wrapper.get('[role="status"]').text()).toBe('暂无折线图数据')
  })

  it('折线图清洗异常值并生成有限路径', () => {
    const wrapper = mount(PhoenixLineChart, {
      props: { series: [{ key: 'profit', label: '利润', points: [{ key: 'a', label: '甲', value: Number.NaN }, { key: 'b', label: '乙', value: Number.NEGATIVE_INFINITY }, { key: 'c', label: '丙', value: -8 }] }] },
    })
    expect(wrapper.get('.px-line-chart__series > path').attributes('d')).not.toMatch(/NaN|Infinity/)
    expect(wrapper.get('svg desc').text()).toContain('丙 -8')
  })

  it('折线图支持点击与键盘选择数据点', async () => {
    const point = { key: 'jan', label: '一月', value: 16 }
    const series = { key: 'orders', label: '订单', points: [point] }
    const wrapper = mount(PhoenixLineChart, { props: { series: [series] } })
    const target = wrapper.get('[role="button"]')
    await target.trigger('click')
    await target.trigger('keydown', { key: ' ' })
    expect(wrapper.emitted('update:activePointKey')).toEqual([['jan'], ['jan']])
    expect(wrapper.emitted('select')?.[0]).toEqual([point, series])
  })

  it('环形图计算份额并提供图像说明', () => {
    const wrapper = mount(PhoenixDonutChart, {
      props: { data: [{ key: 'direct', label: '直接访问', value: 25 }, { key: 'search', label: '搜索', value: 75 }] },
    })
    expect(wrapper.get('svg').attributes('role')).toBe('group')
    expect(wrapper.get('svg desc').text()).toContain('直接访问 25.0%')
    expect(wrapper.text()).toContain('75.0%')
  })

  it('环形图把负数与异常数值收敛后展示空状态', () => {
    const wrapper = mount(PhoenixDonutChart, {
      props: { data: [{ key: 'a', label: '甲', value: -10 }, { key: 'b', label: '乙', value: Number.POSITIVE_INFINITY }] },
    })
    expect(wrapper.get('[role="status"]').text()).toBe('暂无环形图数据')
    expect(wrapper.find('svg').exists()).toBe(false)
  })

  it('环形图点击扇区发出选中键和份额', async () => {
    const item = { key: 'mobile', label: '移动端', value: 40 }
    const wrapper = mount(PhoenixDonutChart, { props: { data: [item, { key: 'web', label: '网页端', value: 60 }] } })
    await wrapper.get('.px-donut-chart__segment').trigger('click')
    expect(wrapper.emitted('update:selectedKey')?.[0]).toEqual(['mobile'])
    expect(wrapper.emitted('select')?.[0]).toEqual([item, 40])
  })

  it('漏斗图提供中文空状态', () => {
    const wrapper = mount(PhoenixFunnelChart, { props: { stages: [] } })
    expect(wrapper.get('[role="status"]').text()).toBe('暂无漏斗数据')
  })

  it('漏斗图收敛负数并展示总体转化率', () => {
    const wrapper = mount(PhoenixFunnelChart, {
      props: { stages: [{ key: 'visit', label: '访问', value: 100 }, { key: 'order', label: '下单', value: 35 }, { key: 'refund', label: '异常', value: -8 }] },
    })
    expect(wrapper.text()).toContain('35.0%')
    expect(wrapper.findAll('button')[2].attributes('aria-label')).toContain('异常：0，总体转化率 0.0%')
  })

  it('漏斗阶段选择发出原始阶段和转化率', async () => {
    const stages = [{ key: 'lead', label: '线索', value: 80 }, { key: 'deal', label: '成交', value: 20 }]
    const wrapper = mount(PhoenixFunnelChart, { props: { stages, activeKey: 'deal' } })
    expect(wrapper.findAll('button')[1].attributes('aria-pressed')).toBe('true')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('update:activeKey')?.[0]).toEqual(['deal'])
    expect(wrapper.emitted('select')?.[0]).toEqual([stages[1], 25])
  })

  it('排行组件提供中文空状态', () => {
    const wrapper = mount(PhoenixRankingList, { props: { items: [] } })
    expect(wrapper.get('[role="status"]').text()).toBe('暂无排行数据')
  })

  it('排行组件稳定排序并把负值收敛为零', () => {
    const wrapper = mount(PhoenixRankingList, {
      props: { items: [{ key: 'a', label: '甲组', value: -4 }, { key: 'b', label: '乙组', value: 30 }, { key: 'c', label: '丙组', value: Number.NaN }] },
    })
    const buttons = wrapper.findAll('button')
    expect(buttons[0].text()).toContain('乙组')
    expect(buttons[1].text()).toContain('甲组')
    expect(buttons[1].attributes('aria-label')).toContain('甲组：0')
    expect(wrapper.text()).not.toMatch(/NaN|Infinity/)
  })

  it('排行点击发出选中键、原始对象和当前名次', async () => {
    const items = [{ key: 'a', label: '甲组', value: 10 }, { key: 'b', label: '乙组', value: 20 }]
    const wrapper = mount(PhoenixRankingList, { props: { items } })
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('update:selectedKey')?.[0]).toEqual(['a'])
    expect(wrapper.emitted('select')?.[0]).toEqual([items[0], 2])
  })

  it('数据摘要提供中文空状态并去除重复键', () => {
    const empty = mount(PhoenixDataSummary, { props: { items: [] } })
    expect(empty.get('[role="status"]').text()).toBe('暂无摘要数据')
    const wrapper = mount(PhoenixDataSummary, { props: { items: [{ key: 'a', label: '甲', value: 1 }, { key: 'a', label: '重复', value: 2 }] } })
    expect(wrapper.findAll('button')).toHaveLength(1)
  })

  it('数据摘要按业务配置收敛正数并以纯文本渲染', () => {
    const wrapper = mount(PhoenixDataSummary, {
      props: { items: [{ key: 'loss', label: '<img src=x onerror=alert(1)>', value: -5, positiveOnly: true }] },
    })
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.text()).toContain('0')
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('数据摘要选择事件保持受控状态', async () => {
    const item = { key: 'users', label: '用户', value: 100 }
    const wrapper = mount(PhoenixDataSummary, { props: { items: [item], selectedKey: '' } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('update:selectedKey')?.[0]).toEqual(['users'])
    expect(wrapper.emitted('select')?.[0]).toEqual([item])
    expect(wrapper.get('button').attributes('aria-pressed')).toBe('false')
  })

  it('仪表盘筛选器以受控方式更新单个筛选值', async () => {
    const filters = [{ key: 'region', label: '区域', options: [{ label: '北区', value: 'north' }] }]
    const wrapper = mount(PhoenixDashboardFilter, { props: { filters, modelValue: {} } })
    await wrapper.get('select').setValue('north')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([{ region: 'north' }])
    expect(wrapper.emitted('change')?.[0]).toEqual(['region', 'north', { region: 'north' }])
    expect(wrapper.props('modelValue')).toEqual({})
  })

  it('仪表盘筛选器清空与提交只发事件', async () => {
    const wrapper = mount(PhoenixDashboardFilter, {
      props: { filters: [], modelValue: { region: 'north' }, appearance: 'soft' },
    })
    expect(wrapper.classes()).toContain('is-soft')
    await wrapper.get('button.is-quiet').trigger('click')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([{}])
    expect(wrapper.emitted('clear')).toHaveLength(1)
    expect(wrapper.emitted('submit')?.[0]).toEqual([{ region: 'north' }])
  })

  it('仪表盘筛选器去重选项并提供表单 ARIA', () => {
    const wrapper = mount(PhoenixDashboardFilter, {
      props: { title: '经营看板筛选', filters: [{ key: 'state', label: '状态', options: [{ label: '启用', value: 'on' }, { label: '重复', value: 'on' }] }] },
    })
    expect(wrapper.get('form').attributes('aria-label')).toBe('经营看板筛选')
    expect(wrapper.findAll('option')).toHaveLength(2)
    expect(wrapper.get('select').attributes('aria-label')).toBe('状态')
  })

  it('图例提供中文空状态与分组语义', () => {
    const wrapper = mount(PhoenixChartLegend, { props: { items: [], title: '销售图例' } })
    expect(wrapper.attributes('role')).toBe('group')
    expect(wrapper.attributes('aria-label')).toBe('销售图例')
    expect(wrapper.get('[role="status"]').text()).toBe('暂无图例')
  })

  it('图例点击发出新的隐藏键与可见状态', async () => {
    const items = [{ key: 'sales', label: '销售额', color: '#123456' }, { key: 'profit', label: '利润' }]
    const wrapper = mount(PhoenixChartLegend, { props: { items, hiddenKeys: ['profit'] } })
    const buttons = wrapper.findAll('button')
    expect(buttons[1].attributes('aria-pressed')).toBe('false')
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')
    expect(wrapper.emitted('update:hiddenKeys')).toEqual([[['profit', 'sales']], [[]]])
    expect(wrapper.emitted('toggle')).toEqual([[items[0], false], [items[1], true]])
  })

  it('禁用图例项不发事件且异常数字不会泄露', async () => {
    const wrapper = mount(PhoenixChartLegend, {
      props: { items: [{ key: 'bad', label: '异常', value: Number.POSITIVE_INFINITY, disabled: true }] },
    })
    expect(wrapper.text()).toContain('0')
    expect(wrapper.text()).not.toContain('Infinity')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('toggle')).toBeUndefined()
  })

  it('所有图表外观 prop 映射为稳定类名', () => {
    const bar = mount(PhoenixBarChart, { props: { data: [], appearance: 'minimal' } })
    const line = mount(PhoenixLineChart, { props: { series: [], appearance: 'soft' } })
    const donut = mount(PhoenixDonutChart, { props: { data: [], appearance: 'modern' } })
    expect(bar.classes()).toContain('is-minimal')
    expect(line.classes()).toContain('is-soft')
    expect(donut.classes()).toContain('is-modern')
  })
})
