// 平台管理面入口（WK12 双前端切分）
// 关注两件事：配置了才出现（未接入 WeKnora 的部署不多一个死链），
// 以及入口是外链而非站内路由（问答链路必须留在本工作台，红线 4 需要前端显式提示通道切换）
import { screen } from '@testing-library/react'
import { renderApp } from '../test/renderApp'
import { adminConsoleFromEnv, normalize } from '../api/adminConsole'

describe('normalize', () => {
  it('同域路径补齐首尾斜杠，避免相对资源解析到错误前缀', () => {
    expect(normalize('weknora')).toBe('/weknora/')
    expect(normalize('/weknora')).toBe('/weknora/')
    expect(normalize('/weknora/')).toBe('/weknora/')
  })

  it('完整 URL 原样保留', () => {
    expect(normalize('http://192.168.200.53:8091')).toBe('http://192.168.200.53:8091')
  })
})

describe('adminConsoleFromEnv', () => {
  it('未配置时 url 为空，表示不渲染入口', () => {
    expect(adminConsoleFromEnv({} as ImportMetaEnv).url).toBe('')
  })

  it('空串与纯空白同样视为未接入', () => {
    expect(adminConsoleFromEnv({ VITE_WEKNORA_CONSOLE_URL: '   ' } as ImportMetaEnv).url).toBe('')
  })

  it('配置后归一化并带上用途说明', () => {
    const entry = adminConsoleFromEnv({ VITE_WEKNORA_CONSOLE_URL: 'weknora' } as ImportMetaEnv)
    expect(entry.url).toBe('/weknora/')
    // 说明必须点明「问答不在此处」，否则用户会绕过红线 4 的通道切换提示
    expect(entry.hint).toContain('问答')
  })
})

describe('左栏管理面入口', () => {
  it('未配置 url 时不渲染入口', async () => {
    renderApp()
    // 等异步主体加载完成，避免 act 警告与竞态
    await screen.findByText(/加载中…|u_/)
    expect(screen.queryByTestId('admin-console-entry')).toBeNull()
  })

  it('配置后以新窗口外链出现，不抢占当前问答页', async () => {
    vi.stubEnv('VITE_WEKNORA_CONSOLE_URL', 'http://192.168.200.53:8091')
    renderApp()
    const entry = await screen.findByTestId('admin-console-entry')
    expect(entry).toHaveAttribute('href', 'http://192.168.200.53:8091')
    expect(entry).toHaveAttribute('target', '_blank')
    vi.unstubAllEnvs()
  })
})
