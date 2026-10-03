// 消息态 2：库内未命中。如实告知 + 上传入口 + 二次确认后才允许改用通用模型
import { useState } from 'react'
import type { NoHitMessage } from '../../state/messages'
import styles from './Message.module.css'

interface Props {
  message: NoHitMessage
  onConfirmFallback: (messageId: string, query: string) => void
}

export default function NoHitBubble({ message, onConfirmFallback }: Props) {
  const [asking, setAsking] = useState(false)

  return (
    <div className={styles.bubble} data-testid="msg-no-hit">
      <div className={styles.banner}>公司相关 · 已检索企业知识库</div>
      <div>知识库暂无相关资料，未找到可引用的企业文档。</div>
      <div className={styles.actions}>
        <button className={styles.action}>上传资料</button>
        {message.fallback_confirmed ? null : asking ? (
          <>
            <span>通用模型回答不基于企业资料，是否继续？</span>
            <button
              className={`${styles.action} ${styles.primary}`}
              onClick={() => onConfirmFallback(message.id, message.query)}
            >
              确认改用通用模型
            </button>
            <button className={styles.action} onClick={() => setAsking(false)}>
              取消
            </button>
          </>
        ) : (
          <button className={styles.action} onClick={() => setAsking(true)}>
            是否改用通用模型回答
          </button>
        )}
      </div>
    </div>
  )
}
