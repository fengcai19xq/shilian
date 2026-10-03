// 红线 2：库内未命中必须如实告知，用户二次确认前不得调用通用模型
import { screen, within } from '@testing-library/react'
import { ask, renderApp } from '../test/renderApp'

describe('库内未命中', () => {
  it('显示未命中提示与上传入口，未确认前不触发通用模型调用', async () => {
    const { api, user } = renderApp()
    await ask(user, '公司食堂菜单')

    const bubble = await screen.findByTestId('msg-no-hit')
    expect(within(bubble).getByText(/知识库暂无相关资料/)).toBeInTheDocument()
    expect(within(bubble).getByRole('button', { name: '上传资料' })).toBeInTheDocument()
    expect(api.search).toHaveBeenCalledTimes(1)
    expect(api.generate).not.toHaveBeenCalled()

    // 第一次点击只弹出二次确认，仍不调用
    await user.click(within(bubble).getByRole('button', { name: '是否改用通用模型回答' }))
    expect(api.generate).not.toHaveBeenCalled()

    // 取消后依旧不调用
    await user.click(within(bubble).getByRole('button', { name: '取消' }))
    expect(api.generate).not.toHaveBeenCalled()
    expect(screen.queryByTestId('msg-general')).not.toBeInTheDocument()
  })

  it('二次确认后才调用通用模型，并标注模型名与「不作为对外口径」', async () => {
    const { api, user } = renderApp()
    await ask(user, '公司食堂菜单')

    const bubble = await screen.findByTestId('msg-no-hit')
    await user.click(within(bubble).getByRole('button', { name: '是否改用通用模型回答' }))
    await user.click(within(bubble).getByRole('button', { name: '确认改用通用模型' }))

    expect(api.generate).toHaveBeenCalledTimes(1)
    const general = await screen.findByTestId('msg-general')
    expect(within(general).getByText(/通用问题 · 模型：deepseek-v3-enterprise · 未检索企业知识库/)).toBeInTheDocument()
    expect(within(general).getByText('本回答不作为对外口径')).toBeInTheDocument()
    expect(screen.getByTestId('right-pane')).toHaveTextContent('暂无引用来源')
    // 已确认后不再显示确认按钮，避免重复调用
    expect(within(bubble).queryByRole('button', { name: /通用模型/ })).not.toBeInTheDocument()
  })

  it('网关返回 switched_reason 时显式提示切换通道', async () => {
    const { user } = renderApp({
      generate: vi.fn().mockResolvedValue({
        model: 'deepseek-v3-enterprise',
        switched_reason: 'L3 内容不允许通用通道',
        text: '示例',
        usage: { prompt_tokens: 1, completion_tokens: 1, cost_cny: 0 },
      }),
    })
    await ask(user, '公司食堂菜单')
    const bubble = await screen.findByTestId('msg-no-hit')
    await user.click(within(bubble).getByRole('button', { name: '是否改用通用模型回答' }))
    await user.click(within(bubble).getByRole('button', { name: '确认改用通用模型' }))
    expect(await screen.findByText('已切换合规通道：L3 内容不允许通用通道')).toBeInTheDocument()
  })
})
