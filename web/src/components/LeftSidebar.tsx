// 左栏：新建对话 / 我的智能体 / 任务中心 / 知识库 / 平台管理面 / 历史会话 / 当前用户与部门
import type { Conversation } from '../state/useWorkbench'
import type { AdminConsoleEntry } from '../api/adminConsole'
import type { Principal } from '../api/types'
import styles from './LeftSidebar.module.css'

const AGENTS = ['融资助手', '人事助手', '经营查数']

interface Props {
  principal: Principal | null
  conversations: Conversation[]
  conversationId: string
  onNewConversation: () => void
  onSwitchConversation: (id: string) => void
  /** 平台管理面入口；url 为空时不渲染（未接入 WeKnora 的部署） */
  adminConsole?: AdminConsoleEntry
}

export default function LeftSidebar({
  principal,
  conversations,
  conversationId,
  onNewConversation,
  onSwitchConversation,
  adminConsole,
}: Props) {
  return (
    <nav className={styles.sidebar} aria-label="侧边导航">
      <button className={styles.newChat} onClick={onNewConversation}>
        + 新建对话
      </button>

      <section>
        <div className={styles.groupTitle}>我的智能体</div>
        {AGENTS.map((agent) => (
          <button key={agent} className={styles.item}>
            {agent}
          </button>
        ))}
      </section>

      <section>
        <div className={styles.groupTitle}>工作区</div>
        <button className={styles.item}>任务中心</button>
        <button className={styles.item}>知识库</button>
      </section>

      {adminConsole?.url ? (
        <section>
          <div className={styles.groupTitle}>平台</div>
          <a
            className={styles.item}
            href={adminConsole.url}
            target="_blank"
            rel="noreferrer"
            title={adminConsole.hint}
            data-testid="admin-console-entry"
          >
            {adminConsole.label} ↗
          </a>
        </section>
      ) : null}

      <section>
        <div className={styles.groupTitle}>历史会话</div>
        {conversations.map((cv) => (
          <button
            key={cv.conversation_id}
            className={`${styles.item} ${cv.conversation_id === conversationId ? styles.active : ''}`}
            onClick={() => onSwitchConversation(cv.conversation_id)}
          >
            {cv.title}
          </button>
        ))}
      </section>

      <div className={styles.user}>
        {principal ? (
          <>
            <div>{principal.user_id}</div>
            <div>{principal.dept.join(' / ')}</div>
          </>
        ) : (
          <div>加载中…</div>
        )}
      </div>
    </nav>
  )
}
