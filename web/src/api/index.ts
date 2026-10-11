// API 依赖注入：VITE_API_MODE=http 时使用真实 HTTP 客户端，否则使用 mock；测试可通过 ApiContext 注入替身
import { createContext, useContext } from 'react'
import { createHttpApi, httpOptionsFromEnv } from './http'
import { createMockApi, type WorkbenchApi } from './mock'

export const API_MODE: 'mock' | 'http' = import.meta.env.VITE_API_MODE === 'http' ? 'http' : 'mock'

export const defaultApi: WorkbenchApi =
  API_MODE === 'http' ? createHttpApi(httpOptionsFromEnv(import.meta.env)) : createMockApi()

export const ApiContext = createContext<WorkbenchApi>(defaultApi)

export function useApi(): WorkbenchApi {
  return useContext(ApiContext)
}

export type { WorkbenchApi }
export { createMockApi }
export { ApiHttpError, createHttpApi } from './http'
export * from './models'
export * from './types'
