// 输入区上方的模型选择器：分两组展示，不合规项置灰并写明原因
import type { ModelOption } from '../api/types'
import styles from './ModelSelector.module.css'

interface Props {
  models: ModelOption[]
  selectedModel: string
  onSelect: (model: string) => void
}

const GROUP_LABEL: Record<ModelOption['group'], string> = {
  general: '通用问答可选',
  corporate: '涉及公司资料可用',
}

export default function ModelSelector({ models, selectedModel, onSelect }: Props) {
  return (
    <div className={styles.selector} role="group" aria-label="模型选择器">
      {(['general', 'corporate'] as const).map((group) => (
        <div key={group} className={styles.group}>
          <span className={styles.groupLabel}>{GROUP_LABEL[group]}</span>
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
