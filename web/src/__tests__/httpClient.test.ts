// http 客户端：路径、权限字段剥离、网关正文归一化、错误处理
import { ApiHttpError, createHttpApi } from '../api/http'
import { INVOKE_PATH, PRINCIPAL, ROUTE_PATH, SEARCH_PATH, gatewayReply, mockFetch } from '../test/httpHarness'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('http 客户端', () => {
  it('三个接口分别打到 /api 前缀下的契约路径，且不发送 principal / X-User-Id', async () => {
    const { calls } = mockFetch({
      [ROUTE_PATH]: { decision: 'general', reason: '', confirm_required: false, allow_general_fallback: true, evidence: { source: 'decide' } },
      [SEARCH_PATH]: { hits: [], scope_desc: 'x', total: 0 },
      [INVOKE_PATH]: gatewayReply('ok'),
    })
    const api = createHttpApi()
    await api.route({ question: 'q', kb_scope: ['kb'], principal: PRINCIPAL })
    await api.search({ query: 'q', kb_scope: ['kb'], mode: 'full', principal: PRINCIPAL })
    await api.generate({ purpose: 'generate', sensitivity: 'L1', preferred_model: 'm', principal: PRINCIPAL, payload: { query: 'q' } })
    expect(calls.map((c) => c.path)).toEqual([ROUTE_PATH, SEARCH_PATH, INVOKE_PATH])
    expect(calls[0].body).toMatchObject({ question: 'q', user_choice: 'auto' })
    for (const call of calls) {
      expect(call.body).not.toHaveProperty('principal')
      expect(call.headers['X-User-Id']).toBeUndefined()
    }
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

  /**
   * 回归防护：后端 InvokeResponse 的真实形状是正文在 output.text（顶层 text 为契约后补）。
   * 此前前端只认顶层 text 与 payload.*，线上会静默显示空答案——而 mock 用的是顶层 text，
   * 所以前端测试全绿却仍漏掉真实缺陷。两种形状都必须取到正文。
   */
  it('后端只给 output.text 时也能取到正文（真实 InvokeResponse 形状）', async () => {
    mockFetch({
      [INVOKE_PATH]: { model: 'deepseek-v3-enterprise', channel: 'enterprise_api', switched_reason: null, budget_alert: null, output: { text: '第三步是贷审会表决' }, usage: { prompt_tokens: 58, completion_tokens: 17, cost_cny: 0.000436 } },
    })
    const res = await createHttpApi().generate({ purpose: 'generate', sensitivity: 'L2', preferred_model: 'deepseek-v3-enterprise', payload: { query: 'q' } })
    expect(res.text).toBe('第三步是贷审会表决')
  })

  it('顶层 text 优先于 output.text，两者一致时不重复取值', async () => {
    mockFetch({
      [INVOKE_PATH]: { model: 'm', output: { text: '旧位置' }, text: '新位置', usage: {} },
    })
    const res = await createHttpApi().generate({ purpose: 'generate', sensitivity: 'L1', preferred_model: 'm', payload: { query: 'q' } })
    expect(res.text).toBe('新位置')
  })

  it('支持自定义 baseUrl；非 2xx 抛出 ApiHttpError', async () => {
    const { calls } = mockFetch({ '/gw/retrieval/search': { status: 500 } })
    const api = createHttpApi({ baseUrl: '/gw/' })
    const err = await api.search({ query: 'q', kb_scope: [], mode: 'full' }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiHttpError)
    expect(err).toMatchObject({ service: 'retrieval', status: 500 })
    expect(calls[0].path).toBe('/gw/retrieval/search')
  })

  it('未注入会话主体时向 /identity/me 取身份，不在前端伪造权限', async () => {
    const { calls } = mockFetch({ '/api/identity/me': PRINCIPAL })
    await expect(createHttpApi().getPrincipal()).resolves.toEqual(PRINCIPAL)
    expect(calls[0].path).toBe('/api/identity/me')
    // GET：不携带请求体，也不由前端自报主体
    expect(calls[0].body).toEqual({})
    expect(calls[0].headers['Content-Type']).toBeUndefined()
  })

  it('身份端只由会话决定，不发送自报身份头', async () => {
    const { calls } = mockFetch({ '/api/identity/me': PRINCIPAL })
    await createHttpApi().getPrincipal()
    expect(calls[0].headers['X-User-Id']).toBeUndefined()
    expect(calls[0].credentials).toBe('include')
  })

  it('构建期注入主体时直接用，不打网络（本地联调免起后端）', async () => {
    const { calls } = mockFetch({})
    await expect(createHttpApi({ principal: PRINCIPAL }).getPrincipal()).resolves.toEqual(PRINCIPAL)
    expect(calls).toHaveLength(0)
  })

  it('会话失效按状态码抛出，前端据此提示重新登录', async () => {
    mockFetch({ '/api/identity/me': { status: 401 } })
    const err = await createHttpApi().getPrincipal().catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiHttpError)
    expect(err).toMatchObject({ service: 'identity', status: 401 })
  })
})

describe('matcher HTTP 映射', () => {
  it('任务 + 清单映射为前端三态卡片，保留候选与原文', async () => {
    mockFetch({
      '/api/matcher/tasks/tk_1': {
        task: {
          task_id: 'tk_1',
          checklist_id: 'cl_1',
          state: 'succeeded',
          total: 1,
          done: 1,
          results: [
            {
              item_id: 'it_1',
              no: '3.1',
              std_type: 'AUDIT_REPORT',
              std_name: '审计报告',
              status: 'pending',
              score: 0.7,
              candidates: [
                { doc_id: 'd_1', doc_name: '审计报告.pdf', rejected: false, final_score: 0.7 },
                { doc_id: 'd_bad', doc_name: '错误.pdf', rejected: true, final_score: 0 },
              ],
              bound_doc_ids: [],
              note: '',
            },
          ],
        },
        progress: 1,
      },
      '/api/matcher/checklists/cl_1': {
        checklist_id: 'cl_1',
        title: '测试清单',
        items: [
          {
            item_id: 'it_1',
            checklist_id: 'cl_1',
            no: '3.1',
            raw_text: '最近三年审计报告',
            std_type: 'AUDIT_REPORT',
            constraints: { period: ['2023'] },
          },
        ],
      },
    })
    const task = await createHttpApi().getMatcherTask('tk_1')
    expect(task).toMatchObject({ task_id: 'tk_1', checklist_id: 'cl_1', title: '测试清单', state: 'succeeded' })
    expect(task.items[0]).toMatchObject({
      item_id: 'it_1',
      raw_text: '最近三年审计报告',
      status: 'pending',
      candidate_doc_name: '审计报告.pdf',
      candidate_doc_ids: ['d_1'],
      final_score: 0.7,
    })
  })

  it('上传清单走 multipart，不手工设置 Content-Type', async () => {
    const { calls } = mockFetch({
      '/api/matcher/checklists': { checklist_id: 'cl_1', title: '测试清单', items: [] },
    })
    const file = new File(['x'], '清单.xlsx')
    const checklist = await createHttpApi().uploadChecklist(file)
    expect(checklist.checklist_id).toBe('cl_1')
    expect(calls[0].path).toBe('/api/matcher/checklists')
    expect(calls[0].headers['Content-Type']).toBeUndefined()
  })
})
