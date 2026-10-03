// 消息态 5：权限拒答。只说明无权访问，不显示任何越权文档名
import styles from './Message.module.css'

export default function PermissionDeniedBubble({ onApply }: { onApply?: () => void }) {
  return (
    <div className={styles.bubble} data-testid="msg-permission-denied">
      <div className={styles.banner}>权限提示</div>
      <div>你当前的权限范围内没有可回答该问题的资料，无法作答。</div>
      <div className={styles.actions}>
        <button className={`${styles.action} ${styles.primary}`} onClick={onApply}>
          申请临时授权
        </button>
      </div>
    </div>
  )
}
