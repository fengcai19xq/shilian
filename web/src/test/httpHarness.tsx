// 测试工具：用真实 http 客户端渲染工作台，fetch 由按路径分发的 mock 替代
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import App from '../App'
import { ApiContext, createHttpApi } from '../api'
import type { Principal } from '../api/types'

export const PRINCIPAL: Principal = {
  user_id: 'u_10231',
  dept: ['融资部', '财务共享'],
  roles: ['financing_staff'],
  max_sensitivity: 'L3',
  projects: ['PRJ-2026-CITIC'],
}

export const ROUTE_PATH = '/api/decide/route'
export const SEARCH_PATH = '/api/retrieval/search'
export const INVOKE_PATH = '/api/gateway/v1/invoke'

type Reply = unknown | { status: number; body?: unknown }
export type Handlers = Partial<Record<string, Reply | ((body: unknown) => Reply)>>

export interface RecordedCall {
  path: string
  body: Record<string, unknown>
  headers: Record<string, string>
}

export function routeReply(decision: 'enterprise' | 'general' | 'kb_empty', scope_desc?: string) {
  return {
    decision,
    reason: '测试',
    confirm_required: decision === 'kb_empty',
    allow_general_fallback: decision === 'general',
    evidence: {
      source: decision === 'general' ? 'decide' : 'probe',
      ...(decision === 'general' ? {} : { matched_entities: [], scope_desc: scope_desc ?? null }),
    },
  }
}

export function gatewayReply(text: string, extra: Record<string, unknown> = {}) {
  return {
    model: 'deepseek-v3-enterprise',
    switched_reason: null,
    usage: { prompt_tokens: 10, completion_tokens: 5, cost_cny: 0.01 },
    text,
    ...extra,
  }
}

function isStatusReply(value: unknown): value is { status: number; body?: unknown } {
  return typeof value === 'object' && value !== null && 'status' in value && typeof (value as { status: unknown }).status === 'number'
}

export function mockFetch(handlers: Handlers) {
  const calls: RecordedCall[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {}
    calls.push({ path, body, headers: { ...(init?.headers as Record<string, string>) } })
    const handler = handlers[path]
    if (handler === undefined) return new Response('not found', { status: 404 })
    const reply = typeof handler === 'function' ? (handler as (b: unknown) => Reply)(body) : handler
    if (isStatusReply(reply)) {
      return new Response(JSON.stringify(reply.body ?? {}), { status: reply.status })
    }
    return new Response(JSON.stringify(reply), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  const callsTo = (path: string) => calls.filter((c) => c.path === path)
  return { fetchMock, calls, callsTo }
}

export function renderHttpApp(handlers: Handlers) {
  const fetchState = mockFetch(handlers)
  const api = createHttpApi({ principal: PRINCIPAL, userId: PRINCIPAL.user_id })
  render(
    <ApiContext.Provider value={api}>
      <App />
    </ApiContext.Provider>,
  )
  return { ...fetchState, user: userEvent.setup() }
}

export async function askQuestion(user: ReturnType<typeof userEvent.setup>, text: string) {
  await screen.findByText(PRINCIPAL.user_id)
  await user.type(screen.getByLabelText('输入问题'), text)
  await user.click(screen.getByRole('button', { name: '发送' }))
}
