// http 客户端：路径、X-User-Id 头、网关正文归一化、错误处理
import { ApiHttpError, createHttpApi } from '../api/http'
import { INVOKE_PATH, PRINCIPAL, ROUTE_PATH, SEARCH_PATH, gatewayReply, mockFetch } from '../test/httpHarness'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('http 客户端', () => {
  it('三个接口分别打到 /api 前缀下的契约路径，并带 X-User-Id', async () => {
    const { calls } = mockFetch({
      [ROUTE_PATH]: { decision: 'general', reason: '', confirm_required: false, allow_general_fallback: true, evidence: { source: 'decide' } },
      [SEARCH_PATH]: { hits: [], scope_desc: 'x', total: 0 },
      [INVOKE_PATH]: gatewayReply('ok'),
    })
    const api = createHttpApi({ userId: 'u_1' })
    await api.route({ question: 'q', kb_scope: ['kb'] })
    await api.search({ query: 'q', kb_scope: ['kb'], mode: 'full', principal: PRINCIPAL })
    await api.generate({ purpose: 'generate', sensitivity: 'L1', preferred_model: 'm', payload: { query: 'q' } })
    expect(calls.map((c) => c.path)).toEqual([ROUTE_PATH, SEARCH_PATH, INVOKE_PATH])
    expect(calls[0].body).toMatchObject({ question: 'q', user_choice: 'auto' })
    expect(calls[0].headers['X-User-Id']).toBe('u_1')
    // 请求体带 principal 时以其 user_id 为准
    expect(calls[1].headers['X-User-Id']).toBe('u_10231')
    expect(calls[2].headers['Content-Type']).toBe('application/json')
  })

  it('网关正文兼容 payload.text / payload.content，可选字段 null 归一为 undefined', async () => {
    mockFetch({
      [INVOKE_PATH]: { model: 'm', switched_reason: null, budget_alert: null, payload: { content: '正文' }, usage: { prompt_tokens: 1, completion_tokens: 2, cost_cny: 0.3 } },
    })
    const res = await createHttpApi().generate({ purpose: 'generate', sensitivity: 'L1', preferred_model: 'm', payload: { query: 'q' } })
    expect(res).toMatchObject({ model: 'm', text: '正文', usage: { cost_cny: 0.3 } })
    expect(res.switched_reason).toBeUndefined()
    expect(res.budget_alert).toBeUndefined()
  })

  it('支持自定义 baseUrl；非 2xx 抛出 ApiHttpError', async () => {
    const { calls } = mockFetch({ '/gw/retrieval/search': { status: 500 } })
    const api = createHttpApi({ baseUrl: '/gw/' })
    const err = await api.search({ query: 'q', kb_scope: [], mode: 'full' }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiHttpError)
    expect(err).toMatchObject({ service: 'retrieval', status: 500 })
    expect(calls[0].path).toBe('/gw/retrieval/search')
  })

  it('未配置会话主体时 getPrincipal 拒绝，不在前端伪造权限', async () => {
    await expect(createHttpApi().getPrincipal()).rejects.toThrow('未配置会话权限主体')
    await expect(createHttpApi({ principal: PRINCIPAL }).getPrincipal()).resolves.toEqual(PRINCIPAL)
  })
})
