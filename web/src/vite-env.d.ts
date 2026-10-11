/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** mock（默认）| http */
  readonly VITE_API_MODE?: string
  /** 接口前缀，默认 /api */
  readonly VITE_API_BASE?: string
  /** 本地联调用的会话用户 ID（X-User-Id） */
  readonly VITE_USER_ID?: string
  /** 本地联调用的权限主体 JSON（生产由网关按会话态注入） */
  readonly VITE_DEV_PRINCIPAL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// CSS Modules 类型声明
declare module '*.module.css' {
  const classes: Record<string, string>
  export default classes
}
