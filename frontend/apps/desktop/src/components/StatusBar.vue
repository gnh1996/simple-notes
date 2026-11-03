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
  syncError: string
}>()

const wordCount = computed(() => {
  const content = props.tab?.content ?? ''
  return [...content.replace(/\s/g, '')].length
})

const saveLabel = computed(() => {
  const tab = props.tab
  if (!tab) {
    return props.session ? '已连接' : '本地模式'
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
    return props.session ? '索引已就绪' : '本地 SQLite · 不连接服务器'
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
    <span :class="tab?.saveState === 'conflict' || tab?.saveState === 'error' ? 'text-red-400' : tab?.dirty ? 'text-amber-400' : ''">
      {{ saveLabel }}
    </span>
    <span v-if="tab" class="hidden sm:inline">{{ wordCount }} 字</span>
    <span v-if="pending" class="text-amber-400">待同步 {{ pending }}</span>
    <span v-if="message" class="truncate text-emerald-400">{{ message }}</span>
    <span v-if="error" class="truncate text-red-400">{{ error }}</span>
    <span v-else-if="syncError" class="truncate text-app-muted">{{ syncError }}</span>
    <span class="ml-auto hidden shrink-0 md:inline">{{ details }}</span>
    <span class="shrink-0" :class="online ? '' : 'text-amber-400'">{{ online ? '在线' : '离线' }}</span>
    <span class="shrink-0">{{ session ? session.nickname || session.username : '本地' }}</span>
  </footer>
</template>
