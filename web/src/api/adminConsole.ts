// 平台管理面入口（WK12 双前端切分）
//
// 分工（评审决策 D2）：
//   - 管理面：知识库创建、模型配置、成员管理 → WeKnora 原生 UI，本模块只提供入口链接
//   - 业务工作台：企业问答、引用溯源、清单成册、审计 → 留在 shilian web
//
// 为什么问答必须留在这里，而不是直接跳 WeKnora UI：
//   红线 4 要求模型切换通道时前端显式提示 switched_reason，而 WeKnora 走标准
//   OpenAI 协议、响应体无处承载该字段（contracts/weknora-integration.md §2.5）。
//   因此入口只做「管理面跳转」，问答链路仍以本工作台为唯一入口。
//
// 共享认证态的落地边界（重要，别按注释想当然）：
//   首期入口是「新窗口打开 WeKnora 原生 UI」，两套 UI 各自登录。
//   想做到同域共用 Cookie，需要下面任一条件，二者都不在当前范围：
//     1) 把 WeKnora 反代到子路径（如 /weknora/）——WeKnora 是 SPA，其绝对路径资源
//        （/assets/…）会打到本前端导致 404，除非上游支持配置 base path；
//     2) 统一身份：接 OIDC 后两侧走同一个 IdP（排在 WK17）。
//   因此这里不把「免二次登录」当已实现能力，也不在前端传递任何 token。

/** 管理面入口配置。url 为空表示未接入 WeKnora，此时不渲染入口。 */
export interface AdminConsoleEntry {
  url: string
  label: string
  /** 悬停说明：讲清用途与「问答不在此处」的原因，避免用户误用绕过红线 4 提示。 */
  hint: string
}

export const ADMIN_CONSOLE_HINT = '知识库、模型与成员在此配置；企业问答请留在本工作台（需显式提示通道切换）'

/** 同域反代时的默认路径前缀，与 nginx / vite 的转发规则保持一致。 */
export const DEFAULT_ADMIN_CONSOLE_PATH = '/weknora/'

/**
 * 从环境变量解析管理面入口。
 *
 * VITE_WEKNORA_CONSOLE_URL 可填完整 URL 或同域路径；留空视为未接入。
 * 不做跨站 fallback：跨域直连 WeKnora 会丢同域 Cookie，等于要用户重复登录。
 */
export function adminConsoleFromEnv(env: ImportMetaEnv): AdminConsoleEntry {
  const raw = (env.VITE_WEKNORA_CONSOLE_URL ?? '').trim()
  if (!raw) return { url: '', label: '平台管理面', hint: ADMIN_CONSOLE_HINT }
  return { url: normalize(raw), label: '平台管理面', hint: ADMIN_CONSOLE_HINT }
}

/** 同域路径必须带结尾斜杠，否则相对资源会解析到错误前缀。 */
export function normalize(url: string): string {
  if (/^https?:\/\//i.test(url)) return url
  const withLeading = url.startsWith('/') ? url : `/${url}`
  return withLeading.endsWith('/') ? withLeading : `${withLeading}/`
}
