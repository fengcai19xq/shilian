// PC 三栏工作台：左栏导航 / 中栏对话 / 右栏溯源
// 管理面（WeKnora 原生 UI）只作为外链入口出现在左栏，问答与成册等业务操作留在本工作台（决策 D2）
import ChatPane from './components/ChatPane'
import LeftSidebar from './components/LeftSidebar'
import RightPane from './components/RightPane'
import { adminConsoleFromEnv } from './api/adminConsole'
import { useWorkbench } from './state/useWorkbench'
import styles from './App.module.css'

export default function App() {
  const workbench = useWorkbench()
  const adminConsole = adminConsoleFromEnv(import.meta.env)

  return (
    <div className={styles.layout}>
      <LeftSidebar
        principal={workbench.principal}
        conversations={workbench.conversations}
        conversationId={workbench.conversationId}
        onNewConversation={workbench.newConversation}
        onSwitchConversation={workbench.switchConversation}
        adminConsole={adminConsole}
      />
      <ChatPane workbench={workbench} />
      <RightPane state={workbench.rightPane} />
    </div>
  )
}
