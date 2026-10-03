// 清单成册任务卡：进度条与三态列表
import { screen, within } from '@testing-library/react'
import { ask, renderApp } from '../test/renderApp'

describe('清单成册任务卡', () => {
  it('三态分组渲染正确并提供操作按钮', async () => {
    const { user } = renderApp()
    await ask(user, '帮我按中信银行清单成册')
    const card = await screen.findByTestId('msg-task-card')

    expect(within(card).getByRole('progressbar')).toHaveAttribute('aria-valuenow', '72')

    const matched = within(card).getByTestId('task-group-matched')
    const pending = within(card).getByTestId('task-group-pending')
    const missing = within(card).getByTestId('task-group-missing')
    expect(matched).toHaveTextContent('已匹配（1）')
    expect(matched).toHaveTextContent('最近三年审计报告')
    expect(pending).toHaveTextContent('待确认（1）')
    expect(pending).toHaveTextContent('近一年主要银行流水')
    expect(missing).toHaveTextContent('缺失（1）')
    expect(missing).toHaveTextContent('最新版公司章程')
    expect(within(missing).getAllByTestId('task-item-missing')).toHaveLength(1)

    for (const name of ['去确认', '缺件派单', '生成资料包']) {
      expect(within(card).getByRole('button', { name })).toBeInTheDocument()
    }

    await user.click(within(card).getByRole('button', { name: '生成资料包' }))
    expect(screen.getByTestId('right-pane')).toHaveTextContent('产物预览')
  })
})
