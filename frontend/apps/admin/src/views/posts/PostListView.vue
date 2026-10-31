<script setup lang="ts">
import { Delete, Edit, Link, Plus, Refresh, Search } from '@element-plus/icons-vue'
import type { PostSummary } from '@simple-notes/api'
import { PUBLISH_STATUS_LABELS } from '@simple-notes/shared'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { postApi } from '@/api'
import { publicPostUrl } from '@/utils/content'
import { formatDateTime, notifyError } from '@/utils/feedback'

type TabKey = 'ALL' | 'DRAFT' | 'PUBLISHED' | 'CHANGES' | 'UNPUBLISHED'

const router = useRouter()
const loading = ref(false)
const rows = ref<PostSummary[]>([])
const total = ref(0)
const activeTab = ref<TabKey>('ALL')

const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
})

const tabs: Array<{ key: TabKey; label: string }> = [
  { key: 'ALL', label: '全部' },
  { key: 'DRAFT', label: PUBLISH_STATUS_LABELS.DRAFT },
  { key: 'PUBLISHED', label: PUBLISH_STATUS_LABELS.PUBLISHED },
  { key: 'CHANGES', label: '有未发布更改' },
  { key: 'UNPUBLISHED', label: PUBLISH_STATUS_LABELS.UNPUBLISHED },
]

const statusMeta = {
  DRAFT: { label: PUBLISH_STATUS_LABELS.DRAFT, type: 'info' as const },
  PUBLISHED: { label: PUBLISH_STATUS_LABELS.PUBLISHED, type: 'success' as const },
  UNPUBLISHED: { label: PUBLISH_STATUS_LABELS.UNPUBLISHED, type: 'warning' as const },
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const status =
      activeTab.value === 'DRAFT' || activeTab.value === 'PUBLISHED' || activeTab.value === 'UNPUBLISHED'
        ? activeTab.value
        : undefined
    const page = await postApi.list({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      status,
      hasUnpublishedChanges: activeTab.value === 'CHANGES' ? true : undefined,
    })
    rows.value = page.items ?? []
    total.value = page.total ?? 0
  } catch (error) {
    notifyError(error, '文章列表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function search(): void {
  query.page = 1
  void load()
}

function switchTab(tab: TabKey): void {
  activeTab.value = tab
  query.page = 1
  void load()
}

function onTabChange(tab: string | number): void {
  switchTab(tab as TabKey)
}

function createPost(): void {
  void router.push('/posts/new')
}

function editPost(row: PostSummary): void {
  void router.push(`/posts/${row.id}`)
}

async function publish(row: PostSummary): Promise<void> {
  try {
    await postApi.publish(row.id, row.version)
    ElMessage.success(row.publishStatus === 'PUBLISHED' ? '已更新发布' : '文章已发布')
    await load()
  } catch (error) {
    notifyError(error, '发布失败')
  }
}

async function unpublish(row: PostSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定下架「${row.title}」吗？下架后公开博客将不可见。`, '下架文章', {
      type: 'warning',
      confirmButtonText: '下架',
    })
  } catch {
    return
  }
  try {
    await postApi.unpublish(row.id)
    ElMessage.success('文章已下架')
    await load()
  } catch (error) {
    notifyError(error, '下架失败')
  }
}

async function removePost(row: PostSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.title}」吗？${row.publishStatus === 'PUBLISHED' ? '已发布文章会先自动下架。' : ''}可在回收站恢复。`,
      '删除文章',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' },
    )
  } catch {
    return
  }
  try {
    await postApi.remove(row.id)
    ElMessage.success('文章已移入回收站')
    await load()
  } catch (error) {
    notifyError(error, '删除失败')
  }
}

function publishLabel(row: PostSummary): string {
  if (row.publishStatus === 'DRAFT') return '发布'
  if (row.publishStatus === 'UNPUBLISHED') return '重新发布'
  return row.hasUnpublishedChanges ? '更新发布' : '已发布'
}

function publicLink(row: PostSummary): string | null {
  return row.publishStatus === 'PUBLISHED' ? publicPostUrl(row.slug) : null
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
          placeholder="搜索标题 / 摘要 / 正文"
          clearable
          class="w-full sm:w-64"
          :prefix-icon="Search"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" :icon="Search" @click="search">搜索</el-button>
        <div class="flex-1" />
        <el-button :icon="Refresh" circle title="刷新" @click="load" />
        <el-button v-perm="'admin:post:delete'" @click="router.push('/trash')">回收站</el-button>
        <el-button v-perm="'admin:post:create'" type="primary" :icon="Plus" @click="createPost">
          新建文章
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never" body-class="pt-0">
      <el-tabs :model-value="activeTab" class="app-content-tabs" @tab-change="onTabChange">
        <el-tab-pane v-for="tab in tabs" :key="tab.key" :label="tab.label" :name="tab.key" />
      </el-tabs>

      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column label="标题" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="flex flex-wrap items-center gap-2">
              <el-tag v-if="row.pinned" size="small" type="warning" effect="plain">置顶</el-tag>
              <el-link type="primary" :underline="false" @click="editPost(row as PostSummary)">
                {{ row.title }}
              </el-link>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="160">
          <template #default="{ row }">
            <div class="flex flex-wrap items-center gap-1">
              <el-tag :type="statusMeta[row.publishStatus as keyof typeof statusMeta].type" size="small" effect="light">
                {{ statusMeta[row.publishStatus as keyof typeof statusMeta].label }}
              </el-tag>
              <el-tag v-if="row.hasUnpublishedChanges && row.publishStatus === 'PUBLISHED'" type="warning" size="small" effect="plain">
                有未发布更改
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.summary || '—' }}</template>
        </el-table-column>
        <el-table-column label="标签" min-width="150">
          <template #default="{ row }">
            <div class="flex flex-wrap gap-1">
              <el-tag v-for="tag in row.tags" :key="tag.id" size="small" effect="plain">{{ tag.name }}</el-tag>
              <span v-if="!row.tags?.length" class="text-xs text-[var(--el-text-color-secondary)]">—</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.firstPublishedAt) }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updateTime || row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" align="right" fixed="right">
          <template #default="{ row }">
            <el-button v-perm="'admin:post:update'" link type="primary" :icon="Edit" @click="editPost(row as PostSummary)">
              编辑
            </el-button>
            <el-button
              v-perm="'admin:post:publish'"
              link
              type="success"
              :disabled="row.publishStatus === 'PUBLISHED' && !row.hasUnpublishedChanges"
              @click="publish(row as PostSummary)"
            >
              {{ publishLabel(row as PostSummary) }}
            </el-button>
            <el-button
              v-if="row.publishStatus === 'PUBLISHED'"
              v-perm="'admin:post:unpublish'"
              link
              type="warning"
              @click="unpublish(row as PostSummary)"
            >
              下架
            </el-button>
            <el-link
              v-if="publicLink(row as PostSummary)"
              :href="publicLink(row as PostSummary) || undefined"
              target="_blank"
              :underline="false"
              class="mx-2 align-middle text-sm"
            >
              <el-icon><Link /></el-icon>
            </el-link>
            <el-button v-perm="'admin:post:delete'" link type="danger" :icon="Delete" @click="removePost(row as PostSummary)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && rows.length === 0" description="暂无文章" :image-size="80" class="py-4" />
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
  </div>
</template>
