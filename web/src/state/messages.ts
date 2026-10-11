// 对话消息模型：对应交互设计的五种消息态
import type { Channel, MatcherTask, RetrievalHit } from '../api/types'

/** 正文片段：纯文本，或带引用角标的文本（角标可点，右栏定位原文） */
export type AnswerPart =
  | { type: 'text'; text: string }
  | { type: 'cite'; index: number; chunk_id: string }

export interface UserMessage {
  kind: 'user'
  id: string
  text: string
}

/** 网关返回的通道信息：switched_reason 存在时必须在回答上方显式提示 */
export interface GatewayMeta {
  model: string
  switched_reason?: string
  channel?: Channel
  budget_alert?: string
}

/** 1. 企业知识命中：hits 为进入 prompt 的片段，角标 [n] 对应 hits[n-1] */
export interface KnowledgeHitMessage extends GatewayMeta {
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
export interface GeneralMessage extends GatewayMeta {
  kind: 'general'
  id: string
  text: string
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

/** 服务调用失败：只展示服务名与状态码 */
export interface ErrorMessage {
  kind: 'error'
  id: string
  text: string
}

export type Message =
  | UserMessage
  | KnowledgeHitMessage
  | NoHitMessage
  | GeneralMessage
  | TaskCardMessage
  | PermissionDeniedMessage
  | ErrorMessage
