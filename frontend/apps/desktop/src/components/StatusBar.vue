<script setup lang="ts">
import type { Session } from '../../bindings/cc.naihao/simplenotes/desktop/internal/auth/models'
import type { DocumentTab } from '../types/document'
import { computed } from 'vue'

const props = defineProps<{
  tab: DocumentTab | null
  session: Session | null
  online: boolean
  pending: number
  folderLabel: string
  message: string
  error: string
  hasErrorDetail: boolean
  syncError: string
}>()

const emit = defineEmits<{
  'show-error': []
}>()

const wordCount = computed(() => {
  const content = props.tab?.content ?? ''
  return [...content.replace(/\s/g, '')].length
})

const saveLabel = computed(() => {
  const tab = props.tab
  if (!tab) {
    return ''
  }
  if (tab.saveState === 'saving') {
    return '保存中…'
  }
  switch (tab.saveState) {
    case 'conflict':
      return '冲突'
    case 'offline':
      return tab.source === 'local' ? '已保存到本地' : '离线待同步'
    case 'error':
      return '保存失败'
    case 'missing':
      return '文件已删除'
    case 'modified':
      return '文件已被外部修改'
    case 'synced':
      return '已同步'
    case 'saved':
      return tab.source === 'file' ? '已保存到文件' : '已保存到本地'
    default:
      if (tab.dirty) {
        return tab.source === 'file' ? '未保存到文件' : '未保存'
      }
      return tab.source === 'file' ? '已保存到文件' : tab.source === 'local' ? '已保存到本地' : '已同步'
  }
})

const details = computed(() => {
  const tab = props.tab
  if (!tab) {
    return props.session ? '索引已就绪' : ''
  }
  if (tab.source === 'file') {
    return `${tab.file?.size ?? 0} B · ${tab.file?.lineEnding === 'crlf' ? 'CRLF' : 'LF'}`
  }
  if (tab.note?.localOnly) {
    return props.folderLabel || '未分类'
  }
  return `v${tab.note?.remoteVersion ?? 0} · ${props.folderLabel || '云端'}`
})
</script>

<template>
  <footer class="flex h-[26px] shrink-0 items-center gap-3 border-t border-app-border px-3 text-[10px] text-app-muted">
    <span
      v-if="saveLabel"
      :class="tab?.saveState === 'conflict' || tab?.saveState === 'error' ? 'text-red-400' : tab?.dirty ? 'text-amber-400' : ''"
    >
      {{ saveLabel }}
    </span>
    <span v-if="tab" class="hidden sm:inline">{{ wordCount }} 字</span>
    <span v-if="pending" class="text-amber-400">待同步 {{ pending }}</span>
    <span v-if="message" class="truncate text-emerald-400" :title="message">{{ message }}</span>
    <span v-if="error" class="flex min-w-0 items-center gap-1 text-red-400">
      <span class="truncate" :title="error">{{ error }}</span>
      <button
        v-if="hasErrorDetail"
        class="shrink-0 rounded border border-red-400/40 px-1 text-[10px] leading-4 text-red-300 hover:bg-red-400/10"
        type="button"
        @click="emit('show-error')"
      >
        详情
      </button>
    </span>
    <span v-else-if="syncError" class="truncate text-app-muted" :title="syncError">{{ syncError }}</span>
    <span v-if="details" class="ml-auto hidden shrink-0 md:inline">{{ details }}</span>
    <span v-if="session" class="ml-auto shrink-0" :class="online ? '' : 'text-amber-400'">{{ online ? '在线' : '离线' }}</span>
    <span class="shrink-0" :class="session ? '' : 'ml-auto'">{{ session ? session.nickname || session.username : '本地模式' }}</span>
  </footer>
</template>
