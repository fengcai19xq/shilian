/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** mock（默认）| http */
  readonly VITE_API_MODE?: string
  /** 接口前缀，默认 /api */
  readonly VITE_API_BASE?: string
  /** 仅本地 mock/界面回显；不随业务请求发送（生产由 BFF 从会话注入） */
  readonly VITE_DEV_PRINCIPAL?: string
  /** 平台管理面（WeKnora 原生 UI）入口：同域反代路径或完整 URL；留空则不显示入口（WK12） */
  readonly VITE_WEKNORA_CONSOLE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// CSS Modules 类型声明
declare module '*.module.css' {
  const classes: Record<string, string>
  export default classes
}
