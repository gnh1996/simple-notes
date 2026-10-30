/** 公开 API 封装：runtime 不依赖生成客户端（docs/blog-runtime-theme.md §12）。 */

export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export interface ApiOptions {
  /** 路由切换时取消上一笔请求；中止后的 rejection 由调用方识别。 */
  signal?: AbortSignal
}

export async function api<T>(
  path: string,
  params?: Record<string, string | number | boolean | null | undefined>,
  options: ApiOptions = {},
): Promise<T> {
  const url = new URL(path, location.origin)
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, String(value))
      }
    })
  }
  const response = await fetch(url, {
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    signal: options.signal,
  })
  if (!response.ok) {
    let detail = `请求失败（${response.status}）`
    try {
      const body = (await response.json()) as { message?: string }
      if (body && body.message) {
        detail = body.message
      }
    } catch {
      /* 非 JSON 错误体 */
    }
    throw new ApiError(detail, response.status)
  }
  return (await response.json()) as T
}
