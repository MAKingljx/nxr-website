import { DOMWrapper, mount } from '@vue/test-utils'
import { h, nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import {
  PhoenixDataTable,
  PhoenixDatePicker,
  PhoenixDialog,
  PhoenixDrawer,
  PhoenixEmpty,
  PhoenixFormItem,
  PhoenixInput,
  PhoenixSelect,
  PhoenixSkeleton,
  PhoenixToast,
  type PhoenixDataTableColumn,
} from '../src/primitives/crud'

afterEach(() => {
  document.body.innerHTML = ''
})

describe('PhoenixInput', () => {
  it('通过 v-model 更新文本并提供中文默认占位', async () => {
    const wrapper = mount(PhoenixInput, { props: { modelValue: '' } })
    await wrapper.get('input').setValue('伊利组件')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['伊利组件'])
    expect(wrapper.get('input').attributes('placeholder')).toBe('请输入内容')
  })

  it('数字输入返回数字类型', async () => {
    const wrapper = mount(PhoenixInput, { props: { modelValue: 0, type: 'number' } })
    await wrapper.get('input').setValue('12')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([12])
  })

  it('可清空内容并重新聚焦输入框', async () => {
    const wrapper = mount(PhoenixInput, { attachTo: document.body, props: { modelValue: '已有内容', clearable: true } })
    await wrapper.get('[aria-label="清空输入内容"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([''])
    expect(wrapper.emitted('clear')).toHaveLength(1)
    expect(document.activeElement).toBe(wrapper.get('input').element)
  })

  it('显示字数并暴露无效状态', () => {
    const wrapper = mount(PhoenixInput, { props: { modelValue: '四个字了', maxLength: 10, showCount: true, invalid: true } })
    expect(wrapper.text()).toContain('4/10')
    expect(wrapper.get('input').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('input').attributes('maxlength')).toBe('10')
  })
})

describe('PhoenixSelect', () => {
  const options = [
    { label: '教学管理', value: 'teaching' },
    { label: '图书管理', value: 2 },
    { label: '暂不可用', value: 'disabled', disabled: true },
  ]

  it('选择选项时更新 v-model 和 change', async () => {
    const wrapper = mount(PhoenixSelect, { props: { options } })
    await wrapper.get('select').setValue('teaching')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['teaching'])
    expect(wrapper.emitted('change')?.[0]).toEqual(['teaching'])
  })

  it('保留数字选项类型', async () => {
    const wrapper = mount(PhoenixSelect, { props: { options } })
    await wrapper.get('select').setValue('2')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([2])
  })

  it('加载时禁用并显示中文状态', () => {
    const wrapper = mount(PhoenixSelect, { props: { options, loading: true } })
    expect(wrapper.get('select').attributes('disabled')).toBeDefined()
    expect(wrapper.find('option').text()).toBe('正在加载')
  })

  it('受控清空选择', async () => {
    const wrapper = mount(PhoenixSelect, { props: { options, modelValue: 'teaching', clearable: true } })
    await wrapper.get('[aria-label="清空选择"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([undefined])
    expect(wrapper.emitted('clear')).toHaveLength(1)
  })
})

describe('PhoenixFormItem 与 PhoenixDatePicker', () => {
  it('表单项关联标签并标识必填', () => {
    const wrapper = mount(PhoenixFormItem, {
      props: { label: '项目名称', htmlFor: 'project-name', required: true },
      slots: { default: () => h(PhoenixInput, { id: 'project-name' }) },
    })
    expect(wrapper.get('label').attributes('for')).toBe('project-name')
    expect(wrapper.get('input').attributes('id')).toBe('project-name')
    expect(wrapper.get('input').attributes('aria-label')).toBeUndefined()
    expect(wrapper.classes()).toContain('is-required')
  })

  it('选择和日期组件把业务 ID 传递给真实控件', () => {
    const select = mount(PhoenixSelect, { props: { id: 'project-category', options: [] } })
    const date = mount(PhoenixDatePicker, { props: { id: 'project-date' } })
    expect(select.get('select').attributes('id')).toBe('project-category')
    expect(select.get('select').attributes('aria-label')).toBeUndefined()
    expect(date.get('input').attributes('id')).toBe('project-date')
    expect(date.get('input').attributes('aria-label')).toBeUndefined()
  })

  it('错误消息具有 alert 语义并向插槽提供描述 ID', () => {
    const wrapper = mount(PhoenixFormItem, {
      props: { label: '项目名称', error: '请输入项目名称' },
      slots: {
        default: (slotProps: { describedBy?: string; invalid: boolean }) => h('input', {
          'aria-describedby': slotProps.describedBy,
          'aria-invalid': slotProps.invalid,
        }),
      },
    })
    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toBe('请输入项目名称')
    expect(wrapper.get('input').attributes('aria-describedby')).toBe(alert.attributes('id'))
  })

  it('日期选择更新值并传递范围约束', async () => {
    const wrapper = mount(PhoenixDatePicker, { props: { modelValue: '', min: '2026-01-01', max: '2026-12-31' } })
    await wrapper.get('input').setValue('2026-08-10')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['2026-08-10'])
    expect(wrapper.get('input').attributes('min')).toBe('2026-01-01')
    expect(wrapper.get('input').attributes('max')).toBe('2026-12-31')
  })

  it('日期可清空并重新聚焦', async () => {
    const wrapper = mount(PhoenixDatePicker, { attachTo: document.body, props: { modelValue: '2026-08-10', clearable: true } })
    await wrapper.get('[aria-label="清空日期"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([''])
    expect(wrapper.emitted('clear')).toHaveLength(1)
    expect(document.activeElement).toBe(wrapper.get('input').element)
  })
})

describe('PhoenixDialog', () => {
  it('提供模态对话框语义和标题关联', async () => {
    const wrapper = mount(PhoenixDialog, { props: { modelValue: true, title: '确认删除' } })
    await nextTick()
    const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]')
    expect(dialog?.getAttribute('aria-modal')).toBe('true')
    expect(dialog?.getAttribute('aria-labelledby')).toBe(document.body.querySelector('h2')?.id)
    wrapper.unmount()
  })

  it('Escape 请求关闭并报告原因', async () => {
    const wrapper = mount(PhoenixDialog, { props: { modelValue: true } })
    await nextTick()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
    expect(wrapper.emitted('close')?.[0]).toEqual(['escape'])
    wrapper.unmount()
  })

  it('点击遮罩请求关闭', async () => {
    const wrapper = mount(PhoenixDialog, { props: { modelValue: true } })
    await nextTick()
    const overlay = document.body.querySelector<HTMLElement>('.px-dialog__overlay')
    await new DOMWrapper(overlay as HTMLElement).trigger('click')
    expect(wrapper.emitted('close')?.[0]).toEqual(['overlay'])
    wrapper.unmount()
  })

  it('打开时聚焦指定元素，关闭后恢复原焦点', async () => {
    const before = document.createElement('button')
    document.body.append(before)
    before.focus()
    const wrapper = mount(PhoenixDialog, {
      props: { modelValue: true, initialFocus: '.primary' },
      slots: { footer: () => h('button', { class: 'primary' }, '确认') },
    })
    await nextTick()
    await nextTick()
    expect(document.activeElement?.classList.contains('primary')).toBe(true)
    await wrapper.setProps({ modelValue: false })
    await nextTick()
    expect(document.activeElement).toBe(before)
    wrapper.unmount()
  })
})

describe('PhoenixDrawer', () => {
  it('渲染指定方向并提供模态语义', async () => {
    const wrapper = mount(PhoenixDrawer, { props: { modelValue: true, placement: 'left', title: '筛选条件' } })
    await nextTick()
    const drawer = document.body.querySelector<HTMLElement>('.px-drawer')
    expect(drawer?.classList.contains('px-drawer--left')).toBe(true)
    expect(drawer?.getAttribute('aria-modal')).toBe('true')
    wrapper.unmount()
  })

  it('Escape 请求关闭抽屉', async () => {
    const wrapper = mount(PhoenixDrawer, { props: { modelValue: true } })
    await nextTick()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(wrapper.emitted('close')?.[0]).toEqual(['escape'])
    wrapper.unmount()
  })

  it('可禁止遮罩关闭', async () => {
    const wrapper = mount(PhoenixDrawer, { props: { modelValue: true, closeOnOverlay: false } })
    await nextTick()
    const overlay = document.body.querySelector<HTMLElement>('.px-drawer__overlay')
    await new DOMWrapper(overlay as HTMLElement).trigger('click')
    expect(wrapper.emitted('close')).toBeUndefined()
    wrapper.unmount()
  })
})

describe('PhoenixToast、PhoenixEmpty 与 PhoenixSkeleton', () => {
  it('Toast 由 modelValue 控制，关闭只发出更新请求', async () => {
    const wrapper = mount(PhoenixToast, { props: { modelValue: true, message: '保存成功', type: 'success' } })
    await wrapper.get('[aria-label="关闭消息"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])
    expect(wrapper.find('.px-toast').exists()).toBe(true)
    expect(wrapper.text()).toContain('保存成功')
  })

  it('错误 Toast 使用即时播报语义', () => {
    const wrapper = mount(PhoenixToast, { props: { modelValue: true, type: 'error', message: '保存失败' } })
    expect(wrapper.get('.px-toast').attributes('role')).toBe('alert')
    expect(wrapper.get('.px-toast').attributes('aria-live')).toBe('assertive')
  })

  it('空状态使用中文默认标题且不添加重复描述', () => {
    const wrapper = mount(PhoenixEmpty)
    expect(wrapper.get('h3').text()).toBe('暂无数据')
    expect(wrapper.find('p').exists()).toBe(false)
  })

  it('空状态支持动作插槽', () => {
    const wrapper = mount(PhoenixEmpty, { slots: { action: () => h('button', '新建数据') } })
    expect(wrapper.get('.px-empty__action').text()).toBe('新建数据')
  })

  it('骨架屏暴露加载语义和行数', () => {
    const wrapper = mount(PhoenixSkeleton, { props: { rows: 4, avatar: true } })
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.attributes('aria-label')).toBe('正在加载')
    expect(wrapper.findAll('.px-skeleton__row')).toHaveLength(4)
    expect(wrapper.find('.px-skeleton__avatar').exists()).toBe(true)
  })

  it('骨架屏可关闭动画与标题', () => {
    const wrapper = mount(PhoenixSkeleton, { props: { animated: false, title: false } })
    expect(wrapper.classes()).not.toContain('is-animated')
    expect(wrapper.find('.px-skeleton__title').exists()).toBe(false)
  })
})

describe('PhoenixDataTable', () => {
  const columns: PhoenixDataTableColumn[] = [
    { key: 'name', label: '名称', sortable: true },
    { key: 'owner.name', label: '负责人' },
    { key: 'count', label: '数量', align: 'right', format: (value) => `${String(value)} 个` },
  ]
  const rows = [
    { id: 1, name: '教学项目', owner: { name: '张老师' }, count: 8 },
    { id: 2, name: '图书项目', owner: { name: '李老师' }, count: 12 },
  ]

  it('渲染嵌套字段和格式化内容', () => {
    const wrapper = mount(PhoenixDataTable, { props: { columns, rows } })
    expect(wrapper.text()).toContain('张老师')
    expect(wrapper.text()).toContain('12 个')
    expect(wrapper.get('caption').text()).toBe('数据表格')
  })

  it('无数据时显示中文空状态', () => {
    const wrapper = mount(PhoenixDataTable, { props: { columns, rows: [] } })
    expect(wrapper.get('.px-data-table__empty').text()).toBe('暂无数据')
  })

  it('加载时显示指定数量骨架行和忙碌状态', () => {
    const wrapper = mount(PhoenixDataTable, { props: { columns, rows, loading: true, loadingRows: 4 } })
    expect(wrapper.attributes('aria-busy')).toBe('true')
    expect(wrapper.findAll('.px-data-table__loading-row')).toHaveLength(4)
    expect(wrapper.text()).toContain('正在加载数据')
  })

  it('选择单行时更新受控键集合', async () => {
    const wrapper = mount(PhoenixDataTable, { props: { columns, rows, selectable: true, modelValue: [] } })
    const checkbox = wrapper.get('[aria-label="选择第 1 行"]')
    await checkbox.setValue(true)
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[1]])
    expect(wrapper.emitted('selection-change')?.[0]).toEqual([[1]])
  })

  it('全选保留非当前页键值', async () => {
    const wrapper = mount(PhoenixDataTable, { props: { columns, rows, selectable: true, modelValue: [99] } })
    await wrapper.get('[aria-label="选择全部当前数据"]').setValue(true)
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[99, 1, 2]])
  })

  it('部分选择时设置表头复选框中间态', () => {
    const wrapper = mount(PhoenixDataTable, { props: { columns, rows, selectable: true, modelValue: [1] } })
    const checkbox = wrapper.get('[aria-label="选择全部当前数据"]').element as HTMLInputElement
    expect(checkbox.indeterminate).toBe(true)
  })

  it('排序按升序、降序、取消的顺序受控切换', async () => {
    const wrapper = mount(PhoenixDataTable, { props: { columns, rows } })
    const sortButton = wrapper.get('.px-data-table__sort')
    await sortButton.trigger('click')
    expect(wrapper.emitted('sort-change')?.[0]).toEqual([{ key: 'name', direction: 'asc' }])
    await wrapper.setProps({ sortBy: 'name', sortDirection: 'asc' })
    await sortButton.trigger('click')
    expect(wrapper.emitted('sort-change')?.[1]).toEqual([{ key: 'name', direction: 'desc' }])
    await wrapper.setProps({ sortDirection: 'desc' })
    await sortButton.trigger('click')
    expect(wrapper.emitted('sort-change')?.[2]).toEqual([{ key: '', direction: null }])
  })

  it('单元格插槽和行点击事件可组合', async () => {
    const wrapper = mount(PhoenixDataTable, {
      props: { columns, rows },
      slots: { 'cell-name': (slotProps: { value: unknown }) => h('strong', `项目：${String(slotProps.value)}`) },
    })
    expect(wrapper.text()).toContain('项目：教学项目')
    await wrapper.find('tbody tr').trigger('click')
    expect(wrapper.emitted('row-click')?.[0]).toEqual([rows[0], 0])
  })
})
