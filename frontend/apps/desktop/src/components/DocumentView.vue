<script setup lang="ts">
import type { Session } from '../../bindings/cc.naihao/simplenotes/desktop/internal/auth/models'
import type { DocumentTab } from '../types/document'
import { computed, defineAsyncComponent, ref, watch } from 'vue'

// 编辑器按需加载：列表 / 起始页不解析 Vditor + Lute，降低启动与空闲内存。
const MarkdownEditor = defineAsyncComponent(() => import('./MarkdownEditor.vue'))

const props = defineProps<{
  tab: DocumentTab
  theme: 'light' | 'dark'
  folderOptions: { id: string; name: string }[]
  session: Session | null
}>()

type Action =
  | 'save'
  | 'save-as'
  | 'import'
  | 'promote'
  | 'export'
  | 'delete'
  | 'close'
  | 'reload'
  | 'force-save'
  | 'diff'
  | 'open-admin'
  | 'resolve-local'
  | 'resolve-remote'
  | 'resolve-delete-local'
  | 'resolve-delete-remote'

const emit = defineEmits<{
  'update:title': [value: string]
  'update:content': [value: string]
  'update:folder': [value: string]
  scroll: [top: number]
  action: [action: Action]
}>()

const menuOpen = ref(false)
const mediaContext = computed(() => {
  if (props.tab.source === 'file') {
    const token = props.tab.file?.mediaToken
    return token ? { target: 'file' as const, token } : null
  }
  if (props.tab.source === 'local') {
    return { target: 'local' as const }
  }
  return { target: 'server' as const }
})
const linkBase = computed(() => {
  if (props.tab.source === 'file' && props.tab.file?.mediaToken) {
    return `${window.location.origin}/__media/file/${props.tab.file.mediaToken}/`
  }
  if (props.tab.source === 'local') {
    return `${window.location.origin}/__media/`
  }
  return ''
})
const sourceLabel = computed(() => (props.tab.source === 'file' ? '文件' : props.tab.source === 'local' ? '本地' : '云端'))
const sourceClass = computed(() =>
  props.tab.source === 'file'
    ? 'bg-amber-500/10 text-amber-400'
    : props.tab.source === 'local'
      ? 'bg-violet-500/10 text-violet-300'
      : 'bg-app-bg text-app-muted',
)

watch(
  () => props.tab.key,
  () => {
    menuOpen.value = false
  },
)

function run(action: Action): void {
  menuOpen.value = false
  emit('action', action)
}

function formatTime(value: number): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(
    date.getMinutes(),
  ).padStart(2, '0')}`
}

function publishLabel(value: string): string {
  switch (value) {
    case 'DRAFT':
      return '草稿'
    case 'PUBLISHED':
      return '已发布'
    case 'UNPUBLISHED':
      return '已下架'
    default:
      return value
  }
}
</script>

<template>
  <section class="flex min-h-0 flex-1 flex-col">
    <div class="shrink-0 border-b border-app-border">
      <div class="mx-auto w-full max-w-[960px] px-6 pb-3 pt-4">
        <input
          v-if="tab.source !== 'file'"
          :value="tab.title"
          class="w-full rounded border border-transparent bg-transparent px-1 py-0.5 text-xl font-semibold outline-none hover:border-app-border focus:border-brand-400"
          placeholder="标题"
          @input="emit('update:title', ($event.target as HTMLInputElement).value)"
        />
        <div v-else class="w-full truncate px-1 py-0.5 text-xl font-semibold" :title="tab.file?.path">
          {{ tab.file?.name || tab.title }}
        </div>
        <div class="mt-1 flex flex-wrap items-center gap-2 px-1 text-[11px] text-app-muted">
          <span class="shrink-0 rounded px-1.5 py-0.5" :class="sourceClass">{{ sourceLabel }}</span>
          <span v-if="tab.source === 'file'" class="min-w-0 max-w-[42%] truncate" :title="tab.file?.path">
            {{ tab.file?.path }}
          </span>
          <select
            v-else-if="tab.source === 'local' || (tab.source === 'server' && tab.note?.kind !== 'POST')"
            :value="tab.note?.folderId ?? ''"
            class="rounded border border-app-border bg-app-bg px-1 py-0.5 text-[11px] text-app-fg outline-none focus:border-brand-400"
            @change="emit('update:folder', ($event.target as HTMLSelectElement).value)"
          >
            <option v-for="option in folderOptions" :key="option.id || 'uncategorized'" :value="option.id">
              {{ option.name }}
            </option>
          </select>
          <span v-if="tab.source === 'server' && tab.note?.kind === 'POST'">博客文章</span>
          <span v-if="tab.source === 'server'">v{{ tab.note?.remoteVersion ?? 0 }}</span>
          <span v-if="tab.note?.publishStatus && tab.note.kind === 'POST'">{{ publishLabel(tab.note.publishStatus) }}</span>
          <span v-if="tab.note?.hasUnpublishedChanges" class="text-amber-400">有未发布更改</span>
          <span v-if="tab.note?.tags?.length" class="truncate">{{ tab.note.tags.join(' · ') }}</span>
          <span class="ml-auto">{{ formatTime(tab.note?.updatedAt ?? tab.file?.mtimeMs ?? 0) }}</span>
          <button
            v-if="tab.source === 'file'"
            class="rounded border border-brand-400/60 px-2 py-0.5 text-[10px] text-brand-400 hover:bg-brand-500/10 disabled:opacity-50"
            type="button"
            :disabled="tab.saveState === 'saving' || !tab.loaded"
            @click="emit('action', 'save')"
          >
            保存到文件
          </button>
          <div class="relative">
            <button
              class="rounded border border-app-border px-2 py-0.5 text-[10px] text-app-muted hover:bg-app-bg"
              type="button"
              @click="menuOpen = !menuOpen"
            >
              ⋯
            </button>
            <div v-if="menuOpen" class="fixed inset-0 z-30" @click="menuOpen = false" />
            <div
              v-if="menuOpen"
              class="absolute right-0 z-40 mt-1 w-52 rounded-lg border border-app-border bg-app-panel p-1 text-xs shadow-xl"
            >
              <template v-if="tab.source === 'file'">
                <button class="w-full rounded px-2 py-1.5 text-left hover:bg-app-bg" type="button" @click="run('save')">
                  保存到文件 <span class="float-right text-[10px] text-app-muted">Ctrl S</span>
                </button>
                <button class="w-full rounded px-2 py-1.5 text-left hover:bg-app-bg" type="button" @click="run('save-as')">
                  另存为…
                </button>
                <button class="w-full rounded px-2 py-1.5 text-left hover:bg-app-bg" type="button" @click="run('reload')">
                  重新加载
                </button>
                <button class="w-full rounded px-2 py-1.5 text-left text-brand-400 hover:bg-app-bg" type="button" @click="run('import')">
                  纳入管理…
                </button>
              </template>
              <template v-else>
                <button
                  v-if="tab.source === 'local' && session"
                  class="w-full rounded px-2 py-1.5 text-left text-brand-400 hover:bg-app-bg"
                  type="button"
                  @click="run('promote')"
                >
                  同步到云端
                </button>
                <button class="w-full rounded px-2 py-1.5 text-left hover:bg-app-bg" type="button" @click="run('export')">
                  导出为 Markdown…
                </button>
                <button
                  v-if="tab.source === 'server' && tab.note?.kind === 'POST'"
                  class="w-full rounded px-2 py-1.5 text-left text-brand-400 hover:bg-app-bg"
                  type="button"
                  @click="run('open-admin')"
                >
                  管理端发布
                </button>
              </template>
              <div class="my-1 border-t border-app-border" />
              <button class="w-full rounded px-2 py-1.5 text-left hover:bg-app-bg" type="button" @click="run('close')">
                关闭标签 <span class="float-right text-[10px] text-app-muted">Ctrl W</span>
              </button>
              <button
                v-if="tab.source !== 'file'"
                class="w-full rounded px-2 py-1.5 text-left text-red-400 hover:bg-app-bg"
                type="button"
                @click="run('delete')"
              >
                删除
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div
      v-if="tab.note?.remoteDeleted && tab.dirty"
      class="shrink-0 border-b border-red-500/30 bg-red-500/10 px-4 py-2 text-xs text-red-300"
    >
      <p>云端已删除这篇内容，本地有未推送修改。</p>
      <div class="mt-2 flex gap-2">
        <button class="rounded border border-red-500/40 px-2.5 py-1 text-[11px] hover:bg-red-500/10" type="button" @click="emit('action', 'resolve-delete-local')">
          保留为本地副本
        </button>
        <button class="rounded border border-app-border px-2.5 py-1 text-[11px] text-app-fg hover:bg-app-bg" type="button" @click="emit('action', 'resolve-delete-remote')">
          放弃本地修改
        </button>
      </div>
    </div>

    <div
      v-if="tab.saveState === 'conflict'"
      class="shrink-0 border-b border-red-500/30 bg-red-500/10 px-4 py-2 text-xs text-red-300"
    >
      <p>云端已有新版本（{{ tab.remoteTitle || '未命名' }} · v{{ tab.remoteVersion }}），本地修改尚未覆盖。</p>
      <div class="mt-2 flex gap-2">
        <button class="rounded border border-red-500/40 px-2.5 py-1 text-[11px] hover:bg-red-500/10" type="button" @click="emit('action', 'resolve-local')">
          以本地为准
        </button>
        <button class="rounded border border-app-border px-2.5 py-1 text-[11px] text-app-fg hover:bg-app-bg" type="button" @click="emit('action', 'resolve-remote')">
          以云端为准
        </button>
        <button class="rounded border border-app-border px-2.5 py-1 text-[11px] text-app-muted hover:bg-app-bg" type="button" @click="emit('action', 'diff')">
          查看差异
        </button>
      </div>
    </div>

    <div
      v-else-if="tab.source === 'file' && tab.saveState === 'modified'"
      class="shrink-0 border-b border-amber-500/30 bg-amber-500/10 px-4 py-2 text-xs text-amber-300"
    >
      <p>文件已被其他程序修改。</p>
      <div class="mt-2 flex gap-2">
        <button class="rounded border border-amber-500/40 px-2 py-1 text-[11px] hover:bg-amber-500/10" type="button" @click="emit('action', 'force-save')">
          覆盖
        </button>
        <button class="rounded border border-app-border px-2 py-1 text-[11px] hover:bg-app-bg" type="button" @click="emit('action', 'save-as')">
          另存为
        </button>
        <button class="rounded border border-app-border px-2 py-1 text-[11px] hover:bg-app-bg" type="button" @click="emit('action', 'reload')">
          重新加载
        </button>
      </div>
    </div>

    <div
      v-else-if="tab.source === 'file' && tab.saveState === 'missing'"
      class="shrink-0 border-b border-red-500/30 bg-red-500/10 px-4 py-2 text-xs text-red-300"
    >
      <p>{{ tab.saveMessage || '文件已被删除。' }}</p>
      <button class="mt-2 rounded border border-red-500/40 px-2 py-1 text-[11px] hover:bg-red-500/10" type="button" @click="emit('action', 'save-as')">
        另存为
      </button>
    </div>

    <div
      v-else-if="tab.source === 'file' && tab.saveState === 'error'"
      class="shrink-0 border-b border-red-500/30 bg-red-500/10 px-4 py-2 text-xs text-red-300"
    >
      {{ tab.saveMessage || '保存失败' }}
    </div>

    <p v-else-if="tab.saveMessage" class="shrink-0 px-6 pt-2 text-[11px] text-amber-400">
      {{ tab.saveMessage }}
    </p>

    <div class="min-h-0 flex-1">
      <div v-if="tab.error" class="mx-auto flex h-full w-full max-w-[960px] flex-col items-start justify-center gap-3 px-6">
        <p class="text-sm text-red-400">{{ tab.error }}</p>
        <button class="rounded border border-app-border px-3 py-1.5 text-xs hover:bg-app-panel" type="button" @click="emit('action', 'reload')">
          重试
        </button>
      </div>
      <div v-else-if="tab.loading || !tab.loaded" class="mx-auto flex h-full w-full max-w-[960px] items-center justify-center px-6">
        <p class="text-xs text-app-muted">{{ tab.loading ? '正在读取…' : '尚未加载，激活标签后读取。' }}</p>
      </div>
      <div v-else class="mx-auto h-full w-full max-w-[960px] px-6 py-4">
        <MarkdownEditor
          :model-value="tab.content"
          :theme="theme"
          :reset-key="tab.key"
          :scroll-top="tab.scrollTop"
          :link-base="linkBase"
          :media-context="mediaContext"
          placeholder="开始写作…"
          @update:model-value="emit('update:content', $event)"
          @scroll="emit('scroll', $event)"
        />
      </div>
    </div>
  </section>
</template>
