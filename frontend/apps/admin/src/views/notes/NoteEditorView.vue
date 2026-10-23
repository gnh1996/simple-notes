<script setup lang="ts">
import { ArrowLeft, Check, Promotion } from '@element-plus/icons-vue'
import type { NoteDetail, NoteFolderResponse } from '@simple-notes/api'
import { markdownCommonProps, markdownToolbars } from '@simple-notes/shared'
import { MdEditor, type UploadImgEvent } from 'md-editor-v3'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { mediaApi, noteApi, noteFolderApi } from '@/api'
import { useTheme } from '@/composables/useTheme'
import { tagNames } from '@/utils/content'
import { formatDateTime, notifyError } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const { isDark } = useTheme()
const editorTheme = computed(() => (isDark.value ? 'dark' : 'light'))
const noteId = computed(() => String(route.params.id ?? 'new'))
const isNew = computed(() => noteId.value === 'new')

const loading = ref(false)
const saving = ref(false)
const loaded = ref(false)
const dirty = ref(false)
const lastSavedAt = ref<string | null>(null)
const folders = ref<NoteFolderResponse[]>([])
let autoSaveTimer: ReturnType<typeof setTimeout> | undefined
let suppressWatch = false

const form = reactive({
  id: '',
  title: '',
  content: '',
  folderId: '',
  tags: [] as string[],
  pinned: false,
  status: 'ACTIVE' as 'ACTIVE' | 'ARCHIVED',
  version: 0,
})

const statusText = computed(() => {
  if (saving.value) return '保存中…'
  if (dirty.value) return '未保存'
  return lastSavedAt.value ? `已保存 ${lastSavedAt.value}` : '已保存'
})

async function load(): Promise<void> {
  loading.value = true
  try {
    folders.value = (await noteFolderApi.list()) ?? []
    if (isNew.value) {
      resetForm()
      loaded.value = true
      return
    }
    const note = await noteApi.get(noteId.value)
    applyNote(note)
    loaded.value = true
  } catch (error) {
    notifyError(error, '笔记加载失败')
    await router.replace('/notes')
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  form.id = ''
  form.title = ''
  form.content = ''
  form.folderId = ''
  form.tags = []
  form.pinned = false
  form.status = 'ACTIVE'
  form.version = 0
  dirty.value = false
  lastSavedAt.value = null
}

function applyNote(note: NoteDetail): void {
  suppressWatch = true
  form.id = note.id
  form.title = note.title
  form.content = note.content
  form.folderId = note.folderId ?? ''
  form.tags = tagNames(note.tags)
  form.pinned = note.pinned
  form.status = note.status
  form.version = note.version
  lastSavedAt.value = formatDateTime(note.updateTime || note.createTime)
  dirty.value = false
  void nextTick(() => {
    suppressWatch = false
  })
}

function scheduleAutoSave(): void {
  if (suppressWatch || !loaded.value || isNew.value) return
  dirty.value = true
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
  autoSaveTimer = setTimeout(() => {
    void save(true)
  }, 1500)
}

watch(
  () => ({
    title: form.title,
    content: form.content,
    folderId: form.folderId,
    tags: [...form.tags],
    pinned: form.pinned,
    status: form.status,
  }),
  scheduleAutoSave,
)

async function save(silent = false): Promise<NoteDetail | null> {
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
  if (saving.value) return null
  if (!form.title.trim()) {
    if (!silent) ElMessage.warning('请填写标题')
    return null
  }
  if (!form.content.trim()) {
    if (!silent) ElMessage.warning('请填写正文')
    return null
  }

  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      content: form.content,
      folderId: form.folderId || undefined,
      tags: form.tags,
      pinned: form.pinned,
      status: form.status,
      version: form.version,
    }
    const note = isNew.value
      ? await noteApi.create({
          title: payload.title,
          content: payload.content,
          folderId: payload.folderId,
          tags: payload.tags,
          pinned: payload.pinned,
        })
      : await noteApi.update(noteId.value, payload)

    applyNote(note)
    if (isNew.value) {
      await router.replace(`/notes/${note.id}`)
    }
    if (!silent) ElMessage.success('已保存')
    return note
  } catch (error) {
    notifyError(error, silent ? '自动保存失败' : '保存失败')
    return null
  } finally {
    saving.value = false
  }
}

async function promote(): Promise<void> {
  if (isNew.value) {
    ElMessage.warning('请先保存笔记')
    return
  }
  if (dirty.value) {
    const saved = await save(true)
    if (!saved) return
  }
  try {
    await ElMessageBox.confirm(
      '转为博客草稿后，这条内容会从随手记中移出；转换不可逆。',
      '转为博客',
      { type: 'warning', confirmButtonText: '转为博客' },
    )
  } catch {
    return
  }
  try {
    const post = await noteApi.promote(noteId.value)
    ElMessage.success('已转为博客草稿，请补充摘要后发布')
    await router.push(`/posts/${post.id}`)
  } catch (error) {
    notifyError(error, '转换失败')
  }
}

function setArchived(value: string | number | boolean): void {
  form.status = value ? 'ARCHIVED' : 'ACTIVE'
}

const uploadImages: UploadImgEvent = async (files, callback) => {
  try {
    const uploaded = await Promise.all(files.map((file) => mediaApi.upload(file)))
    callback(uploaded.map((item) => item.url))
  } catch (error) {
    notifyError(error, '图片上传失败')
  }
}

onMounted(load)

onBeforeUnmount(() => {
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
  if (dirty.value && !isNew.value) {
    void save(true)
  }
})
</script>

<template>
  <div v-loading="loading" class="space-y-4">
    <el-card shadow="never" body-class="py-3">
      <div class="flex flex-wrap items-center gap-2">
        <el-button :icon="ArrowLeft" circle title="返回" @click="router.push('/notes')" />
        <el-input
          v-model="form.title"
          placeholder="笔记标题"
          class="min-w-40 flex-1 text-base"
          @keyup.enter="save()"
        />
        <el-tag :type="dirty ? 'warning' : 'success'" size="small" effect="plain">
          {{ statusText }}
        </el-tag>
        <el-button :icon="Check" :loading="saving" @click="save()">保存</el-button>
        <el-button
          v-perm="'admin:post:create'"
          type="warning"
          :icon="Promotion"
          :disabled="isNew"
          @click="promote"
        >
          转为博客
        </el-button>
      </div>
    </el-card>

    <div class="grid gap-4 xl:grid-cols-[1fr_280px]">
      <el-card shadow="never" body-class="p-0">
        <MdEditor
          v-model="form.content"
          v-bind="markdownCommonProps"
          :theme="editorTheme"
          :toolbars="markdownToolbars"
          :on-upload-img="uploadImages"
          :style="{ height: 'calc(100vh - 280px)', minHeight: '420px' }"
          placeholder="开始记录…"
        />
      </el-card>

      <el-card shadow="never" header="笔记信息">
        <el-form label-position="top">
          <el-form-item label="文件夹">
            <el-select v-model="form.folderId" placeholder="未分类" clearable class="w-full">
              <el-option v-for="folder in folders" :key="folder.id" :label="folder.name" :value="folder.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="标签">
            <el-select
              v-model="form.tags"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="输入后回车创建"
              class="w-full"
            >
              <el-option v-for="tag in form.tags" :key="tag" :label="tag" :value="tag" />
            </el-select>
          </el-form-item>
          <el-form-item label="置顶">
            <el-switch v-model="form.pinned" />
          </el-form-item>
          <el-form-item label="归档">
            <el-switch
              :model-value="form.status === 'ARCHIVED'"
              active-text="已归档"
              inactive-text="使用中"
              @update:model-value="setArchived"
            />
          </el-form-item>
          <el-form-item label="更新时间">
            <span class="text-sm text-[var(--el-text-color-secondary)]">{{ lastSavedAt || '—' }}</span>
          </el-form-item>
        </el-form>
      </el-card>
    </div>
  </div>
</template>
