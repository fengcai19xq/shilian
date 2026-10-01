// PC 三栏工作台：左栏导航 / 中栏对话 / 右栏溯源
import ChatPane from './components/ChatPane'
import LeftSidebar from './components/LeftSidebar'
import RightPane from './components/RightPane'
import { useWorkbench } from './state/useWorkbench'
import styles from './App.module.css'

export default function App() {
  const workbench = useWorkbench()

  return (
    <div className={styles.layout}>
      <LeftSidebar
        principal={workbench.principal}
        conversations={workbench.conversations}
        conversationId={workbench.conversationId}
        onNewConversation={workbench.newConversation}
        onSwitchConversation={workbench.switchConversation}
      />
      <ChatPane workbench={workbench} />
      <RightPane state={workbench.rightPane} />
    </div>
  )
}
