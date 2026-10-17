/**
 * @simple-notes/api —— 管理端 / 博客前端 / 桌面端共用的 API 客户端。
 *
 * 当前为最小可用的 fetch 封装。后端（Spring Boot）接入 springdoc-openapi 后，
 * 计划用 openapi-typescript 依据 OpenAPI 文档生成请求/响应类型，替换这里的裸类型。
 */

export class ApiError extends Error {
  readonly status: number
  readonly body?: unknown

  constructor(status: number, message: string, body?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

export interface ApiClientOptions {
  /** API 前缀，默认 `/api`（开发期由 Vite proxy 转发到 Spring Boot） */
  baseUrl?: string
  /** 每次请求发送前调用，可用于附加 Authorization 等 */
  prepareRequest?: (init: RequestInit) => RequestInit
}

export type ApiClient = ReturnType<typeof createApiClient>

export function createApiClient({ baseUrl = '/api', prepareRequest }: ApiClientOptions = {}) {
  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const finalInit = prepareRequest?.(init) ?? init

    const headers = new Headers(finalInit.headers)
    headers.set('Accept', 'application/json')
    if (finalInit.body != null && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }

    const response = await fetch(baseUrl + path, { ...finalInit, headers })

    if (!response.ok) {
      const body = await response.json().catch(() => undefined)
      throw new ApiError(
        response.status,
        `API 请求失败：${response.status} ${response.statusText}`,
        body,
      )
    }

    if (response.status === 204) {
      return undefined as T
    }
    return (await response.json()) as T
  }

  return {
    request,
    get: <T>(path: string, init?: RequestInit) => request<T>(path, { ...init, method: 'GET' }),
    post: <T>(path: string, body?: unknown, init?: RequestInit) =>
      request<T>(path, {
        ...init,
        method: 'POST',
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    put: <T>(path: string, body?: unknown, init?: RequestInit) =>
      request<T>(path, {
        ...init,
        method: 'PUT',
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    patch: <T>(path: string, body?: unknown, init?: RequestInit) =>
      request<T>(path, {
        ...init,
        method: 'PATCH',
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    delete: <T>(path: string, init?: RequestInit) => request<T>(path, { ...init, method: 'DELETE' }),
  }
}
