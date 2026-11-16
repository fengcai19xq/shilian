// 工作台状态：会话、消息流、右栏溯源、模型选择（按会话记忆，不跨会话）
import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiHttpError, useApi } from '../api'
import {
  NO_SCOPE_DESC,
  type GatewayResponse,
  type MatcherTask,
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

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** 轮询 matcher 任务；mock 没有 state 时立即返回。生产最多等待约 30 秒。 */
async function waitForTask(api: ReturnType<typeof useApi>, taskId: string): Promise<MatcherTask> {
  let task = await api.getMatcherTask(taskId)
  for (let i = 0; i < 60; i++) {
    if (!task.state || task.state === 'succeeded' || task.state === 'failed') return task
    await sleep(500)
    task = await api.getMatcherTask(taskId)
  }
  return task
}

export function useWorkbench() {
  const api = useApi()
  const [principal, setPrincipal] = useState<Principal | null>(null)
  /** 会话身份是否取不到（未登录/会话过期）。与「还没加载完」区分开，避免界面一直转圈。 */
  const [unauthenticated, setUnauthenticated] = useState(false)
  const [models, setModels] = useState<ModelOption[]>([])
  const [conversationId, setConversationId] = useState(CONVERSATIONS[0].conversation_id)
  const [messagesByConversation, setMessagesByConversation] = useState<Record<string, Message[]>>({})
  /** 模型选择按会话记忆：换会话即回到默认，不跨会话继承 */
  const [modelByConversation, setModelByConversation] = useState<Record<string, string>>({})
  const [rightPane, setRightPane] = useState<RightPaneState>({ mode: 'empty' })
  const [pending, setPending] = useState(false)

  useEffect(() => {
    setPrincipal(null)
    setUnauthenticated(false)
    api
      .getPrincipal()
      .then(setPrincipal, () => setUnauthenticated(true))
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

  /** 通用问答：直接走 BFF，不携带 principal 与任何公司数据；身份由 BFF 会话解析 */
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
      // principal 由 BFF 从会话注入；前端只传问题与检索片段
      const answer = await api.generate({
        purpose: 'generate',
        sensitivity: highestSensitivity(context),
        preferred_model: selectedModel,
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
    [api, append, selectedModel],
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
          append({
            kind: 'notice',
            id: nextId('m'),
            text: '请点击输入框下方的「上传清单」，系统会解析清单并进入三态确认。',
          })
          return
        }
        const route = await api.route({
          question: text,
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
    [api, append, answerEnterprise, answerGeneral],
  )

  /** 上传清单 → 触发匹配 → 轮询任务 → 任务卡 */
  const uploadChecklist = useCallback(
    async (file: File) => {
      setPending(true)
      try {
        const checklist = await api.uploadChecklist(file)
        const run = await api.runChecklist(checklist.checklist_id)
        const task = await waitForTask(api, run.task_id)
        append({ kind: 'task_card', id: nextId('m'), task })
      } catch (error) {
        append({ kind: 'error', id: nextId('m'), text: describeError(error) })
      } finally {
        setPending(false)
      }
    },
    [api, append],
  )

  const replaceTask = useCallback(
    (messageId: string, task: MatcherTask) => {
      setMessagesByConversation((prev) => ({
        ...prev,
        [conversationId]: (prev[conversationId] ?? []).map((m) =>
          m.id === messageId && m.kind === 'task_card' ? { ...m, task } : m,
        ),
      }))
    },
    [conversationId],
  )

  /** 人工确认：只能绑定该清单项候选内的文档；空候选表示确认缺件。 */
  const confirmTaskItem = useCallback(
    async (messageId: string, task: MatcherTask, itemId: string, docIds: string[]) => {
      setPending(true)
      try {
        await api.confirmItem(itemId, docIds)
        replaceTask(messageId, await api.getMatcherTask(task.task_id))
      } catch (error) {
        append({ kind: 'error', id: nextId('m'), text: describeError(error) })
      } finally {
        setPending(false)
      }
    },
    [api, append, replaceTask],
  )

  /** 成册：manifest 由 BFF 从 matcher 生成，浏览器不提交 manifest。 */
  const packageTask = useCallback(
    async (task: MatcherTask) => {
      setPending(true)
      try {
        const result = await api.packageChecklist(task.checklist_id)
        setRightPane({ mode: 'artifact', package_title: result.package_id || task.title })
      } catch (error) {
        append({ kind: 'error', id: nextId('m'), text: describeError(error) })
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
    unauthenticated,
    models,
    conversations,
    conversationId,
    messages,
    selectedModel,
    rightPane,
    pending,
    send,
    uploadChecklist,
    confirmTaskItem,
    packageTask,
    selectModel,
    confirmGeneralFallback,
    openCitation,
    openArtifact,
    newConversation,
    switchConversation,
  }
}

export type Workbench = ReturnType<typeof useWorkbench>
