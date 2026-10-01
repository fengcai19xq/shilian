// 企业知识命中：顶部标识 + 引用角标点击后右栏定位并高亮原文
import { screen, within } from '@testing-library/react'
import { ask, renderApp } from '../test/renderApp'

describe('企业知识命中与引用溯源', () => {
  it('顶部显示公司相关、检索范围与命中数', async () => {
    const { user, api } = renderApp()
    await ask(user, '最近三年研发费用占营收比例')
    const bubble = await screen.findByTestId('msg-kb-hit')
    expect(bubble).toHaveTextContent('公司相关 · 检索范围：融资部 / 财务共享（你的权限内） · 命中 3 条')
    expect(api.generate).not.toHaveBeenCalled()
    expect(screen.getByTestId('right-pane')).toHaveTextContent('暂无引用来源')
  })

  it('点击角标后右栏定位并高亮对应片段', async () => {
    const { user } = renderApp()
    await ask(user, '最近三年研发费用占营收比例')
    const bubble = await screen.findByTestId('msg-kb-hit')

    await user.click(within(bubble).getByRole('button', { name: '查看引用 2' }))
    const pane = screen.getByTestId('right-pane')
    expect(pane).toHaveTextContent('引用溯源')
    const active = within(pane).getByTestId('source-c_88157')
    expect(active).toHaveAttribute('data-active', 'true')
    expect(active).toHaveTextContent('2022年度审计报告.pdf · 第 33 页')
    expect(within(pane).getByTestId('source-c_88213')).toHaveAttribute('data-active', 'false')

    await user.click(within(bubble).getByRole('button', { name: '查看引用 1' }))
    expect(within(pane).getByTestId('source-c_88213')).toHaveAttribute('data-active', 'true')
    expect(within(pane).getByTestId('source-c_88157')).toHaveAttribute('data-active', 'false')
  })
})
