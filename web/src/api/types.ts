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

/** POST /retrieval/search 请求体 */
export interface SearchRequest {
  query: string
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
}

/** POST /retrieval/search 返回体 */
export interface SearchResponse {
  hits: RetrievalHit[]
  scope_desc: string
  total: number
  /** 权限拒答场景：无权访问时 total 为 0，且不得携带任何越权文档信息 */
  permission_denied?: boolean
}

/** contracts/gateway.md 模型调用请求 */
export interface GatewayRequest {
  purpose: 'generate' | 'decide' | 'embed' | 'rerank'
  sensitivity: Sensitivity
  preferred_model: string
  payload: { query: string }
}

/** contracts/gateway.md 模型调用返回，switched_reason 必须显式提示用户 */
export interface GatewayResponse {
  model: string
  switched_reason?: string
  text: string
  usage: {
    prompt_tokens: number
    completion_tokens: number
    cost_cny: number
  }
}

/** 模型选择器选项：分「通用问答可选」与「涉及公司资料可用」两组 */
export interface ModelOption {
  model: string
  label: string
  /** general=通用问答可选；corporate=涉及公司资料可用 */
  group: 'general' | 'corporate'
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
  note?: string
}

/** GET /matcher/tasks/{task_id} 进度与结果 */
export interface MatcherTask {
  task_id: string
  checklist_id: string
  title: string
  progress: number
  items: ChecklistItem[]
}

/** 引用角标指向的溯源信息 */
export interface Citation {
  index: number
  chunk_id: string
}
