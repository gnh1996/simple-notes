/**
 * 通用 fetch 客户端：三端（admin / blog / desktop 之外的 Web 端）共用的轻量请求封装。
 *
 * 请求 / 响应类型来自 `src/generated/schema.d.ts`（openapi-typescript 依后端 OpenAPI 契约生成，
 * 见 docs/tech-stack.md「API 契约」）；本文件只负责传输层。
 */

/** 后端统一错误体（backend `common/ApiError`）：`{ code, message }` */
export interface ApiErrorBody {
  code?: string
  message?: string
}

/** 请求失败（HTTP 非 2xx）时抛出；`code` 为后端错误语义码（如 `PERMISSION_DENIED`） */
export class ApiError extends Error {
  readonly status: number
  /** 后端错误语义码（错误体不可解析时为 undefined） */
  readonly code?: string
  readonly body?: unknown

  constructor(status: number, body?: unknown, fallbackMessage?: string) {
    const parsed = (body ?? {}) as ApiErrorBody
    const message =
      typeof parsed.message === 'string' && parsed.message.length > 0
        ? parsed.message
        : (fallbackMessage ?? `请求失败（HTTP ${status}）`)
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = typeof parsed.code === 'string' ? parsed.code : undefined
    this.body = body
  }
}

export interface ApiClientOptions {
  /** API 前缀，默认 `/api`（开发期由 Vite proxy 转发到 Spring Boot） */
  baseUrl?: string
  /** 每次请求发送前调用，可用于附加 Authorization 等 */
  prepareRequest?: (init: RequestInit) => RequestInit
  /** 请求失败（HTTP 非 2xx）时回调：统一 401 / 403 处理入口 */
  onError?: (error: ApiError) => void
}

export type ApiClient = ReturnType<typeof createApiClient>

export function createApiClient({ baseUrl = '/api', prepareRequest, onError }: ApiClientOptions = {}) {
  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const finalInit = prepareRequest?.(init) ?? init

    const headers = new Headers(finalInit.headers)
    headers.set('Accept', 'application/json')
    // FormData 由浏览器自动生成 multipart boundary，不能手动设 Content-Type
    if (finalInit.body != null && !(finalInit.body instanceof FormData) && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }

    const response = await fetch(baseUrl + path, { ...finalInit, headers })

    if (!response.ok) {
      const body = await response.json().catch(() => undefined)
      const error = new ApiError(
        response.status,
        body,
        `请求失败：${response.status} ${response.statusText}`,
      )
      onError?.(error)
      throw error
    }

    if (response.status === 204) {
      return undefined as T
    }
    const text = await response.text()
    return (text ? JSON.parse(text) : undefined) as T
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
