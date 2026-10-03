// 测试工具：用可注入的 mock API 渲染整个工作台
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import App from '../App'
import { ApiContext, createMockApi, type WorkbenchApi } from '../api'

export function renderApp(overrides: Partial<WorkbenchApi> = {}) {
  const base = createMockApi()
  const api: WorkbenchApi = {
    search: vi.fn(base.search),
    generate: vi.fn(base.generate),
    listModels: vi.fn(base.listModels),
    getMatcherTask: vi.fn(base.getMatcherTask),
    getPrincipal: vi.fn(base.getPrincipal),
    ...overrides,
  }
  const utils = render(
    <ApiContext.Provider value={api}>
      <App />
    </ApiContext.Provider>,
  )
  return { api, user: userEvent.setup(), ...utils }
}

export async function ask(user: ReturnType<typeof userEvent.setup>, text: string) {
  await user.type(screen.getByLabelText('输入问题'), text)
  await user.click(screen.getByRole('button', { name: '发送' }))
}
