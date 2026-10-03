// 模型选择器：分组展示，不合规项置灰不可选并显示原因，选择按会话记忆
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ModelSelector from '../components/ModelSelector'
import { mockData } from '../api/mock'
import { renderApp } from '../test/renderApp'

describe('模型选择器', () => {
  it('分两组展示，置灰项 disabled 并显示原因，点击不触发选择', async () => {
    const onSelect = vi.fn()
    render(<ModelSelector models={mockData.MODELS} selectedModel="deepseek-v3" onSelect={onSelect} />)

    expect(screen.getByText('通用问答可选')).toBeInTheDocument()
    expect(screen.getByText('涉及公司资料可用')).toBeInTheDocument()

    const gpt = screen.getByRole('button', { name: 'GPT-4o' })
    expect(gpt).toBeDisabled()
    expect(screen.getByText('（未签数据处理协议）')).toBeInTheDocument()

    await userEvent.click(gpt)
    expect(onSelect).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: '通义千问 Max（公有云）' }))
    expect(onSelect).toHaveBeenCalledWith('qwen-max')
  })

  it('选择按会话记忆，不跨会话继承', async () => {
    const { user } = renderApp()
    const qwen = await screen.findByRole('button', { name: '通义千问 Max（公有云）' })
    await user.click(qwen)
    expect(qwen).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByRole('button', { name: '研发费用占比口径' }))
    expect(screen.getByRole('button', { name: '通义千问 Max（公有云）' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: 'DeepSeek V3 企业版' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByRole('button', { name: '中信银行授信资料准备' }))
    expect(screen.getByRole('button', { name: '通义千问 Max（公有云）' })).toHaveAttribute('aria-pressed', 'true')
  })
})
