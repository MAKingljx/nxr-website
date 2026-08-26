import { mount } from '@vue/test-utils'
import { h } from 'vue'
import { describe, expect, it } from 'vitest'
import {
  PhoenixAvatar,
  PhoenixBadge,
  PhoenixCascader,
  PhoenixCheckbox,
  PhoenixDescriptions,
  PhoenixFileUpload,
  PhoenixNotification,
  PhoenixRadioGroup,
  PhoenixSteps,
  PhoenixTooltip,
  PhoenixTree,
} from '../src/primitives/interaction'

function selectFiles(wrapper: ReturnType<typeof mount>, files: File[]) {
  const input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: files })
  return input.trigger('change')
}

describe('Phoenix 通用交互与数据组件', () => {
  it('复选框通过 v-model 更新并发出 change', async () => {
    const wrapper = mount(PhoenixCheckbox, { props: { modelValue: false, label: '同意协议' } })
    await wrapper.get('input').setValue(true)
    expect(wrapper.text()).toContain('同意协议')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true])
    expect(wrapper.emitted('change')?.[0]).toEqual([true])
  })

  it('复选框提供混合状态和禁用语义', () => {
    const wrapper = mount(PhoenixCheckbox, { props: { modelValue: false, indeterminate: true, disabled: true } })
    expect(wrapper.get('input').attributes('aria-checked')).toBe('mixed')
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
  })

  it('单选组选择可用项并保持值类型', async () => {
    const wrapper = mount(PhoenixRadioGroup, {
      props: { modelValue: 1, options: [{ label: '甲', value: 1 }, { label: '乙', value: 2 }] },
    })
    await wrapper.findAll('input')[1].setValue(true)
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([2])
    expect(wrapper.emitted('change')?.[0]).toEqual([2])
  })

  it('单选组标识组名、方向和禁用项', () => {
    const wrapper = mount(PhoenixRadioGroup, {
      props: { modelValue: 'a', label: '配送方式', direction: 'vertical', options: [{ label: '自提', value: 'a' }, { label: '配送', value: 'b', disabled: true }] },
    })
    expect(wrapper.classes()).toContain('px-radio-group--vertical')
    expect(wrapper.get('legend').text()).toBe('配送方式')
    expect(wrapper.findAll('input')[1].attributes('disabled')).toBeDefined()
  })

  it('多个单选组默认使用不同名称并允许显式覆盖', () => {
    const props = { modelValue: 'a', options: [{ label: '甲', value: 'a' }] }
    const wrapper = mount({
      setup: () => () => h('div', [
        h(PhoenixRadioGroup, props),
        h(PhoenixRadioGroup, props),
        h(PhoenixRadioGroup, { ...props, name: 'delivery-method' }),
      ]),
    })
    const inputs = wrapper.findAll('input')
    expect(inputs[0].attributes('name')).not.toBe(inputs[1].attributes('name'))
    expect(inputs[2].attributes('name')).toBe('delivery-method')
  })

  it('文件选择仅返回通过校验的本地 File', async () => {
    const file = new File(['metadata-only'], '说明.pdf', { type: 'application/pdf' })
    const wrapper = mount(PhoenixFileUpload, { props: { modelValue: [], accept: '.pdf', limit: 2, multiple: true } })
    await selectFiles(wrapper, [file])
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toEqual([file])
    expect(wrapper.emitted('change')?.[0]?.[0]).toEqual([file])
  })

  it('文件选择拒绝不匹配的类型', async () => {
    const file = new File(['x'], '图片.png', { type: 'image/png' })
    const wrapper = mount(PhoenixFileUpload, { props: { modelValue: [], accept: '.pdf' } })
    await selectFiles(wrapper, [file])
    expect(wrapper.emitted('reject')?.[0]?.[0]).toMatchObject({ file, reason: 'type' })
    expect(wrapper.text()).toContain('文件类型不符合要求')
  })

  it('文件选择拒绝超出大小限制的文件', async () => {
    const file = new File([new Uint8Array(2048)], '数据.bin')
    const wrapper = mount(PhoenixFileUpload, { props: { modelValue: [], maxSizeMb: 0.001 } })
    await selectFiles(wrapper, [file])
    expect(wrapper.emitted('reject')?.[0]?.[0]).toMatchObject({ reason: 'size' })
  })

  it('文件选择限制数量并报告多余文件', async () => {
    const existing = new File(['a'], '已有.txt', { type: 'text/plain' })
    const extra = new File(['b'], '新增.txt', { type: 'text/plain' })
    const wrapper = mount(PhoenixFileUpload, { props: { modelValue: [existing], limit: 1 } })
    await selectFiles(wrapper, [extra])
    expect(wrapper.emitted('reject')?.[0]?.[0]).toMatchObject({ reason: 'limit' })
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('文件列表可移除并返回剩余文件', async () => {
    const file = new File(['a'], '计划.txt', { type: 'text/plain' })
    const wrapper = mount(PhoenixFileUpload, { props: { modelValue: [file] } })
    await wrapper.get('[aria-label="移除 计划.txt"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[]])
    expect(wrapper.emitted('remove')?.[0]).toEqual([file, 0])
  })

  it('树形组件展示根节点并展开子节点', async () => {
    const nodes = [{ label: '华北', value: 'north', children: [{ label: '北京', value: 'beijing' }] }]
    const wrapper = mount(PhoenixTree, { props: { nodes, modelValue: null, expandedValues: [] } })
    expect(wrapper.findAll('[role="treeitem"]')).toHaveLength(1)
    await wrapper.get('[aria-label="展开华北"]').trigger('click')
    expect(wrapper.emitted('update:expandedValues')?.[0]).toEqual([['north']])
    expect(wrapper.emitted('expand-change')?.[0]).toEqual([nodes[0], true])
    await wrapper.setProps({ expandedValues: ['north'] })
    expect(wrapper.findAll('[role="treeitem"]')).toHaveLength(2)
  })

  it('树节点选择更新 v-model 和节点详情', async () => {
    const node = { label: '北京', value: 'beijing' }
    const wrapper = mount(PhoenixTree, { props: { nodes: [node], modelValue: null } })
    await wrapper.get('[role="treeitem"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['beijing'])
    expect(wrapper.emitted('change')?.[0]).toEqual(['beijing', node])
  })

  it('树形组件支持方向键移动焦点', async () => {
    const wrapper = mount(PhoenixTree, {
      attachTo: document.body,
      props: { nodes: [{ label: '第一项', value: 1 }, { label: '第二项', value: 2 }], modelValue: 1 },
    })
    await wrapper.findAll('[role="treeitem"]')[0].trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement?.textContent).toContain('第二项')
    wrapper.unmount()
  })

  it('空树提供中文空状态', () => {
    const wrapper = mount(PhoenixTree, { props: { nodes: [], modelValue: null } })
    expect(wrapper.text()).toContain('暂无数据')
    expect(wrapper.get('[role="tree"]').attributes('aria-label')).toBe('树形选项')
  })

  it('级联选择展开父级并提交叶子路径', async () => {
    const options = [{ label: '中国', value: 'cn', children: [{ label: '北京', value: 'bj' }] }]
    const wrapper = mount(PhoenixCascader, { props: { modelValue: [], options } })
    await wrapper.get('[role="option"]').trigger('click')
    expect(wrapper.emitted('expand')?.[0]).toEqual([['cn'], options[0]])
    expect(wrapper.findAll('[role="listbox"]')).toHaveLength(2)
    await wrapper.findAll('[role="option"]')[1].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['cn', 'bj']])
  })

  it('级联选择展示当前路径并支持清空', async () => {
    const options = [{ label: '中国', value: 'cn', children: [{ label: '北京', value: 'bj' }] }]
    const wrapper = mount(PhoenixCascader, { props: { modelValue: ['cn', 'bj'], options } })
    expect(wrapper.text()).toContain('中国 / 北京')
    await wrapper.get('[aria-label="清空级联选择"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[]])
    expect(wrapper.emitted('clear')).toHaveLength(1)
  })

  it('级联选择跳过禁用选项', async () => {
    const wrapper = mount(PhoenixCascader, { props: { modelValue: [], options: [{ label: '不可用', value: 'x', disabled: true }] } })
    const option = wrapper.get('[role="option"]')
    expect(option.attributes('aria-disabled')).toBe('true')
    await option.trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('步骤条标识当前和完成步骤', () => {
    const wrapper = mount(PhoenixSteps, { props: { modelValue: 1, items: [{ title: '填写' }, { title: '确认' }, { title: '完成' }] } })
    expect(wrapper.findAll('li')[0].classes()).toContain('px-step--finish')
    expect(wrapper.get('[aria-current="step"]').text()).toContain('确认')
  })

  it('可点击步骤条更新当前步骤并忽略禁用项', async () => {
    const wrapper = mount(PhoenixSteps, { props: { modelValue: 0, clickable: true, items: [{ title: '第一步' }, { title: '第二步' }, { title: '不可用', disabled: true }] } })
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([1])
    await wrapper.findAll('button')[2].trigger('click')
    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
  })

  it('描述列表提供语义标签和值', () => {
    const wrapper = mount(PhoenixDescriptions, { props: { title: '订单信息', items: [{ label: '编号', value: 'A-01' }, { label: '备注' }] } })
    expect(wrapper.get('section').attributes('aria-label')).toBe('订单信息')
    expect(wrapper.findAll('dt')[0].text()).toBe('编号')
    expect(wrapper.findAll('dd')[0].text()).toBe('A-01')
    expect(wrapper.findAll('dd')[1].text()).toBe('暂无')
  })

  it('描述列表支持跨列和自定义值插槽', () => {
    const wrapper = mount(PhoenixDescriptions, {
      props: { columns: 3, items: [{ label: '状态', value: '原始值', span: 2 }] },
      slots: { 'item-0': '<strong>已完成</strong>' },
    })
    expect(wrapper.get('dl > div').attributes('style')).toContain('--px-description-span: 2')
    expect(wrapper.get('dd').text()).toBe('已完成')
  })

  it('头像无图片时展示名称缩写和状态', () => {
    const wrapper = mount(PhoenixAvatar, { props: { name: '张三丰', status: 'online' } })
    expect(wrapper.text()).toContain('张三')
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toBe('用户头像')
    expect(wrapper.get('[role="status"]').attributes('aria-label')).toBe('在线')
  })

  it('头像图片失败后回退并发出错误事件', async () => {
    const wrapper = mount(PhoenixAvatar, { props: { src: '/missing.png', name: '李雷' } })
    await wrapper.get('img').trigger('error')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('李雷')
    expect(wrapper.emitted('error')).toHaveLength(1)
  })

  it('徽标处理上限和无障碍说明', () => {
    const wrapper = mount(PhoenixBadge, { props: { value: 120, max: 99 }, slots: { default: '<button>消息</button>' } })
    expect(wrapper.text()).toContain('99+')
    expect(wrapper.get('[role="status"]').attributes('aria-label')).toBe('未读消息：99+')
  })

  it('徽标默认隐藏零值并支持点状模式', async () => {
    const wrapper = mount(PhoenixBadge, { props: { value: 0 } })
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    await wrapper.setProps({ dot: true })
    expect(wrapper.get('[role="status"]').classes()).toContain('is-dot')
  })

  it('工具提示在键盘聚焦时可见', async () => {
    const wrapper = mount(PhoenixTooltip, { props: { content: '查看完整名称' }, slots: { default: '名称' } })
    await wrapper.trigger('focus')
    expect(wrapper.get('[role="tooltip"]').text()).toBe('查看完整名称')
    expect(wrapper.attributes('aria-describedby')).toBe(wrapper.get('[role="tooltip"]').attributes('id'))
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true])
  })

  it('工具提示支持 Escape 隐藏', async () => {
    const wrapper = mount(PhoenixTooltip, { props: { modelValue: true } })
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(true)
    await wrapper.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="tooltip"]').exists()).toBe(false)
    expect(wrapper.emitted('hide')).toHaveLength(1)
  })

  it('通知完全由 modelValue 控制且不产生外部容器', () => {
    const hidden = mount(PhoenixNotification, { props: { modelValue: false } })
    expect(hidden.find('.px-notification').exists()).toBe(false)
    const visible = mount(PhoenixNotification, { props: { modelValue: true, title: '保存成功', message: '数据已更新', variant: 'success' } })
    expect(visible.text()).toContain('保存成功')
    expect(visible.text()).toContain('数据已更新')
    expect(document.body.querySelector('.px-notification')).toBeNull()
  })

  it('通知关闭只发出受控更新与关闭原因', async () => {
    const wrapper = mount(PhoenixNotification, { props: { modelValue: true } })
    await wrapper.get('[aria-label="关闭通知"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
    expect(wrapper.emitted('close')?.[0]).toEqual(['close'])
    expect(wrapper.find('.px-notification').exists()).toBe(true)
  })

  it('通知支持操作事件与 Escape 关闭', async () => {
    const wrapper = mount(PhoenixNotification, { props: { modelValue: true, actionText: '查看详情' } })
    await wrapper.get('.px-notification__action').trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
    await wrapper.get('section').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')?.[0]).toEqual(['escape'])
  })
})
