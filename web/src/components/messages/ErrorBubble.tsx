import type { ErrorMessage } from '../../state/messages'
import styles from './Message.module.css'

export default function ErrorBubble({ message }: { message: ErrorMessage }) {
  return (
    <div className={styles.bubble} data-testid="msg-error">
      <div className={styles.banner}>请求失败</div>
      <div className={styles.warn}>{message.text}</div>
    </div>
  )
}
