// 后端 mock 实现：返回写死的脱敏示例数据，不连真实后端。
// 所有接口签名对齐 contracts/；真实 HTTP 实现见 ./http.ts，二者实现同一个 WorkbenchApi。
import { MODEL_CATALOG } from './models'
import {
  NO_SCOPE_DESC,
  type GatewayRequest,
  type GatewayResponse,
  type MatcherTask,
  type ModelOption,
  type Principal,
  type RouteRequest,
  type RouteResponse,
  type SearchRequest,
  type SearchResponse,
} from './types'

export interface WorkbenchApi {
  /** POST /decide/route —— 问答路由：enterprise / general / kb_empty */
  route(req: RouteRequest): Promise<RouteResponse>
  /** POST /retrieval/search —— 权限过滤由服务端在检索前完成 */
  search(req: SearchRequest): Promise<SearchResponse>
  /** POST /v1/invoke —— 模型网关调用，通用问答与企业知识生成都走这里 */
  generate(req: GatewayRequest): Promise<GatewayResponse>
  /** 模型选择器可选项（含置灰原因） */
  listModels(): Promise<ModelOption[]>
  /** GET /matcher/tasks/{task_id} */
  getMatcherTask(task_id: string): Promise<MatcherTask>
  /** 当前登录人与部门（回显用） */
  getPrincipal(): Promise<Principal>
}

const PRINCIPAL: Principal = {
  user_id: 'u_10231',
  dept: ['融资部', '财务共享'],
  roles: ['financing_staff'],
  max_sensitivity: 'L3',
  projects: ['PRJ-2026-CITIC'],
}

const MODELS: ModelOption[] = MODEL_CATALOG

/** 命中企业知识的示例问题关键词 */
const HIT_KEYWORD = '研发费用'
/** 触发权限提示的示例问题关键词 */
const DENY_KEYWORD = '薪酬'
/** 判定为与公司相关、但库内未命中（kb_empty）的示例关键词 */
const KB_EMPTY_KEYWORD = '公司'

/** 企业知识回答：写死的示例正文，角标 [n] 对应传入片段的序号 */
const HIT_ANSWER =
  '最近三年研发费用占营收比例：2023 年 6.4%[1]；2022 年 5.9%[2]；2021 年 5.2%[3]。'

const HIT_RESPONSE: SearchResponse = {
  scope_desc: '融资部 / 财务共享（你的权限内）',
  total: 3,
  hits: [
    {
      chunk_id: 'c_88213',
      doc_id: 'd_2031',
      doc_name: '2023年度审计报告.pdf',
      dept: '财务共享',
      sensitivity: 'L3',
      page: 37,
      score: 0.86,
      text: '2023 年度研发费用 1.82 亿元，占营业收入比例 6.4%，较上年提升 0.5 个百分点。',
    },
    {
      chunk_id: 'c_88157',
      doc_id: 'd_2030',
      doc_name: '2022年度审计报告.pdf',
      dept: '财务共享',
      sensitivity: 'L3',
      page: 33,
      score: 0.81,
      text: '2022 年度研发费用 1.41 亿元，占营业收入比例 5.9%。',
    },
    {
      chunk_id: 'c_88102',
      doc_id: 'd_2029',
      doc_name: '2021年度审计报告.pdf',
      dept: '财务共享',
      sensitivity: 'L3',
      page: 29,
      score: 0.77,
      text: '2021 年度研发费用 1.05 亿元，占营业收入比例 5.2%。',
    },
  ],
}

/** 无可检索范围：只通过 scope_desc 表达，不返回任何文档信息 */
const DENIED_RESPONSE: SearchResponse = {
  hits: [],
  scope_desc: NO_SCOPE_DESC,
  total: 0,
}

/** 库内未命中 */
const EMPTY_RESPONSE: SearchResponse = {
  hits: [],
  scope_desc: '融资部 / 财务共享（你的权限内）',
  total: 0,
}

const TASK: MatcherTask = {
  task_id: 'tk_0007',
  checklist_id: 'cl_0007',
  title: '中信银行授信资料清单',
  progress: 0.72,
  items: [
    {
      item_id: 'it_0031',
      checklist_id: 'cl_0007',
      no: '3.1',
      raw_text: '最近三年审计报告（合并口径，加盖公章）',
      std_type: 'AUDIT_REPORT',
      constraints: { period: ['2021', '2022', '2023'], scope: 'consolidated', copies: 3, stamp_required: true },
      status: 'matched',
      final_score: 0.91,
      candidate_doc_name: '2021-2023年度审计报告（合并）.pdf',
    },
    {
      item_id: 'it_0032',
      checklist_id: 'cl_0007',
      no: '4.2',
      raw_text: '近一年主要银行流水',
      std_type: 'BANK_STATEMENT',
      constraints: { period: ['2025'], copies: 1 },
      status: 'pending',
      final_score: 0.63,
      candidate_doc_name: '2025年招行流水.pdf',
      note: '仅覆盖 1—9 月，需确认是否补齐',
    },
    {
      item_id: 'it_0033',
      checklist_id: 'cl_0007',
      no: '6.3',
      raw_text: '最新版公司章程',
      std_type: 'ARTICLES_OF_ASSOCIATION',
      constraints: { stamp_required: true },
      status: 'missing',
      final_score: 0.21,
      note: '库内仅 2022 版，需最新备案版本',
    },
  ],
}

/** 创建 mock API 客户端；delay 用于测试时置 0 */
export function createMockApi(delay = 0): WorkbenchApi {
  const wait = <T,>(value: T): Promise<T> =>
    delay > 0 ? new Promise((resolve) => setTimeout(() => resolve(value), delay)) : Promise.resolve(value)

  return {
    async route(req: RouteRequest) {
      const q = req.question
      if (q.includes(HIT_KEYWORD) || q.includes(DENY_KEYWORD)) {
        return wait<RouteResponse>({
          decision: 'enterprise',
          reason: '命中企业实体',
          confirm_required: false,
          allow_general_fallback: false,
          evidence: { source: 'entity', matched_entities: [HIT_KEYWORD], decider_backend: 'rule' },
        })
      }
      if (q.includes(KB_EMPTY_KEYWORD)) {
        return wait<RouteResponse>({
          decision: 'kb_empty',
          reason: '与公司相关，但探测检索未命中',
          confirm_required: true,
          allow_general_fallback: false,
          evidence: {
            source: 'probe',
            probe_top_score: 0,
            probe_total: 0,
            probe_threshold: 0.55,
            scope_desc: EMPTY_RESPONSE.scope_desc,
          },
        })
      }
      return wait<RouteResponse>({
        decision: 'general',
        reason: '通用问题',
        confirm_required: false,
        allow_general_fallback: true,
        evidence: { source: 'decide', need_internal_probability: 0.1, decider_backend: 'rule' },
      })
    },
    async search(req: SearchRequest) {
      if (req.query.includes(DENY_KEYWORD)) return wait(DENIED_RESPONSE)
      if (req.query.includes(HIT_KEYWORD)) return wait(HIT_RESPONSE)
      return wait(EMPTY_RESPONSE)
    },
    async generate(req: GatewayRequest) {
      // 密级不允许时网关降级并回传 switched_reason，前端必须显式提示
      const isPublic = MODELS.find((m) => m.model === req.preferred_model)?.group === 'public_api'
      const switched = req.sensitivity !== 'L1' && isPublic
      const enterprise = Boolean(req.principal)
      return wait<GatewayResponse>({
        model: switched ? 'deepseek-v3-enterprise' : req.preferred_model,
        switched_reason: switched ? `${req.sensitivity} 内容不允许通用通道` : undefined,
        channel: switched ? 'enterprise_api' : undefined,
        text: enterprise
          ? HIT_ANSWER
          : `（通用模型回答）关于「${req.payload.query}」的通用说明，仅供参考。`,
        usage: { prompt_tokens: 2841, completion_tokens: 512, cost_cny: 0.14 },
      })
    },
    async listModels() {
      return wait(MODELS)
    },
    async getMatcherTask(task_id: string) {
      return wait({ ...TASK, task_id })
    },
    async getPrincipal() {
      return wait(PRINCIPAL)
    },
  }
}

export const mockData = { PRINCIPAL, MODELS, HIT_RESPONSE, TASK, HIT_KEYWORD, DENY_KEYWORD }
