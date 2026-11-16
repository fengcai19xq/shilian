// 清单成册任务卡：上传清单 → 三态列表 → 人工确认 → 生成资料包
import { screen, within } from '@testing-library/react'
import { renderApp } from '../test/renderApp'

const FILE = new File(['清单'], '清单.xlsx', {
  type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
})

describe('清单成册任务卡', () => {
  it('上传清单后三态分组渲染，并可确认待确认项、生成资料包', async () => {
    const { user, api } = renderApp()
    await user.upload(screen.getByLabelText('上传清单'), FILE)
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

    expect(within(card).getByRole('button', { name: '确认绑定' })).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: '生成资料包' })).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: '确认绑定' }))
    expect(api.confirmItem).toHaveBeenCalledWith('it_0032', ['d_flow'])

    await user.click(within(card).getByRole('button', { name: '生成资料包' }))
    expect(api.packageChecklist).toHaveBeenCalledWith('cl_0007')
    expect(screen.getByTestId('right-pane')).toHaveTextContent('产物预览')
  })

  it('输入「清单」时引导上传，不伪造任务卡', async () => {
    const { user } = renderApp()
    await user.type(screen.getByLabelText('输入问题'), '帮我按中信银行清单成册')
    await user.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByTestId('msg-notice')).toHaveTextContent('上传清单')
    expect(screen.queryByTestId('msg-task-card')).not.toBeInTheDocument()
  })
})
