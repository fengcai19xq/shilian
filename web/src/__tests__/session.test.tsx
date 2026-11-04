// 会话身份（WK17）：身份来自服务端，前端不自报；取不到时显示未登录而不是永久转圈
import { screen } from '@testing-library/react'
import { render, cleanup } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { ApiContext, createHttpApi } from '../api'
import { PRINCIPAL, mockFetch, type Handlers } from '../test/httpHarness'

const ME_PATH = '/api/identity/me'

function renderWithSession(handlers: Handlers, userId?: string) {
  const fetchState = mockFetch(handlers)
  // 不注入 principal：身份必须来自网络，这才是生产形态
  const api = createHttpApi({ userId })
  render(
    <ApiContext.Provider value={api}>
      <App />
    </ApiContext.Provider>,
  )
  return fetchState
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('会话身份', () => {
  it('从 /identity/me 取得主体后显示在侧边栏', async () => {
    const { calls } = renderWithSession({ [ME_PATH]: PRINCIPAL })

    await screen.findByText(PRINCIPAL.user_id)

    expect(calls[0].path).toBe(ME_PATH)
    expect(screen.getByText(PRINCIPAL.dept.join(' / '))).toBeInTheDocument()
    expect(screen.queryByText('加载中…')).not.toBeInTheDocument()
  })

  it('会话失效显示未登录，而不是停留在加载中', async () => {
    renderWithSession({ [ME_PATH]: { status: 401 } })

    await screen.findByText('未登录或会话已过期')
    expect(screen.queryByText('加载中…')).not.toBeInTheDocument()
  })

  it('后端不可达同样按未登录处理，不伪造主体', async () => {
    renderWithSession({ [ME_PATH]: { status: 500 } })

    await screen.findByText('未登录或会话已过期')
    expect(screen.queryByText(PRINCIPAL.user_id)).not.toBeInTheDocument()
  })

  /** 未登录前不应发出任何业务请求，避免匿名状态打到检索链路上。 */
  it('身份未就绪时页面仍可交互，业务请求由用户动作触发', async () => {
    const { calls } = renderWithSession({ [ME_PATH]: { status: 401 } })
    await screen.findByText('未登录或会话已过期')

    expect(calls.map((c) => c.path)).toEqual([ME_PATH])
  })

  it('本地联调可用构建期主体，不打身份网络', async () => {
    const { calls } = mockFetch({})
    const api = createHttpApi({ principal: PRINCIPAL })
    render(
      <ApiContext.Provider value={api}>
        <App />
      </ApiContext.Provider>,
    )

    await screen.findByText(PRINCIPAL.user_id)
    expect(calls.map((c) => c.path)).not.toContain(ME_PATH)
  })
})
