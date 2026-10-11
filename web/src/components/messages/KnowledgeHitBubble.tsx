// 消息态 1：企业知识命中。顶部标「公司相关 + 检索范围 + 命中数」，正文角标 [n] 可点击溯源
import type { KnowledgeHitMessage } from '../../state/messages'
import GatewayNotice from './GatewayNotice'
import styles from './Message.module.css'

interface Props {
  message: KnowledgeHitMessage
  onCiteClick: (chunkId: string) => void
}

export default function KnowledgeHitBubble({ message, onCiteClick }: Props) {
  return (
    <div className={styles.bubble} data-testid="msg-kb-hit">
      <div className={styles.banner}>
        公司相关 · 检索范围：{message.scope_desc} · 命中 {message.total} 条 · 模型：{message.model}
      </div>
      <GatewayNotice meta={message} />
      <div data-testid="answer-text">
        {message.parts.map((part, i) =>
          part.type === 'text' ? (
            <span key={i}>{part.text}</span>
          ) : (
            <button
              key={i}
              className={styles.cite}
              aria-label={`查看引用 ${part.index}`}
              onClick={() => onCiteClick(part.chunk_id)}
            >
              [{part.index}]
            </button>
          ),
        )}
      </div>
    </div>
  )
}
