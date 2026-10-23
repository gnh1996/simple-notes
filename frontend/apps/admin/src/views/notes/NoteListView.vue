<script setup lang="ts">
import { Delete, Edit, FolderAdd, MoreFilled, Plus, Refresh, Search } from '@element-plus/icons-vue'
import type { NoteFolderResponse, NoteSummary } from '@simple-notes/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { noteApi, noteFolderApi } from '@/api'
import { formatDateTime, notifyError } from '@/utils/feedback'

const router = useRouter()
const loading = ref(false)
const rows = ref<NoteSummary[]>([])
const total = ref(0)
const folders = ref<NoteFolderResponse[]>([])

const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  folderId: '',
  pinnedOnly: false,
})

const folderMap = computed(() => new Map(folders.value.map((folder) => [folder.id, folder.name])))

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await noteApi.list({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      folderId: query.folderId || undefined,
      pinnedOnly: query.pinnedOnly || undefined,
    })
    rows.value = page.items ?? []
    total.value = page.total ?? 0
  } catch (error) {
    notifyError(error, '笔记列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadFolders(): Promise<void> {
  try {
    folders.value = (await noteFolderApi.list()) ?? []
  } catch (error) {
    notifyError(error, '文件夹加载失败')
  }
}

onMounted(async () => {
  await Promise.all([load(), loadFolders()])
})

function search(): void {
  query.page = 1
  void load()
}

function selectFolder(folderId?: string): void {
  query.folderId = folderId ?? ''
  search()
}

function reset(): void {
  query.keyword = ''
  query.folderId = ''
  query.pinnedOnly = false
  query.page = 1
  void load()
}

function createNote(): void {
  void router.push('/notes/new')
}

function editNote(row: NoteSummary): void {
  void router.push(`/notes/${row.id}`)
}

async function removeNote(row: NoteSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除笔记「${row.title}」吗？可在回收站恢复。`, '删除笔记', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  try {
    await noteApi.remove(row.id)
    ElMessage.success('笔记已移入回收站')
    await load()
  } catch (error) {
    notifyError(error, '删除失败')
  }
}

async function promote(row: NoteSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将「${row.title}」转为博客草稿后，它会从随手记中移出。转换不可逆，是否继续？`,
      '转为博客',
      { type: 'warning', confirmButtonText: '转为博客' },
    )
  } catch {
    return
  }
  try {
    const post = await noteApi.promote(row.id)
    ElMessage.success('已转为博客草稿，请补充摘要后再发布')
    await router.push(`/posts/${post.id}`)
  } catch (error) {
    notifyError(error, '转换失败')
  }
}

async function createFolder(): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('文件夹名称', '新建文件夹', {
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
      confirmButtonText: '创建',
    })
    await noteFolderApi.create({ name: value.trim() })
    ElMessage.success('文件夹已创建')
    await loadFolders()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') notifyError(error, '创建文件夹失败')
  }
}

async function renameFolder(folder: NoteFolderResponse): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('文件夹名称', '重命名文件夹', {
      inputValue: folder.name,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
      confirmButtonText: '保存',
    })
    await noteFolderApi.update(folder.id, { name: value.trim(), sort: folder.sort })
    ElMessage.success('文件夹已更新')
    await loadFolders()
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') notifyError(error, '更新文件夹失败')
  }
}

async function removeFolder(folder: NoteFolderResponse): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除文件夹「${folder.name}」？其中的笔记会移到未分类。`, '删除文件夹', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
    })
    await noteFolderApi.remove(folder.id)
    ElMessage.success('文件夹已删除')
    if (query.folderId === folder.id) query.folderId = ''
    await Promise.all([loadFolders(), load()])
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') notifyError(error, '删除文件夹失败')
  }
}

function folderName(row: NoteSummary): string {
  return row.folderName || (row.folderId ? folderMap.value.get(row.folderId) : '') || '未分类'
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
          placeholder="搜索标题或正文"
          clearable
          class="w-full sm:w-64"
          :prefix-icon="Search"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select v-model="query.folderId" placeholder="全部文件夹" clearable class="w-40" @change="search">
          <el-option v-for="folder in folders" :key="folder.id" :label="folder.name" :value="folder.id" />
        </el-select>
        <el-checkbox v-model="query.pinnedOnly" @change="search">仅置顶</el-checkbox>
        <el-button type="primary" :icon="Search" @click="search">搜索</el-button>
        <el-button @click="reset">重置</el-button>
        <div class="flex-1" />
        <el-button :icon="Refresh" circle title="刷新" @click="load" />
        <el-button v-perm="'admin:note:delete'" @click="router.push('/trash')">回收站</el-button>
        <el-button v-perm="'admin:note:create'" type="primary" :icon="Plus" @click="createNote">
          新建笔记
        </el-button>
      </div>
    </el-card>

    <div class="grid gap-4 lg:grid-cols-[220px_1fr]">
      <el-card shadow="never" class="h-fit">
        <template #header>
          <div class="flex items-center justify-between">
            <span>文件夹</span>
            <el-button
              v-perm="'admin:note:create'"
              link
              type="primary"
              :icon="FolderAdd"
              @click="createFolder"
            />
          </div>
        </template>
        <button
          class="mb-1 flex w-full items-center justify-between rounded-md px-3 py-2 text-left text-sm transition-colors"
          :class="query.folderId === '' ? 'bg-[var(--el-color-primary-light-9)] text-[var(--el-color-primary)]' : 'hover:bg-[var(--el-fill-color-light)]'"
          @click="selectFolder()"
        >
          <span>全部笔记</span>
          <span class="text-xs text-[var(--el-text-color-secondary)]">{{ total }}</span>
        </button>
        <div
          v-for="folder in folders"
          :key="folder.id"
          class="group mb-1 flex items-center gap-1 rounded-md px-1 transition-colors"
          :class="query.folderId === folder.id ? 'bg-[var(--el-color-primary-light-9)]' : 'hover:bg-[var(--el-fill-color-light)]'"
        >
          <button class="min-w-0 flex-1 truncate px-2 py-2 text-left text-sm" @click="selectFolder(folder.id)">
            {{ folder.name }}
          </button>
          <el-dropdown trigger="click">
            <el-button link :icon="MoreFilled" size="small" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-perm="'admin:note:update'" @click="renameFolder(folder)">重命名</el-dropdown-item>
                <el-dropdown-item v-perm="'admin:note:delete'" @click="removeFolder(folder)">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <el-empty v-if="folders.length === 0" description="暂无文件夹" :image-size="60" />
      </el-card>

      <el-card shadow="never">
        <el-table v-loading="loading" :data="rows" row-key="id">
          <el-table-column label="标题" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="flex items-center gap-2">
                <el-tag v-if="row.pinned" size="small" type="warning" effect="plain">置顶</el-tag>
                <el-link type="primary" :underline="false" @click="editNote(row as NoteSummary)">
                  {{ row.title }}
                </el-link>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="文件夹" width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ folderName(row as NoteSummary) }}</template>
          </el-table-column>
          <el-table-column label="标签" min-width="160">
            <template #default="{ row }">
              <div class="flex flex-wrap gap-1">
                <el-tag v-for="tag in row.tags" :key="tag.id" size="small" effect="plain">{{ tag.name }}</el-tag>
                <span v-if="!row.tags?.length" class="text-xs text-[var(--el-text-color-secondary)]">—</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.updateTime || row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="230" align="right" fixed="right">
            <template #default="{ row }">
              <el-button v-perm="'admin:note:update'" link type="primary" :icon="Edit" @click="editNote(row as NoteSummary)">
                编辑
              </el-button>
              <el-button v-perm="'admin:post:create'" link type="warning" @click="promote(row as NoteSummary)">
                转为博客
              </el-button>
              <el-button v-perm="'admin:note:delete'" link type="danger" :icon="Delete" @click="removeNote(row as NoteSummary)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && rows.length === 0" description="暂无笔记，先记一条吧" :image-size="80" class="py-4" />
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
  </div>
</template>
