<script setup lang="ts">
/**
 * AI 主题对话抽屉（docs/theme-ai.md §2）。
 *
 * 无服务端记忆：history 随每轮请求发送；产物进临时草稿缓存，定版走既有 createVersion。
 * 流式协议（SSE 事件）：token / round / done / error。
 */
import type { ThemeSummary, ThemeVersionResponse } from '@simple-notes/api'
import { ElMessage } from 'element-plus'
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'

import { getStoredToken, themeApi } from '@/api'
import { notifyError } from '@/utils/feedback'

const BLOG_URL = (import.meta.env.VITE_BLOG_URL as string | undefined) ?? 'http://localhost:8080'
const BLOG_ORIGIN = (() => {
  try {
    return new URL(BLOG_URL).origin
  } catch {
    return window.location.origin
  }
})()

/**
 * TODO(pnpm gen:api)：后端 AiChatRequest 落契约后，用生成类型替换以下两处本地镜像。
 * 后端跑起来（需 DB）执行一次 `pnpm gen:api` 即可，前端无需改调用处。
 */
interface AiChatTurn {
  role: 'user' | 'assistant'
  content: string
}

interface AiChatRequest {
  history: AiChatTurn[]
  prompt: string
  baseHtml?: string | null
  baseCss?: string | null
  includeStarter: boolean
}

interface AiAttempt {
  round: number
  errors: string[]
}

interface AiArtifact {
  html: string
  css: string
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

/** fenced 代码块（含流式未闭合）与 <plan> 块（含流式未闭合）。 */
const FENCED_BLOCK = /```[\s\S]*?(?:```|$)/g
const PLAN_BLOCK = /<plan>([\s\S]*?)(?:<\/plan>|$)/gi

/** 从 assistant 文本提取本轮计划（后端 SYSTEM_RULES 约定先输出 <plan>，流式未闭合也解析）。 */
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

/** 展示用正文：去掉 <plan> 与 fenced 代码块（产物走产物卡片，不在这里重复展示）。 */
function proseOf(text: string): string {
  return text
    .replace(/<plan>[\s\S]*?(?:<\/plan>|$)/gi, '')
    .replace(FENCED_BLOCK, '')
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
  fill: [html: string, css: string]
  saved: [version: ThemeVersionResponse]
  created: [theme: ThemeSummary]
  previewDraft: [draftId: string, token: string, html: string, css: string]
}>()

const visible = defineModel<boolean>('visible', { default: false })

const messages = ref<ChatMessage[]>([])
const input = ref('')
const streaming = ref(false)
const includeStarter = ref(true)
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
  const raw =
    message.role === 'assistant' && message.artifact
      ? `${message.text}\n[产物：${message.artifact.errors.length ? `校验未通过（${message.artifact.errors.join('；')}）` : '校验通过'}]`
      : message.text
  // 历史只留决策上下文：plan 去标签保留要点，代码块太大只留占位（省 token，长会话不丢早期版式决策见 §6 缺口 3）。
  return raw
    .replace(/<plan>([\s\S]*?)(?:<\/plan>|$)/gi, (_, plan: string) => `[本轮计划：${plan.trim().slice(0, 500)}]`)
    .replace(FENCED_BLOCK, '[代码块已省略]')
}

function historyOf(): AiChatTurn[] {
  const turns: AiChatTurn[] = []
  for (const message of messages.value) {
    if (message.streaming) continue
    turns.push({ role: message.role, content: historyTextOf(message).slice(0, 2000) })
  }
  return turns.slice(-20)
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

  const body: AiChatRequest = {
    history: historyOf().slice(0, -1),
    prompt,
    baseHtml: props.baseHtml || null,
    baseCss: props.baseCss || null,
    includeStarter: includeStarter.value,
  }
  includeStarter.value = false
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
      throw new Error(`AI 请求失败（HTTP ${response.status}）`)
    }
    await readStream(response.body.getReader(), assistant, queueDelta, stopFlush)
  } catch (error) {
    if ((error as Error).name === 'AbortError') {
      assistant.text += '\n（已中断）'
    } else if (!assistant.artifact) {
      // done 已到达后的断流（如连接复用回收）不算失败：产物卡片已展示
      notifyError(error, 'AI 生成失败')
      assistant.text += '\n（出错：请重试）'
    }
  } finally {
    stopFlush()
    assistant.streaming = false
    streaming.value = false
    aborter = null
    stopTimer()
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
      draftId: (payload.draftId as string | null) ?? null,
      errors: (payload.errors as string[] | undefined) ?? [],
      attempts: (payload.attempts as AiAttempt[] | undefined) ?? [],
    }
  } else if (event === 'error') {
    assistant.text += `\n（${String(payload.message ?? '生成失败')}）`
  }
}

function stop(): void {
  aborter?.abort()
}

async function fillEditor(artifact: AiArtifact): Promise<void> {
  emit('fill', artifact.html, artifact.css)
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
      contract: 1,
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
      contract: 1,
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
  const lines = [
    '渲染自检发现以下问题，请输出完整修正后的 ```html / ```css（不是 diff），先输出 <plan> 说明每条怎么改：',
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

onBeforeUnmount(() => {
  window.removeEventListener('message', onDiagnoseMessage)
})
</script>

<template>
  <el-drawer v-model="visible" title="AI 定制主题" size="min(560px, 94vw)" destroy-on-close>
    <div class="flex h-full flex-col gap-3">
      <div class="flex items-center justify-between gap-2 text-xs text-[var(--el-text-color-secondary)]">
        <span>对话直接改主题；产物先过契约校验，通过才进草稿。</span>
        <el-checkbox v-model="includeStarter" size="small" :disabled="streaming">首轮附带默认主题</el-checkbox>
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
                v-if="extractPlan(message.text).length"
                class="mb-2 space-y-1 border-l-2 border-[var(--el-color-primary)] pl-3"
              >
                <li
                  v-for="(step, index) in extractPlan(message.text)"
                  :key="index"
                  class="flex gap-1.5 text-xs text-[var(--el-text-color-secondary)]"
                >
                  <span>{{ message.streaming && index === extractPlan(message.text).length - 1 ? '⏳' : '✓' }}</span>
                  <span>{{ step }}</span>
                </li>
              </ol>
              <div
                v-if="proseOf(message.text)"
                class="ai-prose break-words text-sm"
                v-html="renderProseHtml(proseOf(message.text))"
              />
              <p v-else class="text-sm text-[var(--el-text-color-secondary)]">
                {{ message.streaming ? '已发送，等待模型响应…' : '…' }}
              </p>
            </template>

          <div v-if="message.rounds?.length" class="mt-2 space-y-1 text-xs text-[var(--el-text-color-secondary)]">
            <p v-for="round in message.rounds" :key="round.round">
              第 {{ round.round }} 轮{{ round.status === 'repairing' ? '自动修复中' : '' }}<span v-if="round.errors.length">：{{ round.errors.join('；') }}</span>
            </p>
          </div>

          <div v-if="message.artifact" class="mt-2 rounded-md border border-[var(--el-border-color-lighter)] p-2 text-xs">
            <p v-if="message.artifact.errors.length" class="text-[var(--el-color-danger)]">
              校验未通过（{{ message.artifact.errors.length }}）：{{ message.artifact.errors.join('；') }}
            </p>
            <p v-else class="text-[var(--el-color-success)]">
              校验通过，共 {{ message.artifact.attempts.length }} 轮，可预览或存版。
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
