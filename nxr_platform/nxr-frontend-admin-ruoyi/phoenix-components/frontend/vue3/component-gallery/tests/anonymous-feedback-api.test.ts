import { describe, expect, it, vi } from 'vitest'
import { AnonymousApiError, createAnonymousRecordClient } from '../src/feedback/api'
import { canSendEditCredential } from '../src/feedback/security'
import componentCatalog from '../src/data/components.json'

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('匿名反馈接口客户端', () => {
  const galleryVersion = componentCatalog.find((item) => item.id === 'frontend.vue3.component-gallery')!.version
  const requestPayload = { title: '新组件', description: '需求内容', capabilityArea: '通用能力', useCase: '真实项目', expectedOutcome: '可以复用', targetStacks: ['vue3'], priority: 'medium' as const, referenceUrl: null }
  const feedbackPayload = { componentId: 'frontend.vue3.component-gallery', componentVersion: galleryVersion, title: '使用反馈', useCase: '真实项目', problem: '不易操作', impact: '交付变慢', keyImprovement: '简化流程', acceptanceCriteria: '三步内完成' }

  it('需求提交使用同源相对路径与幂等键', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ record: { id: 'REQ-1', status: 'proposed' }, editToken: 'token' }, 201))
    await createAnonymousRecordClient(fetchClient).createRequest(requestPayload, 'request-key')
    expect(fetchClient).toHaveBeenCalledWith('/api/requests', expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'Idempotency-Key': 'request-key' }) }))
  })

  it('公开读取需求不发送编辑凭证', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ records: [], total: 0 }))
    await createAnonymousRecordClient(fetchClient).listRequests()
    expect(fetchClient).toHaveBeenCalledWith('/api/requests', { method: 'GET', headers: { Accept: 'application/json' } })
    expect(JSON.stringify(fetchClient.mock.calls[0])).not.toContain('X-Edit-Token')
  })

  it('需求更新用路径编码和编辑凭证 header', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ record: { id: 'REQ-1' } }))
    await createAnonymousRecordClient(fetchClient).updateRequest('REQ /1', 'secret', requestPayload)
    expect(fetchClient).toHaveBeenCalledWith('/api/requests/REQ%20%2F1', expect.objectContaining({ method: 'PATCH', headers: expect.objectContaining({ 'X-Edit-Token': 'secret' }) }))
  })

  it('需求读取不在 URL 中传凭证', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ record: { id: 'REQ-1' } }))
    await createAnonymousRecordClient(fetchClient).loadRequest('REQ-1')
    const [url, init] = fetchClient.mock.calls[0]!
    expect(url).toBe('/api/requests/REQ-1')
    expect(url).not.toContain('private-token')
    expect(init.headers['X-Edit-Token']).toBeUndefined()
  })

  it('反馈提交保留关键改进字段', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ record: { id: 'FDB-1' }, editToken: 'token' }, 201))
    await createAnonymousRecordClient(fetchClient).createFeedback(feedbackPayload, 'feedback-key')
    const [, init] = fetchClient.mock.calls[0]!
    expect(JSON.parse(init.body)).toEqual(feedbackPayload)
  })

  it('反馈提交可以携带客户端生成的编辑凭证用于安全重放', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ record: { id: 'FDB-1' }, editToken: 'client-secret' }, 201))
    await createAnonymousRecordClient(fetchClient).createFeedback(feedbackPayload, 'feedback-key', 'client-secret')
    expect(fetchClient).toHaveBeenCalledWith('/api/feedbacks', expect.objectContaining({
      headers: expect.objectContaining({ 'Idempotency-Key': 'feedback-key', 'X-Edit-Token': 'client-secret' }),
    }))
  })

  it('公开读取反馈不发送编辑凭证', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ records: [], total: 0 }))
    await createAnonymousRecordClient(fetchClient).listFeedbacks()
    expect(fetchClient).toHaveBeenCalledWith('/api/feedbacks', { method: 'GET', headers: { Accept: 'application/json' } })
    expect(JSON.stringify(fetchClient.mock.calls[0])).not.toContain('X-Edit-Token')
  })

  it('反馈更新使用 PATCH 和凭证 header', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ record: { id: 'FDB-1' } }))
    await createAnonymousRecordClient(fetchClient).updateFeedback('FDB-1', 'secret', feedbackPayload)
    expect(fetchClient).toHaveBeenCalledWith('/api/feedbacks/FDB-1', expect.objectContaining({ method: 'PATCH', headers: expect.objectContaining({ 'X-Edit-Token': 'secret' }) }))
  })

  it('反馈读取使用 GET', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ record: { id: 'FDB-1' } }))
    await createAnonymousRecordClient(fetchClient).loadFeedback('FDB-1')
    expect(fetchClient).toHaveBeenCalledWith('/api/feedbacks/FDB-1', expect.objectContaining({ method: 'GET' }))
  })

  it('透传服务端中文错误', async () => {
    const fetchClient = vi.fn().mockResolvedValue(response({ error: { code: 'invalid_token', message: '编辑凭证无效' } }, 403))
    await expect(createAnonymousRecordClient(fetchClient).loadFeedback('FDB-1')).rejects.toMatchObject({ message: '编辑凭证无效', code: 'invalid_token', status: 403 })
  })

  it('网络失败返回统一可读错误', async () => {
    const fetchClient = vi.fn().mockRejectedValue(new TypeError('network'))
    await expect(createAnonymousRecordClient(fetchClient).createFeedback(feedbackPayload, 'key')).rejects.toBeInstanceOf(AnonymousApiError)
    await expect(createAnonymousRecordClient(fetchClient).createFeedback(feedbackPayload, 'key')).rejects.toThrow('静态组件清单仍可正常浏览')
  })

  it('拒绝无法识别的成功响应', async () => {
    const fetchClient = vi.fn().mockResolvedValue(new Response('not-json', { status: 200 }))
    await expect(createAnonymousRecordClient(fetchClient).loadRequest('REQ-1')).rejects.toMatchObject({ code: 'invalid_response' })
  })

  it('HTTPS 和本地开发允许发送凭证', () => {
    expect(canSendEditCredential({ hostname: 'gallery.example.com' } as Location, true)).toBe(true)
    expect(canSendEditCredential({ hostname: 'localhost' } as Location, false)).toBe(true)
    expect(canSendEditCredential({ hostname: '127.0.0.1' } as Location, false)).toBe(true)
  })

  it('公网 HTTP 禁止发送凭证', () => {
    expect(canSendEditCredential({ hostname: '45.196.97.35' } as Location, false)).toBe(false)
  })
})
