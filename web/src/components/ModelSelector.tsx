// 输入区上方的模型选择器：按 gateway 契约的三类合规通道分组，不合规项置灰并写明原因
import { CHANNEL_GROUP_TITLE, CHANNEL_ORDER } from '../api/models'
import type { ModelOption } from '../api/types'
import styles from './ModelSelector.module.css'

interface Props {
  models: ModelOption[]
  selectedModel: string
  onSelect: (model: string) => void
}

export default function ModelSelector({ models, selectedModel, onSelect }: Props) {
  return (
    <div className={styles.selector} role="group" aria-label="模型选择器">
      {CHANNEL_ORDER.map((group) => (
        <div key={group} className={styles.group}>
          <span className={styles.groupLabel}>{CHANNEL_GROUP_TITLE[group]}</span>
          {models
            .filter((m) => m.group === group)
            .map((m) => (
              <span key={m.model}>
                <button
                  className={`${styles.option} ${m.disabled ? styles.disabled : ''} ${
                    m.model === selectedModel ? styles.selected : ''
                  }`}
                  disabled={m.disabled}
                  aria-disabled={m.disabled}
                  aria-pressed={m.model === selectedModel}
                  title={m.disabled_reason}
                  onClick={() => onSelect(m.model)}
                >
                  {m.label}
                </button>
                {m.disabled && m.disabled_reason ? (
                  <span className={styles.reason}>（{m.disabled_reason}）</span>
                ) : null}
              </span>
            ))}
        </div>
      ))}
    </div>
  )
}
