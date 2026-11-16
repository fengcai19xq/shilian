import type { NoticeMessage } from '../../state/messages'
import styles from './Message.module.css'

/** 轻量提示：用于引导上传清单等，不承载任何文档内容。 */
export default function NoticeBubble({ message }: { message: NoticeMessage }) {
  return (
    <div className={styles.bubble} data-testid="msg-notice">
      {message.text}
    </div>
  )
}
