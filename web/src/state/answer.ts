// 企业知识问答：用命中片段组织 prompt、解析回答中的引用角标
// 日期、金额等一律不在前端计算，正文原样展示网关返回
import type { ChatTurn, RetrievalHit, Sensitivity } from '../api/types'
import type { AnswerPart } from './messages'

/** 上下文按 rerank 后的 top 片段截断，不整篇塞（AGENTS.md 省 token） */
export const MAX_CONTEXT_CHUNKS = 6
export const MAX_CHUNK_CHARS = 800

const SENSITIVITY_ORDER: Sensitivity[] = ['L1', 'L2', 'L3', 'L4']

/** 取进入 prompt 的片段：retrieval 已按相关度排序，直接取前 N 条 */
export function selectContext(hits: RetrievalHit[]): RetrievalHit[] {
  return hits.slice(0, MAX_CONTEXT_CHUNKS)
}

/** 调用网关时的密级取所用片段中的最高密级，由网关据此选择合规通道 */
export function highestSensitivity(hits: RetrievalHit[]): Sensitivity {
  return hits.reduce<Sensitivity>(
    (max, hit) =>
      SENSITIVITY_ORDER.indexOf(hit.sensitivity) > SENSITIVITY_ORDER.indexOf(max) ? hit.sensitivity : max,
    'L1',
  )
}

const SYSTEM_PROMPT = [
  '你是航锂企业知识助手。只能依据下面编号的资料片段回答问题。',
  '每个事实或数字后用 [编号] 标注来源，例如 [1]；不要编造资料中没有的内容。',
  '日期、金额、比例直接引用原文数值，不要自行换算或计算。资料不足时如实说明。',
].join('\n')

export function buildEnterpriseMessages(question: string, context: RetrievalHit[]): ChatTurn[] {
  const blocks = context.map((hit, i) => {
    const text = hit.text.length > MAX_CHUNK_CHARS ? `${hit.text.slice(0, MAX_CHUNK_CHARS)}…` : hit.text
    return `[${i + 1}] 《${hit.doc_name}》第 ${hit.page} 页\n${text}`
  })
  return [
    { role: 'system', content: SYSTEM_PROMPT },
    { role: 'user', content: `资料：\n${blocks.join('\n\n')}\n\n问题：${question}` },
  ]
}

/** 把回答中的 [n] 解析为可点击角标；超出片段范围的编号按原文保留 */
export function parseAnswerParts(text: string, context: RetrievalHit[]): AnswerPart[] {
  const parts: AnswerPart[] = []
  const pattern = /\[(\d+)\]/g
  let last = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(text)) !== null) {
    const index = Number(match[1])
    const hit = context[index - 1]
    if (!hit) continue
    if (match.index > last) parts.push({ type: 'text', text: text.slice(last, match.index) })
    parts.push({ type: 'cite', index, chunk_id: hit.chunk_id })
    last = match.index + match[0].length
  }
  if (last < text.length) parts.push({ type: 'text', text: text.slice(last) })
  return parts
}
