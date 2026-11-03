<script setup lang="ts">
import type { Session } from '../../bindings/cc.naihao/simplenotes/desktop/internal/auth/models'
import type { Status } from '../../bindings/cc.naihao/simplenotes/desktop/internal/system/models'
import { ref } from 'vue'

defineProps<{
  session: Session | null
  status: Status | null
  syncing: boolean
  pending: number
  online: boolean
  sidebarOpen: boolean
  isDark: boolean
}>()

const emit = defineEmits<{
  'toggle-sidebar': []
  'quick-open': []
  'new-local': []
  'new-server': []
  'open-file': []
  'open-import': []
  login: []
  logout: []
  sync: []
  retry: []
  'toggle-theme': []
}>()

const newMenu = ref(false)
const accountMenu = ref(false)
const syncMenu = ref(false)

function closeMenus(): void {
  newMenu.value = false
  accountMenu.value = false
  syncMenu.value = false
}

function run(action: () => void): void {
  closeMenus()
  action()
}
</script>

<template>
  <header class="flex h-11 shrink-0 items-center gap-2 border-b border-app-border px-3">
    <button
      class="flex h-8 w-8 shrink-0 items-center justify-center rounded text-sm text-app-muted hover:bg-app-panel hover:text-app-fg"
      :class="sidebarOpen ? 'bg-app-panel text-app-fg' : ''"
      type="button"
      title="资料库（Ctrl+B / Ctrl+Shift+L）"
      @click="emit('toggle-sidebar')"
    >
      ☰
    </button>
    <span class="hidden shrink-0 text-sm font-semibold sm:inline">Simple Notes</span>

    <button
      class="mx-auto flex h-8 w-full max-w-md items-center gap-2 rounded-md border border-app-border bg-app-panel px-2.5 text-left text-xs text-app-muted hover:border-brand-400/60"
      type="button"
      title="快速打开（Ctrl+K / Ctrl+Shift+K）"
      @click="emit('quick-open')"
    >
      <span class="text-sm leading-none">⌕</span>
      <span class="truncate">快速打开</span>
      <span class="ml-auto hidden shrink-0 rounded border border-app-border px-1 text-[10px] md:inline">Ctrl K</span>
    </button>

    <div class="relative shrink-0">
      <button
        class="flex h-8 items-center gap-1 rounded border border-app-border px-2.5 text-xs hover:bg-app-panel"
        type="button"
        @click="newMenu = !newMenu; accountMenu = false; syncMenu = false"
      >
        <span class="text-sm leading-none">+</span>
        <span>新建</span>
        <span class="text-[10px] text-app-muted">▾</span>
      </button>
      <div v-if="newMenu" class="fixed inset-0 z-30" @click="newMenu = false" />
      <div
        v-if="newMenu"
        class="absolute right-0 z-40 mt-1 w-56 rounded-lg border border-app-border bg-app-panel p-1 shadow-xl"
      >
        <button class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg" type="button" @click="run(() => emit('new-local'))">
          新建本地笔记 <span class="float-right text-[10px] text-app-muted">Ctrl N</span>
        </button>
        <button
          v-if="session"
          class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg"
          type="button"
          @click="run(() => emit('new-server'))"
        >
          新建到云端
        </button>
        <div class="my-1 border-t border-app-border" />
        <button class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg" type="button" @click="run(() => emit('open-file'))">
          打开 Markdown 文件… <span class="float-right text-[10px] text-app-muted">Ctrl O</span>
        </button>
        <button class="w-full rounded px-2 py-1.5 text-left text-xs hover:bg-app-bg" type="button" @click="run(() => emit('open-import'))">
          打开并纳入管理…
        </button>
      </div>
    </div>

    <div class="relative shrink-0">
      <button
        class="flex h-8 items-center gap-1.5 rounded border border-app-border px-2.5 text-xs hover:bg-app-panel"
        type="button"
        title="同步状态"
        @click="syncMenu = !syncMenu; newMenu = false; accountMenu = false"
      >
        <span :class="syncing ? 'inline-block animate-spin' : ''">⟳</span>
        <span v-if="pending" class="rounded bg-amber-500/15 px-1 text-[10px] text-amber-400">{{ pending }}</span>
        <span v-else-if="!online" class="h-1.5 w-1.5 rounded-full bg-amber-400" />
      </button>
      <div v-if="syncMenu" class="fixed inset-0 z-30" @click="syncMenu = false" />
      <div
        v-if="syncMenu"
        class="absolute right-0 z-40 mt-1 w-64 rounded-lg border border-app-border bg-app-panel p-2 text-xs shadow-xl"
      >
        <p class="px-1 py-1 text-[11px] text-app-muted">
          {{ session ? (syncing ? '正在同步索引…' : online ? '已连接服务器' : '离线，本地内容仍可用') : '本地模式，不连接服务器' }}
        </p>
        <button
          v-if="session"
          class="mt-1 w-full rounded px-2 py-1.5 text-left hover:bg-app-bg disabled:opacity-50"
          type="button"
          :disabled="syncing"
          @click="closeMenus(); emit('sync')"
        >
          同步索引
        </button>
        <button
          v-if="session && pending"
          class="w-full rounded px-2 py-1.5 text-left text-amber-400 hover:bg-app-bg"
          type="button"
          @click="closeMenus(); emit('retry')"
        >
          重试待同步 {{ pending }} 项
        </button>
        <button
          v-if="session"
          class="w-full rounded px-2 py-1.5 text-left hover:bg-app-bg"
          type="button"
          @click="closeMenus(); emit('toggle-theme')"
        >
          {{ isDark ? '切换浅色主题' : '切换深色主题' }}
        </button>
        <button
          v-if="!session"
          class="mt-1 w-full rounded px-2 py-1.5 text-left text-brand-400 hover:bg-app-bg"
          type="button"
          @click="closeMenus(); emit('login')"
        >
          登录以同步到服务器
        </button>
      </div>
    </div>

    <div class="relative shrink-0">
      <button
        class="flex h-8 max-w-[150px] items-center gap-1 rounded border border-app-border px-2.5 text-xs hover:bg-app-panel"
        type="button"
        @click="accountMenu = !accountMenu; newMenu = false; syncMenu = false"
      >
        <span class="truncate">{{ session ? status?.nickname || status?.username || '已登录' : '本地' }}</span>
        <span class="text-[10px] text-app-muted">▾</span>
      </button>
      <div v-if="accountMenu" class="fixed inset-0 z-30" @click="accountMenu = false" />
      <div
        v-if="accountMenu"
        class="absolute right-0 z-40 mt-1 w-64 rounded-lg border border-app-border bg-app-panel p-2 text-xs shadow-xl"
      >
        <p v-if="session" class="px-1 py-1 text-[11px] leading-relaxed text-app-muted">
          {{ status?.nickname || status?.username }}<br />
          <span class="break-all">{{ status?.serverUrl }}</span>
        </p>
        <p v-else class="px-1 py-1 text-[11px] leading-relaxed text-app-muted">
          本地模式：笔记与文件都只存在本机，不连接服务器。
        </p>
        <button
          v-if="session"
          class="mt-1 w-full rounded px-2 py-1.5 text-left hover:bg-app-bg"
          type="button"
          @click="closeMenus(); emit('sync')"
        >
          同步索引
        </button>
        <button
          v-if="session"
          class="w-full rounded px-2 py-1.5 text-left text-red-400 hover:bg-app-bg"
          type="button"
          @click="closeMenus(); emit('logout')"
        >
          退出登录
        </button>
        <button v-else class="mt-1 w-full rounded px-2 py-1.5 text-left text-brand-400 hover:bg-app-bg" type="button" @click="closeMenus(); emit('login')">
          登录
        </button>
      </div>
    </div>
  </header>
</template>
