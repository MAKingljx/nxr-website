import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import {
  PhoenixAppShell,
  PhoenixCalendar,
  PhoenixCollapse,
  PhoenixDropdown,
  PhoenixKanban,
  PhoenixPageHeader,
  PhoenixPopover,
  PhoenixProgress,
  PhoenixResult,
  PhoenixSideMenu,
  PhoenixTimeline,
  PhoenixTopBar,
} from '../src/primitives/platform'

describe('Phoenix 平台布局与数据反馈组件', () => {
  it('应用骨架按区域展示插槽', () => {
    const wrapper = mount(PhoenixAppShell, {
      slots: { topbar: '顶部', sidebar: '导航', default: '正文' },
    })
    expect(wrapper.get('.px-app-shell__top').text()).toBe('顶部')
    expect(wrapper.get('aside').text()).toBe('导航')
    expect(wrapper.get('main').text()).toBe('正文')
    expect(wrapper.get('main').attributes('aria-label')).toBe('应用界面')
  })

  it('应用骨架遮罩和 Escape 只请求关闭侧栏', async () => {
    const wrapper = mount(PhoenixAppShell, { props: { sidebarOpen: true } })
    await wrapper.get('.px-app-shell__scrim').trigger('click')
    await wrapper.get('.px-app-shell').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('update:sidebarOpen')).toEqual([[false], [false]])
  })

  it('侧边菜单选择项目并返回详情', async () => {
    const items = [{ label: '首页', value: 'home' }, { label: '订单', value: 'orders', badge: 3 }]
    const wrapper = mount(PhoenixSideMenu, { props: { items, modelValue: 'home' } })
    expect(wrapper.findAll('[role="menuitem"]')[0].attributes('aria-current')).toBe('page')
    await wrapper.findAll('[role="menuitem"]')[1].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['orders'])
    expect(wrapper.emitted('change')?.[0]).toEqual(['orders', items[1]])
  })

  it('侧边菜单折叠时保留名称并忽略禁用项', async () => {
    const wrapper = mount(PhoenixSideMenu, { props: { collapsed: true, modelValue: null, items: [{ label: '不可用', value: 1, disabled: true }] } })
    const item = wrapper.get('[role="menuitem"]')
    expect(item.attributes('aria-label')).toBe('不可用')
    await item.trigger('click')
    expect(wrapper.emitted('change')).toBeUndefined()
  })

  it('侧边菜单方向键跳过禁用项', async () => {
    const wrapper = mount(PhoenixSideMenu, {
      attachTo: document.body,
      props: { modelValue: 'a', items: [{ label: '甲', value: 'a' }, { label: '禁用', value: 'x', disabled: true }, { label: '乙', value: 'b' }] },
    })
    await wrapper.findAll('[role="menuitem"]')[0].trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement?.textContent).toContain('乙')
    wrapper.unmount()
  })

  it('顶栏展示中文标题并发出菜单事件', async () => {
    const wrapper = mount(PhoenixTopBar)
    expect(wrapper.text()).toContain('工作台')
    await wrapper.get('[aria-label="打开导航"]').trigger('click')
    expect(wrapper.emitted('menu')).toHaveLength(1)
  })

  it('顶栏支持品牌和操作插槽', () => {
    const wrapper = mount(PhoenixTopBar, { slots: { brand: '品牌', actions: '<button>个人中心</button>', default: '搜索' } })
    expect(wrapper.text()).toContain('品牌')
    expect(wrapper.text()).toContain('搜索')
    expect(wrapper.get('.px-top-bar__actions').text()).toBe('个人中心')
  })

  it('页头展示标题描述并发出返回事件', async () => {
    const wrapper = mount(PhoenixPageHeader, { props: { title: '订单详情', description: '当前订单', showBack: true } })
    expect(wrapper.get('h1').text()).toBe('订单详情')
    expect(wrapper.get('p').text()).toBe('当前订单')
    await wrapper.get('[aria-label="返回"]').trigger('click')
    expect(wrapper.emitted('back')).toHaveLength(1)
  })

  it('页头支持面包屑和操作插槽', () => {
    const wrapper = mount(PhoenixPageHeader, { slots: { breadcrumb: '首页 / 订单', actions: '<button>编辑</button>' } })
    expect(wrapper.get('.px-page-header__breadcrumb').text()).toBe('首页 / 订单')
    expect(wrapper.get('.px-page-header__actions').text()).toBe('编辑')
  })

  it('下拉菜单受控打开并选择操作', async () => {
    const items = [{ label: '编辑', value: 'edit' }, { label: '删除', value: 'delete', danger: true }]
    const wrapper = mount(PhoenixDropdown, { props: { modelValue: false, items } })
    await wrapper.get('.px-dropdown__trigger').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true])
    await wrapper.setProps({ modelValue: true })
    await wrapper.findAll('[role="menuitem"]')[1].trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['delete', items[1]])
    expect(wrapper.emitted('update:modelValue')?.[1]).toEqual([false])
  })

  it('下拉菜单可用键盘打开、移动和关闭', async () => {
    const wrapper = mount(PhoenixDropdown, {
      attachTo: document.body,
      props: { modelValue: false, items: [{ label: '甲', value: 'a' }, { label: '乙', value: 'b' }] },
    })
    await wrapper.get('.px-dropdown__trigger').trigger('keydown', { key: 'ArrowDown' })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true])
    await wrapper.setProps({ modelValue: true })
    await wrapper.findAll('[role="menuitem"]')[0].trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement?.textContent).toBe('乙')
    await wrapper.findAll('[role="menuitem"]')[1].trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('下拉菜单禁用时不请求打开', async () => {
    const wrapper = mount(PhoenixDropdown, { props: { disabled: true, items: [] } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('气泡卡片展示标题内容并发出显隐事件', async () => {
    const wrapper = mount(PhoenixPopover, { props: { modelValue: false, title: '详情' }, slots: { default: '内容' } })
    await wrapper.get('.px-popover__trigger').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true])
    expect(wrapper.emitted('show')).toHaveLength(1)
    await wrapper.setProps({ modelValue: true })
    expect(wrapper.get('[role="dialog"]').text()).toContain('详情内容')
  })

  it('气泡卡片支持 Escape 关闭和方向样式', async () => {
    const wrapper = mount(PhoenixPopover, { props: { modelValue: true, placement: 'left' } })
    expect(wrapper.get('[role="dialog"]').classes()).toContain('px-popover__panel--left')
    await wrapper.get('.px-popover').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
    expect(wrapper.emitted('hide')).toHaveLength(1)
  })

  it('折叠面板切换受控值并提供区域语义', async () => {
    const items = [{ title: '基础信息', value: 'base', content: '内容' }]
    const wrapper = mount(PhoenixCollapse, { props: { items, modelValue: [] } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['base']])
    await wrapper.setProps({ modelValue: ['base'] })
    expect(wrapper.get('[role="region"]').text()).toBe('内容')
  })

  it('折叠面板手风琴模式只保留当前项', async () => {
    const items = [{ title: '甲', value: 'a' }, { title: '乙', value: 'b' }]
    const wrapper = mount(PhoenixCollapse, { props: { items, modelValue: ['a'], accordion: true } })
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['b']])
  })

  it('折叠面板忽略禁用项并支持内容插槽', async () => {
    const wrapper = mount(PhoenixCollapse, { props: { items: [{ title: '甲', value: 'a', disabled: true }], modelValue: ['a'] }, slots: { 'item-0': '<strong>自定义</strong>' } })
    expect(wrapper.get('[role="region"]').text()).toBe('自定义')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('change')).toBeUndefined()
  })

  it('时间线展示状态、时间和内容', () => {
    const wrapper = mount(PhoenixTimeline, { props: { items: [{ title: '已提交', time: '10:00', content: '申请已提交', status: 'success' }] } })
    expect(wrapper.get('li').classes()).toContain('px-timeline__item--success')
    expect(wrapper.get('time').text()).toBe('10:00')
    expect(wrapper.text()).toContain('申请已提交')
  })

  it('时间线支持倒序和中文空状态', () => {
    const reversed = mount(PhoenixTimeline, { props: { reverse: true, items: [{ title: '第一' }, { title: '第二' }] } })
    expect(reversed.findAll('strong')[0].text()).toBe('第二')
    expect(mount(PhoenixTimeline, { props: { items: [] } }).text()).toContain('暂无记录')
  })

  it('进度条限制数值范围并提供无障碍值', async () => {
    const wrapper = mount(PhoenixProgress, { props: { percentage: 125 } })
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('100')
    expect(wrapper.text()).toContain('100%')
    await wrapper.setProps({ percentage: -2 })
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('0')
  })

  it('进度条支持状态尺寸和文本插槽', () => {
    const wrapper = mount(PhoenixProgress, { props: { percentage: 40, status: 'warning', size: 'large' }, slots: { text: '进行中' } })
    expect(wrapper.classes()).toContain('px-progress--warning')
    expect(wrapper.classes()).toContain('px-progress--large')
    expect(wrapper.text()).toBe('进行中')
  })

  it('结果页提供默认中文成功状态', () => {
    const wrapper = mount(PhoenixResult)
    expect(wrapper.attributes('role')).toBe('status')
    expect(wrapper.get('h2').text()).toBe('操作成功')
    expect(wrapper.get('.px-result__icon').text()).toBe('✓')
  })

  it('结果页支持错误状态、补充内容和操作', () => {
    const wrapper = mount(PhoenixResult, { props: { status: 'error', title: '提交失败', description: '请重试' }, slots: { default: '错误编号', actions: '<button>重试</button>' } })
    expect(wrapper.classes()).toContain('px-result--error')
    expect(wrapper.text()).toContain('错误编号')
    expect(wrapper.get('.px-result__actions').text()).toBe('重试')
  })

  it('日历无需日期库生成固定六周网格', () => {
    const wrapper = mount(PhoenixCalendar, { props: { viewDate: '2026-08-01' } })
    expect(wrapper.text()).toContain('2026年8月')
    expect(wrapper.findAll('[role="gridcell"]')).toHaveLength(42)
    expect(wrapper.findAll('.px-calendar__week span')[0].text()).toBe('一')
  })

  it('日历选择日期并返回标准格式', async () => {
    const wrapper = mount(PhoenixCalendar, { props: { viewDate: '2026-08-01' } })
    await wrapper.get('[aria-label="2026-08-10"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['2026-08-10'])
    expect(wrapper.emitted('select')?.[0]).toEqual(['2026-08-10'])
  })

  it('日历切换月份仅请求更新视图', async () => {
    const wrapper = mount(PhoenixCalendar, { props: { viewDate: '2026-01-01' } })
    await wrapper.get('[aria-label="上个月"]').trigger('click')
    await wrapper.get('[aria-label="下个月"]').trigger('click')
    expect(wrapper.emitted('update:viewDate')).toEqual([['2025-12-01'], ['2026-02-01']])
  })

  it('日历遵守日期边界并可从周日开始', async () => {
    const wrapper = mount(PhoenixCalendar, { props: { viewDate: '2026-08-01', min: '2026-08-10', max: '2026-08-20', weekStartsOn: 0 } })
    expect(wrapper.findAll('.px-calendar__week span')[0].text()).toBe('日')
    const blocked = wrapper.get('[aria-label="2026-08-09"]')
    expect(blocked.attributes('disabled')).toBeDefined()
    await blocked.trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('看板展示列、数量和中文空状态', () => {
    const wrapper = mount(PhoenixKanban, { props: { columns: [{ id: 'todo', title: '待处理', cards: [{ id: 1, title: '任务一' }] }, { id: 'done', title: '已完成', cards: [] }] } })
    expect(wrapper.findAll('.px-kanban__column')).toHaveLength(2)
    expect(wrapper.text()).toContain('任务一')
    expect(wrapper.text()).toContain('暂无任务')
  })

  it('看板选择卡片但不修改输入数据', async () => {
    const columns = [{ id: 'todo', title: '待处理', cards: [{ id: 1, title: '任务一' }] }]
    const snapshot = JSON.stringify(columns)
    const wrapper = mount(PhoenixKanban, { props: { columns } })
    await wrapper.get('.px-kanban__card').trigger('click')
    expect(wrapper.emitted('update:selectedId')?.[0]).toEqual([1])
    expect(wrapper.emitted('select')?.[0]).toEqual([columns[0].cards[0], columns[0]])
    expect(JSON.stringify(columns)).toBe(snapshot)
  })

  it('看板方向键只发出跨列移动事件', async () => {
    const columns = [{ id: 'todo', title: '待处理', cards: [{ id: 1, title: '任务一' }] }, { id: 'done', title: '已完成', cards: [] }]
    const wrapper = mount(PhoenixKanban, { props: { columns } })
    await wrapper.get('.px-kanban__card').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('move')?.[0]).toEqual([columns[0].cards[0], 'todo', 'done', 0])
    expect(columns[0].cards).toHaveLength(1)
    expect(columns[1].cards).toHaveLength(0)
  })

  it('看板只读和列上限会阻止移动', async () => {
    const columns = [{ id: 'todo', title: '待处理', cards: [{ id: 1, title: '任务一' }] }, { id: 'done', title: '已完成', limit: 1, cards: [{ id: 2, title: '已有任务' }] }]
    const wrapper = mount(PhoenixKanban, { props: { columns } })
    await wrapper.findAll('.px-kanban__card')[0].trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('move')).toBeUndefined()
    await wrapper.setProps({ readonly: true })
    expect(wrapper.findAll('.px-kanban__card')[0].attributes('draggable')).toBe('false')
  })
})
