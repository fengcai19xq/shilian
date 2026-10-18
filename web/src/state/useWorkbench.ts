// 工作台状态：会话、消息流、右栏溯源、模型选择（按会话记忆，不跨会话）
import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiHttpError, useApi } from '../api'
import {
  NO_SCOPE_DESC,
  type GatewayResponse,
  type ModelOption,
  type Principal,
  type RetrievalHit,
} from '../api/types'
import { buildEnterpriseMessages, highestSensitivity, parseAnswerParts, selectContext } from './answer'
import type { GatewayMeta, Message } from './messages'

export interface Conversation {
  conversation_id: string
  title: string
}

/** 右栏三种形态：空状态 / 引用溯源 / 产物预览 */
export type RightPaneState =
  | { mode: 'empty' }
  | { mode: 'source'; hits: RetrievalHit[]; active_chunk_id: string; scope_desc: string }
  | { mode: 'artifact'; package_title: string }

const KB_SCOPE = ['kb_finance', 'kb_audit']
const DEFAULT_MODEL = 'deepseek-v3-enterprise'

const CONVERSATIONS: Conversation[] = [
  { conversation_id: 'cv_1', title: '中信银行授信资料准备' },
  { conversation_id: 'cv_2', title: '研发费用占比口径' },
]

/** contracts/retrieval.md 默认值：full 模式取 top 8、阈值 0.35 */
const SEARCH_TOP_K = 8
const SEARCH_SCORE_THRESHOLD = 0.35

let seq = 0
const nextId = (prefix: string) => `${prefix}_${++seq}`

function gatewayMeta(answer: GatewayResponse): GatewayMeta {
  return {
    model: answer.model,
    switched_reason: answer.switched_reason ?? undefined,
    channel: answer.channel,
    budget_alert: answer.budget_alert ?? undefined,
  }
}

/** 错误提示只给服务名与状态码，不透传后端响应内容 */
function describeError(error: unknown): string {
  if (error instanceof ApiHttpError) return `${error.service} 服务返回 ${error.status}，请稍后重试`
  return '服务暂不可用，请稍后重试'
}

export function useWorkbench() {
  const api = useApi()
  const [principal, setPrincipal] = useState<Principal | null>(null)
  const [models, setModels] = useState<ModelOption[]>([])
  const [conversationId, setConversationId] = useState(CONVERSATIONS[0].conversation_id)
  const [messagesByConversation, setMessagesByConversation] = useState<Record<string, Message[]>>({})
  /** 模型选择按会话记忆：换会话即回到默认，不跨会话继承 */
  const [modelByConversation, setModelByConversation] = useState<Record<string, string>>({})
  const [rightPane, setRightPane] = useState<RightPaneState>({ mode: 'empty' })
  const [pending, setPending] = useState(false)

  useEffect(() => {
    api.getPrincipal().then(setPrincipal, () => setPrincipal(null))
    void api.listModels().then(setModels)
  }, [api])

  const messages = messagesByConversation[conversationId] ?? []
  const selectedModel = modelByConversation[conversationId] ?? DEFAULT_MODEL

  const append = useCallback(
    (message: Message) => {
      setMessagesByConversation((prev) => ({
        ...prev,
        [conversationId]: [...(prev[conversationId] ?? []), message],
      }))
    },
    [conversationId],
  )

  const selectModel = useCallback(
    (model: string) => {
      const option = models.find((m) => m.model === model)
      // 置灰项不可选，直接忽略
      if (!option || option.disabled) return
      setModelByConversation((prev) => ({ ...prev, [conversationId]: model }))
    },
    [conversationId, models],
  )

  /** 通用问答：直接走网关，不带 principal（身份只由 X-User-Id 头承载），不携带任何公司数据 */
  const answerGeneral = useCallback(
    async (query: string) => {
      const answer = await api.generate({
        purpose: 'generate',
        sensitivity: 'L1',
        preferred_model: selectedModel,
        payload: { query },
      })
      append({ kind: 'general', id: nextId('m'), text: answer.text, ...gatewayMeta(answer) })
    },
    [api, append, selectedModel],
  )

  /** 企业知识问答：full 检索 → 命中片段组织 prompt → 网关生成 → 角标溯源 */
  const answerEnterprise = useCallback(
    async (query: string) => {
      const result = await api.search({
        query,
        principal: principal ?? undefined,
        kb_scope: KB_SCOPE,
        top_k: SEARCH_TOP_K,
        score_threshold: SEARCH_SCORE_THRESHOLD,
        mode: 'full',
      })
      if (result.scope_desc === NO_SCOPE_DESC) {
        append({ kind: 'permission_denied', id: nextId('m') })
        return
      }
      if (result.total === 0 || result.hits.length === 0) {
        append({ kind: 'no_hit', id: nextId('m'), query, fallback_confirmed: false })
        return
      }
      const context = selectContext(result.hits)
      const answer = await api.generate({
        purpose: 'generate',
        sensitivity: highestSensitivity(context),
        preferred_model: selectedModel,
        principal: principal ? { user_id: principal.user_id, dept: principal.dept } : undefined,
        payload: { query, messages: buildEnterpriseMessages(query, context) },
      })
      append({
        kind: 'kb_hit',
        id: nextId('m'),
        scope_desc: result.scope_desc,
        total: result.total,
        parts: parseAnswerParts(answer.text, context),
        hits: context,
        ...gatewayMeta(answer),
      })
    },
    [api, append, principal, selectedModel],
  )

  /** 发送提问：先经 decide 路由；未命中只如实告知，绝不静默转通用模型 */
  const send = useCallback(
    async (query: string) => {
      const text = query.trim()
      if (!text) return
      append({ kind: 'user', id: nextId('m'), text })
      setPending(true)
      try {
        if (text.includes('清单') || text.includes('成册')) {
          const task = await api.getMatcherTask('tk_0007')
          append({ kind: 'task_card', id: nextId('m'), task })
          return
        }
        const route = await api.route({
          question: text,
          principal: principal ?? undefined,
          user_choice: 'auto',
          kb_scope: KB_SCOPE,
        })
        if (route.evidence?.scope_desc === NO_SCOPE_DESC) {
          append({ kind: 'permission_denied', id: nextId('m') })
          return
        }
        switch (route.decision) {
          case 'general':
            await answerGeneral(text)
            return
          case 'kb_empty':
            append({ kind: 'no_hit', id: nextId('m'), query: text, fallback_confirmed: false })
            return
          case 'enterprise':
            await answerEnterprise(text)
            return
        }
      } catch (error) {
        append({ kind: 'error', id: nextId('m'), text: describeError(error) })
      } finally {
        setPending(false)
      }
    },
    [api, append, answerEnterprise, answerGeneral, principal],
  )

  /** 用户二次确认后才调用通用模型 */
  const confirmGeneralFallback = useCallback(
    async (messageId: string, query: string) => {
      setMessagesByConversation((prev) => ({
        ...prev,
        [conversationId]: (prev[conversationId] ?? []).map((m) =>
          m.id === messageId && m.kind === 'no_hit' ? { ...m, fallback_confirmed: true } : m,
        ),
      }))
      setPending(true)
      try {
        await answerGeneral(query)
      } catch (error) {
        append({ kind: 'error', id: nextId('m'), text: describeError(error) })
      } finally {
        setPending(false)
      }
    },
    [answerGeneral, append, conversationId],
  )

  /** 点击引用角标：右栏定位并高亮对应原文片段 */
  const openCitation = useCallback((hits: RetrievalHit[], chunkId: string, scopeDesc: string) => {
    setRightPane({ mode: 'source', hits, active_chunk_id: chunkId, scope_desc: scopeDesc })
  }, [])

  const openArtifact = useCallback((title: string) => {
    setRightPane({ mode: 'artifact', package_title: title })
  }, [])

  const newConversation = useCallback(() => {
    const id = nextId('cv')
    setConversationId(id)
    setRightPane({ mode: 'empty' })
  }, [])

  const switchConversation = useCallback((id: string) => {
    setConversationId(id)
    setRightPane({ mode: 'empty' })
  }, [])

  const conversations = useMemo(() => CONVERSATIONS, [])

  return {
    principal,
    models,
    conversations,
    conversationId,
    messages,
    selectedModel,
    rightPane,
    pending,
    send,
    selectModel,
    confirmGeneralFallback,
    openCitation,
    openArtifact,
    newConversation,
    switchConversation,
  }
}

export type Workbench = ReturnType<typeof useWorkbench>
