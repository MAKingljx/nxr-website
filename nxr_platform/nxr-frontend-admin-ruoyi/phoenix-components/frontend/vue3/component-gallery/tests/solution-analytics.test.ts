import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { PhoenixAnalyticsDashboardPage } from '../src/patterns/solutions/analytics'

const metrics = [
  { key: 'revenue', label: '销售额', value: 128800, unit: '元', trend: 12.4 },
  { key: 'orders', label: '订单量', value: 3260, unit: '单', trend: -2.1 },
]

const trend = [
  { key: 'july', label: '七月', value: 100 },
  { key: 'august', label: '八月', value: 125 },
]

const categories = [
  { key: 'north', label: '北区', value: 88, color: '#5b5ce2' },
  { key: 'south', label: '南区', value: 62, color: '#22a06b' },
]

const ranking = [
  { key: 'store-a', label: '旗舰店', value: 90 },
  { key: 'store-b', label: '社区店', value: 60 },
]

const filters = [
  { key: 'region', label: '区域', options: [{ label: '北区', value: 'north' }, { label: '南区', value: 'south' }] },
]

function mountDashboard(overrides: Record<string, unknown> = {}) {
  return mount(PhoenixAnalyticsDashboardPage, {
    props: { metrics, trend, categories, ranking, filters, ...overrides },
  })
}

describe('Phoenix 成品数据看板组合页', () => {
  it('用标题关联主区域并展示现代中文说明', () => {
    const wrapper = mountDashboard({ title: '区域经营总览', subtitle: '截至本月的关键经营表现' })
    const heading = wrapper.get('h1')
    expect(heading.text()).toBe('区域经营总览')
    expect(wrapper.get('main').attributes('aria-labelledby')).toBe(heading.attributes('id'))
    expect(wrapper.text()).toContain('截至本月的关键经营表现')
  })

  it('一次组合六种分析组件并透出页面操作区', () => {
    const wrapper = mountDashboard()
    expect(wrapper.find('.px-dashboard-filter').exists()).toBe(true)
    expect(wrapper.findAll('.px-metric-card')).toHaveLength(2)
    expect(wrapper.find('.px-trend-chart').exists()).toBe(true)
    expect(wrapper.find('.px-bar-chart').exists()).toBe(true)
    expect(wrapper.find('.px-ranking-list').exists()).toBe(true)
    expect(wrapper.find('.px-chart-legend').exists()).toBe(true)
  })

  it('没有筛选项时不占用筛选布局', () => {
    const wrapper = mountDashboard({ filters: [] })
    expect(wrapper.find('.px-dashboard-filter').exists()).toBe(false)
  })

  it('筛选变化仅发出完整的受控 filter 事件', async () => {
    const wrapper = mountDashboard({ values: {} })
    await wrapper.get('.px-dashboard-filter select').setValue('north')
    expect(wrapper.emitted('filter')?.[0]).toEqual([{ region: 'north' }])
    expect(wrapper.props('values')).toEqual({})
  })

  it('清空筛选转发空对象且不修改 values prop', async () => {
    const wrapper = mountDashboard({ values: { region: 'north' } })
    await wrapper.get('.px-dashboard-filter button.is-quiet').trigger('click')
    expect(wrapper.emitted('filter')?.[0]).toEqual([{}])
    expect(wrapper.props('values')).toEqual({ region: 'north' })
  })

  it('指标卡按 key 去重并保留输入顺序', () => {
    const wrapper = mountDashboard({ metrics: [...metrics, { key: 'revenue', label: '重复指标', value: 1 }] })
    const cards = wrapper.findAll('.px-metric-card')
    expect(cards).toHaveLength(2)
    expect(cards[0].text()).toContain('销售额')
    expect(wrapper.text()).not.toContain('重复指标')
  })

  it('指标选择统一为 source、key、item、position 事件', async () => {
    const wrapper = mountDashboard({ selection: { metric: 'orders' } })
    const buttons = wrapper.findAll('.px-metric-card button')
    expect(buttons[1].attributes('aria-pressed')).toBe('true')
    await buttons[0].trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([{
      source: 'metric',
      key: 'revenue',
      item: metrics[0],
      position: 1,
    }])
    expect(buttons[1].attributes('aria-pressed')).toBe('true')
  })

  it('禁用指标不会向页面冒泡选择事件', async () => {
    const wrapper = mountDashboard({ metrics: [{ key: 'locked', label: '受限指标', value: 10, disabled: true }] })
    await wrapper.get('.px-metric-card button').trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('趋势点选择被归一为页面 select 事件', async () => {
    const wrapper = mountDashboard()
    await wrapper.findAll('.px-trend-chart__point')[1].trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([{
      source: 'trend',
      key: 'august',
      item: trend[1],
      position: 2,
    }])
  })

  it('分类柱选择被归一为页面 select 事件', async () => {
    const wrapper = mountDashboard()
    await wrapper.findAll('.px-bar-chart__plot button')[1].trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([{
      source: 'category',
      key: 'south',
      item: categories[1],
      position: 2,
    }])
  })

  it('排行选择保留排序后的当前名次', async () => {
    const wrapper = mountDashboard({ ranking: [ranking[1], ranking[0]] })
    await wrapper.findAll('.px-ranking-list__items button')[1].trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([{
      source: 'ranking',
      key: 'store-b',
      item: ranking[1],
      position: 2,
    }])
  })

  it('图例由分类数据自动生成并控制可见柱条', () => {
    const wrapper = mountDashboard({ hiddenLegendKeys: ['south'] })
    const legendButtons = wrapper.findAll('.px-chart-legend button')
    expect(legendButtons).toHaveLength(2)
    expect(legendButtons[1].attributes('aria-pressed')).toBe('false')
    expect(wrapper.findAll('.px-bar-chart__plot button')).toHaveLength(1)
    expect(wrapper.text()).toContain('南区')
  })

  it('图例切换一次给出消费者可直接保存的 hiddenKeys', async () => {
    const wrapper = mountDashboard({ hiddenLegendKeys: ['south'] })
    await wrapper.findAll('.px-chart-legend button')[0].trigger('click')
    expect(wrapper.emitted('toggle')?.[0]).toEqual([{
      key: 'north',
      visible: false,
      hiddenKeys: ['south', 'north'],
      item: { key: 'north', label: '北区', color: '#5b5ce2', value: 88, disabled: undefined },
    }])
    expect(wrapper.findAll('.px-bar-chart__plot button')).toHaveLength(1)
  })

  it('selection 同时控制趋势、分类和排行选中态', () => {
    const wrapper = mountDashboard({ selection: { trend: 'july', category: 'north', ranking: 'store-a' } })
    expect(wrapper.findAll('.px-trend-chart__point')[0].attributes('aria-pressed')).toBe('true')
    expect(wrapper.findAll('.px-bar-chart__plot button')[0].attributes('aria-pressed')).toBe('true')
    expect(wrapper.findAll('.px-ranking-list__items button')[0].attributes('aria-pressed')).toBe('true')
  })

  it('appearance 一次传入即可同步全部子组件', () => {
    const wrapper = mountDashboard({ appearance: 'soft' })
    expect(wrapper.get('main').classes()).toContain('is-soft')
    for (const selector of ['.px-dashboard-filter', '.px-metric-card', '.px-trend-chart', '.px-ranking-list', '.px-chart-legend', '.px-bar-chart']) {
      expect(wrapper.get(selector).classes()).toContain('is-soft')
    }
  })

  it('空数据自动汇总为中文空状态而无需消费者分支', () => {
    const wrapper = mountDashboard({ metrics: [], trend: [], categories: [], ranking: [], filters: [] })
    expect(wrapper.text()).toContain('暂无核心指标')
    expect(wrapper.text()).toContain('暂无趋势数据')
    expect(wrapper.text()).toContain('暂无排行数据')
    expect(wrapper.text()).toContain('暂无图例')
    expect(wrapper.text()).toContain('暂无柱状图数据')
  })

  it('异常数值统一收敛且页面不出现 NaN 或 Infinity', () => {
    const wrapper = mountDashboard({
      metrics: [{ key: 'bad', label: '异常指标', value: Number.NaN }],
      trend: [{ key: 'bad', label: '异常趋势', value: Number.POSITIVE_INFINITY }],
      categories: [{ key: 'bad', label: '异常分类', value: Number.NEGATIVE_INFINITY }],
      ranking: [{ key: 'bad', label: '异常排行', value: Number.NaN }],
    })
    expect(wrapper.text()).not.toMatch(/NaN|Infinity/)
    expect(wrapper.html()).not.toMatch(/NaN|Infinity/)
  })

  it('外部字符串始终按纯文本渲染', () => {
    const wrapper = mountDashboard({ title: '<img src=x onerror=alert(1)>', metrics: [] })
    expect(wrapper.get('h1').text()).toBe('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
  })
})
