// 消息态 4：清单成册任务卡。进度条 + 已匹配/待确认/缺失三态列表 + 去确认/派单/生成资料包
import type { MatchStatus } from '../../api/types'
import type { TaskCardMessage } from '../../state/messages'
import styles from './Message.module.css'

interface Props {
  message: TaskCardMessage
  onGeneratePackage: (title: string) => void
}

const STATE_TITLE: Record<MatchStatus, string> = {
  matched: '已匹配',
  pending: '待确认',
  missing: '缺失',
}

export default function TaskCardBubble({ message, onGeneratePackage }: Props) {
  const { task } = message

  return (
    <div className={styles.bubble} data-testid="msg-task-card">
      <div className={styles.banner}>清单成册 · {task.title}</div>
      <div className={styles.progressOuter} role="progressbar" aria-valuenow={Math.round(task.progress * 100)}>
        <div className={styles.progressInner} style={{ width: `${Math.round(task.progress * 100)}%` }} />
      </div>
      <div>进度 {Math.round(task.progress * 100)}%</div>

      {(['matched', 'pending', 'missing'] as const).map((status) => {
        const items = task.items.filter((item) => item.status === status)
        return (
          <section key={status} data-testid={`task-group-${status}`}>
            <div className={`${styles.stateGroupTitle} ${styles[status]}`}>
              {STATE_TITLE[status]}（{items.length}）
            </div>
            <ul className={styles.itemList}>
              {items.map((item) => (
                <li key={item.item_id} className={styles.item} data-testid={`task-item-${item.status}`}>
                  <span>{item.no}</span>
                  <span>{item.raw_text}</span>
                  {item.candidate_doc_name ? <span>→ {item.candidate_doc_name}</span> : null}
                  {item.note ? <span className={styles.warn}>{item.note}</span> : null}
                </li>
              ))}
            </ul>
          </section>
        )
      })}

      <div className={styles.actions}>
        <button className={styles.action}>去确认</button>
        <button className={styles.action}>缺件派单</button>
        <button
          className={`${styles.action} ${styles.primary}`}
          onClick={() => onGeneratePackage(task.title)}
        >
          生成资料包
        </button>
      </div>
      <div className={styles.footnote}>资料包生成后仍需人工终审并登记外发，系统不自动对外发送</div>
    </div>
  )
}
