import type { UserMessage } from '../../state/messages'
import styles from './Message.module.css'

export default function UserBubble({ message }: { message: UserMessage }) {
  return <div className={`${styles.bubble} ${styles.user}`}>{message.text}</div>
}
