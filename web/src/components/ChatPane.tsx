// 中栏：对话流 + 输入区
import type { Workbench } from '../state/useWorkbench'
import Composer from './Composer'
import GeneralBubble from './messages/GeneralBubble'
import KnowledgeHitBubble from './messages/KnowledgeHitBubble'
import NoHitBubble from './messages/NoHitBubble'
import PermissionDeniedBubble from './messages/PermissionDeniedBubble'
import TaskCardBubble from './messages/TaskCardBubble'
import UserBubble from './messages/UserBubble'
import styles from './ChatPane.module.css'

export default function ChatPane({ workbench }: { workbench: Workbench }) {
  const { messages, models, selectedModel, pending } = workbench

  return (
    <main className={styles.pane}>
      <div className={styles.stream}>
        {messages.length === 0 ? (
          <div className={styles.empty}>提问企业知识，或输入「清单」查看成册任务</div>
        ) : null}
        {messages.map((message) => {
          switch (message.kind) {
            case 'user':
              return <UserBubble key={message.id} message={message} />
            case 'kb_hit':
              return (
                <KnowledgeHitBubble
                  key={message.id}
                  message={message}
                  onCiteClick={(chunkId) =>
                    workbench.openCitation(message.hits, chunkId, message.scope_desc)
                  }
                />
              )
            case 'no_hit':
              return (
                <NoHitBubble
                  key={message.id}
                  message={message}
                  onConfirmFallback={workbench.confirmGeneralFallback}
                />
              )
            case 'general':
              return <GeneralBubble key={message.id} message={message} />
            case 'task_card':
              return (
                <TaskCardBubble
                  key={message.id}
                  message={message}
                  onGeneratePackage={workbench.openArtifact}
                />
              )
            case 'permission_denied':
              return <PermissionDeniedBubble key={message.id} />
          }
        })}
      </div>
      <Composer
        models={models}
        selectedModel={selectedModel}
        pending={pending}
        onSelectModel={workbench.selectModel}
        onSend={(text) => void workbench.send(text)}
      />
    </main>
  )
}
