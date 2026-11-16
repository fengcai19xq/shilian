// 字段命名对齐 contracts/：retrieval.md、matching.md、packaging.md、gateway.md
// 前端只做展示与交互，权限主体由网关按会话态注入，此处的 principal 仅用于回显

export type Sensitivity = 'L1' | 'L2' | 'L3' | 'L4'

/** contracts/retrieval.md 权限主体（前端只读回显，不得自行构造 max_sensitivity） */
export interface Principal {
  user_id: string
  dept: string[]
  roles: string[]
  max_sensitivity: Sensitivity
  projects: string[]
}

/** contracts/retrieval.md 补充：无任何授权时 scope_desc 固定为此值，前端只能据此展示权限提示 */
export const NO_SCOPE_DESC = '（无可检索范围）'

/** POST /retrieval/search 请求体 */
export interface SearchRequest {
  query: string
  /**
   * @deprecated 浏览器不再发送；由 BFF 从会话解析并注入。
   * 保留字段仅为 mock/旧调用方类型兼容。
   */
  principal?: Principal
  kb_scope: string[]
  top_k?: number
  score_threshold?: number
  mode: 'probe' | 'full'
}

/** 检索命中片段 */
export interface RetrievalHit {
  chunk_id: string
  doc_id: string
  doc_name: string
  dept: string
  sensitivity: Sensitivity
  page: number
  score: number
  text: string
  /** 补充字段（可选）：期间、口径、份数、是否盖章 */
  period?: string
  scope?: string
  copies?: number
  stamped?: boolean
}

/** POST /retrieval/search 返回体 */
export interface SearchResponse {
  hits: RetrievalHit[]
  scope_desc: string
  total: number
}

/** contracts/decide.md POST /decide/route 请求体 */
export interface RouteRequest {
  question: string
  principal?: Principal
  user_choice?: 'enterprise' | 'general' | 'auto'
  kb_scope: string[]
}

export type RouteDecision = 'enterprise' | 'general' | 'kb_empty'

/** contracts/decide.md 路由响应 */
export interface RouteResponse {
  decision: RouteDecision
  reason: string
  confirm_required: boolean
  allow_general_fallback: boolean
  evidence: {
    source: 'user_choice' | 'entity' | 'probe' | 'decide'
    matched_entities?: string[]
    probe_top_score?: number
    probe_total?: number
    probe_threshold?: number
    need_internal_probability?: number
    decider_backend?: 'rule' | 'jev' | 'llm'
    decider_fallback_reason?: string | null
    scope_desc?: string | null
  }
}

/** contracts/gateway.md 补充：实际落地通道 */
export type Channel = 'public_api' | 'enterprise_api' | 'vpc_self_hosted'

export interface ChatTurn {
  role: 'system' | 'user'
  content: string
}

/** contracts/gateway.md 模型调用请求（POST /v1/invoke） */
export interface GatewayRequest {
  purpose: 'generate' | 'decide' | 'embed' | 'rerank'
  sensitivity: Sensitivity
  preferred_model: string
  /**
   * @deprecated 浏览器不再发送；由 BFF 从会话解析并注入。
   * 通用问答尤其不得携带 principal（contracts/decide.md）。
   */
  principal?: Pick<Principal, 'user_id' | 'dept'>
  /** generate 的 payload：原始问题 + 组织好的消息（企业知识问答含命中片段） */
  payload: { query: string; messages?: ChatTurn[] }
}

/** contracts/gateway.md 模型调用返回，switched_reason 必须显式提示用户 */
export interface GatewayResponse {
  model: string
  switched_reason?: string | null
  channel?: Channel
  budget_alert?: string | null
  /** 生成正文（http 客户端从 text / payload.text / payload.content 归一化而来） */
  text: string
  usage: {
    prompt_tokens: number
    completion_tokens: number
    cost_cny: number
  }
}

/** 模型选择器选项：按 contracts/gateway.md 密级路由的三类通道分组 */
export interface ModelOption {
  model: string
  label: string
  /** public_api=通用公有云（L1）；enterprise_api=企业版 API（L2/L3）；vpc_self_hosted=VPC 自部署（L4） */
  group: Channel
  /** 不合规时置灰 */
  disabled: boolean
  /** 置灰原因，例如「未签数据处理协议」 */
  disabled_reason?: string
}

/** contracts/matching.md 三态 */
export type MatchStatus = 'matched' | 'pending' | 'missing'

/** 清单项及其匹配结果 */
export interface ChecklistItem {
  item_id: string
  checklist_id: string
  no: string
  raw_text: string
  std_type: string
  constraints: {
    period?: string[]
    scope?: string
    copies?: number
    stamp_required?: boolean
  }
  status: MatchStatus
  final_score: number
  /** 建议绑定的文档名（仅权限内文档） */
  candidate_doc_name?: string
  /** 候选文档 ID（仅权限内文档；用于人工确认绑定） */
  candidate_doc_ids?: string[]
  note?: string
}

/** GET /matcher/checklists/{id} 的清单原始项（不含匹配结果） */
export interface ChecklistSourceItem {
  item_id: string
  checklist_id: string
  no: string
  raw_text: string
  std_type: string
  constraints?: ChecklistItem['constraints']
}

export interface Checklist {
  checklist_id: string
  title: string
  items: ChecklistSourceItem[]
}

/** POST /matcher/checklists/{id}/run */
export interface RunMatchResponse {
  task_id: string
  checklist_id: string
  state: string
}

/** matcher 任务原始返回（GET /matcher/tasks/{id}） */
export interface MatcherTaskResponse {
  task: {
    task_id: string
    checklist_id: string
    state: string
    total: number
    done: number
    error?: string | null
    results: Array<{
      item_id: string
      no: string
      std_type: string
      std_name: string
      status: MatchStatus
      score: number
      candidates: Array<{
        doc_id: string
        doc_name: string
        uri?: string
        rejected?: boolean
        final_score?: number
        p_satisfies?: number
      }>
      bound_doc_ids: string[]
      confirmed_by?: string | null
      note?: string
    }>
  }
  progress: number
}

export interface PackageArtifact {
  kind: string
  filename: string
  uri: string
  size: number
}

export interface PackageResult {
  package_id: string
  artifacts: PackageArtifact[]
  missing_entries: string[]
  requires_manual_review: boolean
}

/** GET /matcher/tasks/{task_id} 进度与结果 */
export interface MatcherTask {
  task_id: string
  checklist_id: string
  title: string
  progress: number
  /** queued / running / succeeded / failed；mock 可省略 */
  state?: string
  items: ChecklistItem[]
}

/** 引用角标指向的溯源信息 */
export interface Citation {
  index: number
  chunk_id: string
}
