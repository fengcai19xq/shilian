// 对话主链路（http 客户端 + fetch mock）：decide 路由三态 → retrieval → gateway，严格对齐 contracts/
import { screen, within } from '@testing-library/react'
import { NO_SCOPE_DESC } from '../api/types'
import {
  INVOKE_PATH,
  PRINCIPAL,
  ROUTE_PATH,
  SEARCH_PATH,
  askQuestion,
  gatewayReply,
  renderHttpApp,
  routeReply,
} from '../test/httpHarness'

const SCOPE = '融资部 / 财务共享（你的权限内）'

const HITS = [
  {
    chunk_id: 'c_1',
    doc_id: 'd_1',
    doc_name: '2023年度审计报告.pdf',
    dept: '财务共享',
    sensitivity: 'L3',
    page: 37,
    score: 0.86,
    text: '2023 年度研发费用 1.82 亿元，占营业收入比例 6.4%。',
  },
  {
    chunk_id: 'c_2',
    doc_id: 'd_2',
    doc_name: '研发管理制度.docx',
    dept: '融资部',
    sensitivity: 'L2',
    page: 5,
    score: 0.74,
    text: '研发费用按项目归集，季度复核。',
  },
]

const SEARCH_HIT = { hits: HITS, scope_desc: SCOPE, total: 2 }
const SEARCH_EMPTY = { hits: [], scope_desc: SCOPE, total: 0 }
const SEARCH_NO_SCOPE = { hits: [], scope_desc: NO_SCOPE_DESC, total: 0 }

const ANSWER = '2023 年研发费用占营收比例为 6.4%[1]，按项目归集[2]。'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('路由三态', () => {
  it('enterprise：带 principal/X-User-Id 发 full 检索，片段进 prompt，再经网关生成', async () => {
    const { user, callsTo } = renderHttpApp({
      [ROUTE_PATH]: routeReply('enterprise'),
      [SEARCH_PATH]: SEARCH_HIT,
      [INVOKE_PATH]: gatewayReply(ANSWER),
    })
    await askQuestion(user, '最近三年研发费用占营收比例')
    const bubble = await screen.findByTestId('msg-kb-hit')
    expect(bubble).toHaveTextContent(`公司相关 · 检索范围：${SCOPE} · 命中 2 条`)
    expect(within(bubble).getByTestId('answer-text')).toHaveTextContent(
      '2023 年研发费用占营收比例为 6.4%[1]，按项目归集[2]。',
    )

    const [route] = callsTo(ROUTE_PATH)
    expect(route.body).toMatchObject({
      question: '最近三年研发费用占营收比例',
      principal: PRINCIPAL,
      user_choice: 'auto',
    })
    expect(route.headers['X-User-Id']).toBe('u_10231')

    const [search] = callsTo(SEARCH_PATH)
    expect(search.body).toMatchObject({
      query: '最近三年研发费用占营收比例',
      principal: PRINCIPAL,
      mode: 'full',
      top_k: 8,
      score_threshold: 0.35,
    })
    expect(search.headers['X-User-Id']).toBe('u_10231')

    const [invoke] = callsTo(INVOKE_PATH)
    expect(invoke.body).toMatchObject({
      purpose: 'generate',
      sensitivity: 'L3',
      preferred_model: 'deepseek-v3-enterprise',
      principal: { user_id: 'u_10231', dept: ['融资部', '财务共享'] },
    })
    const messages = (invoke.body.payload as { messages: { content: string }[] }).messages
    const prompt = messages.map((m) => m.content).join('\n')
    expect(prompt).toContain('[1] 《2023年度审计报告.pdf》第 37 页')
    expect(prompt).toContain('[2] 《研发管理制度.docx》第 5 页')
    expect(prompt).toContain('问题：最近三年研发费用占营收比例')
  })

  it('general：不检索，直接走网关，只带记账身份、不携带公司数据', async () => {
    const { user, callsTo } = renderHttpApp({
      [ROUTE_PATH]: routeReply('general'),
      [INVOKE_PATH]: gatewayReply('Python 列表推导式示例……', { model: 'deepseek-v3' }),
    })
    await askQuestion(user, '怎么写列表推导式')
    const general = await screen.findByTestId('msg-general')
    expect(general).toHaveTextContent('通用问题 · 模型：deepseek-v3 · 未检索企业知识库')
    expect(general).toHaveTextContent('Python 列表推导式示例……')
    expect(callsTo(SEARCH_PATH)).toHaveLength(0)
    const [invoke] = callsTo(INVOKE_PATH)
    expect(invoke.body).toMatchObject({ purpose: 'generate', sensitivity: 'L1', payload: { query: '怎么写列表推导式' } })
    expect(Object.keys(invoke.body.principal as object).sort()).toEqual(['dept', 'user_id'])
    expect(invoke.body.payload).not.toHaveProperty('messages')
  })

  it('kb_empty：如实告知「知识库中没有依据」，不检索、不调用网关', async () => {
    const { user, callsTo } = renderHttpApp({
      [ROUTE_PATH]: routeReply('kb_empty', SCOPE),
    })
    await askQuestion(user, '公司食堂菜单')
    const bubble = await screen.findByTestId('msg-no-hit')
    expect(bubble).toHaveTextContent('知识库中没有依据')
    expect(callsTo(SEARCH_PATH)).toHaveLength(0)
    expect(callsTo(INVOKE_PATH)).toHaveLength(0)
  })
})

describe('引用角标定位', () => {
  it('点击 [n] 后右栏定位到对应片段：文件名、页码、原文、密级', async () => {
    const { user } = renderHttpApp({
      [ROUTE_PATH]: routeReply('enterprise'),
      [SEARCH_PATH]: SEARCH_HIT,
      [INVOKE_PATH]: gatewayReply(ANSWER),
    })
    await askQuestion(user, '研发费用')
    const bubble = await screen.findByTestId('msg-kb-hit')
    const pane = screen.getByTestId('right-pane')
    expect(pane).toHaveTextContent('暂无引用来源')

    await user.click(within(bubble).getByRole('button', { name: '查看引用 2' }))
    const second = within(pane).getByTestId('source-c_2')
    expect(second).toHaveAttribute('data-active', 'true')
    expect(second).toHaveTextContent('[2] 研发管理制度.docx · 第 5 页 · 融资部 · L2')
    expect(second).toHaveTextContent('研发费用按项目归集，季度复核。')
    expect(within(pane).getByTestId('source-c_1')).toHaveAttribute('data-active', 'false')

    await user.click(within(bubble).getByRole('button', { name: '查看引用 1' }))
    const first = within(pane).getByTestId('source-c_1')
    expect(first).toHaveAttribute('data-active', 'true')
    expect(first).toHaveTextContent('2023年度审计报告.pdf · 第 37 页 · 财务共享 · L3')
    expect(within(pane).getByTestId('source-c_2')).toHaveAttribute('data-active', 'false')
  })

  it('超出片段范围的编号不生成角标，数字原样展示不在前端换算', async () => {
    const { user } = renderHttpApp({
      [ROUTE_PATH]: routeReply('enterprise'),
      [SEARCH_PATH]: SEARCH_HIT,
      [INVOKE_PATH]: gatewayReply('合计 1.82 亿元[1]，另见[9]。'),
    })
    await askQuestion(user, '研发费用')
    const bubble = await screen.findByTestId('msg-kb-hit')
    expect(within(bubble).getAllByRole('button', { name: /查看引用/ })).toHaveLength(1)
    expect(within(bubble).getByTestId('answer-text')).toHaveTextContent('合计 1.82 亿元[1]，另见[9]。')
  })
})

describe('switched_reason 提示', () => {
  it('企业知识回答：在回答上方显式提示切换原因、实际通道与模型', async () => {
    const { user } = renderHttpApp({
      [ROUTE_PATH]: routeReply('enterprise'),
      [SEARCH_PATH]: SEARCH_HIT,
      [INVOKE_PATH]: gatewayReply(ANSWER, {
        switched_reason: 'L3 内容不允许通用通道',
        channel: 'enterprise_api',
        budget_alert: '本部门本月模型预算已用 85%',
      }),
    })
    await askQuestion(user, '研发费用')
    const bubble = await screen.findByTestId('msg-kb-hit')
    const notice = within(bubble).getByTestId('switched-notice')
    expect(notice).toHaveTextContent(
      '已切换合规通道：L3 内容不允许通用通道 · 实际通道：企业版 API · 实际模型：deepseek-v3-enterprise',
    )
    const answer = within(bubble).getByTestId('answer-text')
    // 提示必须位于回答正文之上
    expect(notice.compareDocumentPosition(answer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(within(bubble).getByTestId('budget-alert')).toHaveTextContent('本部门本月模型预算已用 85%')
  })

  it('通用回答：同样显式提示；无 switched_reason 时不提示', async () => {
    const { user } = renderHttpApp({
      [ROUTE_PATH]: routeReply('general'),
      [INVOKE_PATH]: gatewayReply('示例', { model: 'hangli-local-14b', switched_reason: '月度预算已达 100%，降级到低成本模型', channel: 'vpc_self_hosted' }),
    })
    await askQuestion(user, '你好')
    const general = await screen.findByTestId('msg-general')
    expect(within(general).getByTestId('switched-notice')).toHaveTextContent(
      '已切换合规通道：月度预算已达 100%，降级到低成本模型 · 实际通道：VPC 自部署 · 实际模型：hangli-local-14b',
    )

    await askQuestion(user, '再来一个')
    const all = await screen.findAllByTestId('msg-general')
    expect(all).toHaveLength(2)
  })

  it('网关未返回 switched_reason 时不出现切换提示', async () => {
    const { user } = renderHttpApp({
      [ROUTE_PATH]: routeReply('enterprise'),
      [SEARCH_PATH]: SEARCH_HIT,
      [INVOKE_PATH]: gatewayReply(ANSWER),
    })
    await askQuestion(user, '研发费用')
    const bubble = await screen.findByTestId('msg-kb-hit')
    expect(within(bubble).queryByTestId('switched-notice')).not.toBeInTheDocument()
  })
})

describe('权限提示', () => {
  it('retrieval 返回「（无可检索范围）」：显示权限提示，不调用网关、不显示文档信息', async () => {
    const { user, callsTo } = renderHttpApp({
      [ROUTE_PATH]: routeReply('enterprise'),
      [SEARCH_PATH]: SEARCH_NO_SCOPE,
    })
    await askQuestion(user, '高管薪酬方案')
    const bubble = await screen.findByTestId('msg-permission-denied')
    expect(bubble).toHaveTextContent('无法作答')
    expect(bubble.textContent).not.toMatch(/\.pdf|\.docx|\.xlsx/)
    expect(screen.queryByTestId('msg-no-hit')).not.toBeInTheDocument()
    expect(callsTo(INVOKE_PATH)).toHaveLength(0)
  })

  it('decide evidence.scope_desc 为「（无可检索范围）」：直接权限提示，不再检索', async () => {
    const { user, callsTo } = renderHttpApp({
      [ROUTE_PATH]: routeReply('kb_empty', NO_SCOPE_DESC),
    })
    await askQuestion(user, '公司战略规划')
    await screen.findByTestId('msg-permission-denied')
    expect(screen.queryByTestId('msg-no-hit')).not.toBeInTheDocument()
    expect(callsTo(SEARCH_PATH)).toHaveLength(0)
    expect(callsTo(INVOKE_PATH)).toHaveLength(0)
  })
})

describe('未命中二次确认', () => {
  it('enterprise 但 total=0：提示没有依据，确认前不调用网关；确认后才走通用模型且不带公司数据', async () => {
    const { user, callsTo } = renderHttpApp({
      [ROUTE_PATH]: routeReply('enterprise'),
      [SEARCH_PATH]: SEARCH_EMPTY,
      [INVOKE_PATH]: gatewayReply('通用说明', { model: 'deepseek-v3' }),
    })
    await askQuestion(user, '公司 2019 年团建方案')
    const bubble = await screen.findByTestId('msg-no-hit')
    expect(bubble).toHaveTextContent('知识库中没有依据')
    expect(callsTo(INVOKE_PATH)).toHaveLength(0)

    await user.click(within(bubble).getByRole('button', { name: '改用通用模型回答' }))
    expect(callsTo(INVOKE_PATH)).toHaveLength(0)
    await user.click(within(bubble).getByRole('button', { name: '取消' }))
    expect(callsTo(INVOKE_PATH)).toHaveLength(0)

    await user.click(within(bubble).getByRole('button', { name: '改用通用模型回答' }))
    await user.click(within(bubble).getByRole('button', { name: '确认改用通用模型' }))
    const general = await screen.findByTestId('msg-general')
    expect(general).toHaveTextContent('通用说明')
    const invokes = callsTo(INVOKE_PATH)
    expect(invokes).toHaveLength(1)
    expect(invokes[0].body).toMatchObject({ sensitivity: 'L1', payload: { query: '公司 2019 年团建方案' } })
    expect(Object.keys(invokes[0].body.principal as object).sort()).toEqual(['dept', 'user_id'])
    expect(within(bubble).queryByRole('button', { name: /通用模型/ })).not.toBeInTheDocument()
  })

  it('kb_empty：确认后才发起 general 请求', async () => {
    const { user, callsTo } = renderHttpApp({
      [ROUTE_PATH]: routeReply('kb_empty', SCOPE),
      [INVOKE_PATH]: gatewayReply('通用说明', { model: 'deepseek-v3' }),
    })
    await askQuestion(user, '公司食堂菜单')
    const bubble = await screen.findByTestId('msg-no-hit')
    await user.click(within(bubble).getByRole('button', { name: '改用通用模型回答' }))
    expect(callsTo(INVOKE_PATH)).toHaveLength(0)
    await user.click(within(bubble).getByRole('button', { name: '确认改用通用模型' }))
    await screen.findByTestId('msg-general')
    expect(callsTo(INVOKE_PATH)).toHaveLength(1)
  })
})

describe('服务异常', () => {
  it('非 2xx 时显示服务名与状态码，不透传响应内容', async () => {
    const { user } = renderHttpApp({
      [ROUTE_PATH]: { status: 503, body: { detail: '内部：2024薪酬.xlsx 不可用' } },
    })
    await askQuestion(user, '研发费用')
    const bubble = await screen.findByTestId('msg-error')
    expect(bubble).toHaveTextContent('decide 服务返回 503，请稍后重试')
    expect(bubble.textContent).not.toContain('薪酬')
  })
})
