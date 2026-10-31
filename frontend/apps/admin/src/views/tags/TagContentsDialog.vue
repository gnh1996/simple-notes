<script setup lang="ts">
import { Plus, Refresh } from '@element-plus/icons-vue'
import type {
  TagAdminSummary,
  TagContentCandidate,
  TagContentResponse,
} from '@simple-notes/api'
import { NOTE_KIND_LABELS, noteKindLabel, publishStatusLabel, type NoteKindValue } from '@simple-notes/shared'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, ref, watch } from 'vue'

import { tagApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, notifyError } from '@/utils/feedback'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ tag: TagAdminSummary }>()
const emit = defineEmits<{ changed: [] }>()

type Kind = NoteKindValue

const auth = useAuthStore()
const canAssign = computed(() => auth.can('admin:tag:assign'))
const activeTab = ref<Kind>('NOTE')
const loading = ref(false)
const candidateLoading = ref(false)
const contents = ref<TagContentResponse[]>([])
const candidates = ref<TagContentCandidate[]>([])
const selectedIds = ref<string[]>([])
const keyword = ref('')

const kindLabel = computed(() => NOTE_KIND_LABELS[activeTab.value])

async function loadContents(): Promise<void> {
  loading.value = true
  try {
    const page = await tagApi.contents(props.tag.id, {
      kind: activeTab.value,
      page: 1,
      size: 100,
      keyword: keyword.value || undefined,
    })
    contents.value = page.items ?? []
  } catch (error) {
    notifyError(error, '关联内容加载失败')
  } finally {
    loading.value = false
  }
}

async function loadCandidates(search = ''): Promise<void> {
  candidateLoading.value = true
  try {
    const page = await tagApi.candidates(props.tag.id, {
      kind: activeTab.value,
      page: 1,
      size: 50,
      keyword: search || undefined,
    })
    candidates.value = page.items ?? []
  } catch (error) {
    notifyError(error, '候选内容加载失败')
  } finally {
    candidateLoading.value = false
  }
}

async function loadAll(): Promise<void> {
  const tasks: Array<Promise<void>> = [loadContents()]
  if (canAssign.value) {
    tasks.push(loadCandidates(keyword.value))
  }
  await Promise.all(tasks)
}

watch(
  visible,
  (open) => {
    if (!open) return
    activeTab.value = 'NOTE'
    keyword.value = ''
    selectedIds.value = []
    void loadAll()
  },
  { immediate: true },
)

watch(activeTab, () => {
  keyword.value = ''
  selectedIds.value = []
  void loadAll()
})

function searchCandidates(value: string): void {
  keyword.value = value
  void loadCandidates(value)
}

async function addSelected(): Promise<void> {
  if (selectedIds.value.length === 0) {
    ElMessage.warning('请先选择要添加的内容')
    return
  }
  try {
    await tagApi.assign(props.tag.id, {
      kind: activeTab.value,
      contentIds: selectedIds.value,
    })
    ElMessage.success(
      activeTab.value === 'POST'
        ? '标签已写入当前草稿；已发布文章需更新发布后线上才生效'
        : '标签已添加到随手记',
    )
    selectedIds.value = []
    await loadAll()
    emit('changed')
  } catch (error) {
    notifyError(error, '分配标签失败')
  }
}

async function removeContent(content: TagContentResponse): Promise<void> {
  const message = content.publishedTagged
    ? `已从当前草稿移除；线上快照仍使用该标签，需在文章编辑页更新发布后线上才会消失。是否继续？`
    : `确定从「${content.title}」移除标签「${props.tag.name}」吗？`
  try {
    await ElMessageBox.confirm(message, '移除标签', {
      type: 'warning',
      confirmButtonText: '移除',
    })
  } catch {
    return
  }
  try {
    await tagApi.removeContent(props.tag.id, content.id)
    ElMessage.success('已从当前草稿移除')
    await loadAll()
    emit('changed')
  } catch (error) {
    notifyError(error, '移除失败')
  }
}

function statusText(content: TagContentResponse): string {
  return publishStatusLabel(content.publishStatus)
}

function candidateLabel(candidate: TagContentCandidate): string {
  return `${candidate.title}（${noteKindLabel(candidate.kind)}）`
}

function reload(): void {
  void loadAll()
}
</script>

<template>
  <el-dialog v-model="visible" :title="`管理内容 · ${tag.name}`" width="860px" :close-on-click-modal="false">
    <div class="mb-4 flex flex-wrap items-center gap-3 text-sm text-[var(--el-text-color-secondary)]">
      <span>随手记 {{ tag.noteCount }}</span>
      <span>博客草稿 {{ tag.postCount }}</span>
      <span>已发布快照 {{ tag.publishedCount }}</span>
      <el-button link :icon="Refresh" @click="reload">刷新</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane :label="NOTE_KIND_LABELS.NOTE" name="NOTE" />
      <el-tab-pane :label="NOTE_KIND_LABELS.POST" name="POST" />
    </el-tabs>

    <el-card v-if="canAssign" shadow="never" body-class="py-3">
      <div class="flex flex-wrap items-center gap-2">
        <el-select
          v-model="selectedIds"
          multiple
          filterable
          remote
          reserve-keyword
          :remote-method="searchCandidates"
          :loading="candidateLoading"
          placeholder="搜索并选择要添加的内容"
          class="min-w-60 flex-1"
        >
          <el-option
            v-for="candidate in candidates"
            :key="candidate.id"
            :label="candidateLabel(candidate)"
            :value="candidate.id"
          />
        </el-select>
        <el-button v-perm="'admin:tag:assign'" type="primary" :icon="Plus" @click="addSelected">
          添加所选
        </el-button>
      </div>
      <el-text size="small" type="info" class="mt-2 block">
        分配只写入当前草稿；已发布文章需在文章编辑页点击「更新发布」后，线上标签才会变化。
      </el-text>
    </el-card>

    <el-table v-loading="loading" :data="contents" row-key="id" class="mt-4">
      <el-table-column label="标题" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.title }}</template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ noteKindLabel(row.kind) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <span
            v-if="row.kind === 'NOTE'"
            class="text-xs text-[var(--el-text-color-secondary)]"
          >—</span>
          <el-tag
            v-else
            :type="row.publishStatus === 'PUBLISHED' ? 'success' : 'info'"
            size="small"
            effect="plain"
          >
            {{ statusText(row as TagContentResponse) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标签状态" width="210">
        <template #default="{ row }">
          <div class="flex flex-wrap gap-1">
            <el-tag v-if="row.draftTagged" size="small" effect="plain">草稿</el-tag>
            <el-tag v-if="row.publishedTagged" size="small" type="success" effect="plain">线上快照</el-tag>
            <el-tag v-if="row.hasUnpublishedChanges && row.kind === 'POST'" size="small" type="warning" effect="plain">
              待更新发布
            </el-tag>
            <span v-if="!row.draftTagged && !row.publishedTagged" class="text-xs">—</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="right">
        <template #default="{ row }">
          <el-button
            v-if="row.draftTagged"
            v-perm="'admin:tag:assign'"
            link
            type="danger"
            @click="removeContent(row as TagContentResponse)"
          >
            从草稿移除
          </el-button>
          <span v-else class="text-xs text-[var(--el-text-color-secondary)]">仅线上</span>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && contents.length === 0" :description="`暂无关联的${kindLabel}`" :image-size="70" />

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>
