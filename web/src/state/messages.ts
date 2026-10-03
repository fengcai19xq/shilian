// 对话消息模型：对应交互设计的五种消息态
import type { MatcherTask, RetrievalHit } from '../api/types'

/** 正文片段：纯文本，或带引用角标的文本（角标可点，右栏定位原文） */
export type AnswerPart =
  | { type: 'text'; text: string }
  | { type: 'cite'; text: string; index: number; chunk_id: string }

export interface UserMessage {
  kind: 'user'
  id: string
  text: string
}

/** 1. 企业知识命中 */
export interface KnowledgeHitMessage {
  kind: 'kb_hit'
  id: string
  scope_desc: string
  total: number
  parts: AnswerPart[]
  hits: RetrievalHit[]
}

/** 2. 库内未命中：必须二次确认才可改用通用模型 */
export interface NoHitMessage {
  kind: 'no_hit'
  id: string
  query: string
  /** 用户是否已点过「改用通用模型」并完成二次确认 */
  fallback_confirmed: boolean
}

/** 3. 通用问答 */
export interface GeneralMessage {
  kind: 'general'
  id: string
  model: string
  text: string
  switched_reason?: string
}

/** 4. 清单成册任务卡 */
export interface TaskCardMessage {
  kind: 'task_card'
  id: string
  task: MatcherTask
}

/** 5. 权限拒答：不得出现任何越权文档名 */
export interface PermissionDeniedMessage {
  kind: 'permission_denied'
  id: string
}

export type Message =
  | UserMessage
  | KnowledgeHitMessage
  | NoHitMessage
  | GeneralMessage
  | TaskCardMessage
  | PermissionDeniedMessage

/** 把检索命中拼成带引用角标的正文；数字必须挂角标，便于右栏溯源 */
export function buildAnswerParts(hits: RetrievalHit[]): AnswerPart[] {
  const parts: AnswerPart[] = [{ type: 'text', text: '最近三年研发费用占营收比例：' }]
  hits.forEach((hit, i) => {
    const ratio = hit.text.match(/占营业收入比例\s*([\d.]+%)/)?.[1] ?? '—'
    const year = hit.text.match(/^(\d{4})\s*年度/)?.[1] ?? ''
    parts.push({ type: 'text', text: `${i > 0 ? '；' : ''}${year} 年 ` })
    parts.push({ type: 'cite', text: ratio, index: i + 1, chunk_id: hit.chunk_id })
  })
  parts.push({ type: 'text', text: '。' })
  return parts
}
