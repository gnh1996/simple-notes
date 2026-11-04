/**
 * 把绑定层异常翻译成「用户短提示 + 诊断详情」。
 *
 * Wails binding 抛出的错误形如 `RuntimeError: <Go error>`，同时带 `cause`。
 * 用户界面只展示可行动的短句，原始错误链交给日志与详情弹层。
 */

export type ErrorKind = 'offline' | 'auth' | 'outdated' | 'server' | 'local' | 'unknown'

export interface ErrorInfo {
  kind: ErrorKind
  /** 状态栏 / 对话框里的短提示。 */
  message: string
  /** 复制到诊断信息里的完整错误链（不含堆栈中的用户内容）。 */
  detail: string
}

const NETWORK_PATTERN =
  /请求后端失败|dial tcp|connectex|actively refused|connection refused|no such host|i\/o timeout|context deadline exceeded|network is unreachable|network is down|tls handshake timeout|broken pipe/i
const AUTH_PATTERN = /会话已失效|http 401|unauthorized/i
const OUTDATED_PATTERN = /云端版本较低|服务端过旧/
const SERVER_PATTERN = /http 5\d\d|internal server error|服务端异常/i

/** 取出 Wails / JS 异常的纯文本消息，去掉 `RuntimeError:` 前缀。 */
export function errorText(err: unknown): string {
  if (err instanceof Error) {
    return (err.message || err.name || '').trim()
  }
  if (typeof err === 'string') {
    return err.trim()
  }
  if (err == null) {
    return ''
  }
  return String(err).replace(/^[A-Za-z]*Error:\s*/, '').trim()
}

/** 生成可复制的技术详情：错误名、消息、cause（有堆栈时附带堆栈）。 */
export function errorDetail(err: unknown): string {
  const lines: string[] = []
  if (err instanceof Error) {
    lines.push(`${err.name}: ${err.message}`)
    const cause = (err as { cause?: unknown }).cause
    if (cause != null && String(cause) !== err.message) {
      lines.push(`cause: ${String(cause)}`)
    }
    if (err.stack) {
      lines.push('', err.stack)
    }
  } else {
    const text = errorText(err)
    if (text) {
      lines.push(text)
    }
  }
  return lines.join('\n').trim()
}

export function isNetworkError(err: unknown): boolean {
  return NETWORK_PATTERN.test(errorText(err)) || NETWORK_PATTERN.test(errorDetail(err))
}

export function describeError(err: unknown, fallback = '操作失败'): ErrorInfo {
  const raw = errorText(err)
  const detail = errorDetail(err) || raw || fallback

  if (isNetworkError(err)) {
    return { kind: 'offline', message: '无法连接服务器，本地内容仍可用', detail }
  }
  if (AUTH_PATTERN.test(raw)) {
    return { kind: 'auth', message: '登录已失效，请重新登录', detail }
  }
  if (OUTDATED_PATTERN.test(raw)) {
    return { kind: 'outdated', message: '云端版本较低，请升级云端服务', detail }
  }
  if (SERVER_PATTERN.test(raw)) {
    return {
      kind: 'server',
      message: /请求失败（HTTP 5\d\d）/.test(raw) ? '服务器暂时不可用，请稍后重试' : raw,
      detail,
    }
  }
  if (!raw) {
    return { kind: 'unknown', message: fallback, detail }
  }
  return { kind: 'local', message: raw, detail }
}
