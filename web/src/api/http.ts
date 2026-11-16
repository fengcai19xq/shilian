// 真实 HTTP 客户端：与 mock 实现同一个 WorkbenchApi 接口。
// 路径统一挂在 /api 前缀下，由 BFF（生产 nginx / 本地 Vite dev proxy）统一接入：
//   /api/identity/me       → BFF → wecom-sync /identity/me（只读回显）
//   /api/decide/route      → BFF → decide /decide/route
//   /api/retrieval/search  → BFF → retrieval-proxy /retrieval/search
//   /api/gateway/v1/invoke → BFF → gateway /v1/invoke
//
// 安全边界（2026-11 收口）：
// - 浏览器不构造、也不发送 principal；dept / max_sensitivity / projects 只由 BFF 注入。
// - 浏览器不发送 X-User-Id；会话凭据走 Cookie / Authorization，由 BFF 换 principal。
// - VITE_DEV_PRINCIPAL 只用于本地 mock/回显，不随业务请求发送。
import type { WorkbenchApi } from './mock'
import { MODEL_CATALOG } from './models'
import type {
  Checklist,
  GatewayRequest,
  GatewayResponse,
  MatcherTask,
  MatcherTaskResponse,
  PackageResult,
  Principal,
  RouteRequest,
  RouteResponse,
  RunMatchResponse,
  SearchRequest,
  SearchResponse,
} from './types'

export type ServiceName = 'decide' | 'retrieval' | 'gateway' | 'identity' | 'matcher' | 'packager'

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
  /**
   * 仅本地 mock/联调时用于界面回显，**不会随业务请求发送**。
   * 生产身份一律由 BFF 从会话解析。
   */
  principal?: Principal
  /** 便于测试替换；默认取调用时的全局 fetch */
  fetchImpl?: typeof fetch
}

type JsonRecord = Record<string, unknown>

/** gateway 契约未规定 generate 正文字段名，这里兼容 text / payload.text / payload.content / payload.answer / output.text */
function extractText(raw: JsonRecord): string {
  if (typeof raw.text === 'string') return raw.text
  // 后端 InvokeResponse 的正经结构是 output.text；顶层 text 是契约 T07 补充要求的字段。
  // 两者历史上出现过只给其中一个的情况，任一缺失都不得让答案静默变空。
  const output = raw.output
  if (output && typeof output === 'object') {
    const o = output as JsonRecord
    for (const key of ['text', 'content', 'answer']) {
      if (typeof o[key] === 'string') return o[key] as string
    }
  }
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

/** 去掉浏览器可能塞进来的权限字段；BFF 会再覆盖一次，这里保证请求体本身不带。 */
function withoutPrincipal<T extends { principal?: unknown }>(body: T): Omit<T, 'principal'> {
  const { principal: _ignored, ...rest } = body
  return rest
}

export function createHttpApi(options: HttpApiOptions = {}): WorkbenchApi {
  const baseUrl = (options.baseUrl ?? '/api').replace(/\/$/, '')

  async function post<T>(service: ServiceName, path: string, body: unknown) {
    const doFetch = options.fetchImpl ?? fetch
    const res = await doFetch(`${baseUrl}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      credentials: 'include',
    })
    if (!res.ok) throw new ApiHttpError(service, res.status)
    return (await res.json()) as T
  }

  /** 会话身份：只向 BFF 询问“我是谁”，不携带任何自报身份头。 */
  async function postForm<T>(service: ServiceName, path: string, form: FormData) {
    const doFetch = options.fetchImpl ?? fetch
    const res = await doFetch(`${baseUrl}${path}`, {
      method: 'POST',
      body: form,
      credentials: 'include',
    })
    if (!res.ok) throw new ApiHttpError(service, res.status)
    return (await res.json()) as T
  }

  async function get<T>(service: ServiceName, path: string) {
    const doFetch = options.fetchImpl ?? fetch
    const res = await doFetch(`${baseUrl}${path}`, {
      method: 'GET',
      credentials: 'include',
    })
    if (!res.ok) throw new ApiHttpError(service, res.status)
    return (await res.json()) as T
  }

  return {
    route(req: RouteRequest) {
      return post<RouteResponse>('decide', '/decide/route', {
        user_choice: 'auto',
        ...withoutPrincipal(req),
      })
    },
    search(req: SearchRequest) {
      return post<SearchResponse>('retrieval', '/retrieval/search', withoutPrincipal(req))
    },
    async generate(req: GatewayRequest) {
      const raw = await post<JsonRecord>(
        'gateway',
        '/gateway/v1/invoke',
        withoutPrincipal(req),
      )
      return normalizeGatewayResponse(raw)
    },
    async listModels() {
      // gateway 契约暂无模型列表接口，使用前端静态目录
      return MODEL_CATALOG
    },
    async uploadChecklist(file: File): Promise<Checklist> {
      const form = new FormData()
      form.append('file', file)
      return postForm<Checklist>('matcher', '/matcher/checklists', form)
    },
    getChecklist(checklist_id: string) {
      return get<Checklist>('matcher', `/matcher/checklists/${checklist_id}`)
    },
    runChecklist(checklist_id: string) {
      return post<RunMatchResponse>('matcher', `/matcher/checklists/${checklist_id}/run`, {})
    },
    async getMatcherTask(task_id: string): Promise<MatcherTask> {
      const raw = await get<MatcherTaskResponse>('matcher', `/matcher/tasks/${task_id}`)
      const checklist = await get<Checklist>('matcher', `/matcher/checklists/${raw.task.checklist_id}`)
      const byItem = new Map(checklist.items.map((item) => [item.item_id, item]))
      return {
        task_id: raw.task.task_id,
        checklist_id: raw.task.checklist_id,
        title: checklist.title,
        progress: raw.progress,
        state: raw.task.state,
        items: raw.task.results.map((result) => {
          const source = byItem.get(result.item_id)
          const candidates = result.candidates ?? []
          const best = candidates
            .filter((c) => !c.rejected)
            .sort((a, b) => (b.final_score ?? 0) - (a.final_score ?? 0))[0]
          return {
            item_id: result.item_id,
            checklist_id: raw.task.checklist_id,
            no: result.no,
            raw_text: source?.raw_text ?? result.std_name,
            std_type: result.std_type,
            constraints: source?.constraints ?? {},
            status: result.status,
            final_score: result.score,
            candidate_doc_name: best?.doc_name ?? candidates[0]?.doc_name,
            candidate_doc_ids: candidates.filter((c) => !c.rejected).map((c) => c.doc_id),
            note: result.note,
          }
        }),
      }
    },
    async confirmItem(item_id: string, doc_ids: string[], note = '') {
      await post<unknown>('matcher', `/matcher/items/${item_id}/confirm`, { doc_ids, note })
    },
    packageChecklist(checklist_id: string) {
      return post<PackageResult>('matcher', `/matcher/checklists/${checklist_id}/package`, {})
    },
    async getPrincipal() {
      // 仅本地 mock 回显可直接用；生产一律走 BFF 会话
      if (options.principal) return options.principal
      return get<Principal>('identity', '/identity/me')
    },
  }
}

/** 从 Vite 环境变量读取 http 客户端配置。 */
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
    principal,
  }
}
