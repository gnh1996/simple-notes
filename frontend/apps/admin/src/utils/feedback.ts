import { ElMessage } from 'element-plus'

import { ApiError } from '@/api'

/** 统一取错误提示文案（后端错误体 message 已是可读中文） */
export function errorMessage(error: unknown, fallback = '操作失败'): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return fallback
}

export function notifyError(error: unknown, fallback?: string): void {
  ElMessage.error(errorMessage(error, fallback))
}

/** 时间格式化（`2026-10-18 18:30`）；空值显示 `—` */
export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}
