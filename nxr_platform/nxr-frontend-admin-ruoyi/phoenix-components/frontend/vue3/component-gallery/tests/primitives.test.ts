import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import {
  PhoenixAlert,
  PhoenixBreadcrumb,
  PhoenixButton,
  PhoenixCard,
  PhoenixDivider,
  PhoenixPagination,
  PhoenixSearch,
  PhoenixSwitch,
  PhoenixTabs,
  PhoenixTag,
} from '../src/primitives'

describe('Phoenix 基础组件', () => {
  it('按钮支持样式、尺寸和块级显示', () => {
    const wrapper = mount(PhoenixButton, {
      props: { variant: 'danger', size: 'large', block: true },
      slots: { default: '确认删除' },
    })
    expect(wrapper.text()).toBe('确认删除')
    expect(wrapper.classes()).toEqual(expect.arrayContaining(['px-button--danger', 'px-button--large', 'px-button--block']))
  })

  it('按钮加载时禁用并展示中文提示', async () => {
    const wrapper = mount(PhoenixButton, {
      props: { loading: true },
      slots: { default: '保存' },
    })
    expect(wrapper.attributes('disabled')).toBeDefined()
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.text()).toBe('加载中')
    await wrapper.trigger('click')
    expect(wrapper.emitted('click')).toBeUndefined()
  })

  it('搜索框通过 v-model 更新并在回车时搜索', async () => {
    const wrapper = mount(PhoenixSearch, { props: { modelValue: '' } })
    await wrapper.get('input').setValue('通用组件')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['通用组件'])
    await wrapper.setProps({ modelValue: '通用组件' })
    await wrapper.get('input').trigger('keydown.enter')
    expect(wrapper.emitted('search')?.[0]).toEqual(['通用组件'])
    expect(wrapper.get('input').attributes('placeholder')).toBe('请输入关键词搜索')
  })

  it('搜索框可清空已有关键词', async () => {
    const wrapper = mount(PhoenixSearch, { props: { modelValue: '按钮' } })
    await wrapper.get('[aria-label="清除搜索内容"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([''])
    expect(wrapper.emitted('clear')).toHaveLength(1)
  })

  it('分割线支持带文字的水平模式和垂直模式', () => {
    const horizontal = mount(PhoenixDivider, { props: { text: '基础组件', contentPosition: 'left', dashed: true } })
    expect(horizontal.text()).toBe('基础组件')
    expect(horizontal.classes()).toEqual(expect.arrayContaining(['px-divider--horizontal', 'px-divider--left', 'is-dashed']))
    expect(horizontal.attributes('aria-orientation')).toBe('horizontal')
    const vertical = mount(PhoenixDivider, { props: { direction: 'vertical' } })
    expect(vertical.attributes('aria-orientation')).toBe('vertical')
  })

  it('标签页通过 v-model 切换并忽略禁用项', async () => {
    const wrapper = mount(PhoenixTabs, {
      props: {
        modelValue: 'button',
        items: [
          { label: '按钮', value: 'button' },
          { label: '搜索框', value: 'search', badge: 2 },
          { label: '建设中', value: 'disabled', disabled: true },
        ],
      },
    })
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['search'])
    expect(wrapper.emitted('change')?.[0]).toEqual(['search'])
    await wrapper.findAll('[role="tab"]')[2].trigger('click')
    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
  })

  it('标签页支持键盘导航并跳过禁用项', async () => {
    const wrapper = mount(PhoenixTabs, {
      attachTo: document.body,
      props: {
        modelValue: 'first',
        items: [
          { label: '第一项', value: 'first' },
          { label: '不可用', value: 'disabled', disabled: true },
          { label: '第三项', value: 'third' },
        ],
      },
    })
    await wrapper.findAll('[role="tab"]')[0].trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['third'])
    expect(document.activeElement?.textContent).toContain('第三项')
    wrapper.unmount()
  })

  it('面包屑标识当前页并暴露选择事件', async () => {
    const wrapper = mount(PhoenixBreadcrumb, {
      props: {
        items: [
          { label: '首页', href: '#home' },
          { label: '组件中心' },
        ],
      },
    })
    await wrapper.get('a').trigger('click')
    expect(wrapper.emitted('select')?.[0]?.[0]).toMatchObject({ label: '首页' })
    expect(wrapper.get('[aria-current="page"]').text()).toBe('组件中心')
    expect(wrapper.get('nav').attributes('aria-label')).toBe('面包屑导航')
  })

  it('分页展示中文总数并更新当前页', async () => {
    const wrapper = mount(PhoenixPagination, { props: { modelValue: 1, total: 96, pageSize: 10 } })
    expect(wrapper.text()).toContain('共 96 条')
    expect(wrapper.get('[aria-current="page"]').text()).toBe('1')
    await wrapper.get('[aria-label="第 2 页"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([2])
    expect(wrapper.emitted('change')?.[0]).toEqual([2])
  })

  it('分页在首尾正确禁用翻页按钮', async () => {
    const wrapper = mount(PhoenixPagination, { props: { modelValue: 1, total: 20, pageSize: 10 } })
    expect(wrapper.get('[aria-label="上一页"]').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ modelValue: 2 })
    expect(wrapper.get('[aria-label="下一页"]').attributes('disabled')).toBeDefined()
  })

  it('开关通过 v-model 切换并提供无障碍状态', async () => {
    const wrapper = mount(PhoenixSwitch, { props: { modelValue: false, activeText: '已启用', inactiveText: '已停用' } })
    expect(wrapper.text()).toContain('已停用')
    expect(wrapper.get('[role="switch"]').attributes('aria-checked')).toBe('false')
    await wrapper.get('[role="switch"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true])
    expect(wrapper.emitted('change')?.[0]).toEqual([true])
  })

  it('标签提供中文默认内容和关闭事件', async () => {
    const wrapper = mount(PhoenixTag, { props: { variant: 'success', closable: true } })
    expect(wrapper.text()).toContain('标签')
    expect(wrapper.classes()).toContain('px-tag--success')
    await wrapper.get('[aria-label="关闭标签"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('提示条按类型展示中文默认标题', () => {
    const success = mount(PhoenixAlert, { props: { variant: 'success', description: '数据已经保存' } })
    expect(success.text()).toContain('操作成功')
    expect(success.text()).toContain('数据已经保存')
    expect(success.attributes('role')).toBe('status')
    const error = mount(PhoenixAlert, { props: { variant: 'error' } })
    expect(error.text()).toContain('出现错误')
    expect(error.attributes('role')).toBe('alert')
  })

  it('提示条关闭后移除并发出事件', async () => {
    const wrapper = mount(PhoenixAlert, { props: { closable: true } })
    await wrapper.get('[aria-label="关闭提示"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(wrapper.find('.px-alert').exists()).toBe(false)
  })

  it('卡片组合标题、扩展区、正文和底部插槽', () => {
    const wrapper = mount(PhoenixCard, {
      props: { title: '组件概览', subtitle: '最近更新时间', elevated: true },
      slots: { default: '共有 10 个基础组件', extra: '查看全部', footer: '继续浏览' },
    })
    expect(wrapper.text()).toContain('组件概览')
    expect(wrapper.text()).toContain('查看全部')
    expect(wrapper.text()).toContain('共有 10 个基础组件')
    expect(wrapper.text()).toContain('继续浏览')
    expect(wrapper.classes()).toContain('is-elevated')
  })
})
