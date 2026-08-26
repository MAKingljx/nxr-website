import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import themeTokens from '../src/theme-tokens.css?raw'
import { PhoenixButton, PhoenixThemeProvider, usePhoenixTheme } from '../src/primitives'

function relativeLuminance(hex: string) {
  const channels = [1, 3, 5].map((start) => Number.parseInt(hex.slice(start, start + 2), 16) / 255)
  const [red, green, blue] = channels.map((channel) => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

function contrastRatio(first: string, second: string) {
  const [lighter, darker] = [relativeLuminance(first), relativeLuminance(second)].sort((a, b) => b - a)
  return (lighter + 0.05) / (darker + 0.05)
}

describe('PhoenixThemeProvider', () => {
  it('默认使用现代主题并渲染插槽', () => {
    const wrapper = mount(PhoenixThemeProvider, { slots: { default: '主题内容' } })
    expect(wrapper.attributes('data-theme')).toBe('modern')
    expect(wrapper.text()).toBe('主题内容')
  })

  it.each(['modern', 'business', 'minimal', 'festive'] as const)('支持 %s 主题', (theme) => {
    const wrapper = mount(PhoenixThemeProvider, { props: { theme, label: `${theme}主题` } })
    expect(wrapper.attributes('data-theme')).toBe(theme)
    expect(wrapper.attributes('aria-label')).toBe(`${theme}主题`)
  })

  it('运行时收到未知主题时安全回退到现代主题', () => {
    const wrapper = mount(PhoenixThemeProvider, { props: { theme: 'unknown' as never } })
    expect(wrapper.attributes('data-theme')).toBe('modern')
  })

  it('向后代提供受控且可更新的主题上下文', async () => {
    const Consumer = defineComponent({
      setup() {
        const context = usePhoenixTheme()
        return () => h('output', context?.theme.value ?? '未提供主题')
      },
    })
    const wrapper = mount(PhoenixThemeProvider, { props: { theme: 'business' }, slots: { default: Consumer } })
    expect(wrapper.get('output').text()).toBe('business')
    await wrapper.setProps({ theme: 'festive' })
    expect(wrapper.get('output').text()).toBe('festive')
  })

  it('只通过局部容器传递主题，不修改文档根节点', () => {
    const original = document.documentElement.getAttribute('data-theme')
    const wrapper = mount(PhoenixThemeProvider, { props: { theme: 'minimal' }, slots: { default: PhoenixButton } })
    expect(wrapper.find('.px-button').exists()).toBe(true)
    expect(document.documentElement.getAttribute('data-theme')).toBe(original)
  })

  it('四套主题的主要操作色与白字均达到 WCAG AA 对比度', () => {
    const primaryColors = [...themeTokens.matchAll(/--px-primary:\s*(#[\da-f]{6})/gi)].map((match) => match[1])
    expect(primaryColors).toHaveLength(4)
    for (const color of primaryColors) expect(contrastRatio(color, '#ffffff')).toBeGreaterThanOrEqual(4.5)
  })
})
