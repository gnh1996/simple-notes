export function formatDate(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date)
}

export function readingMinutes(content: string): number {
  const text = content.replace(/\s+/g, '')
  return Math.max(1, Math.round(text.length / 400))
}
