<script setup lang="ts">
import { Delete, Edit, Plus, Refresh, Search, Setting } from '@element-plus/icons-vue'
import type { TagAdminSummary } from '@simple-notes/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { tagApi } from '@/api'
import { formatDateTime, notifyError } from '@/utils/feedback'

import TagContentsDialog from './TagContentsDialog.vue'

const loading = ref(false)
const rows = ref<TagAdminSummary[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, keyword: '' })

const contentsVisible = ref(false)
const selectedTag = ref<TagAdminSummary | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await tagApi.list({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
    })
    rows.value = page.items ?? []
    total.value = page.total ?? 0
    if (selectedTag.value) {
      const current = rows.value.find((row) => row.id === selectedTag.value?.id)
      if (current) selectedTag.value = current
    }
  } catch (error) {
    notifyError(error, '标签列表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function search(): void {
  query.page = 1
  void load()
}

function usageText(tag: TagAdminSummary): string {
  return `${tag.noteCount} 篇随手记、${tag.postCount} 篇博客（其中 ${tag.publishedCount} 篇已发布）`
}

function hasUsage(tag: TagAdminSummary): boolean {
  return tag.noteCount + tag.postCount + tag.publishedCount > 0
}

async function createTag(): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('标签名称', '新建标签', {
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
      confirmButtonText: '创建',
    })
    await tagApi.create({ name: value.trim() })
    ElMessage.success('标签已创建')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') notifyError(error, '创建标签失败')
  }
}

async function renameTag(tag: TagAdminSummary): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('标签名称', '重命名标签', {
      inputValue: tag.name,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
      confirmButtonText: '保存',
    })
    const next = value.trim()
    if (next === tag.name) return
    if (hasUsage(tag)) {
      await ElMessageBox.confirm(
        `该标签已关联 ${usageText(tag)}。改名会立即影响所有当前内容和公开博客上的标签展示，是否继续？`,
        '确认改名',
        { type: 'warning', confirmButtonText: '继续改名' },
      )
    }
    await tagApi.rename(tag.id, { name: next })
    ElMessage.success('标签已改名')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') notifyError(error, '改名失败')
  }
}

async function removeTag(tag: TagAdminSummary): Promise<void> {
  const force = hasUsage(tag)
  const message = force
    ? `标签「${tag.name}」仍关联 ${usageText(tag)}。删除后当前内容会移除该标签，公开博客上的该标签也会立即消失；文章正文不会被删除。是否继续？`
    : `确定删除标签「${tag.name}」吗？`
  try {
    await ElMessageBox.confirm(message, '删除标签', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  try {
    await tagApi.remove(tag.id, force)
    ElMessage.success('标签已删除')
    await load()
  } catch (error) {
    notifyError(error, '删除失败')
  }
}

function manageContents(tag: TagAdminSummary): void {
  selectedTag.value = tag
  contentsVisible.value = true
}

function handlePageChange(page: number): void {
  query.page = page
  void load()
}
</script>

<template>
  <div class="space-y-4">
    <el-card shadow="never" body-class="py-4">
      <div class="flex flex-wrap items-center gap-2">
        <el-input
          v-model="query.keyword"
          placeholder="搜索标签"
          clearable
          class="w-full sm:w-64"
          :prefix-icon="Search"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" :icon="Search" @click="search">搜索</el-button>
        <div class="flex-1" />
        <el-button :icon="Refresh" circle title="刷新" @click="load" />
        <el-button v-perm="'admin:tag:create'" type="primary" :icon="Plus" @click="createTag">
          新建标签
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column label="标签" min-width="220">
          <template #default="{ row }">
            <span class="font-medium">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="随手记" width="100" align="center">
          <template #default="{ row }">{{ row.noteCount }}</template>
        </el-table-column>
        <el-table-column label="博客草稿" width="110" align="center">
          <template #default="{ row }">{{ row.postCount }}</template>
        </el-table-column>
        <el-table-column label="已发布快照" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="row.publishedCount > 0 ? 'success' : 'info'" size="small" effect="plain">
              {{ row.publishedCount }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.updateTime || row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="280" align="right" fixed="right">
          <template #default="{ row }">
            <el-button
              v-perm="'admin:tag:update'"
              link
              type="primary"
              :icon="Edit"
              @click="renameTag(row as TagAdminSummary)"
            >
              改名
            </el-button>
            <el-button
              v-perm="'admin:tag'"
              link
              type="primary"
              :icon="Setting"
              @click="manageContents(row as TagAdminSummary)"
            >
              管理内容
            </el-button>
            <el-button
              v-perm="'admin:tag:delete'"
              link
              type="danger"
              :icon="Delete"
              @click="removeTag(row as TagAdminSummary)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && rows.length === 0" description="暂无标签" :image-size="80" class="py-4" />
      <div class="mt-4 flex justify-end">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <TagContentsDialog
      v-if="selectedTag"
      v-model="contentsVisible"
      :tag="selectedTag"
      @changed="load"
    />
  </div>
</template>
