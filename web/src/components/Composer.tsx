// 输入区：模型选择器 + 文本框 + 发送
import { useState } from 'react'
import type { ModelOption } from '../api/types'
import ModelSelector from './ModelSelector'
import styles from './Composer.module.css'

interface Props {
  models: ModelOption[]
  selectedModel: string
  pending: boolean
  onSelectModel: (model: string) => void
  onSend: (text: string) => void
}

export default function Composer({ models, selectedModel, pending, onSelectModel, onSend }: Props) {
  const [text, setText] = useState('')

  const submit = () => {
    if (!text.trim() || pending) return
    onSend(text)
    setText('')
  }

  return (
    <div className={styles.composer}>
      <ModelSelector models={models} selectedModel={selectedModel} onSelect={onSelectModel} />
      <div className={styles.row}>
        <textarea
          className={styles.input}
          aria-label="输入问题"
          placeholder="问点什么，例如：最近三年研发费用占营收比例"
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
        <button className={styles.send} onClick={submit} disabled={pending}>
          发送
        </button>
      </div>
    </div>
  )
}
