<script setup lang="ts">
import { ArrowLeft, Check, Link, Promotion, Upload } from '@element-plus/icons-vue'
import type { PostDetail } from '@simple-notes/api'
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { mediaApi, postApi, tagApi } from '@/api'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import { useTheme } from '@/composables/useTheme'
import { publicPostUrl, summarizeMarkdown, tagNames } from '@/utils/content'
import { formatDateTime, notifyError } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const { isDark } = useTheme()
const editorTheme = computed(() => (isDark.value ? 'dark' : 'light'))
const postId = computed(() => String(route.params.id ?? 'new'))
const isNew = computed(() => postId.value === 'new')

const loading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const loaded = ref(false)
const dirty = ref(false)
const lastSavedAt = ref<string | null>(null)
const tagOptions = ref<string[]>([])
let autoSaveTimer: ReturnType<typeof setTimeout> | undefined
let suppressWatch = false

const form = reactive({
  id: '',
  title: '',
  content: '',
  summary: '',
  slug: '',
  coverAttachmentId: '',
  tags: [] as string[],
  pinned: false,
  publishStatus: 'DRAFT' as 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED',
  publishedRevisionId: '',
  hasUnpublishedChanges: false,
  publicUrl: '',
  firstPublishedAt: null as string | null,
  lastPublishedAt: null as string | null,
  version: 0,
})

const isPublished = computed(() => form.publishStatus === 'PUBLISHED')
const isUnpublished = computed(() => form.publishStatus === 'UNPUBLISHED')
const slugLocked = computed(() => Boolean(form.publishedRevisionId))
const coverUrl = computed(() =>
  form.coverAttachmentId ? `/api/media/${form.coverAttachmentId}` : '',
)
const publicLink = computed(() => (isPublished.value ? publicPostUrl(form.slug) : null))
const tagOptionNames = computed(() =>
  Array.from(new Set([...tagOptions.value, ...form.tags])),
)

const statusLabel = computed(() => {
  if (form.publishStatus === 'DRAFT') return '草稿'
  if (form.publishStatus === 'UNPUBLISHED') return '已下架'
  return form.hasUnpublishedChanges ? '已发布 · 有未发布更改' : '已发布'
})
const statusType = computed(() => {
  if (form.publishStatus === 'DRAFT') return 'info' as const
  if (form.publishStatus === 'UNPUBLISHED') return 'warning' as const
  return form.hasUnpublishedChanges ? 'warning' as const : 'success' as const
})
const publishLabel = computed(() => {
  if (form.publishStatus === 'DRAFT') return '发布'
  if (form.publishStatus === 'UNPUBLISHED') return '重新发布'
  return form.hasUnpublishedChanges ? '更新发布' : '已发布'
})
const publishDisabled = computed(
  () => isPublished.value && !form.hasUnpublishedChanges,
)
const savedText = computed(() => {
  if (saving.value) return '保存中…'
  if (dirty.value) return '未保存'
  return lastSavedAt.value ? `已保存 ${lastSavedAt.value}` : '已保存'
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const tagRows = await tagApi.options()
    tagOptions.value = (tagRows ?? []).map((tag) => tag.name)
    if (isNew.value) {
      resetForm()
      loaded.value = true
      return
    }
    applyPost(await postApi.get(postId.value))
    loaded.value = true
  } catch (error) {
    notifyError(error, '文章加载失败')
    await router.replace('/posts')
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  form.id = ''
  form.title = ''
  form.content = ''
  form.summary = ''
  form.slug = ''
  form.coverAttachmentId = ''
  form.tags = []
  form.pinned = false
  form.publishStatus = 'DRAFT'
  form.publishedRevisionId = ''
  form.hasUnpublishedChanges = false
  form.publicUrl = ''
  form.firstPublishedAt = null
  form.lastPublishedAt = null
  form.version = 0
  dirty.value = false
  lastSavedAt.value = null
}

function applyPost(post: PostDetail): void {
  suppressWatch = true
  form.id = post.id
  form.title = post.title
  form.content = post.content
  form.summary = post.summary ?? ''
  form.slug = post.slug ?? ''
  form.coverAttachmentId = post.coverAttachmentId ?? ''
  form.tags = tagNames(post.tags)
  form.pinned = post.pinned
  form.publishStatus = post.publishStatus
  form.publishedRevisionId = post.publishedRevisionId ?? ''
  form.hasUnpublishedChanges = post.hasUnpublishedChanges
  form.publicUrl = post.publicUrl ?? ''
  form.firstPublishedAt = post.firstPublishedAt ?? null
  form.lastPublishedAt = post.lastPublishedAt ?? null
  form.version = post.version
  lastSavedAt.value = formatDateTime(post.updateTime || post.createTime)
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
    summary: form.summary,
    slug: form.slug,
    coverAttachmentId: form.coverAttachmentId,
    tags: [...form.tags],
    pinned: form.pinned,
  }),
  scheduleAutoSave,
)

function fillSummary(): void {
  const next = summarizeMarkdown(form.content, 160)
  if (!next) {
    ElMessage.info('正文暂无可提取的纯文本')
    return
  }
  if (next !== form.summary) {
    form.summary = next
    dirty.value = true
  }
}

async function save(silent = false): Promise<PostDetail | null> {
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
    const common = {
      title: form.title.trim(),
      content: form.content,
      summary: form.summary,
      slug: form.slug.trim() || undefined,
      coverAttachmentId: form.coverAttachmentId || undefined,
      tags: form.tags,
      pinned: form.pinned,
    }
    const post = isNew.value
      ? await postApi.create(common)
      : await postApi.update(postId.value, { ...common, version: form.version })

    applyPost(post)
    if (isNew.value) {
      await router.replace(`/posts/${post.id}`)
    }
    if (!silent) ElMessage.success('草稿已保存')
    return post
  } catch (error) {
    notifyError(error, silent ? '自动保存失败' : '保存失败')
    return null
  } finally {
    saving.value = false
  }
}

async function publish(): Promise<void> {
  if (isNew.value) {
    ElMessage.warning('请先保存草稿')
    return
  }
  if (!form.summary.trim()) {
    fillSummary()
  }
  if (!form.summary.trim()) {
    ElMessage.warning('发布前请填写摘要')
    return
  }
  if (dirty.value) {
    const saved = await save(true)
    if (!saved) return
  }
  publishing.value = true
  try {
    const post = await postApi.publish(postId.value, form.version)
    applyPost(post)
    ElMessage.success(form.publishStatus === 'PUBLISHED' ? '已更新发布' : '文章已发布')
  } catch (error) {
    notifyError(error, '发布失败')
  } finally {
    publishing.value = false
  }
}

async function unpublish(): Promise<void> {
  try {
    await ElMessageBox.confirm('下架后公开博客将不可见，文章仍保留在后台。', '下架文章', {
      type: 'warning',
      confirmButtonText: '下架',
    })
  } catch {
    return
  }
  try {
    const post = await postApi.unpublish(postId.value)
    applyPost(post)
    ElMessage.success('文章已下架')
  } catch (error) {
    notifyError(error, '下架失败')
  }
}

async function uploadCover(options: UploadRequestOptions): Promise<void> {
  try {
    const attachment = await mediaApi.upload(options.file)
    form.coverAttachmentId = attachment.id
    ElMessage.success('封面已上传')
  } catch (error) {
    notifyError(error, '封面上传失败')
  }
}

function removeCover(): void {
  form.coverAttachmentId = ''
}

function setSummaryFromContent(): void {
  fillSummary()
}

async function uploadImages(files: File[]): Promise<{ url: string; alt?: string }[]> {
  const uploaded = await Promise.all(files.map((file) => mediaApi.upload(file)))
  return uploaded.map((item) => ({ url: item.url, alt: item.altText ?? item.originalName }))
}

function handleUploadError(error: unknown): void {
  notifyError(error, '图片上传失败')
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
        <el-button :icon="ArrowLeft" circle title="返回" @click="router.push('/posts')" />
        <el-input v-model="form.title" placeholder="文章标题" class="min-w-40 flex-1 text-base" />
        <el-tag :type="statusType" size="small" effect="light">{{ statusLabel }}</el-tag>
        <el-tag :type="dirty ? 'warning' : 'success'" size="small" effect="plain">{{ savedText }}</el-tag>
        <el-button :icon="Check" :loading="saving" @click="save()">保存草稿</el-button>
        <el-button
          v-perm="'admin:post:publish'"
          type="primary"
          :icon="Promotion"
          :loading="publishing"
          :disabled="publishDisabled"
          @click="publish"
        >
          {{ publishLabel }}
        </el-button>
        <el-button
          v-if="isPublished"
          v-perm="'admin:post:unpublish'"
          type="warning"
          @click="unpublish"
        >
          下架
        </el-button>
        <el-link
          v-if="publicLink"
          :href="publicLink"
          target="_blank"
          :underline="false"
          class="ml-2"
        >
          <el-icon><Link /></el-icon>
          <span class="ml-1">查看线上</span>
        </el-link>
      </div>
      <el-alert
        v-if="isPublished && form.hasUnpublishedChanges"
        class="mt-3"
        type="warning"
        :closable="false"
        show-icon
        title="当前编辑的是草稿，线上仍是上次发布快照；点击「更新发布」后才会生效。"
      />
      <el-alert
        v-else-if="isUnpublished"
        class="mt-3"
        type="info"
        :closable="false"
        show-icon
        title="文章已下架，公开博客不可见；修改后可重新发布。"
      />
    </el-card>

    <div class="grid gap-4 xl:grid-cols-[1fr_300px]">
      <el-card shadow="never" body-class="p-0">
        <MarkdownEditor
          v-model="form.content"
          :theme="editorTheme"
          :cache-id="`post-${postId}`"
          :height="'calc(100vh - 300px)'"
          :upload="uploadImages"
          :on-upload-error="handleUploadError"
          placeholder="开始写文章…"
        />
      </el-card>

      <el-card shadow="never" header="发布信息">
        <el-form label-position="top">
          <el-form-item label="摘要">
            <el-input
              v-model="form.summary"
              type="textarea"
              :rows="4"
              maxlength="500"
              show-word-limit
              placeholder="列表与社交分享使用；发布前必填"
            />
            <el-button link type="primary" class="mt-1" @click="setSummaryFromContent">
              从正文生成
            </el-button>
          </el-form-item>

          <el-form-item label="slug">
            <el-input
              v-model="form.slug"
              :disabled="slugLocked"
              placeholder="留空则首次发布自动生成"
            />
            <el-text v-if="slugLocked" size="small" type="info">已发布文章 slug 不可修改</el-text>
            <el-text v-else size="small" type="info">
              小写字母 / 数字 / 连字符；首次发布后固定
            </el-text>
          </el-form-item>

          <el-form-item label="封面">
            <div v-if="coverUrl" class="w-full">
              <img :src="coverUrl" alt="封面" class="mb-2 max-h-40 w-full rounded-md object-cover" />
              <div class="flex gap-2">
                <el-upload :show-file-list="false" accept="image/*" :http-request="uploadCover">
                  <el-button size="small" :icon="Upload">更换</el-button>
                </el-upload>
                <el-button size="small" type="danger" plain @click="removeCover">移除</el-button>
              </div>
            </div>
            <el-upload v-else :show-file-list="false" accept="image/*" :http-request="uploadCover">
              <el-button :icon="Upload">上传封面</el-button>
            </el-upload>
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
              <el-option v-for="tag in tagOptionNames" :key="tag" :label="tag" :value="tag" />
            </el-select>
          </el-form-item>

          <el-form-item label="置顶">
            <el-switch v-model="form.pinned" />
          </el-form-item>

          <el-form-item label="发布时间">
            <div class="text-sm text-[var(--el-text-color-secondary)]">
              <p>首次：{{ formatDateTime(form.firstPublishedAt) }}</p>
              <p>最近：{{ formatDateTime(form.lastPublishedAt) }}</p>
            </div>
          </el-form-item>
        </el-form>
      </el-card>
    </div>
  </div>
</template>
