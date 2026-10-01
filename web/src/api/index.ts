// API 依赖注入：默认使用 mock 实现，测试可通过 ApiProvider 注入替身
import { createContext, useContext } from 'react'
import { createMockApi, type WorkbenchApi } from './mock'

export const defaultApi: WorkbenchApi = createMockApi()

export const ApiContext = createContext<WorkbenchApi>(defaultApi)

export function useApi(): WorkbenchApi {
  return useContext(ApiContext)
}

export type { WorkbenchApi }
export { createMockApi }
export * from './types'
