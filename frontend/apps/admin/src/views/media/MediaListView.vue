<script setup lang="ts">
import { CopyDocument, Delete, Upload } from '@element-plus/icons-vue'
import type { AttachmentResponse } from '@simple-notes/api'
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
import { onMounted, ref } from 'vue'

import { mediaApi } from '@/api'
import { formatDateTime, notifyError } from '@/utils/feedback'

const loading = ref(false)
const uploading = ref(false)
const items = ref<AttachmentResponse[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    items.value = (await mediaApi.list()) ?? []
  } catch (error) {
    notifyError(error, '媒体库加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function upload(options: UploadRequestOptions): Promise<void> {
  uploading.value = true
  try {
    await mediaApi.upload(options.file)
    ElMessage.success('图片已上传')
    await load()
  } catch (error) {
    notifyError(error, '上传失败')
  } finally {
    uploading.value = false
  }
}

async function copy(item: AttachmentResponse): Promise<void> {
  const url = new URL(item.url, window.location.origin).href
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('图片 URL 已复制')
  } catch {
    ElMessage.warning(url)
  }
}

async function remove(item: AttachmentResponse): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除「${item.originalName}」吗？被封面引用的图片无法删除。`, '删除图片', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  try {
    await mediaApi.remove(item.id)
    ElMessage.success('图片已删除')
    await load()
  } catch (error) {
    notifyError(error, '删除失败')
  }
}
</script>

<template>
  <div class="space-y-4">
    <el-card shadow="never" body-class="py-4">
      <div class="flex flex-wrap items-center gap-2">
        <el-text size="small" type="info">
          正文使用稳定站内 URL；删除前会检查文章封面引用
        </el-text>
        <div class="flex-1" />
        <el-upload
          v-perm="'admin:media:upload'"
          :show-file-list="false"
          accept="image/png,image/jpeg,image/gif,image/webp,image/avif"
          :http-request="upload"
        >
          <el-button type="primary" :icon="Upload" :loading="uploading">上传图片</el-button>
        </el-upload>
      </div>
    </el-card>

    <el-card v-loading="loading" shadow="never">
      <div v-if="items.length" class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        <div
          v-for="item in items"
          :key="item.id"
          class="overflow-hidden rounded-lg border border-[var(--el-border-color-lighter)]"
        >
          <div class="flex h-40 items-center justify-center bg-[var(--el-fill-color-lighter)]">
            <img :src="item.url" :alt="item.altText || item.originalName" class="max-h-full max-w-full object-contain" />
          </div>
          <div class="space-y-2 p-3">
            <p class="truncate text-sm font-medium" :title="item.originalName">{{ item.originalName }}</p>
            <p class="text-xs text-[var(--el-text-color-secondary)]">
              {{ Math.ceil(item.sizeBytes / 1024) }} KB · {{ formatDateTime(item.createTime) }}
            </p>
            <div class="flex items-center justify-end gap-1">
              <el-button link type="primary" :icon="CopyDocument" @click="copy(item)">复制 URL</el-button>
              <el-button
                v-perm="'admin:media:delete'"
                link
                type="danger"
                :icon="Delete"
                @click="remove(item)"
              >
                删除
              </el-button>
            </div>
          </div>
        </div>
      </div>
      <el-empty v-else-if="!loading" description="还没有上传图片" :image-size="80" class="py-8" />
    </el-card>
  </div>
</template>
