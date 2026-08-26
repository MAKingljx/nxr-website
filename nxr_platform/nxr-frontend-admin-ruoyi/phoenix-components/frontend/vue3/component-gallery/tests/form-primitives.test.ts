import { mount } from '@vue/test-utils'
import { h } from 'vue'
import { describe, expect, it } from 'vitest'
import {
  PhoenixAutocomplete,
  PhoenixColorPicker,
  PhoenixDateRangePicker,
  PhoenixMultiSelect,
  PhoenixNumberInput,
  PhoenixOtpInput,
  PhoenixRate,
  PhoenixSegmented,
  PhoenixSlider,
  PhoenixTextarea,
  PhoenixTimePicker,
  PhoenixTransfer,
} from '../src/primitives/forms'

describe('Phoenix 高频表单组件', () => {
  it('多行文本受控更新并收敛最大长度', async () => {
    const wrapper = mount(PhoenixTextarea, { props: { modelValue: '', maxLength: 4 } })
    await wrapper.get('textarea').setValue('abcdef')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['abcd'])
    expect(wrapper.emitted('change')?.at(-1)).toEqual(['abcd'])
  })

  it('多行文本提供标签、计数和禁用语义', () => {
    const wrapper = mount(PhoenixTextarea, { props: { modelValue: '说明', label: '备注', rows: 0, maxLength: 10, showCount: true, disabled: true } })
    const textarea = wrapper.get('textarea')
    expect(textarea.attributes('aria-label')).toBe('备注')
    expect(textarea.attributes('rows')).toBe('4')
    expect(textarea.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('2/10')
  })

  it('数字输入按步长增加并发出变更', async () => {
    const wrapper = mount(PhoenixNumberInput, { props: { modelValue: 2, step: 0.5, precision: 1 } })
    await wrapper.get('[aria-label="增加"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([2.5])
    expect(wrapper.emitted('change')?.[0]).toEqual([2.5])
  })

  it('数字输入在边界处收敛并禁用不可用操作', async () => {
    const wrapper = mount(PhoenixNumberInput, { props: { modelValue: 10, min: 0, max: 10, label: '数量' } })
    expect(wrapper.get('input').attributes('aria-label')).toBe('数量')
    expect(wrapper.get('[aria-label="增加"]').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ modelValue: 9.8, step: 1 })
    await wrapper.get('[aria-label="增加"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([10])
  })

  it('多选框保留选项值类型并发出选择', async () => {
    const wrapper = mount(PhoenixMultiSelect, { props: { modelValue: [1], options: [{ label: '甲', value: 1 }, { label: '乙', value: 2 }] } })
    const options = wrapper.findAll('option')
    ;(options[0].element as HTMLOptionElement).selected = false
    ;(options[1].element as HTMLOptionElement).selected = true
    await wrapper.get('select').trigger('change')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([[2]])
  })

  it('多选框为多实例生成不同名称并限制可见行数', () => {
    const props = { modelValue: [], options: [{ label: '甲', value: 'a' }], size: 99, label: '项目' }
    const wrapper = mount({ setup: () => () => h('div', [h(PhoenixMultiSelect, props), h(PhoenixMultiSelect, props)]) })
    const selects = wrapper.findAll('select')
    expect(selects[0].attributes('name')).not.toBe(selects[1].attributes('name'))
    expect(selects[0].attributes('size')).toBe('12')
    expect(selects[0].attributes('aria-label')).toBe('项目')
  })

  it('时间选择受控更新并提供输入约束', async () => {
    const wrapper = mount(PhoenixTimePicker, { props: { modelValue: '', min: '09:00', max: '18:00', step: 900 } })
    await wrapper.get('input').setValue('10:30')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['10:30'])
    expect(wrapper.get('input').attributes()).toMatchObject({ min: '09:00', max: '18:00', step: '900' })
  })

  it('时间选择收敛步长并支持禁用语义', () => {
    const wrapper = mount(PhoenixTimePicker, { props: { modelValue: '10:00', step: 0, disabled: true, label: '到达时间' } })
    expect(wrapper.get('input').attributes('step')).toBe('60')
    expect(wrapper.get('input').attributes('aria-label')).toBe('到达时间')
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
  })

  it('日期范围在结束日期早于开始日期时自动排序', async () => {
    const wrapper = mount(PhoenixDateRangePicker, { props: { modelValue: ['2026-08-10', ''] } })
    await wrapper.findAll('input')[1].setValue('2026-08-01')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['2026-08-01', '2026-08-10']])
  })

  it('日期范围生成唯一字段名并组合标签', () => {
    const wrapper = mount({ setup: () => () => h('div', [h(PhoenixDateRangePicker), h(PhoenixDateRangePicker)]) })
    const inputs = wrapper.findAll('input')
    expect(inputs[0].attributes('name')).not.toBe(inputs[2].attributes('name'))
    expect(inputs[0].attributes('aria-label')).toBe('开始日期')
    expect(inputs[1].attributes('aria-label')).toBe('结束日期')
  })

  it('自动补全更新值并在精确匹配时发出选择', async () => {
    const option = { label: '北京市', value: 'beijing' }
    const wrapper = mount(PhoenixAutocomplete, { props: { modelValue: '', options: [option] } })
    await wrapper.get('input').setValue('beijing')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['beijing'])
    expect(wrapper.emitted('select')?.[0]).toEqual([option])
  })

  it('自动补全排除禁用项并为多实例生成不同列表', () => {
    const props = { options: [{ label: '可用', value: 'a' }, { label: '停用', value: 'b', disabled: true }] }
    const wrapper = mount({ setup: () => () => h('div', [h(PhoenixAutocomplete, props), h(PhoenixAutocomplete, props)]) })
    const inputs = wrapper.findAll('input')
    expect(inputs[0].attributes('list')).not.toBe(inputs[1].attributes('list'))
    expect(wrapper.findAll('option')).toHaveLength(2)
  })

  it('自动补全将运行时输入收敛到最大长度', async () => {
    const wrapper = mount(PhoenixAutocomplete, { props: { modelValue: '', maxLength: 3 } })
    await wrapper.get('input').setValue('abcdef')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['abc'])
  })

  it('滑块收敛越界值并展示当前值', () => {
    const wrapper = mount(PhoenixSlider, { props: { modelValue: 120, min: 10, max: 100, label: '音量' } })
    expect(wrapper.get('input').element.value).toBe('100')
    expect(wrapper.get('input').attributes('aria-label')).toBe('音量')
    expect(wrapper.get('output').text()).toBe('100')
  })

  it('滑块更新数值并保留禁用状态', async () => {
    const wrapper = mount(PhoenixSlider, { props: { modelValue: 20, step: 5 } })
    await wrapper.get('input').setValue('35')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([35])
    await wrapper.setProps({ disabled: true })
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
  })

  it('评分选择和再次选择可清空', async () => {
    const wrapper = mount(PhoenixRate, { props: { modelValue: 0 } })
    await wrapper.findAll('input')[2].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([3])
    await wrapper.setProps({ modelValue: 3 })
    await wrapper.findAll('input')[2].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([0])
  })

  it('评分限制最大项数、生成唯一名称并支持只读', () => {
    const wrapper = mount({ setup: () => () => h('div', [h(PhoenixRate, { max: 20 }), h(PhoenixRate, { readonly: true })]) })
    const fields = wrapper.findAll('fieldset')
    expect(fields[0].findAll('input')).toHaveLength(10)
    expect(fields[0].get('input').attributes('name')).not.toBe(fields[1].get('input').attributes('name'))
    expect(fields[1].get('input').attributes('disabled')).toBeDefined()
  })

  it('颜色选择规范化值并发出更新', async () => {
    const wrapper = mount(PhoenixColorPicker, { props: { modelValue: '#AABBCC' } })
    expect(wrapper.get('output').text()).toBe('#aabbcc')
    await wrapper.get('input').setValue('#112233')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['#112233'])
  })

  it('颜色选择为非法输入使用安全回退且只读不可编辑', () => {
    const wrapper = mount(PhoenixColorPicker, { props: { modelValue: 'red', readonly: true, label: '主题色' } })
    expect(wrapper.get('input').element.value).toBe('#635bff')
    expect(wrapper.get('input').attributes('aria-label')).toBe('主题色')
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
  })

  it('穿梭框将勾选的可用项移至已选', async () => {
    const options = [{ label: '甲', value: 'a' }, { label: '乙', value: 'b' }]
    const wrapper = mount(PhoenixTransfer, { props: { modelValue: [], options } })
    await wrapper.findAll('input')[0].setValue(true)
    await wrapper.get('[aria-label="移至已选"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['a']])
    expect(wrapper.emitted('change')?.[0]).toEqual([['a'], 'right', ['a']])
  })

  it('穿梭框不能移动禁用项且提供分区名称', async () => {
    const wrapper = mount(PhoenixTransfer, { props: { modelValue: [], options: [{ label: '锁定', value: 1, disabled: true }] } })
    expect(wrapper.get('section').attributes('aria-label')).toBe('待选')
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[aria-label="移至已选"]').attributes('disabled')).toBeDefined()
  })

  it('穿梭框多实例默认字段名称唯一', () => {
    const props = { options: [{ label: '甲', value: 'a' }] }
    const wrapper = mount({ setup: () => () => h('div', [h(PhoenixTransfer, props), h(PhoenixTransfer, props)]) })
    const inputs = wrapper.findAll('input')
    expect(inputs[0].attributes('name')).not.toBe(inputs[1].attributes('name'))
  })

  it('穿梭框可将已选项移回且保持未移动项', async () => {
    const options = [{ label: '甲', value: 'a' }, { label: '乙', value: 'b' }]
    const wrapper = mount(PhoenixTransfer, { props: { modelValue: ['a', 'b'], options } })
    await wrapper.findAll('input')[0].setValue(true)
    await wrapper.get('[aria-label="移回待选"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['b']])
  })

  it('分段选择保留值类型并发出变更', async () => {
    const wrapper = mount(PhoenixSegmented, { props: { modelValue: 1, options: [{ label: '日', value: 1 }, { label: '月', value: 2 }] } })
    await wrapper.findAll('input')[1].setValue(true)
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([2])
    expect(wrapper.emitted('change')?.[0]).toEqual([2])
  })

  it('分段选择生成唯一名称并忽略禁用项', () => {
    const props = { modelValue: 'a', options: [{ label: '甲', value: 'a', disabled: true }] }
    const wrapper = mount({ setup: () => () => h('div', [h(PhoenixSegmented, props), h(PhoenixSegmented, props)]) })
    const inputs = wrapper.findAll('input')
    expect(inputs[0].attributes('name')).not.toBe(inputs[1].attributes('name'))
    expect(inputs[0].attributes('disabled')).toBeDefined()
  })

  it('验证码仅接收数字并在填满时发出完成', async () => {
    const wrapper = mount(PhoenixOtpInput, { props: { modelValue: '12', length: 3 } })
    await wrapper.findAll('input')[2].setValue('a3')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['123'])
    expect(wrapper.emitted('complete')?.[0]).toEqual(['123'])
  })

  it('验证码限制长度并提供逐位标签', () => {
    const wrapper = mount(PhoenixOtpInput, { props: { modelValue: '1234567890123', length: 20, label: '短信码' } })
    expect(wrapper.findAll('input')).toHaveLength(12)
    expect(wrapper.findAll('input')[0].attributes('aria-label')).toBe('短信码第 1 位')
    expect(wrapper.findAll('input')[11].element.value).toBe('2')
  })

  it('验证码多实例名称唯一且禁用可传递', () => {
    const wrapper = mount({ setup: () => () => h('div', [h(PhoenixOtpInput, { length: 1 }), h(PhoenixOtpInput, { length: 1, disabled: true })]) })
    const inputs = wrapper.findAll('input')
    expect(inputs[0].attributes('name')).not.toBe(inputs[1].attributes('name'))
    expect(inputs[1].attributes('disabled')).toBeDefined()
  })

  it('验证码支持方向键移动焦点', async () => {
    const wrapper = mount(PhoenixOtpInput, { attachTo: document.body, props: { modelValue: '', length: 3 } })
    const inputs = wrapper.findAll('input')
    inputs[1].element.focus()
    await inputs[1].trigger('keydown', { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(inputs[0].element)
    wrapper.unmount()
  })

  it('验证码可配置为接收非数字字符', async () => {
    const wrapper = mount(PhoenixOtpInput, { props: { modelValue: '', length: 2, digitsOnly: false } })
    await wrapper.findAll('input')[0].setValue('A')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['A'])
    expect(wrapper.findAll('input')[0].attributes('inputmode')).toBe('text')
  })

  it('基础字段多实例默认名称互不冲突', () => {
    const components = [PhoenixTextarea, PhoenixNumberInput, PhoenixTimePicker, PhoenixAutocomplete, PhoenixSlider, PhoenixColorPicker]
    for (const component of components) {
      const wrapper = mount({ setup: () => () => h('div', [h(component), h(component)]) })
      const controls = wrapper.findAll('input, textarea')
      expect(controls[0].attributes('name')).not.toBe(controls[1].attributes('name'))
      wrapper.unmount()
    }
  })
})
