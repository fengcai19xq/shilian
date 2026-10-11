// prompt 组织与角标解析
import type { RetrievalHit } from '../api/types'
import {
  MAX_CHUNK_CHARS,
  MAX_CONTEXT_CHUNKS,
  buildEnterpriseMessages,
  highestSensitivity,
  parseAnswerParts,
  selectContext,
} from '../state/answer'

const hit = (i: number, sensitivity: RetrievalHit['sensitivity'] = 'L1', text = `片段${i}`): RetrievalHit => ({
  chunk_id: `c_${i}`,
  doc_id: `d_${i}`,
  doc_name: `文档${i}.pdf`,
  dept: '财务共享',
  sensitivity,
  page: i,
  score: 1 - i / 100,
  text,
})

describe('企业知识 prompt 与角标', () => {
  it('上下文按 top 片段截断，超长片段截断', () => {
    const hits = Array.from({ length: 10 }, (_, i) => hit(i + 1))
    expect(selectContext(hits)).toHaveLength(MAX_CONTEXT_CHUNKS)
    const long = hit(1, 'L1', '字'.repeat(MAX_CHUNK_CHARS + 50))
    const [, userTurn] = buildEnterpriseMessages('问题', [long])
    expect(userTurn.content).toContain(`${'字'.repeat(MAX_CHUNK_CHARS)}…`)
    expect(userTurn.content).not.toContain('字'.repeat(MAX_CHUNK_CHARS + 1))
  })

  it('密级取所用片段的最高值', () => {
    expect(highestSensitivity([hit(1, 'L2'), hit(2, 'L3'), hit(3, 'L1')])).toBe('L3')
    expect(highestSensitivity([])).toBe('L1')
  })

  it('[n] 解析为角标并对应第 n 个片段，越界编号保留为文本', () => {
    const parts = parseAnswerParts('甲[1]乙[2][3]丙', [hit(1), hit(2)])
    expect(parts).toEqual([
      { type: 'text', text: '甲' },
      { type: 'cite', index: 1, chunk_id: 'c_1' },
      { type: 'text', text: '乙' },
      { type: 'cite', index: 2, chunk_id: 'c_2' },
      { type: 'text', text: '[3]丙' },
    ])
  })
})
