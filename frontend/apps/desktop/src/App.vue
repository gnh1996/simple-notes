<script setup lang="ts">
import { markdownCommonProps, markdownToolbars } from '@simple-notes/shared'
import type { MeResponse } from '@simple-notes/api'
import { MdEditor } from 'md-editor-v3'
import { computed, onMounted, ref } from 'vue'

import { Service as ApiService } from '../bindings/cc.naihao/simplenotes/desktop/internal/api'
import { Service as SystemService } from '../bindings/cc.naihao/simplenotes/desktop/internal/system'
import type { Status } from '../bindings/cc.naihao/simplenotes/desktop/internal/system'

// 骨架阶段只验证：Wails 壳 + 共享编辑器 + Go 侧 HTTP 转发。
// 业务页面（笔记 / 文章 / 同步）随功能设计定稿后再实现。
const content = ref(`# Simple Notes 桌面端骨架

这是一段用于验证方言与编辑器的示例。

> [!NOTE]
> 预览与 blog 前台使用同一份 \`markdownDialect\` 配置。

\`\`\`ts
const hello = (name: string) => \`Hello, \${name}\`
\`\`\`
`)

const status = ref<Status | null>(null)
const me = ref<MeResponse | null>(null)
const probe = ref('尚未测试')
const probing = ref(false)
const isDark = ref(window.matchMedia('(prefers-color-scheme: dark)').matches)

const editorTheme = computed(() => (isDark.value ? 'dark' : 'light'))

function applyTheme(): void {
  document.documentElement.classList.toggle('dark', isDark.value)
  document.documentElement.classList.toggle('light', !isDark.value)
}

function toggleTheme(): void {
  isDark.value = !isDark.value
  applyTheme()
}

async function loadStatus(): Promise<void> {
  try {
    status.value = await SystemService.Status()
  } catch (error) {
    probe.value = `读取运行状态失败：${String(error)}`
  }
}

async function testConnection(): Promise<void> {
  probing.value = true
  try {
    const res = await ApiService.Do({ method: 'GET', path: '/auth/me' })
    if (res?.status === 200) {
      me.value = JSON.parse(res.body) as MeResponse
      probe.value = `已登录：${me.value.user.nickname}（${me.value.entries.join(', ')}）`
    } else {
      probe.value = res
        ? `后端可达：HTTP ${res.status}${res.status === 401 ? '（未登录，符合预期）' : ''}`
        : '后端无响应'
    }
  } catch (error) {
    probe.value = `连接失败：${String(error)}`
  } finally {
    probing.value = false
  }
}

applyTheme()
onMounted(loadStatus)
</script>

<template>
  <div class="flex h-full flex-col bg-app-bg text-app-fg">
    <header class="flex h-14 shrink-0 items-center justify-between border-b border-app-border px-4">
      <div class="flex items-center gap-3">
        <span class="text-base font-semibold">Simple Notes</span>
        <span class="rounded bg-brand-500/10 px-2 py-0.5 text-xs text-brand-400">
          Wails v3 骨架
        </span>
      </div>
      <button
        class="rounded border border-app-border px-3 py-1 text-sm hover:bg-app-panel"
        type="button"
        @click="toggleTheme"
      >
        {{ isDark ? '浅色' : '深色' }}
      </button>
    </header>

    <main class="grid min-h-0 flex-1 grid-cols-[200px_minmax(0,1fr)_300px] gap-4 p-4">
      <aside class="rounded-lg border border-app-border bg-app-panel p-3 text-sm">
        <p class="mb-2 text-xs uppercase tracking-wide text-app-muted">导航占位</p>
        <ul class="space-y-1">
          <li class="rounded bg-brand-500/10 px-2 py-1.5 text-brand-400">编辑器 POC</li>
          <li class="px-2 py-1.5 text-app-muted">随手记</li>
          <li class="px-2 py-1.5 text-app-muted">博客文章</li>
          <li class="px-2 py-1.5 text-app-muted">设置</li>
        </ul>
      </aside>

      <section class="flex min-h-0 flex-col rounded-lg border border-app-border bg-app-panel p-3">
        <MdEditor
          v-model="content"
          v-bind="markdownCommonProps"
          :theme="editorTheme"
          :toolbars="markdownToolbars"
          :style="{ height: '100%', minHeight: '420px' }"
          placeholder="开始记录…"
        />
      </section>

      <aside class="space-y-3 text-sm">
        <div class="rounded-lg border border-app-border bg-app-panel p-4">
          <h2 class="mb-3 text-sm font-medium">运行状态</h2>
          <dl class="space-y-2 text-xs">
            <div class="flex justify-between gap-2">
              <dt class="text-app-muted">版本</dt>
              <dd>{{ status?.version ?? '—' }}</dd>
            </div>
            <div class="flex justify-between gap-2">
              <dt class="text-app-muted">后端</dt>
              <dd class="truncate text-right" :title="status?.apiBaseUrl">{{ status?.apiBaseUrl ?? '—' }}</dd>
            </div>
            <div class="flex justify-between gap-2">
              <dt class="text-app-muted">会话</dt>
              <dd>{{ status?.hasToken ? '已保存' : '未登录' }}</dd>
            </div>
            <div class="flex justify-between gap-2">
              <dt class="text-app-muted">本地库</dt>
              <dd class="truncate text-right" :title="status?.databasePath">{{ status?.databasePath ?? '—' }}</dd>
            </div>
          </dl>
          <button
            class="mt-3 w-full rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-bg disabled:opacity-60"
            type="button"
            :disabled="probing"
            @click="testConnection"
          >
            {{ probing ? '测试中…' : '测试后端连接' }}
          </button>
          <p class="mt-2 text-xs text-app-muted">{{ probe }}</p>
        </div>

        <div class="rounded-lg border border-app-border bg-app-panel p-4 text-xs text-app-muted">
          <p class="mb-2 font-medium text-app-fg">当前验证点</p>
          <ul class="list-disc space-y-1 pl-4">
            <li>WebView 内加载共享编辑器与方言配置</li>
            <li>Go binding：系统状态 / HTTP 转发</li>
            <li>SQLite：本地库路径与令牌状态</li>
          </ul>
          <p class="mt-3">登录、笔记列表与同步在功能设计定稿后接入。</p>
        </div>
      </aside>
    </main>
  </div>
</template>
