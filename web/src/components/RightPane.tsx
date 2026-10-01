// 右栏：引用溯源 / 产物预览 / 空状态
import type { RightPaneState } from '../state/useWorkbench'
import styles from './RightPane.module.css'

export default function RightPane({ state }: { state: RightPaneState }) {
  if (state.mode === 'empty') {
    return (
      <aside className={styles.pane} data-testid="right-pane">
        <div className={styles.empty}>暂无引用来源；通用问答不检索企业知识库</div>
      </aside>
    )
  }

  if (state.mode === 'artifact') {
    return (
      <aside className={styles.pane} data-testid="right-pane">
        <div className={styles.title}>产物预览</div>
        <div className={styles.scope}>{state.package_title}</div>
        <div className={styles.snippet}>zip / merged_pdf / checklist_xlsx（待人工终审后外发）</div>
      </aside>
    )
  }

  return (
    <aside className={styles.pane} data-testid="right-pane">
      <div className={styles.title}>引用溯源</div>
      <div className={styles.scope}>检索范围：{state.scope_desc}</div>
      {state.hits.map((hit) => {
        const active = hit.chunk_id === state.active_chunk_id
        return (
          <div
            key={hit.chunk_id}
            className={`${styles.snippet} ${active ? styles.active : ''}`}
            data-testid={`source-${hit.chunk_id}`}
            data-active={active}
          >
            <div className={styles.meta}>
              {hit.doc_name} · 第 {hit.page} 页 · {hit.dept} · {hit.sensitivity}
            </div>
            <div>{hit.text}</div>
          </div>
        )
      })}
    </aside>
  )
}
