import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { describe, expect, it } from 'vitest'
import {
  PhoenixActivityFeed,
  PhoenixAnnouncementBanner,
  PhoenixCommentComposer,
  PhoenixDocumentPreview,
  PhoenixFileDropzone,
  PhoenixImagePreview,
  PhoenixMediaGallery,
  PhoenixMentionInput,
  PhoenixMessageInbox,
  PhoenixNotificationCenter,
} from '../src/primitives/content'

function chooseFiles(wrapper: VueWrapper, files: File[]) {
  const input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: files })
  return input.trigger('change')
}

describe('Phoenix 内容与消息组件', () => {
  describe('PhoenixNotificationCenter', () => {
    const items = [
      { id: 1, title: '审批待处理', description: '你有新的审批任务', read: false, tone: 'warning' as const, actionLabel: '查看', dismissible: true },
      { id: 2, title: '资料已更新', read: true, tone: 'success' as const },
    ]

    it('提供中文默认文案并统计未读通知', () => {
      const wrapper = mount(PhoenixNotificationCenter, { props: { items } })
      expect(wrapper.text()).toContain('通知中心')
      expect(wrapper.text()).toContain('1 条未读')
      expect(wrapper.findAll('.px-notification-center__item')).toHaveLength(2)
    })

    it('未读筛选完全由 filter 属性控制', async () => {
      const wrapper = mount(PhoenixNotificationCenter, { props: { items, filter: 'unread' } })
      expect(wrapper.text()).toContain('审批待处理')
      expect(wrapper.text()).not.toContain('资料已更新')
      await wrapper.get('[aria-label="通知筛选"] button').trigger('click')
      expect(wrapper.emitted('update:filter')?.[0]).toEqual(['all'])
      expect(wrapper.text()).not.toContain('资料已更新')
    })

    it('读取、动作、移除和全部已读均只发出事件', async () => {
      const wrapper = mount(PhoenixNotificationCenter, { props: { items } })
      await wrapper.get('.px-notification-center__main').trigger('click')
      await wrapper.get('[aria-label="标记已读：审批待处理"]').trigger('click')
      await wrapper.get('[aria-label="移除通知：审批待处理"]').trigger('click')
      await wrapper.findAll('.px-content-actions button')[1].trigger('click')
      await wrapper.get('.px-content-header button').trigger('click')
      expect(wrapper.emitted('select')?.[0]).toEqual([items[0]])
      expect(wrapper.emitted('mark-read')?.[0]).toEqual([items[0]])
      expect(wrapper.emitted('dismiss')?.[0]).toEqual([items[0]])
      expect(wrapper.emitted('action')?.[0]).toEqual([items[0]])
      expect(wrapper.emitted('mark-all-read')).toHaveLength(1)
    })

    it('通知正文按纯文本呈现', () => {
      const wrapper = mount(PhoenixNotificationCenter, { props: { items: [{ id: 1, title: '<img src=x onerror=alert(1)>', description: '<script>bad()</script>' }] } })
      expect(wrapper.text()).toContain('<script>bad()</script>')
      expect(wrapper.find('script').exists()).toBe(false)
      expect(wrapper.find('img').exists()).toBe(false)
    })
  })

  describe('PhoenixMessageInbox', () => {
    const threads = [
      { id: 'a', sender: '王敏', subject: '项目周报', preview: '请查收本周进展', unread: true, starred: true, messageCount: 3 },
      { id: 'b', sender: '李华', subject: '会议纪要', archived: true },
    ]

    it('搜索值受控并发出更新事件', async () => {
      const wrapper = mount(PhoenixMessageInbox, { props: { threads, query: '周报' } })
      expect(wrapper.text()).toContain('项目周报')
      expect(wrapper.text()).not.toContain('会议纪要')
      await wrapper.get('input[type="search"]').setValue('纪要')
      expect(wrapper.emitted('update:query')?.[0]).toEqual(['纪要'])
      expect(wrapper.text()).toContain('项目周报')
      expect(wrapper.text()).not.toContain('会议纪要')
    })

    it('文件夹筛选与选中态由属性控制', async () => {
      const wrapper = mount(PhoenixMessageInbox, { props: { threads, folder: 'archived', selectedId: 'b' } })
      expect(wrapper.text()).toContain('会议纪要')
      expect(wrapper.text()).not.toContain('项目周报')
      expect(wrapper.get('.px-message-inbox__item').classes()).toContain('is-selected')
      await wrapper.get('[aria-label="消息文件夹"] button').trigger('click')
      expect(wrapper.emitted('update:folder')?.[0]).toEqual(['inbox'])
    })

    it('选择会话同时发出值更新和完整会话', async () => {
      const wrapper = mount(PhoenixMessageInbox, { props: { threads } })
      await wrapper.get('.px-message-inbox__main').trigger('click')
      expect(wrapper.emitted('update:selectedId')?.[0]).toEqual(['a'])
      expect(wrapper.emitted('select')?.[0]).toEqual([threads[0]])
    })

    it('拒绝危险头像地址，标星、归档和写消息只发事件', async () => {
      const thread = { ...threads[0], avatar: 'javascript:alert(1)' }
      const wrapper = mount(PhoenixMessageInbox, { props: { threads: [thread] } })
      expect(wrapper.find('img').exists()).toBe(false)
      await wrapper.get('[aria-label="取消标星：项目周报"]').trigger('click')
      await wrapper.get('[aria-label="归档：项目周报"]').trigger('click')
      await wrapper.get('.px-content-header button').trigger('click')
      expect(wrapper.emitted('star')?.[0]).toEqual([thread, false])
      expect(wrapper.emitted('archive')?.[0]).toEqual([thread])
      expect(wrapper.emitted('compose')).toHaveLength(1)
    })
  })

  describe('PhoenixAnnouncementBanner', () => {
    it('默认呈现中文公告且支持外观', () => {
      const wrapper = mount(PhoenixAnnouncementBanner, { props: { appearance: 'soft' } })
      expect(wrapper.text()).toContain('重要公告')
      expect(wrapper.attributes('data-appearance')).toBe('soft')
      expect(wrapper.attributes('role')).toBe('status')
    })

    it('关闭公告发出受控可见性和 dismiss 事件', async () => {
      const wrapper = mount(PhoenixAnnouncementBanner)
      await wrapper.get('[aria-label="关闭公告"]').trigger('click')
      expect(wrapper.emitted('update:visible')?.[0]).toEqual([false])
      expect(wrapper.emitted('dismiss')).toHaveLength(1)
      expect(wrapper.exists()).toBe(true)
    })

    it('危险级公告使用 alert，正文仍为纯文本', async () => {
      const wrapper = mount(PhoenixAnnouncementBanner, { props: { tone: 'danger', description: '<b>系统维护</b>', actionLabel: '了解详情' } })
      expect(wrapper.attributes('role')).toBe('alert')
      expect(wrapper.find('b').exists()).toBe(false)
      await wrapper.findAll('button')[0].trigger('click')
      expect(wrapper.emitted('action')).toHaveLength(1)
    })
  })

  describe('PhoenixActivityFeed', () => {
    const item = { id: 1, actor: '系统', action: '更新了', target: '项目状态', description: '<b>已完成</b>', actionLabel: '查看' }

    it('动态正文为纯文本且危险头像回退为首字', () => {
      const wrapper = mount(PhoenixActivityFeed, { props: { items: [{ ...item, avatar: 'data:text/html,bad' }] } })
      expect(wrapper.text()).toContain('<b>已完成</b>')
      expect(wrapper.find('b b').exists()).toBe(false)
      expect(wrapper.find('img').exists()).toBe(false)
    })

    it('点击和键盘均可选择动态', async () => {
      const wrapper = mount(PhoenixActivityFeed, { props: { items: [item] } })
      await wrapper.get('article').trigger('click')
      await wrapper.get('article').trigger('keydown', { key: 'Enter' })
      expect(wrapper.emitted('select')).toHaveLength(2)
      expect(wrapper.emitted('select')?.[0]).toEqual([item, 0])
    })

    it('操作和加载更多不执行外部行为', async () => {
      const wrapper = mount(PhoenixActivityFeed, { props: { items: [item], hasMore: true } })
      await wrapper.get('.px-activity-feed__list > li > button').trigger('click')
      await wrapper.get('.px-content-footer button').trigger('click')
      expect(wrapper.emitted('action')?.[0]).toEqual([item])
      expect(wrapper.emitted('load-more')).toHaveLength(1)
    })

    it('区分空状态和加载状态', async () => {
      const wrapper = mount(PhoenixActivityFeed, { props: { items: [] } })
      expect(wrapper.get('[role="status"]').text()).toBe('暂无动态')
      await wrapper.setProps({ loading: true })
      expect(wrapper.get('[role="status"]').text()).toBe('动态加载中')
    })
  })

  describe('PhoenixCommentComposer', () => {
    it('输入按最大长度截断并发出受控更新', async () => {
      const wrapper = mount(PhoenixCommentComposer, { props: { modelValue: '', maxLength: 4 } })
      await wrapper.get('textarea').setValue('一二三四五')
      expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['一二三四'])
      expect(wrapper.text()).toContain('0/4')
    })

    it('点击与快捷键提交去除首尾空白的文本', async () => {
      const wrapper = mount(PhoenixCommentComposer, { props: { modelValue: '  有价值的评论  ' } })
      await wrapper.get('.px-content-primary').trigger('click')
      await wrapper.get('textarea').trigger('keydown', { key: 'Enter', ctrlKey: true })
      expect(wrapper.emitted('submit')).toEqual([['有价值的评论'], ['有价值的评论']])
    })

    it('空白、禁用和提交中状态阻止提交', async () => {
      const blank = mount(PhoenixCommentComposer, { props: { modelValue: '   ' } })
      expect(blank.get('.px-content-primary').attributes('disabled')).toBeDefined()
      const busy = mount(PhoenixCommentComposer, { props: { modelValue: '评论', submitting: true } })
      await busy.get('.px-content-primary').trigger('click')
      expect(busy.emitted('submit')).toBeUndefined()
      expect(busy.attributes('aria-busy')).toBe('true')
    })

    it('附件删除、请求添加和取消回复只发事件', async () => {
      const attachment = { id: 1, name: '说明.pdf', size: 1536 }
      const wrapper = mount(PhoenixCommentComposer, { props: { attachments: [attachment], replyTo: '王敏' } })
      expect(wrapper.text()).toContain('1.5 KB')
      await wrapper.get('[aria-label="移除附件：说明.pdf"]').trigger('click')
      await wrapper.findAll('footer button')[0].trigger('click')
      await wrapper.get('.px-comment-composer__reply button').trigger('click')
      expect(wrapper.emitted('remove-attachment')?.[0]).toEqual([attachment])
      expect(wrapper.emitted('request-attachment')).toHaveLength(1)
      expect(wrapper.emitted('cancel')).toHaveLength(1)
    })
  })

  describe('PhoenixMentionInput', () => {
    const suggestions = [
      { id: 1, label: '王敏', handle: 'wangmin', description: '产品经理' },
      { id: 2, label: '李华', handle: 'lihua', disabled: true },
    ]

    it('输入 @ 查询时发出搜索并展示匹配项', async () => {
      const wrapper = mount(PhoenixMentionInput, { props: { modelValue: '', suggestions } })
      await wrapper.get('textarea').setValue('请联系 @wang')
      expect(wrapper.emitted('search')?.[0]).toEqual(['wang'])
      await wrapper.setProps({ modelValue: '请联系 @wang' })
      expect(wrapper.text()).toContain('王敏')
      expect(wrapper.text()).not.toContain('李华')
    })

    it('选择建议插入 handle 并发出 mention', async () => {
      const wrapper = mount(PhoenixMentionInput, { props: { modelValue: '请联系 @wang', suggestions } })
      await wrapper.get('[role="option"] button').trigger('click')
      expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['请联系 @wangmin '])
      expect(wrapper.emitted('mention')?.[0]).toEqual([suggestions[0]])
    })

    it('方向键更新受控活动项，回车选择当前项', async () => {
      const wrapper = mount(PhoenixMentionInput, { props: { modelValue: '@', suggestions } })
      const input = wrapper.get('textarea')
      const listbox = wrapper.get('[role="listbox"]')
      const option = wrapper.get('[role="option"]')
      expect(input.attributes('role')).toBe('combobox')
      expect(input.attributes('aria-controls')).toBe(listbox.attributes('id'))
      expect(input.attributes('aria-activedescendant')).toBe(option.attributes('id'))
      expect(option.attributes('id')).toMatch(`${listbox.attributes('id')}-option-0`)
      expect(option.attributes('aria-selected')).toBe('true')
      await wrapper.get('textarea').trigger('keydown', { key: 'ArrowDown' })
      await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
      expect(wrapper.emitted('update:activeSuggestionId')?.[0]).toEqual([1])
      expect(wrapper.emitted('mention')?.[0]).toEqual([suggestions[0]])
    })

    it('禁用建议不可选择且 Escape 只请求关闭', async () => {
      const wrapper = mount(PhoenixMentionInput, { props: { modelValue: '@li', suggestions } })
      expect(wrapper.get('[role="option"] button').attributes('disabled')).toBeDefined()
      await wrapper.get('textarea').trigger('keydown', { key: 'Escape' })
      expect(wrapper.emitted('dismiss')).toHaveLength(1)
      expect(wrapper.emitted('mention')).toBeUndefined()
    })

    it('危险头像不渲染且无结果使用中文空状态', async () => {
      const wrapper = mount(PhoenixMentionInput, { props: { modelValue: '@王', suggestions: [{ id: 1, label: '王敏', avatar: 'javascript:bad()' }] } })
      expect(wrapper.find('img').exists()).toBe(false)
      await wrapper.setProps({ modelValue: '@不存在' })
      const input = wrapper.get('textarea')
      const listbox = wrapper.get('[role="listbox"]')
      expect(input.attributes('aria-expanded')).toBe('true')
      expect(input.attributes('aria-controls')).toBe(listbox.attributes('id'))
      expect(input.attributes('aria-activedescendant')).toBeUndefined()
      expect(wrapper.get('[role="status"]').text()).toBe('没有匹配的成员')
    })

    it('关闭建议时收起 combobox 且不同实例使用独立 listbox id', () => {
      const closed = mount(PhoenixMentionInput, { props: { modelValue: '', suggestions } })
      expect(closed.get('textarea').attributes('aria-expanded')).toBe('false')
      expect(closed.get('textarea').attributes('aria-controls')).toBeUndefined()
      expect(closed.find('[role="listbox"]').exists()).toBe(false)
      const pair = mount(defineComponent({
        setup: () => () => h('div', [
          h(PhoenixMentionInput, { modelValue: '@', suggestions }),
          h(PhoenixMentionInput, { modelValue: '@', suggestions }),
        ]),
      }))
      const listboxes = pair.findAll('[role="listbox"]')
      expect(listboxes[0].attributes('id')).not.toBe(listboxes[1].attributes('id'))
    })
  })

  describe('PhoenixMediaGallery', () => {
    const items = [
      { id: 1, kind: 'image' as const, title: '办公空间', thumbnail: '/images/office.webp' },
      { id: 2, kind: 'video' as const, title: '品牌短片', thumbnail: 'javascript:bad()', duration: '01:20' },
    ]

    it('仅白名单图片地址生成 img', () => {
      const wrapper = mount(PhoenixMediaGallery, { props: { items } })
      expect(wrapper.findAll('img')).toHaveLength(1)
      expect(wrapper.get('img').attributes('src')).toBe('/images/office.webp')
      expect(wrapper.text()).toContain('视频')
    })

    it('选择和双击预览分别发出事件', async () => {
      const wrapper = mount(PhoenixMediaGallery, { props: { items } })
      const cards = wrapper.findAll('.px-media-gallery__grid > li > button:first-child')
      await cards[0].trigger('click')
      await cards[0].trigger('dblclick')
      expect(wrapper.emitted('update:selectedId')?.[0]).toEqual([1])
      expect(wrapper.emitted('select')?.[0]).toEqual([items[0]])
      expect(wrapper.emitted('preview')?.[0]).toEqual([items[0]])
    })

    it('禁用媒体不会发出选择或预览', async () => {
      const disabled = { ...items[0], disabled: true }
      const wrapper = mount(PhoenixMediaGallery, { props: { items: [disabled] } })
      await wrapper.get('.px-media-gallery__grid > li > button:first-child').trigger('click')
      await wrapper.get('.px-media-gallery__preview').trigger('click')
      expect(wrapper.emitted('select')).toBeUndefined()
      expect(wrapper.emitted('preview')).toBeUndefined()
    })

    it('列数收敛在安全范围且空状态为中文', async () => {
      const wrapper = mount(PhoenixMediaGallery, { props: { items, columns: 99 } })
      expect(wrapper.get('.px-media-gallery__grid').attributes('style')).toContain('--px-gallery-columns: 6')
      await wrapper.setProps({ items: [] })
      expect(wrapper.get('[role="status"]').text()).toBe('暂无媒体内容')
    })
  })

  describe('PhoenixImagePreview', () => {
    it('关闭时不渲染，打开后拒绝脚本协议', async () => {
      const wrapper = mount(PhoenixImagePreview, { props: { open: false, src: 'javascript:alert(1)' } })
      expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
      await wrapper.setProps({ open: true })
      expect(wrapper.find('img').exists()).toBe(false)
      expect(wrapper.text()).toContain('图片地址不可用')
    })

    it('关闭按钮发出受控开关和 close 事件', async () => {
      const wrapper = mount(PhoenixImagePreview, { props: { open: true, src: '/photo.jpg' } })
      await wrapper.get('[aria-label="关闭图片预览"]').trigger('click')
      expect(wrapper.emitted('update:open')?.[0]).toEqual([false])
      expect(wrapper.emitted('close')).toHaveLength(1)
      expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    })

    it('缩放值被限制并以事件更新', async () => {
      const wrapper = mount(PhoenixImagePreview, { props: { open: true, src: '/photo.jpg', scale: 9 } })
      expect(wrapper.get('[aria-label="当前缩放比例"]').text()).toBe('400%')
      expect(wrapper.get('[aria-label="放大图片"]').attributes('disabled')).toBeDefined()
      await wrapper.get('[aria-label="缩小图片"]').trigger('click')
      expect(wrapper.emitted('update:scale')?.[0]).toEqual([3.75])
    })

    it('导航和下载只发事件且不创建链接', async () => {
      const wrapper = mount(PhoenixImagePreview, { props: { open: true, src: 'https://cdn.example.com/photo.jpg', hasPrevious: true, hasNext: true, downloadable: true } })
      const footerButtons = wrapper.findAll('footer button')
      await footerButtons[0].trigger('click')
      await footerButtons[1].trigger('click')
      await footerButtons.at(-1)!.trigger('click')
      expect(wrapper.emitted('previous')).toHaveLength(1)
      expect(wrapper.emitted('next')).toHaveLength(1)
      expect(wrapper.emitted('download')?.[0]).toEqual(['https://cdn.example.com/photo.jpg'])
      expect(wrapper.find('a').exists()).toBe(false)
    })

    it('打开时接管焦点并让 Tab 在首尾控件间循环', async () => {
      const trigger = document.createElement('button')
      document.body.append(trigger)
      trigger.focus()
      const wrapper = mount(PhoenixImagePreview, { attachTo: document.body, props: { open: false, src: '/photo.jpg' } })
      trigger.focus()
      await wrapper.setProps({ open: true })
      await wrapper.vm.$nextTick()
      expect(document.activeElement?.getAttribute('aria-label')).toBe('关闭图片预览')
      const buttons = wrapper.findAll('button')
      const first = buttons[0].element as HTMLButtonElement
      const last = buttons.at(-1)!.element as HTMLButtonElement
      trigger.focus()
      trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }))
      expect(document.activeElement).toBe(first)
      last.focus()
      last.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }))
      expect(document.activeElement).toBe(first)
      first.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }))
      expect(document.activeElement).toBe(last)
      wrapper.unmount()
      trigger.remove()
    })

    it('焦点在模态框外时 Escape 仍请求关闭并在受控关闭后恢复焦点', async () => {
      const trigger = document.createElement('button')
      document.body.append(trigger)
      trigger.focus()
      const wrapper = mount(PhoenixImagePreview, { attachTo: document.body, props: { open: false, src: '/photo.jpg' } })
      trigger.focus()
      await wrapper.setProps({ open: true })
      await wrapper.vm.$nextTick()
      trigger.focus()
      trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
      expect(wrapper.emitted('close')).toHaveLength(1)
      await wrapper.setProps({ open: false })
      await wrapper.vm.$nextTick()
      expect(document.activeElement).toBe(trigger)
      wrapper.unmount()
      trigger.remove()
    })
  })

  describe('PhoenixDocumentPreview', () => {
    const document = { id: 1, name: '项目说明.pdf', size: 2048, mimeType: 'application/pdf', pages: 12, owner: '产品组', url: '/docs/readme.pdf' }

    it('只展示文档元信息，不解析或执行文档', () => {
      const wrapper = mount(PhoenixDocumentPreview, { props: { document } })
      expect(wrapper.text()).toContain('项目说明.pdf')
      expect(wrapper.text()).toContain('2.0 KB')
      expect(wrapper.text()).toContain('12 页')
      expect(wrapper.find('iframe').exists()).toBe(false)
      expect(wrapper.find('object').exists()).toBe(false)
      expect(wrapper.find('embed').exists()).toBe(false)
    })

    it('打开和下载发出文档与安全地址', async () => {
      const wrapper = mount(PhoenixDocumentPreview, { props: { document, downloadable: true } })
      await wrapper.findAll('.px-content-actions button')[0].trigger('click')
      await wrapper.findAll('.px-content-actions button')[1].trigger('click')
      expect(wrapper.emitted('open')?.[0]).toEqual([document, '/docs/readme.pdf'])
      expect(wrapper.emitted('download')?.[0]).toEqual([document, '/docs/readme.pdf'])
      expect(wrapper.find('a').exists()).toBe(false)
    })

    it('脚本和 data 地址不会启用操作', () => {
      const wrapper = mount(PhoenixDocumentPreview, { props: { document: { ...document, url: 'data:text/html,<script>bad()</script>' }, downloadable: true } })
      expect(wrapper.findAll('.px-content-actions button').every((button) => button.attributes('disabled') !== undefined)).toBe(true)
      expect(wrapper.find('script').exists()).toBe(false)
    })

    it('区分空状态与加载状态', async () => {
      const wrapper = mount(PhoenixDocumentPreview)
      expect(wrapper.get('[role="status"]').text()).toBe('暂无文档信息')
      await wrapper.setProps({ loading: true })
      expect(wrapper.get('[role="status"]').text()).toBe('文档信息加载中')
    })
  })

  describe('PhoenixFileDropzone', () => {
    it('原生文件选择器透传 accept 和 multiple', () => {
      const wrapper = mount(PhoenixFileDropzone, { props: { accept: '.png,image/jpeg' } })
      const input = wrapper.get('input[type="file"]')
      expect(input.attributes('accept')).toBe('.png,image/jpeg')
      expect(input.attributes('multiple')).toBeDefined()
      expect(wrapper.text()).toContain('不会自动上传或读取文件内容')
    })

    it('合法文件只作为 File 对象发出，不读取内容', async () => {
      const wrapper = mount(PhoenixFileDropzone, { props: { accept: 'image/*' } })
      const file = new File(['pixels'], 'avatar.png', { type: 'image/png' })
      await chooseFiles(wrapper, [file])
      expect(wrapper.emitted('files-selected')?.[0]).toEqual([[file]])
      expect(wrapper.emitted('rejected')).toBeUndefined()
    })

    it('拒绝不匹配类型和超过大小的文件', async () => {
      const wrapper = mount(PhoenixFileDropzone, { props: { accept: '.png', maxSize: 3 } })
      const wrongType = new File(['a'], 'note.txt', { type: 'text/plain' })
      const tooLarge = new File(['1234'], 'large.png', { type: 'image/png' })
      await chooseFiles(wrapper, [wrongType, tooLarge])
      expect(wrapper.emitted('files-selected')).toBeUndefined()
      expect(wrapper.emitted('rejected')?.[0]?.[0]).toEqual([
        { file: wrongType, reason: 'type' },
        { file: tooLarge, reason: 'size' },
      ])
    })

    it('单选模式把额外文件标记为 count 拒绝', async () => {
      const wrapper = mount(PhoenixFileDropzone, { props: { multiple: false } })
      const first = new File(['1'], 'first.txt', { type: 'text/plain' })
      const second = new File(['2'], 'second.txt', { type: 'text/plain' })
      await chooseFiles(wrapper, [first, second])
      expect(wrapper.emitted('files-selected')?.[0]).toEqual([[first]])
      expect(wrapper.emitted('rejected')?.[0]?.[0]).toEqual([{ file: second, reason: 'count' }])
    })

    it('拖放只更新即时拖拽状态并发出选择事件', async () => {
      const wrapper = mount(PhoenixFileDropzone)
      const file = new File(['1'], 'drop.txt', { type: 'text/plain' })
      await wrapper.trigger('dragenter')
      expect(wrapper.classes()).toContain('is-dragging')
      await wrapper.trigger('drop', { dataTransfer: { files: [file] } })
      expect(wrapper.classes()).not.toContain('is-dragging')
      expect(wrapper.emitted('drag-state')).toEqual([[true], [false]])
      expect(wrapper.emitted('files-selected')?.[0]).toEqual([[file]])
    })

    it('禁用状态忽略文件选择', async () => {
      const wrapper = mount(PhoenixFileDropzone, { props: { disabled: true } })
      const file = new File(['1'], 'ignored.txt', { type: 'text/plain' })
      await chooseFiles(wrapper, [file])
      expect(wrapper.emitted('files-selected')).toBeUndefined()
      expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    })
  })
})
