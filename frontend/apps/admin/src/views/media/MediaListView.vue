<script setup lang="ts">
import { CopyDocument, Delete, Document, Upload } from '@element-plus/icons-vue'
import type { AttachmentResponse, StorageStatusResponse } from '@simple-notes/api'
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
import { computed, onMounted, ref } from 'vue'

import { mediaApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, notifyError } from '@/utils/feedback'

const auth = useAuthStore()
const loading = ref(false)
const uploading = ref(false)
const savingStorage = ref(false)
const items = ref<AttachmentResponse[]>([])
const storageStatus = ref<StorageStatusResponse>()
const selectedMode = ref('')

const canSwitchStorage = computed(() => auth.can('admin:media:storage'))

function isImage(item: AttachmentResponse): boolean {
  return item.mimeType?.startsWith('image/') ?? false
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.ceil(bytes / 1024))} KB`
}

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

async function loadStorage(): Promise<void> {
  try {
    const status = await mediaApi.storage()
    storageStatus.value = status
    selectedMode.value = status.mode
  } catch (error) {
    notifyError(error, '存储状态加载失败')
  }
}

onMounted(() => {
  void load()
  if (auth.can('admin:media')) void loadStorage()
})

async function upload(options: UploadRequestOptions): Promise<void> {
  uploading.value = true
  try {
    await mediaApi.upload(options.file)
    ElMessage.success('文件已上传')
    await Promise.allSettled([load(), loadStorage()])
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
    ElMessage.success('文件 URL 已复制')
  } catch {
    ElMessage.warning(url)
  }
}

async function remove(item: AttachmentResponse): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除「${item.originalName}」吗？被封面引用的图片无法删除。`, '删除文件', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  try {
    await mediaApi.remove(item.id)
    ElMessage.success('文件已删除')
    await Promise.allSettled([load(), loadStorage()])
  } catch (error) {
    notifyError(error, '删除失败')
  }
}

async function saveStorage(): Promise<void> {
  if (!selectedMode.value || selectedMode.value === storageStatus.value?.mode) return
  try {
    await ElMessageBox.confirm(
      `切换后仅影响新上传文件；历史文件仍从原介质读取。确定切换到 ${selectedMode.value} 吗？`,
      '切换存储介质',
      { type: 'warning', confirmButtonText: '切换' },
    )
  } catch {
    return
  }
  savingStorage.value = true
  try {
    storageStatus.value = await mediaApi.switchStorage({ mode: selectedMode.value })
    ElMessage.success('存储介质已切换')
  } catch (error) {
    notifyError(error, '切换失败')
    selectedMode.value = storageStatus.value?.mode ?? ''
  } finally {
    savingStorage.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <el-card v-if="storageStatus" shadow="never" body-class="py-4">
      <div class="flex flex-wrap items-center gap-3">
        <div class="min-w-0">
          <el-text size="small" type="info">新上传介质</el-text>
          <div class="mt-1 flex flex-wrap items-center gap-2">
            <el-select v-model="selectedMode" size="small" class="w-36" :disabled="!canSwitchStorage">
              <el-option v-for="mode in storageStatus.availableModes" :key="mode" :label="mode" :value="mode" />
            </el-select>
            <el-tag v-for="(count, mode) in storageStatus.counts" :key="mode" size="small" effect="plain">
              {{ mode }} {{ count }}
            </el-tag>
          </div>
        </div>
        <div class="flex-1" />
        <el-button
          v-perm="'admin:media:storage'"
          type="primary"
          plain
          size="small"
          :loading="savingStorage"
          @click="saveStorage"
        >
          应用介质
        </el-button>
      </div>
      <div class="mt-2 flex flex-wrap gap-2">
        <el-tag
          v-for="(value, mode) in storageStatus.health"
          :key="mode"
          size="small"
          :type="value === 'OK' ? 'success' : 'warning'"
          effect="plain"
        >
          {{ mode }}: {{ value }}
        </el-tag>
      </div>
    </el-card>

    <el-card shadow="never" body-class="py-4">
      <div class="flex flex-wrap items-center gap-2">
        <el-text size="small" type="info">
          支持任意文件；图片 inline，其他文件强制下载。删除前会检查文章封面引用。
        </el-text>
        <div class="flex-1" />
        <el-upload
          v-perm="'admin:media:upload'"
          :show-file-list="false"
          :http-request="upload"
        >
          <el-button type="primary" :icon="Upload" :loading="uploading">上传文件</el-button>
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
            <img
              v-if="isImage(item)"
              :src="item.url"
              :alt="item.altText || item.originalName"
              class="max-h-full max-w-full object-contain"
            />
            <div v-else class="flex flex-col items-center gap-2 px-3 text-center">
              <el-icon :size="42"><Document /></el-icon>
              <span class="line-clamp-2 text-xs text-[var(--el-text-color-secondary)]">
                {{ item.mimeType || 'application/octet-stream' }}
              </span>
            </div>
          </div>
          <div class="space-y-2 p-3">
            <p class="truncate text-sm font-medium" :title="item.originalName">{{ item.originalName }}</p>
            <p class="text-xs text-[var(--el-text-color-secondary)]">
              {{ formatSize(item.sizeBytes) }} · {{ formatDateTime(item.createTime) }}
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
      <el-empty v-else-if="!loading" description="还没有上传文件" :image-size="80" class="py-8" />
    </el-card>
  </div>
</template>
