// 消息态 3：通用问答。顶部标模型名与未检索企业知识库，底部标不作为对外口径
import type { GeneralMessage } from '../../state/messages'
import styles from './Message.module.css'

export default function GeneralBubble({ message }: { message: GeneralMessage }) {
  return (
    <div className={styles.bubble} data-testid="msg-general">
      <div className={styles.banner}>
        通用问题 · 模型：{message.model} · 未检索企业知识库
      </div>
      <div>{message.text}</div>
      {message.switched_reason ? (
        <div className={styles.switched}>已切换合规通道：{message.switched_reason}</div>
      ) : null}
      <div className={styles.footnote}>本回答不作为对外口径</div>
    </div>
  )
}
