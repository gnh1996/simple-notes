<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import type { TrashItemResponse } from '@simple-notes/api'
import { noteKindLabel, publishStatusLabel } from '@simple-notes/shared'
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'

import { trashApi } from '@/api'
import { formatDateTime, notifyError } from '@/utils/feedback'

const activeTab = ref<'notes' | 'posts'>('notes')
const loading = ref(false)
const rows = ref<TrashItemResponse[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = (activeTab.value === 'notes' ? await trashApi.notes() : await trashApi.posts()) ?? []
  } catch (error) {
    notifyError(error, '回收站加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function switchTab(tab: string | number): void {
  activeTab.value = tab as 'notes' | 'posts'
  void load()
}

async function restore(row: TrashItemResponse): Promise<void> {
  try {
    if (activeTab.value === 'notes') {
      await trashApi.restoreNote(row.id)
    } else {
      await trashApi.restorePost(row.id)
    }
    ElMessage.success('已恢复')
    await load()
  } catch (error) {
    notifyError(error, '恢复失败')
  }
}
</script>

<template>
  <div class="space-y-4">
    <el-card shadow="never" body-class="py-4">
      <div class="flex items-center gap-2">
        <el-text size="small" type="info">
          回收站只做恢复；永久删除与清理策略在后续版本实现
        </el-text>
        <div class="flex-1" />
        <el-button :icon="Refresh" circle title="刷新" @click="load" />
      </div>
    </el-card>

    <el-card shadow="never" body-class="pt-0">
      <el-tabs :model-value="activeTab" @tab-change="switchTab">
        <el-tab-pane label="随手记" name="notes" />
        <el-tab-pane label="博客文章" name="posts" />
      </el-tabs>

      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="title" label="标题" min-width="260" show-overflow-tooltip />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ noteKindLabel(row.kind) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <span v-if="row.kind === 'NOTE'">—</span>
            <el-tag v-else size="small" type="info" effect="plain">{{ publishStatusLabel(row.publishStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="删除时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.deletedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="restore(row as TrashItemResponse)">恢复</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && rows.length === 0" description="回收站是空的" :image-size="80" class="py-4" />
    </el-card>
  </div>
</template>
