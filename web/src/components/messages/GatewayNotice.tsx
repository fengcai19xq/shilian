// 网关通道提示：switched_reason 存在时在回答上方显式提示切换原因与实际通道（红线 4）
import { CHANNEL_LABEL } from '../../api/models'
import type { GatewayMeta } from '../../state/messages'
import styles from './Message.module.css'

export default function GatewayNotice({ meta }: { meta: GatewayMeta }) {
  return (
    <>
      {meta.switched_reason ? (
        <div className={styles.switched} role="alert" data-testid="switched-notice">
          已切换合规通道：{meta.switched_reason} · 实际通道：
          {meta.channel ? CHANNEL_LABEL[meta.channel] : '未标注'} · 实际模型：{meta.model}
        </div>
      ) : null}
      {meta.budget_alert ? (
        <div className={styles.budget} data-testid="budget-alert">
          {meta.budget_alert}
        </div>
      ) : null}
    </>
  )
}
