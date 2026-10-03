// 红线：权限拒答不得泄露越权文档名，并提供临时授权入口
import { screen, within } from '@testing-library/react'
import { ask, renderApp } from '../test/renderApp'

describe('权限拒答', () => {
  it('说明无权访问、提供临时授权入口、不显示文档名也不调用通用模型', async () => {
    const { user, api } = renderApp()
    await ask(user, '高管薪酬方案')
    const bubble = await screen.findByTestId('msg-permission-denied')
    expect(bubble).toHaveTextContent('无法作答')
    expect(within(bubble).getByRole('button', { name: '申请临时授权' })).toBeInTheDocument()
    expect(bubble.textContent).not.toMatch(/\.pdf|\.docx|\.xlsx/)
    expect(api.generate).not.toHaveBeenCalled()
    expect(screen.getByTestId('right-pane')).toHaveTextContent('暂无引用来源')
  })
})
