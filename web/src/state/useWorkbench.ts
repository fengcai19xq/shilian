// 工作台状态：会话、消息流、右栏溯源、模型选择（按会话记忆，不跨会话）
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useApi } from '../api'
import type { ModelOption, Principal, RetrievalHit } from '../api/types'
import { buildAnswerParts, type Message } from './messages'

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

let seq = 0
const nextId = (prefix: string) => `${prefix}_${++seq}`

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
    void api.getPrincipal().then(setPrincipal)
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

  /** 发送提问：先检索，未命中时只如实告知，绝不静默转通用模型 */
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
        const result = await api.search({ query: text, kb_scope: KB_SCOPE, top_k: 8, mode: 'full' })
        if (result.permission_denied) {
          append({ kind: 'permission_denied', id: nextId('m') })
          return
        }
        if (result.total > 0) {
          append({
            kind: 'kb_hit',
            id: nextId('m'),
            scope_desc: result.scope_desc,
            total: result.total,
            parts: buildAnswerParts(result.hits),
            hits: result.hits,
          })
          return
        }
        append({ kind: 'no_hit', id: nextId('m'), query: text, fallback_confirmed: false })
      } finally {
        setPending(false)
      }
    },
    [api, append],
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
        const answer = await api.generate({
          purpose: 'generate',
          sensitivity: 'L1',
          preferred_model: selectedModel,
          payload: { query },
        })
        append({
          kind: 'general',
          id: nextId('m'),
          model: answer.model,
          text: answer.text,
          switched_reason: answer.switched_reason,
        })
      } finally {
        setPending(false)
      }
    },
    [api, append, conversationId, selectedModel],
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
