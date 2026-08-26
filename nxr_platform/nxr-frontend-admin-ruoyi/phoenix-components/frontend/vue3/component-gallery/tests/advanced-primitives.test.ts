import { mount } from '@vue/test-utils'
import { h } from 'vue'
import { describe, expect, it } from 'vitest'
import {
  PhoenixChartContainer,
  PhoenixFileManager,
  PhoenixMapContainer,
  PhoenixMediaPlayer,
  PhoenixRealtimeFeed,
  PhoenixRichTextEditor,
  PhoenixVirtualList,
} from '../src/primitives/advanced'

describe('Phoenix 高级展示与适配组件', () => {
  it('虚拟列表只渲染可视范围', () => {
    const items = Array.from({ length: 100 }, (_, id) => ({ id, label: `项目 ${id}` }))
    const wrapper = mount(PhoenixVirtualList, { props: { items, height: 100, itemHeight: 20, overscan: 0 } })
    expect(wrapper.findAll('[role="listitem"]')).toHaveLength(5)
    expect(wrapper.text()).toContain('项目 0')
    expect(wrapper.get('[role="listitem"]').attributes('aria-setsize')).toBe('100')
  })

  it('虚拟列表滚动时更新范围并发出事件', async () => {
    const items = Array.from({ length: 40 }, (_, id) => ({ id, label: `项目 ${id}` }))
    const wrapper = mount(PhoenixVirtualList, { props: { items, height: 100, itemHeight: 20, overscan: 0 } })
    const list = wrapper.get('[role="list"]')
    ;(list.element as HTMLElement).scrollTop = 200
    await list.trigger('scroll')
    expect(wrapper.text()).toContain('项目 10')
    expect(wrapper.emitted('scroll')?.[0]).toEqual([200])
    expect(wrapper.emitted('range-change')?.[0]).toEqual([10, 15])
  })

  it('虚拟列表提供中文空状态和键盘滚动', async () => {
    const empty = mount(PhoenixVirtualList, { props: { items: [] } })
    expect(empty.text()).toBe('暂无列表内容')
    const items = Array.from({ length: 30 }, (_, id) => ({ id, name: `文件 ${id}` }))
    const wrapper = mount(PhoenixVirtualList, { props: { items, height: 100, itemHeight: 20, overscan: 0 } })
    await wrapper.get('[role="list"]').trigger('keydown', { key: 'PageDown' })
    expect(wrapper.text()).toContain('文件 5')
  })

  it('文本编辑器受控更新纯文本', async () => {
    const wrapper = mount(PhoenixRichTextEditor, { props: { modelValue: '' } })
    await wrapper.get('textarea').setValue('正文内容')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['正文内容'])
    expect(wrapper.emitted('change')?.[0]).toEqual(['正文内容'])
    expect(wrapper.get('textarea').attributes('placeholder')).toBe('请输入正文，支持 Markdown 风格标记')
  })

  it('文本编辑器工具栏插入 Markdown 风格标记', async () => {
    const wrapper = mount(PhoenixRichTextEditor, { props: { modelValue: '正文' } })
    const textarea = wrapper.get('textarea').element as HTMLTextAreaElement
    textarea.setSelectionRange(0, 2)
    await wrapper.get('[aria-label="加粗"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['**正文**'])
  })

  it('文本编辑器不会把输入内容作为 HTML 执行', () => {
    const payload = '<img src=x onerror=alert(1)>'
    const wrapper = mount(PhoenixRichTextEditor, { props: { modelValue: payload } })
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe(payload)
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('文本编辑器只读时禁用格式按钮并支持长度提示', () => {
    const wrapper = mount(PhoenixRichTextEditor, { props: { modelValue: '内容', readonly: true, maxLength: 20 } })
    expect(wrapper.get('[aria-label="加粗"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('textarea').attributes('maxlength')).toBe('20')
    expect(wrapper.text()).toContain('2 / 20')
  })

  it('图表容器提供标题和图表插槽', () => {
    const wrapper = mount(PhoenixChartContainer, { slots: { default: '<div class="chart-stub">折线图</div>' } })
    expect(wrapper.text()).toContain('数据图表')
    expect(wrapper.get('.chart-stub').text()).toBe('折线图')
    expect(wrapper.attributes('aria-label')).toBe('数据图表')
  })

  it('图表容器加载时隐藏图表内容', () => {
    const wrapper = mount(PhoenixChartContainer, { props: { loading: true }, slots: { default: '<div class="chart-stub">图表</div>' } })
    expect(wrapper.text()).toContain('图表加载中')
    expect(wrapper.find('.chart-stub').exists()).toBe(false)
    expect(wrapper.attributes('aria-busy')).toBe('true')
  })

  it('图表容器区分错误与空状态', async () => {
    const wrapper = mount(PhoenixChartContainer, { props: { error: '数据读取失败' } })
    expect(wrapper.get('[role="alert"]').text()).toBe('数据读取失败')
    await wrapper.setProps({ error: '', empty: true })
    expect(wrapper.get('[role="status"]').text()).toBe('暂无图表数据')
  })

  it('媒体播放器渲染原生视频并透传基础属性', () => {
    const wrapper = mount(PhoenixMediaPlayer, { props: { src: '/media/demo.mp4', title: '产品演示', poster: '/poster.jpg' } })
    const media = wrapper.get('video')
    expect(media.attributes('src')).toBe('/media/demo.mp4')
    expect(media.attributes('aria-label')).toBe('产品演示')
    expect(media.attributes('poster')).toBe('/poster.jpg')
    expect(media.attributes('controls')).toBeDefined()
  })

  it('媒体播放器支持音频模式和原生事件', async () => {
    const wrapper = mount(PhoenixMediaPlayer, { props: { src: '/media/demo.mp3', mediaType: 'audio' } })
    await wrapper.get('audio').trigger('play')
    await wrapper.get('audio').trigger('ended')
    expect(wrapper.emitted('play')).toHaveLength(1)
    expect(wrapper.emitted('ended')).toHaveLength(1)
  })

  it('媒体播放器拒绝脚本协议和非媒体 data 地址', async () => {
    const wrapper = mount(PhoenixMediaPlayer, { props: { src: 'javascript:alert(1)' } })
    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无可播放媒体')
    await wrapper.setProps({ src: 'data:text/html,<script>alert(1)</script>' })
    expect(wrapper.find('video').exists()).toBe(false)
  })

  it('媒体播放器把异常运行时类型收敛为视频标签', () => {
    const wrapper = mount(PhoenixMediaPlayer, {
      props: { src: '/media/demo.mp4', mediaType: 'iframe' as 'video' },
    })
    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.find('video').exists()).toBe(true)
  })

  it('实时动态只展示外部传入数据并保留纯文本', () => {
    const wrapper = mount(PhoenixRealtimeFeed, {
      props: { items: [{ id: 1, title: '订单更新', message: '<b>已完成</b>', actor: '系统', timestamp: '10:20', status: 'success' }] },
    })
    expect(wrapper.get('[role="feed"]').attributes('aria-live')).toBe('polite')
    expect(wrapper.text()).toContain('<b>已完成</b>')
    expect(wrapper.find('b').exists()).toBe(false)
  })

  it('实时动态支持点击和键盘选择事件', async () => {
    const item = { id: 'notice-1', message: '库存不足' }
    const wrapper = mount(PhoenixRealtimeFeed, { props: { items: [item] } })
    await wrapper.get('article').trigger('click')
    await wrapper.get('article').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('select')).toHaveLength(2)
    expect(wrapper.emitted('select')?.[0]).toEqual([item, 0])
  })

  it('实时动态空状态和刷新只发出请求事件', async () => {
    const wrapper = mount(PhoenixRealtimeFeed, { props: { items: [], showRefresh: true } })
    expect(wrapper.text()).toContain('暂无动态')
    await wrapper.get('[aria-label="刷新动态"]').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('地图容器承载外部地图并发出定位事件', async () => {
    const wrapper = mount(PhoenixMapContainer, { slots: { default: '<div class="map-stub">地图适配器</div>' } })
    expect(wrapper.get('.map-stub').text()).toBe('地图适配器')
    await wrapper.get('[aria-label="定位当前位置"]').trigger('click')
    expect(wrapper.emitted('locate')).toHaveLength(1)
  })

  it('地图容器在定位中禁用按钮', () => {
    const wrapper = mount(PhoenixMapContainer, { props: { locating: true } })
    const button = wrapper.get('[aria-label="定位当前位置"]')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.text()).toBe('定位中')
    expect(wrapper.attributes('aria-busy')).toBe('true')
  })

  it('地图容器区分错误与空状态', async () => {
    const wrapper = mount(PhoenixMapContainer, { props: { error: '地图加载失败' } })
    expect(wrapper.get('[role="alert"]').text()).toBe('地图加载失败')
    await wrapper.setProps({ error: '', empty: true })
    expect(wrapper.get('[role="status"]').text()).toBe('暂无地图内容')
  })

  it('文件管理器展示外部文件模型和格式化大小', () => {
    const wrapper = mount(PhoenixFileManager, {
      props: { files: [{ id: 1, name: '项目说明.pdf', size: 1536, type: 'PDF', updatedAt: '2026-08-10' }] },
    })
    expect(wrapper.text()).toContain('项目说明.pdf')
    expect(wrapper.text()).toContain('1.5 KB')
    expect(wrapper.text()).toContain('PDF')
    expect(wrapper.get('input').attributes('aria-label')).toBe('选择文件 项目说明.pdf')
  })

  it('文件管理器以受控方式更新多选结果', async () => {
    const files = [{ id: 1, name: '一号文件' }, { id: 2, name: '二号文件' }]
    const wrapper = mount(PhoenixFileManager, { props: { files, selectedIds: [1] } })
    await wrapper.findAll('input')[1].setValue(true)
    expect(wrapper.emitted('update:selectedIds')?.[0]).toEqual([[1, 2]])
    expect(wrapper.emitted('select')?.[0]).toEqual([files[1], true])
  })

  it('文件管理器单选时替换已有选择', async () => {
    const files = [{ id: 'a', name: '甲' }, { id: 'b', name: '乙' }]
    const wrapper = mount(PhoenixFileManager, { props: { files, selectedIds: ['a'], multiple: false } })
    await wrapper.findAll('input')[1].setValue(true)
    expect(wrapper.emitted('update:selectedIds')?.[0]).toEqual([['b']])
    expect(wrapper.findAll('input').every((input) => input.attributes('type') === 'radio')).toBe(true)
  })

  it('多个文件管理器单选实例使用独立名称并支持覆盖', () => {
    const files = [{ id: 'a', name: '甲' }]
    const wrapper = mount({
      setup: () => () => h('div', [
        h(PhoenixFileManager, { files, multiple: false }),
        h(PhoenixFileManager, { files, multiple: false }),
        h(PhoenixFileManager, { files, multiple: false, name: 'featured-file' }),
      ]),
    })
    const inputs = wrapper.findAll('input')
    expect(inputs[0].attributes('name')).not.toBe(inputs[1].attributes('name'))
    expect(inputs[2].attributes('name')).toBe('featured-file')
  })

  it('文件管理器下载和删除只发出事件而不创建网络链接', async () => {
    const file = { id: 1, name: '合同.docx' }
    const wrapper = mount(PhoenixFileManager, { props: { files: [file] } })
    await wrapper.get('[aria-label="下载文件 合同.docx"]').trigger('click')
    await wrapper.get('[aria-label="删除文件 合同.docx"]').trigger('click')
    expect(wrapper.emitted('download')?.[0]).toEqual([file])
    expect(wrapper.emitted('delete')?.[0]).toEqual([file])
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('文件管理器只读模式隐藏操作并提供空状态', async () => {
    const wrapper = mount(PhoenixFileManager, { props: { files: [{ id: 1, name: '只读文件' }], readonly: true } })
    expect(wrapper.find('.px-file-manager__actions').exists()).toBe(false)
    await wrapper.setProps({ files: [] })
    expect(wrapper.get('[role="status"]').text()).toBe('暂无文件')
  })
})
