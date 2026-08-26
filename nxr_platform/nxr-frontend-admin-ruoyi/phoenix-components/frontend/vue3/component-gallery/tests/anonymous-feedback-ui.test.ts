import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import FeedbackShowcase from '../src/feedback/FeedbackShowcase.vue'
import RequestSubmissionPanel from '../src/requests/RequestSubmissionPanel.vue'
import componentCatalog from '../src/data/components.json'

const galleryVersion = componentCatalog.find((item) => item.id === 'frontend.vue3.component-gallery')!.version

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

async function fillRequest(wrapper: ReturnType<typeof mount>) {
  const inputs = wrapper.findAll('.px-contribution__form input')
  await inputs[0]!.setValue('通用筛选组件')
  await inputs[1]!.setValue('数据处理')
  await inputs[2]!.setValue('vue3，uni-app-x, vue3')
  const textareas = wrapper.findAll('.px-contribution__form textarea')
  await textareas[0]!.setValue('支持组合筛选能力')
  await textareas[1]!.setValue('多类资源页面需要一致筛选')
  await textareas[2]!.setValue('可以跨主题复用')
}

async function fillFeedback(wrapper: ReturnType<typeof mount>) {
  const inputs = wrapper.findAll('.px-contribution__form input')
  await inputs[0]!.setValue('frontend.vue3.component-gallery')
  await inputs[2]!.setValue('移动端操作不顺畅')
  const values = ['移动端管理资源', '按钮容易误触', '任务完成时间增加', '扩大点击区并保持键盘可用', '窄屏和键盘测试通过']
  const textareas = wrapper.findAll('.px-contribution__form textarea')
  for (const [index, value] of values.entries()) await textareas[index]!.setValue(value)
}

describe('匿名需求与反馈界面', () => {
  beforeEach(() => {
    Object.defineProperty(globalThis, 'isSecureContext', { value: true, configurable: true })
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } })
  })

  afterEach(() => vi.restoreAllMocks())

  it('需求表单包含通用能力字段且不按主题限制', () => {
    const wrapper = mount(RequestSubmissionPanel)
    for (const label of ['需求标题', '能力分类', '技术方向', '需求内容', '真实使用场景', '预期结果']) expect(wrapper.text()).toContain(label)
    expect(wrapper.text()).not.toMatch(/仅限|只支持教学|只支持商城/)
  })

  it('需求缺少必填项时不调用接口', async () => {
    const fetchClient = vi.fn()
    const wrapper = mount(RequestSubmissionPanel, { props: { fetchClient } })
    await wrapper.get('.px-contribution__form').trigger('submit')
    expect(fetchClient).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('必填')
  })

  it('需求提交去重技术方向并显示编号和一次性凭证', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ record: { id: 'REQ-101', status: 'proposed' }, editToken: 'request-secret' }, 201))
    const wrapper = mount(RequestSubmissionPanel, { props: { fetchClient } })
    await fillRequest(wrapper)
    await wrapper.get('.px-contribution__form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('REQ-101')
    expect(wrapper.text()).toContain('编辑凭证（仅显示这一次）')
    const payload = JSON.parse(fetchClient.mock.calls[0]![1].body)
    expect(payload.targetStacks).toEqual(['vue3', 'uni-app-x'])
  })

  it('需求提交中禁止重复提交并只发一次请求', async () => {
    let resolveRequest!: (value: Response) => void
    const fetchClient = vi.fn().mockReturnValue(new Promise<Response>((resolve) => { resolveRequest = resolve }))
    const wrapper = mount(RequestSubmissionPanel, { props: { fetchClient } })
    await fillRequest(wrapper)
    await wrapper.get('.px-contribution__form').trigger('submit')
    await wrapper.get('.px-contribution__form').trigger('submit')
    expect(fetchClient).toHaveBeenCalledTimes(1)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    resolveRequest(response({ record: { id: 'REQ-1', status: 'proposed' }, editToken: 'token' }, 201))
    await flushPromises()
  })

  it('需求凭证可以复制但不会写入浏览器存储', async () => {
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem')
    const fetchClient = vi.fn().mockResolvedValue(response({ record: { id: 'REQ-1', status: 'proposed' }, editToken: 'secret' }, 201))
    const wrapper = mount(RequestSubmissionPanel, { props: { fetchClient } })
    await fillRequest(wrapper)
    await wrapper.get('.px-contribution__form').trigger('submit')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('复制'))!.trigger('click')
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('REQ-1\nsecret')
    expect(storageSpy).not.toHaveBeenCalled()
  })

  it('重复响应没有凭证时明确提示不可恢复', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ record: { id: 'REQ-1', status: 'proposed' }, replayed: true }))
    const wrapper = mount(RequestSubmissionPanel, { props: { fetchClient } })
    await fillRequest(wrapper)
    await wrapper.get('.px-contribution__form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('只在首次成功响应中返回')
  })

  it('需求可用编号和凭证加载且状态只读', async () => {
    const record = { id: 'REQ-1', title: '原需求', description: '内容', capabilityArea: '通用', useCase: '场景', expectedOutcome: '结果', targetStacks: ['vue3'], priority: 'medium', referenceUrl: null, status: 'accepted' }
    const fetchClient = vi.fn().mockResolvedValue(response({ record }))
    const wrapper = mount(RequestSubmissionPanel, { props: { fetchClient } })
    await wrapper.get('summary').trigger('click')
    const credentialInputs = wrapper.findAll('.px-credential-lookup input')
    await credentialInputs[0]!.setValue('REQ-1')
    await credentialInputs[1]!.setValue('secret')
    await wrapper.get('.px-credential-lookup').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('当前状态：accepted')
    expect(wrapper.find('select[name="status"]').exists()).toBe(false)
  })

  it('需求加载后可以 PATCH 修改', async () => {
    const record = { id: 'REQ-1', title: '原需求', description: '内容', capabilityArea: '通用', useCase: '场景', expectedOutcome: '结果', targetStacks: ['vue3'], priority: 'medium', referenceUrl: null, status: 'accepted' }
    const fetchClient = vi.fn().mockResolvedValueOnce(response({ record })).mockResolvedValueOnce(response({ record: { ...record, title: '更新需求' } }))
    const wrapper = mount(RequestSubmissionPanel, { props: { fetchClient } })
    const credentialInputs = wrapper.findAll('.px-credential-lookup input')
    await credentialInputs[0]!.setValue('REQ-1')
    await credentialInputs[1]!.setValue('secret')
    await wrapper.get('.px-credential-lookup').trigger('submit')
    await flushPromises()
    const editForm = wrapper.findAll('.px-contribution__form').at(-1)!
    await editForm.findAll('input')[0]!.setValue('更新需求')
    await editForm.trigger('submit')
    await flushPromises()
    expect(fetchClient).toHaveBeenLastCalledWith('/api/requests/REQ-1', expect.objectContaining({ method: 'PATCH' }))
    expect(wrapper.text()).toContain('需求已更新')
  })

  it('需求接口不可用时显示错误且组件可继续渲染', async () => {
    const fetchClient = vi.fn().mockRejectedValue(new TypeError('network'))
    const wrapper = mount(RequestSubmissionPanel, { props: { fetchClient } })
    await fillRequest(wrapper)
    await wrapper.get('.px-contribution__form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('静态组件清单仍可正常浏览')
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('反馈页展示七个关键字段和组件候选', () => {
    const wrapper = mount(FeedbackShowcase, { props: { componentIds: ['frontend.vue3.demo'] } })
    for (const label of ['组件 ID', '组件版本', '反馈标题', '真实使用场景', '遇到的问题', '造成的影响', '关键改进', '验收标准']) expect(wrapper.text()).toContain(label)
    expect(wrapper.get('datalist option').attributes('value')).toBe('frontend.vue3.demo')
  })

  it('反馈页公开展示接口中的最新记录', async () => {
    const record = { id: 'FDB-PUBLIC', componentId: 'frontend.vue3.demo', title: '公开反馈', useCase: '通用项目', problem: '问题', impact: '影响', keyImprovement: '关键改进内容', acceptanceCriteria: '验收通过', status: 'open' }
    const fetchClient = vi.fn().mockResolvedValue(response({ records: [record], total: 1 }))
    const wrapper = mount(FeedbackShowcase, { props: { fetchClient } })
    await flushPromises()
    expect(wrapper.get('[data-feedback-id="FDB-PUBLIC"]').text()).toContain('公开反馈')
    expect(wrapper.get('[data-feedback-id="FDB-PUBLIC"]').text()).toContain('关键改进内容')
  })

  it('反馈缺少必填项时阻止提交', async () => {
    const fetchClient = vi.fn()
    const wrapper = mount(FeedbackShowcase, { props: { fetchClient } })
    await wrapper.get('.px-contribution__form').trigger('submit')
    expect(fetchClient.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
    expect(wrapper.get('[role="alert"]').text()).toContain('必填')
  })

  it('反馈提交成功显示编号状态与一次性凭证', async () => {
    const fetchClient = vi.fn().mockResolvedValueOnce(response({ records: [] })).mockResolvedValueOnce(response({ record: { id: 'FDB-88', status: 'open' }, editToken: 'feedback-secret' }, 201))
    const wrapper = mount(FeedbackShowcase, { props: { fetchClient } })
    await fillFeedback(wrapper)
    await wrapper.get('.px-contribution__form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('FDB-88')
    expect(wrapper.text()).toContain('open')
    expect(wrapper.text()).toContain('仅显示这一次')
  })

  it('反馈提交时原样发送关键改进内容', async () => {
    const fetchClient = vi.fn().mockResolvedValueOnce(response({ records: [] })).mockResolvedValueOnce(response({ record: { id: 'FDB-1', status: 'open' }, editToken: 'token' }, 201))
    const wrapper = mount(FeedbackShowcase, { props: { fetchClient } })
    await fillFeedback(wrapper)
    await wrapper.get('.px-contribution__form').trigger('submit')
    await flushPromises()
    const postCall = fetchClient.mock.calls.find(([, init]) => init?.method === 'POST')!
    const payload = JSON.parse(postCall[1].body)
    expect(payload.componentVersion).toBe(galleryVersion)
    expect(payload.keyImprovement).toBe('扩大点击区并保持键盘可用')
    expect(payload.acceptanceCriteria).toBe('窄屏和键盘测试通过')
  })

  it('反馈组件 ID 不在目录中时不提交且不伪造版本', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ records: [] }))
    const wrapper = mount(FeedbackShowcase, { props: { fetchClient } })
    await fillFeedback(wrapper)
    await wrapper.findAll('.px-contribution__form input')[0]!.setValue('frontend.vue3.unknown')
    await wrapper.get('.px-contribution__form').trigger('submit')
    expect(fetchClient.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
    expect(wrapper.get('[role="alert"]').text()).toContain('必填')
  })

  it('反馈可以加载和更新但不能更改状态', async () => {
    const record = { id: 'FDB-1', componentId: 'frontend.vue3.demo', title: '反馈', useCase: '场景', problem: '问题', impact: '影响', keyImprovement: '改进', acceptanceCriteria: '验收', status: 'reviewing' }
    const fetchClient = vi.fn().mockResolvedValueOnce(response({ records: [] })).mockResolvedValueOnce(response({ record })).mockResolvedValueOnce(response({ record: { ...record, title: '新反馈' } }))
    const wrapper = mount(FeedbackShowcase, { props: { fetchClient } })
    const inputs = wrapper.findAll('.px-credential-lookup input')
    await inputs[0]!.setValue('FDB-1')
    await inputs[1]!.setValue('secret')
    await wrapper.get('.px-credential-lookup').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('当前状态：reviewing')
    expect(wrapper.find('select[name="status"]').exists()).toBe(false)
    const editForm = wrapper.findAll('.px-contribution__form').at(-1)!
    await editForm.findAll('input')[2]!.setValue('新反馈')
    await editForm.trigger('submit')
    await flushPromises()
    expect(fetchClient).toHaveBeenLastCalledWith('/api/feedbacks/FDB-1', expect.objectContaining({ method: 'PATCH' }))
  })

  it('反馈接口异常显示中文错误', async () => {
    const fetchClient = vi.fn().mockResolvedValueOnce(response({ records: [] })).mockResolvedValueOnce(response({ error: { code: 'busy', message: '服务繁忙，请稍后再试' } }, 503))
    const wrapper = mount(FeedbackShowcase, { props: { fetchClient } })
    await fillFeedback(wrapper)
    await wrapper.get('.px-contribution__form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe('服务繁忙，请稍后再试')
  })

  it('非安全上下文下仍允许本地开发读取', async () => {
    Object.defineProperty(globalThis, 'isSecureContext', { value: false, configurable: true })
    const record = { id: 'FDB-1', componentId: 'frontend.vue3.demo', title: '反馈', useCase: '场景', problem: '问题', impact: '影响', keyImprovement: '改进', acceptanceCriteria: '验收', status: 'open' }
    const fetchClient = vi.fn().mockResolvedValueOnce(response({ records: [] })).mockResolvedValueOnce(response({ record }))
    const wrapper = mount(FeedbackShowcase, { props: { fetchClient } })
    const inputs = wrapper.findAll('.px-credential-lookup input')
    await inputs[0]!.setValue('FDB-1')
    await inputs[1]!.setValue('secret')
    await wrapper.get('.px-credential-lookup').trigger('submit')
    await flushPromises()
    expect(fetchClient).toHaveBeenCalledTimes(2)
  })

  it('输入控件有显式中文标签且凭证使用 password', () => {
    const wrapper = mount(FeedbackShowcase)
    expect(wrapper.findAll('label').length).toBeGreaterThanOrEqual(9)
    expect(wrapper.find('.px-credential-lookup input[type="password"]').exists()).toBe(true)
    expect(wrapper.get('.px-contribution__form button[type="submit"]').text()).toContain('匿名提交')
  })
})
