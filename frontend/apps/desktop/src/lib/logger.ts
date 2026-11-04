/**
 * 前端日志：所有运行期异常 / 关键操作经 Go binding 写入同一个日志文件。
 *
 * - 只记录操作名、错误链与结构化字段，不记录令牌、密码、正文与请求体；
 * - 写日志失败绝不反向影响业务（fire-and-forget）。
 */
import type { App } from 'vue'

import { Binding as LogService } from '../../bindings/cc.naihao/simplenotes/desktop/internal/logging'
import { errorDetail, errorText } from './errors'

export type LogLevel = 'debug' | 'info' | 'warn' | 'error'

let installed = false

export function log(
  level: LogLevel,
  event: string,
  message: string,
  fields?: Record<string, string | undefined>,
): void {
  const payload: Record<string, string> = {}
  for (const [key, value] of Object.entries(fields ?? {})) {
    if (value != null && value !== '') {
      payload[key] = value
    }
  }
  try {
    void LogService.Write(level, event, message, payload).catch(() => {
      // 日志不可用（例如浏览器调试模式没有 Go binding）时静默降级。
    })
  } catch {
    // 同步抛错同样忽略，避免影响主流程。
  }
}

/** 记录异常；返回可直接展示给用户的短提示。 */
export function logError(event: string, err: unknown, message: string, fields?: Record<string, string>): string {
  const detail = errorDetail(err)
  log('error', event, message, { ...fields, err: detail || errorText(err) })
  return message
}

/** 安装全局兜底：Vue 组件异常、未捕获 Promise、脚本错误。 */
export function installErrorLogging(app: App): void {
  if (installed) {
    return
  }
  installed = true

  app.config.errorHandler = (err, _instance, info) => {
    log('error', 'ui.vue', 'Vue 组件异常', { info, err: errorDetail(err) })
  }

  window.addEventListener('error', (event) => {
    log('error', 'ui.window', '未捕获的脚本错误', {
      err: event.error ? errorDetail(event.error) : event.message,
    })
  })

  window.addEventListener('unhandledrejection', (event) => {
    log('error', 'ui.promise', '未处理的 Promise 拒绝', {
      err: errorDetail(event.reason),
    })
  })
}
