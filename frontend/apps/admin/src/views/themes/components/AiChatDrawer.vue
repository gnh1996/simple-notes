<script setup lang="ts">
/**
 * AI 主题对话抽屉（docs/theme-ai.md，v2.1）。
 *
 * 首轮 / 无当前主题 → 完整 html/css；已有当前主题 → 后端只接收/应用小 JSON 补丁，
 * 当前主题与对话记忆都在服务端，前端 history 只作重启后的种子。
 * 流式协议（SSE 事件）：token / round / done / error。
 */
import type { Schema, ThemeSummary, ThemeVersionResponse } from '@simple-notes/api'
import { ElMessage } from 'element-plus'
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'

import { getStoredToken, handleUnauthorized, themeApi } from '@/api'
import { notifyError } from '@/utils/feedback'

const BLOG_URL = (import.meta.env.VITE_BLOG_URL as string | undefined) ?? 'http://localhost:8080'
const BLOG_ORIGIN = (() => {
  try {
    return new URL(BLOG_URL).origin
  } catch {
    return window.location.origin
  }
})()

/** 契约类型来自后端 OpenAPI（pnpm gen:api）；SSE done 载荷没有 OpenAPI 契约，见下方 AiArtifact。 */
type AiChatRequest = Schema['AiChatRequest']
type AiChatTurn = Schema['AiChatTurn']

interface AiAttempt {
  round: number
  errors: string[]
}

interface AiArtifact {
  html: string
  css: string
  summary: string
  plan: string[]
  contract: number
  mode: string
  finishReason: string
  truncated: boolean
  outputChars: number
  reasoningChars: number
  errorKind: string
  usage: Record<string, number>
  draftId: string | null
  errors: string[]
  attempts: AiAttempt[]
}

interface DiagnoseReport {
  ok: boolean
  errors: string[]
  warnings: string[]
  stats: Record<string, string | number | boolean>
}

interface DiagnoseState {
  status: 'idle' | 'running' | 'done' | 'failed'
  report?: DiagnoseReport
  note?: string
}

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  streaming?: boolean
  rounds?: Array<{ round: number; status: string; errors: string[] }>
  artifact?: AiArtifact
}

/** fenced 代码块（含流式未闭合）与 <plan> 块（含流式未闭合）。产物块（html/css/json）走产物卡片，不在正文重复展示。 */
const FENCED_BLOCK = /```[\s\S]*?(?:```|$)/g
/** 无 fence 的裸产物 JSON（summary/plan/ops/html/css 任一开头都隐藏），正文/种子里都不保留。 */
const BARE_JSON_TAIL = /\{\s*"(?:summary|plan|ops|html|css)"[\s\S]*$/
const PLAN_BLOCK = /<plan>([\s\S]*?)(?:<\/plan>|$)/gi

function looksLikeJson(text: string): boolean {
  return /^\s*\{\s*"/.test(text)
}

/** 从 assistant 文本提取本轮计划（完整模式约定先输出 <plan>，流式未闭合也解析；补丁模式用 done.plan）。 */
function extractPlan(text: string): string[] {
  PLAN_BLOCK.lastIndex = 0
  const match = PLAN_BLOCK.exec(text)
  PLAN_BLOCK.lastIndex = 0
  if (!match) return []
  return (match[1] ?? '')
    .split('\n')
    .map((line) => line.replace(/^\s*(?:[-*•]|\d+[.)])\s*/, '').trim())
    .filter((line) => line.length > 0)
    .slice(0, 8)
}

/** 本轮计划：结构化 plan 优先（全 JSON 契约），旧自由文本 <plan> 只作兼容。 */
function planOf(message: ChatMessage): string[] {
  if (message.artifact?.plan?.length) return message.artifact.plan
  return extractPlan(message.text)
}

/** 展示用正文：散文优先，纯 JSON 回答则用结构化 summary（流式 JSON 原文永不展示）。 */
function mainTextOf(message: ChatMessage): string {
  if (message.streaming && looksLikeJson(message.text)) return ''
  return proseOf(message.text) || message.artifact?.summary || ''
}
/** 展示用正文：去掉 <plan> 与 fenced 代码块与裸 JSON（产物走产物卡片，不在这里重复展示）。 */
function proseOf(text: string): string {
  return text
    .replace(/<plan>[\s\S]*?(?:<\/plan>|$)/gi, '')
    .replace(FENCED_BLOCK, '')
    .replace(BARE_JSON_TAIL, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function escapeProse(source: string): string {
  return source
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 内联格式：先转义再套我们自己的标签，v-html 安全（无外部 HTML 进入）。 */
function renderInline(source: string): string {
  const codes: string[] = []
  const protectedText = escapeProse(source).replace(/`([^`\n]+)`/g, (_, code: string) => {
    codes.push(code)
    return `@@CODE${codes.length - 1}@@`
  })
  const formatted = protectedText
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
  return formatted.replace(/@@CODE(\d+)@@/g, (_, index: string) => `<code>${codes[Number(index)] ?? ''}</code>`)
}

/** 轻量散文渲染：标题 / 列表 / 段落；对话说明文字用，不走完整 Markdown 管线。 */
function renderProseHtml(prose: string): string {
  const lines = prose.split('\n')
  let html = ''
  let inList = false
  const closeList = (): void => {
    if (inList) {
      html += '</ul>'
      inList = false
    }
  }
  for (const line of lines) {
    const trimmed = line.trim()
    const item = trimmed.match(/^[-*•]\s+(.*)/)
    const heading = trimmed.match(/^#{1,3}\s+(.*)/)
    if (item) {
      if (!inList) {
        html += '<ul>'
        inList = true
      }
      html += `<li>${renderInline(item[1] ?? '')}</li>`
    } else if (heading) {
      closeList()
      html += `<p><strong>${renderInline(heading[1] ?? '')}</strong></p>`
    } else if (!trimmed) {
      closeList()
    } else {
      closeList()
      html += `<p>${renderInline(trimmed)}</p>`
    }
  }
  closeList()
  return html
}

const props = defineProps<{
  themeId: string | null
  baseHtml: string
  baseCss: string
}>()

const emit = defineEmits<{
  fill: [html: string, css: string, contract: number]
  saved: [version: ThemeVersionResponse]
  created: [theme: ThemeSummary]
  previewDraft: [draftId: string, token: string, html: string, css: string]
}>()

const visible = defineModel<boolean>('visible', { default: false })

const messages = ref<ChatMessage[]>([])
const input = ref('')
const streaming = ref(false)
const includeStarter = ref(true)
/** 后端 ChatMemory 会话 id：同一段对话共用；「新对话」或切换主题时重新生成。 */
const sessionId = ref('')
/** 记录当前会话所属主题，切换主题时清空对话，避免旧主题上下文串台。 */
const lastThemeId = ref<string | null>(null)
const saving = reactive<Record<string, boolean>>({})
const creating = reactive<Record<string, boolean>>({})
const newThemeName = ref('')
const elapsed = ref(0)
const bodyRef = ref<HTMLElement>()
/** 是否贴底：用户上滑查看历史时为 false，不再强制吸底（滚动打架是之前“滑动不灵敏”的根因）。 */
const stick = ref(true)
const diags = reactive<Record<string, DiagnoseState>>({})
const diagIframe = ref<HTMLIFrameElement>()
let diagPending: {
  id: string
  html: string
  css: string
  resolve: (report: DiagnoseReport) => void
  reject: (error: Error) => void
  timer: number
} | null = null

let seq = 0
let aborter: AbortController | null = null
let timer: number | null = null

function newSessionId(): string {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/** 网络层失败（Vite/后端未启动）给出可操作提示，而不是裸的 Failed to fetch。 */
function aiErrorMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error ?? '')
  if (
    error instanceof TypeError ||
    /failed to fetch|networkerror|load failed|err_connection_refused/i.test(message)
  ) {
    return '无法连接后端：请确认 Spring Boot 已在 8080 启动，且 Vite 代理可用'
  }
  return message || 'AI 生成失败'
}

/** 开一段新对话：清空消息 / 自检 / 会话 id，后端 memory 与当前主题自然换新。 */
function resetConversation(reason?: string): void {
  if (streaming.value) {
    ElMessage.warning('生成中，请先中断再开新对话')
    return
  }
  messages.value = []
  Object.keys(diags).forEach((key) => delete diags[key])
  sessionId.value = newSessionId()
  lastThemeId.value = props.themeId
  includeStarter.value = true
  input.value = ''
  newThemeName.value = ''
  stick.value = true
  if (reason) ElMessage.info(reason)
}

function startTimer(): void {
  stopTimer()
  elapsed.value = 0
  timer = window.setInterval(() => {
    elapsed.value += 1
  }, 1000)
}

function stopTimer(): void {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

function onBodyScroll(): void {
  const el = bodyRef.value
  if (!el) return
  stick.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function backToBottom(): void {
  stick.value = true
  scrollBottom()
}

function scrollBottom(): void {
  if (!stick.value) return
  void nextTick(() => {
    bodyRef.value?.scrollTo({ top: bodyRef.value.scrollHeight })
  })
}

watch(messages, scrollBottom, { deep: true })

function historyTextOf(message: ChatMessage): string {
  // 结构化产物只留决策上下文（summary + plan + 状态），几十 KB 的 html/css 绝不进种子。
  if (message.role === 'assistant' && message.artifact) {
    const status = message.artifact.errors.length
      ? `校验未通过（${message.artifact.errors.join('；')}）`
      : '校验通过'
    const plan = message.artifact.plan.length ? `[计划：${message.artifact.plan.join('；')}]` : ''
    return `[本轮：${message.artifact.summary}]${plan}[产物：${status}]`
  }
  return message.text
}

function historyOf(): AiChatTurn[] {
  const turns: AiChatTurn[] = []
  for (const message of messages.value) {
    if (message.streaming) continue
    turns.push({ role: message.role, content: historyTextOf(message).slice(0, 2000) })
  }
  return turns.slice(-20)
}

/** 最近一次校验通过的 AI 产物：后端重启/新会话时作为完整生成的参考，避免丢未保存草稿。 */
function lastValidArtifact(): AiArtifact | undefined {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const message = messages.value[i]
    if (message.role === 'assistant' && message.artifact && message.artifact.errors.length === 0) {
      return message.artifact
    }
  }
  return undefined
}

async function send(): Promise<void> {
  const prompt = input.value.trim()
  if (!prompt || streaming.value) return
  input.value = ''
  await sendPrompt(prompt)
}

/** 发送一轮对话（抽屉输入框与“把渲染问题发给 AI”共用）。 */
async function sendPrompt(prompt: string): Promise<void> {
  if (!prompt || streaming.value) return
  const token = getStoredToken()
  if (!token) {
    ElMessage.warning('登录状态失效，请重新登录')
    return
  }
  stick.value = true
  messages.value.push({ id: `m-${++seq}`, role: 'user', text: prompt })
  const assistant: ChatMessage = { id: `m-${++seq}`, role: 'assistant', text: '', streaming: true, rounds: [] }
  messages.value.push(assistant)
  streaming.value = true
  // 流式渲染节流：token 先攒 buffer，250ms 刷一次，避免上万次响应式更新卡死浏览器
  let pending = ''
  let flushTimer: number | null = null
  const appendDisplay = (chunk: string): void => {
    let next = assistant.text + chunk
    if (next.length > 8000) {
      next = '…（前面输出过长已折叠，仅保留尾部）\n' + next.slice(-6000)
    }
    assistant.text = next
  }
  const flushPending = (): void => {
    if (pending) {
      appendDisplay(pending)
      pending = ''
    }
  }
  const queueDelta = (delta: string): void => {
    pending += delta
    if (flushTimer === null) {
      flushTimer = window.setInterval(flushPending, 250)
    }
  }
  const stopFlush = (): void => {
    if (flushTimer !== null) {
      window.clearInterval(flushTimer)
      flushTimer = null
    }
    flushPending()
  }

  const lastValid = lastValidArtifact()
  const baseHtml = lastValid?.html ?? props.baseHtml
  const baseCss = lastValid?.css ?? props.baseCss
  const body: AiChatRequest = {
    sessionId: sessionId.value,
    themeId: props.themeId ?? undefined,
    history: historyOf().slice(0, -1),
    prompt,
    baseHtml: baseHtml || undefined,
    baseCss: baseCss || undefined,
    includeStarter: includeStarter.value,
  }
  console.debug('[ai-theme] request', {
    sessionId: body.sessionId,
    themeId: body.themeId,
    history: body.history?.length ?? 0,
    promptChars: prompt.length,
    baseHtmlChars: body.baseHtml?.length ?? 0,
    baseCssChars: body.baseCss?.length ?? 0,
    includeStarter: body.includeStarter,
  })
  lastThemeId.value = props.themeId
  aborter = new AbortController()
  startTimer()

  try {
    const response = await fetch('/api/admin/themes/ai-chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(body),
      signal: aborter.signal,
    })
    if (!response.ok || !response.body) {
      let message = `AI 请求失败（HTTP ${response.status}）`
      try {
        const text = await response.text()
        const payload = JSON.parse(text) as { message?: string }
        if (payload?.message) message = payload.message
      } catch {
        // 保持 HTTP 状态兜底
      }
      if (response.status === 401) {
        void handleUnauthorized()
      }
      throw new Error(message)
    }
    await readStream(response.body.getReader(), assistant, queueDelta, stopFlush)
  } catch (error) {
    if ((error as Error).name === 'AbortError') {
      assistant.text += '\n（已中断）'
    } else if (!assistant.artifact) {
      // done 已到达后的断流（如连接复用回收）不算失败：产物卡片已展示
      const message = aiErrorMessage(error)
      ElMessage.error(message)
      assistant.text += `\n（${message}）`
    }
  } finally {
    stopFlush()
    assistant.streaming = false
    streaming.value = false
    aborter = null
    stopTimer()
    // 只有真正产出有效主题后才不再附带 starter；失败重试仍可带默认主题。
    if (assistant.artifact && assistant.artifact.errors.length === 0) {
      includeStarter.value = false
    }
  }
}

async function readStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  assistant: ChatMessage,
  onToken: (delta: string) => void,
  onDone: () => void,
): Promise<void> {
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split('\n\n')
    buffer = frames.pop() ?? ''
    for (const frame of frames) {
      let event = 'message'
      let data = ''
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data += line.slice(5).trim()
      }
      if (data) handleEvent(event, data, assistant, onToken, onDone)
    }
  }
}

function handleEvent(
  event: string,
  data: string,
  assistant: ChatMessage,
  onToken: (delta: string) => void,
  onDone: () => void,
): void {
  let payload: Record<string, unknown>
  try {
    payload = JSON.parse(data) as Record<string, unknown>
  } catch {
    return
  }
  if (event === 'token') {
    onToken(String(payload.delta ?? ''))
    return
  }
  if (event === 'round') {
    assistant.rounds?.push({
      round: Number(payload.round ?? 0),
      status: String(payload.status ?? ''),
      errors: (payload.errors as string[] | undefined) ?? [],
    })
  } else if (event === 'done') {
    onDone()
    assistant.artifact = {
      html: String(payload.html ?? ''),
      css: String(payload.css ?? ''),
      summary: String(payload.summary ?? ''),
      plan: Array.isArray(payload.plan) ? (payload.plan as string[]) : [],
      contract: Number(payload.contract ?? 1),
      mode: String(payload.mode ?? 'full'),
      finishReason: String(payload.finishReason ?? ''),
      truncated: Boolean(payload.truncated),
      outputChars: Number(payload.outputChars ?? 0),
      reasoningChars: Number(payload.reasoningChars ?? 0),
      errorKind: String(payload.errorKind ?? 'none'),
      usage: (payload.usage as Record<string, number> | undefined) ?? {},
      draftId: (payload.draftId as string | null) ?? null,
      errors: (payload.errors as string[] | undefined) ?? [],
      attempts: (payload.attempts as AiAttempt[] | undefined) ?? [],
    }
    console.debug('[ai-theme] done', {
      mode: assistant.artifact.mode,
      finishReason: assistant.artifact.finishReason,
      truncated: assistant.artifact.truncated,
      outputChars: assistant.artifact.outputChars,
      reasoningChars: assistant.artifact.reasoningChars,
      errorKind: assistant.artifact.errorKind,
      errors: assistant.artifact.errors,
      usage: assistant.artifact.usage,
    })
  } else if (event === 'error') {
    assistant.text += `\n（${String(payload.message ?? '生成失败')}）`
  }
}

function stop(): void {
  aborter?.abort()
}

async function fillEditor(artifact: AiArtifact): Promise<void> {
  emit('fill', artifact.html, artifact.css, artifact.contract)
}

async function saveVersion(message: ChatMessage): Promise<void> {
  const artifact = message.artifact
  if (!artifact || !props.themeId) {
    ElMessage.warning('请先在主题列表选中一个主题，或使用「存为新主题」')
    return
  }
  if (artifact.errors.length) {
    ElMessage.warning('校验未通过的产物不建议保存，可继续对话修复')
    return
  }
  saving[message.id] = true
  try {
    const version = await themeApi.createVersion(props.themeId, {
      html: artifact.html,
      css: artifact.css,
      contract: artifact.contract,
    })
    ElMessage.success(`已保存为新版本 v${version.versionNo}`)
    emit('saved', version)
  } catch (error) {
    notifyError(error, '保存失败')
  } finally {
    saving[message.id] = false
  }
}

async function saveAsNewTheme(message: ChatMessage): Promise<void> {
  const artifact = message.artifact
  const name = newThemeName.value.trim()
  if (!artifact || !name) return
  if (artifact.errors.length) {
    ElMessage.warning('校验未通过的产物不建议保存，可继续对话修复')
    return
  }
  creating[message.id] = true
  try {
    const theme = await themeApi.create({ name })
    const version = await themeApi.createVersion(theme.id, {
      html: artifact.html,
      css: artifact.css,
      contract: artifact.contract,
    })
    ElMessage.success(`已创建主题「${name}」v${version.versionNo}`)
    emit('created', theme)
  } catch (error) {
    notifyError(error, '创建失败')
  } finally {
    creating[message.id] = false
  }
}

async function previewDraft(message: ChatMessage): Promise<void> {
  const artifact = message.artifact
  if (!artifact?.draftId) {
    ElMessage.warning('草稿不存在或已过期，请重新生成')
    return
  }
  try {
    const issued = await themeApi.draftPreviewToken(artifact.draftId)
    emit('previewDraft', artifact.draftId, issued.token, artifact.html, artifact.css)
  } catch (error) {
    notifyError(error, '预览失败')
  }
}

/**
 * A 档渲染自检（docs/theme-ai.md §4）：hidden iframe 挂载草稿整页，
 * 等 `blog-preview:ready` 后发 `blog-preview:diagnose`，收确定性报告。
 * 只在用户点击时跑，不自动烧 token；拿到报告后一键发回模型修下一版。
 */
async function runDiagnose(message: ChatMessage): Promise<void> {
  const artifact = message.artifact
  if (!artifact?.draftId || diags[message.id]?.status === 'running') return
  diags[message.id] = { status: 'running' }
  try {
    const issued = await themeApi.draftPreviewToken(artifact.draftId)
    const params = new URLSearchParams({
      previewDraft: artifact.draftId,
      token: issued.token,
      fixtures: '1',
      t: Date.now().toString(),
    })
    const report = await requestDiagnose(`${BLOG_URL}/?${params.toString()}`, artifact.html, artifact.css)
    diags[message.id] = { status: 'done', report }
  } catch (error) {
    diags[message.id] = {
      status: 'failed',
      note: error instanceof Error ? error.message : '自检失败',
    }
  }
}

function requestDiagnose(url: string, html: string, css: string): Promise<DiagnoseReport> {
  return new Promise((resolve, reject) => {
    if (diagPending) {
      reject(new Error('已有自检在进行'))
      return
    }
    const id = `${Date.now()}-${Math.random().toString(16).slice(2)}`
    const timer = window.setTimeout(() => {
      if (diagPending?.id === id) {
        diagPending = null
        reject(new Error('自检超时（25s）：预览页无响应'))
      }
    }, 25000)
    diagPending = { id, html, css, resolve, reject, timer }
    diagIframe.value?.setAttribute('src', url)
  })
}

function onDiagnoseMessage(event: MessageEvent): void {
  if (event.origin !== BLOG_ORIGIN) return
  const data = event.data as Record<string, unknown> | null
  if (!data || typeof data !== 'object' || !diagPending) return
  if (data.type === 'blog-preview:ready') {
    diagIframe.value?.contentWindow?.postMessage(
      {
        type: 'blog-preview:diagnose',
        id: diagPending.id,
        html: diagPending.html,
        css: diagPending.css,
      },
      BLOG_ORIGIN,
    )
  } else if (data.type === 'blog-preview:diagnosed' && data.id === diagPending.id) {
    const pending = diagPending
    diagPending = null
    window.clearTimeout(pending.timer)
    pending.resolve(data.report as DiagnoseReport)
  }
}

/** 把自检报告拼成修复指令发回模型（生成中则填入输入框，用户手动发）。 */
async function sendReportToAi(message: ChatMessage): Promise<void> {
  const diag = diags[message.id]
  if (diag?.status !== 'done' || !diag.report) return
  const report = diag.report
  const patchMode = message.artifact?.mode === 'patch'
  const lines = [
    patchMode
      ? '渲染自检发现以下问题，请输出修正后的补丁 JSON（ops，不是完整主题；除非必要才用 replaceHtml/replaceCss），plan 字段针对下面每条各写一行怎么改：'
      : '渲染自检发现以下问题，请输出修正后的完整主题（先 <plan>，再恰好一个 ```html 与 ```css），plan 针对下面每条各写一行怎么改：',
    ...report.errors.map((item) => `- [错误] ${item}`),
    ...report.warnings.map((item) => `- [警告] ${item}`),
  ]
  if (streaming.value) {
    input.value = lines.join('\n')
    ElMessage.info('生成中，已把修复指令填入输入框，完成后手动发送')
    return
  }
  await sendPrompt(lines.join('\n'))
}

onMounted(() => {
  window.addEventListener('message', onDiagnoseMessage)
})

watch(
  visible,
  (open) => {
    if (open && !sessionId.value) {
      sessionId.value = newSessionId()
      lastThemeId.value = props.themeId
    }
  },
  { immediate: true },
)

watch(
  () => props.themeId,
  (next) => {
    if (next === lastThemeId.value) return
    if (messages.value.length) {
      resetConversation('已切换主题，开启新对话')
    } else {
      sessionId.value = newSessionId()
      lastThemeId.value = next
    }
  },
)

onBeforeUnmount(() => {
  window.removeEventListener('message', onDiagnoseMessage)
  aborter?.abort()
})
</script>

<template>
  <el-drawer v-model="visible" title="AI 定制主题" size="min(560px, 94vw)" destroy-on-close>
    <div class="flex h-full flex-col gap-3">
      <div class="flex items-center justify-between gap-2 text-xs text-[var(--el-text-color-secondary)]">
        <span>对话直接改主题；产物先过契约校验，通过才进草稿。</span>
        <div class="flex items-center gap-2">
          <el-checkbox
            v-model="includeStarter"
            size="small"
            :disabled="streaming || !!props.baseHtml"
            :title="props.baseHtml ? '已有当前主题，无需再附默认主题' : '没有当前主题时附带默认主题作契约参考'"
          >
            首轮附带默认主题
          </el-checkbox>
          <el-button size="small" :disabled="streaming" @click="resetConversation()">新对话</el-button>
        </div>
      </div>

      <div class="relative min-h-0 flex-1">
        <div
          ref="bodyRef"
          class="h-full space-y-3 overflow-y-auto rounded-lg bg-[var(--el-fill-color-light)] p-3"
          @scroll="onBodyScroll"
        >
          <el-empty v-if="!messages.length" description="描述想要的风格，例如：暗色优先、杂志风首页、圆角卡片" :image-size="64" />
          <div
            v-for="message in messages"
            :key="message.id"
            class="max-w-full rounded-lg p-3 text-sm"
            :class="message.role === 'user'
              ? 'ml-8 bg-[var(--el-color-primary-light-9)]'
              : 'mr-8 border border-[var(--el-border-color-lighter)] bg-[var(--el-bg-color)]'"
          >
            <p class="mb-1 text-xs text-[var(--el-text-color-secondary)]">
              {{ message.role === 'user' ? '你' : 'AI' }}{{ message.streaming ? `（生成中…${elapsed}s）` : '' }}
            </p>
            <p v-if="message.role === 'user'" class="whitespace-pre-wrap break-words">{{ message.text }}</p>
            <template v-else>
              <ol
                v-if="planOf(message).length"
                class="mb-2 space-y-1 border-l-2 border-[var(--el-color-primary)] pl-3"
              >
                <li
                  v-for="(step, index) in planOf(message)"
                  :key="index"
                  class="flex gap-1.5 text-xs text-[var(--el-text-color-secondary)]"
                >
                  <span>{{ message.streaming && index === planOf(message).length - 1 ? '⏳' : '✓' }}</span>
                  <span>{{ step }}</span>
                </li>
              </ol>
              <div
                v-if="mainTextOf(message)"
                class="ai-prose break-words text-sm"
                v-html="renderProseHtml(mainTextOf(message))"
              />
              <p v-else class="text-sm text-[var(--el-text-color-secondary)]">
                {{ message.streaming ? (looksLikeJson(message.text) ? '正在生成编辑指令…' : '已发送，等待模型响应…') : '…' }}
              </p>
            </template>

          <div v-if="message.rounds?.length" class="mt-2 space-y-1 text-xs text-[var(--el-text-color-secondary)]">
            <p v-for="round in message.rounds" :key="round.round">
              第 {{ round.round }} 轮{{ round.status === 'repairing' ? '自动修复中' : '' }}<span v-if="round.errors.length">：{{ round.errors.join('；') }}</span>
            </p>
          </div>

          <div v-if="message.artifact" class="mt-2 rounded-md border border-[var(--el-border-color-lighter)] p-2 text-xs">
            <p v-if="message.artifact.errors.length" class="text-[var(--el-color-danger)]">
              {{ message.artifact.errorKind === 'parse' ? '解析失败' : message.artifact.errorKind === 'truncated' ? '输出被截断' : '校验未通过' }}（{{ message.artifact.errors.length }}）：{{ message.artifact.errors.join('；') }}
            </p>
            <p v-else class="text-[var(--el-color-success)]">
              校验通过，共 {{ message.artifact.attempts.length }} 轮，可预览或存版。
            </p>
            <p class="mt-1 text-[var(--el-text-color-secondary)]">
              {{ message.artifact.mode === 'patch' ? '增量补丁' : '完整生成' }}
              <span v-if="message.artifact.outputChars"> · 输出 {{ message.artifact.outputChars }} 字符</span>
              <span v-if="message.artifact.reasoningChars"> · reasoning {{ message.artifact.reasoningChars }} 字符</span>
              <span v-if="message.artifact.usage?.totalTokens"> · {{ message.artifact.usage.totalTokens }} tokens</span>
              <span v-if="message.artifact.truncated" class="text-[var(--el-color-warning)]"> · 输出被截断</span>
              <span v-if="message.artifact.truncated && message.artifact.finishReason">（{{ message.artifact.finishReason }}）</span>
            </p>
            <div class="mt-2 border-t border-[var(--el-border-color-lighter)] pt-2">
              <div class="flex flex-wrap items-center gap-2">
                <el-button
                  size="small"
                  :loading="diags[message.id]?.status === 'running'"
                  :disabled="!message.artifact.draftId"
                  @click="runDiagnose(message)"
                >
                  {{ diags[message.id]?.status === 'running' ? '自检中…' : '渲染自检' }}
                </el-button>
                <el-button
                  v-if="diags[message.id]?.status === 'done' && !diags[message.id]?.report?.ok"
                  size="small"
                  type="warning"
                  @click="sendReportToAi(message)"
                >
                  把渲染问题发给 AI
                </el-button>
                <span
                  v-if="diags[message.id]?.status === 'done' && diags[message.id]?.report?.ok"
                  class="text-[var(--el-color-success)]"
                >
                  自检通过：挂载正常、无溢出、无运行时错误
                </span>
                <span v-else-if="diags[message.id]?.status === 'failed'" class="text-[var(--el-color-danger)]">
                  自检失败：{{ diags[message.id]?.note }}
                </span>
              </div>
              <div
                v-if="diags[message.id]?.status === 'done' && diags[message.id]?.report && !diags[message.id]?.report?.ok"
                class="mt-1 space-y-0.5"
              >
                <p
                  v-for="(item, index) in diags[message.id]?.report?.errors"
                  :key="`e-${index}`"
                  class="text-[var(--el-color-danger)]"
                >
                  ✕ {{ item }}
                </p>
                <p
                  v-for="(item, index) in diags[message.id]?.report?.warnings"
                  :key="`w-${index}`"
                  class="text-[var(--el-color-warning)]"
                >
                  ⚠ {{ item }}
                </p>
              </div>
            </div>
            <div class="mt-2 flex flex-wrap gap-2">
              <el-button size="small" :disabled="!message.artifact.html" @click="fillEditor(message.artifact!)">
                填入编辑器
              </el-button>
              <el-button size="small" :disabled="!message.artifact.draftId" @click="previewDraft(message)">
                预览草稿
              </el-button>
              <el-button
                size="small"
                type="primary"
                :loading="!!saving[message.id]"
                :disabled="!props.themeId || !message.artifact.draftId || !!message.artifact.errors.length"
                @click="saveVersion(message)"
              >
                存为新版本
              </el-button>
            </div>
            <div class="mt-2 flex flex-wrap items-center gap-2">
              <el-input
                v-model="newThemeName"
                size="small"
                placeholder="新主题名称"
                style="width: 150px"
                :disabled="streaming"
              />
              <el-button
                size="small"
                :loading="!!creating[message.id]"
                :disabled="!message.artifact.draftId || !!message.artifact.errors.length || !newThemeName.trim()"
                @click="saveAsNewTheme(message)"
              >
                存为新主题
              </el-button>
            </div>
          </div>
        </div>
      </div>
      <el-button
        v-if="!stick && messages.length"
        class="absolute bottom-3 left-1/2 -translate-x-1/2 shadow-md"
        size="small"
        round
        @click="backToBottom"
      >
        回到底部
      </el-button>
    </div>

    <iframe ref="diagIframe" title="渲染自检" class="hidden" aria-hidden="true" />

    <div class="flex items-end gap-2">
      <el-input
        v-model="input"
        type="textarea"
        :rows="3"
        placeholder="例如：把首页改成杂志风，文章卡片圆角大一些"
        :disabled="streaming"
        @keydown.enter.exact.prevent="send"
      />
      <div class="flex flex-col gap-2">
        <el-button type="primary" :loading="streaming" :disabled="!input.trim()" @click="send">发送</el-button>
        <el-button v-if="streaming" @click="stop">中断</el-button>
      </div>
    </div>
  </div>
</el-drawer>
</template>

<style scoped>
.ai-prose p {
  margin: 0 0 0.4em;
}
.ai-prose p:last-child {
  margin-bottom: 0;
}
.ai-prose ul {
  margin: 0 0 0.4em;
  padding-left: 1.2em;
  list-style: disc;
}
.ai-prose li {
  margin: 0.15em 0;
}
.ai-prose code {
  padding: 0.1em 0.35em;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.85em;
}
</style>
