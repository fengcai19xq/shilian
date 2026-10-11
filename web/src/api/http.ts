// 真实 HTTP 客户端：与 mock 实现同一个 WorkbenchApi 接口。
// 路径统一挂在 /api 前缀下，由 Vite dev proxy（或部署时的反向代理）转发到三个服务：
//   /api/decide/route      → {DECIDE_URL}/decide/route
//   /api/retrieval/search  → {RETRIEVAL_URL}/retrieval/search
//   /api/gateway/v1/invoke → {GATEWAY_URL}/v1/invoke
import type { WorkbenchApi } from './mock'
import { MODEL_CATALOG } from './models'
import type {
  GatewayRequest,
  GatewayResponse,
  MatcherTask,
  Principal,
  RouteRequest,
  RouteResponse,
  SearchRequest,
  SearchResponse,
} from './types'

export type ServiceName = 'decide' | 'retrieval' | 'gateway'

/** 服务返回非 2xx。只保留服务名与状态码，不透传响应体，避免把后端信息带到界面上 */
export class ApiHttpError extends Error {
  constructor(
    readonly service: ServiceName,
    readonly status: number,
  ) {
    super(`${service} 服务返回 ${status}`)
    this.name = 'ApiHttpError'
  }
}

export interface HttpApiOptions {
  /** 接口前缀，默认 /api */
  baseUrl?: string
  /** 会话用户 ID，作为 X-User-Id 头；请求体带 principal 时以 principal.user_id 为准 */
  userId?: string
  /** 会话态权限主体（仅回显 + 透传，服务端以网关注入为准） */
  principal?: Principal
  /** 便于测试替换；默认取调用时的全局 fetch */
  fetchImpl?: typeof fetch
}

type JsonRecord = Record<string, unknown>

/** gateway 契约未规定 generate 正文字段名，这里兼容 text / payload.text / payload.content / payload.answer */
function extractText(raw: JsonRecord): string {
  if (typeof raw.text === 'string') return raw.text
  const payload = raw.payload
  if (payload && typeof payload === 'object') {
    const p = payload as JsonRecord
    for (const key of ['text', 'content', 'answer']) {
      if (typeof p[key] === 'string') return p[key] as string
    }
  }
  return ''
}

export function normalizeGatewayResponse(raw: JsonRecord): GatewayResponse {
  const usage = (raw.usage ?? {}) as Partial<GatewayResponse['usage']>
  return {
    model: String(raw.model ?? ''),
    switched_reason: (raw.switched_reason as string | null | undefined) ?? undefined,
    channel: raw.channel as GatewayResponse['channel'],
    budget_alert: (raw.budget_alert as string | null | undefined) ?? undefined,
    text: extractText(raw),
    usage: {
      prompt_tokens: usage.prompt_tokens ?? 0,
      completion_tokens: usage.completion_tokens ?? 0,
      cost_cny: usage.cost_cny ?? 0,
    },
  }
}

export function createHttpApi(options: HttpApiOptions = {}): WorkbenchApi {
  const baseUrl = (options.baseUrl ?? '/api').replace(/\/$/, '')

  async function post<T>(service: ServiceName, path: string, body: unknown, userId?: string) {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    const uid = userId ?? options.userId
    if (uid) headers['X-User-Id'] = uid
    const doFetch = options.fetchImpl ?? fetch
    const res = await doFetch(`${baseUrl}${path}`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
    })
    if (!res.ok) throw new ApiHttpError(service, res.status)
    return (await res.json()) as T
  }

  return {
    route(req: RouteRequest) {
      return post<RouteResponse>(
        'decide',
        '/decide/route',
        { user_choice: 'auto', ...req },
        req.principal?.user_id,
      )
    },
    search(req: SearchRequest) {
      return post<SearchResponse>('retrieval', '/retrieval/search', req, req.principal?.user_id)
    },
    async generate(req: GatewayRequest) {
      const raw = await post<JsonRecord>('gateway', '/gateway/v1/invoke', req, req.principal?.user_id)
      return normalizeGatewayResponse(raw)
    },
    async listModels() {
      // gateway 契约暂无模型列表接口，使用前端静态目录
      return MODEL_CATALOG
    },
    async getMatcherTask(task_id: string): Promise<MatcherTask> {
      // 成册任务不在 T07 范围内，http 模式暂不接 matcher
      throw new Error(`matcher 未接入 http 模式：${task_id}`)
    },
    async getPrincipal() {
      if (options.principal) return options.principal
      throw new Error('未配置会话权限主体')
    },
  }
}

/** 从 Vite 环境变量读取 http 客户端配置；VITE_DEV_PRINCIPAL 仅用于本地联调 */
export function httpOptionsFromEnv(env: ImportMetaEnv): HttpApiOptions {
  let principal: Principal | undefined
  if (env.VITE_DEV_PRINCIPAL) {
    try {
      principal = JSON.parse(env.VITE_DEV_PRINCIPAL) as Principal
    } catch {
      principal = undefined
    }
  }
  return {
    baseUrl: env.VITE_API_BASE || '/api',
    userId: env.VITE_USER_ID || principal?.user_id,
    principal,
  }
}
