<script setup lang="ts">
import { useBreakpoint } from '@simple-notes/shared'

interface NoteItem {
  id: number
  title: string
  tag: string
  updatedAt: string
}

const notes: NoteItem[] = [
  { id: 1, title: '设计随手记的数据库表结构', tag: '开发', updatedAt: '2026-10-15' },
  { id: 2, title: '读完《深入理解 JVM》第三章', tag: '读书', updatedAt: '2026-10-14' },
  { id: 3, title: '周末去爬山', tag: '生活', updatedAt: '2026-10-12' },
]

// 全项目统一的断点判断来源（禁止用 UA 判断设备）
const { isMobile } = useBreakpoint()
</script>

<template>
  <div class="min-h-screen bg-gray-50">
    <header class="border-b border-gray-200 bg-white px-4 py-3 md:px-8">
      <h1 class="text-lg font-semibold text-brand-600">Simple Notes · 管理端</h1>
    </header>

    <main class="mx-auto max-w-5xl p-4 md:p-8">
      <div class="mb-4 flex items-center justify-between">
        <h2 class="text-xl font-bold">随手记列表</h2>
        <span class="text-sm text-gray-500">当前判定：{{ isMobile ? '移动端' : '桌面端' }}</span>
      </div>

      <!--
        展示层按断点分叉、数据与逻辑共享：
        桌面端用表格，移动端用卡片列表，路由和 store 只有一份。
      -->
      <el-table v-if="!isMobile" :data="notes" border stripe>
        <el-table-column prop="title" label="内容" min-width="240" />
        <el-table-column prop="tag" label="标签" width="120" />
        <el-table-column prop="updatedAt" label="更新时间" width="140" />
        <el-table-column label="操作" width="120">
          <template #default>
            <el-button link type="primary" size="small">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-else class="space-y-3">
        <el-card v-for="note in notes" :key="note.id" shadow="never">
          <div class="flex items-start justify-between gap-2">
            <p class="font-medium">{{ note.title }}</p>
            <el-tag size="small">{{ note.tag }}</el-tag>
          </div>
          <p class="mt-2 text-xs text-gray-500">{{ note.updatedAt }}</p>
        </el-card>
      </div>
    </main>
  </div>
</template>
