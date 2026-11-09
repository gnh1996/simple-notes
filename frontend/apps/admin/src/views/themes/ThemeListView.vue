<script setup lang="ts">
import type { ThemeSummary, ThemeVersionResponse } from '@simple-notes/api'
import { useBreakpoint } from '@simple-notes/shared'
import { starterThemeMeta } from '@simple-notes/theme-starter'
import starterThemeCss from '@simple-notes/theme-starter/theme.css?raw'
import starterThemeHtml from '@simple-notes/theme-starter/theme.html?raw'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { themeApi } from '@/api'
import { notifyError } from '@/utils/feedback'
import AiChatDrawer from './components/AiChatDrawer.vue'
import LargeTextEditor from './components/LargeTextEditor.vue'

const BLOG_URL = (import.meta.env.VITE_BLOG_URL as string | undefined) ?? 'http://localhost:8080'

const { isDesktop } = useBreakpoint()
const route = useRoute()
const router = useRouter()

const themes = ref<ThemeSummary[]>([])
const selected = ref<ThemeSummary | null>(null)
const versions = ref<ThemeVersionResponse[]>([])
const loading = ref(false)
const versionsLoading = ref(false)
const saving = ref(false)
const deleting = ref(false)
const activatingDefault = ref(false)
/** 窄屏：列表与详情二选一；桌面端始终同时展示 */
const mobileDetailVisible = ref(false)
const aiVisible = ref(false)
/** 当前主题是否有未结束的 AI 对话：决定头部显示「继续 AI 对话」还是隐藏 */
const aiHasConversation = ref(false)
/** 当前 AI 会话的基线版本；null = 跟随当前主题最新版本 / 已有会话 */
const aiSeed = ref<{ html: string; css: string; label: string } | null>(null)
/** 变更即重建 AiChatDrawer，强制开启新会话（切换基线 / 切换主题时） */
const aiSessionKey = ref(0)

const editorVisible = ref(false)
const editor = reactive({
  themeId: '',
  versionId: '',
  versionNo: 0,
  html: '',
  css: '',
  contract: starterThemeMeta.contract as number,
})

const previewVisible = ref(false)
const previewUrl = ref('')
const previewWidth = ref(1024)
const previewing = ref('')
const previewVersion = ref<ThemeVersionResponse | null>(null)
const previewDraft = ref<{ id: string; token: string; html: string; css: string } | null>(null)
const previewIframe = ref<HTMLIFrameElement>()
const previewErrors = ref<Array<{ line: number; message: string }>>([])
const previewValidated = ref(false)
const previewMessageId = ref('')
const useFixtures = ref(true)

/** null = 内置默认主题 */
const isDefaultSelected = computed(() => selected.value === null)
const selectedKey = computed(() => selected.value?.id ?? 'default')
const hasActiveTheme = computed(() => themes.value.some((theme) => theme.isActiveSiteTheme))
const hasContractMismatch = computed(() =>
  versions.value.some((version) => version.contract !== starterThemeMeta.contract),
)

/**
 * 版本是否“已应用”：只有当前线上主题的生效版本才算。
 * 非线上主题仍保留自己的 activeVersionId（用于切回时回滚到上次版本），
 * 若只比较 activeVersionId，会把这些版本误标为已应用并禁用「应用」按钮，
 * 导致无法切换回用过的主题。
 */
function isAppliedVersion(versionId: string): boolean {
  return !!selected.value?.isActiveSiteTheme && versionId === selected.value.activeVersionId
}

/** 版本状态：线上 / 上次应用（非线上主题保留的 activeVersionId） */
function versionStatus(row: any): { label: string; type: 'success' | 'info' } | null {
  if (isAppliedVersion(row.id)) return { label: '线上', type: 'success' }
  if (
    selected.value &&
    !selected.value.isActiveSiteTheme &&
    selected.value.activeVersionId === row.id
  ) {
    return { label: '上次应用', type: 'info' }
  }
  return null
}

async function loadThemes(): Promise<void> {
  loading.value = true
  try {
    themes.value = await themeApi.list()
    if (!selectionInitialized) {
      selectionInitialized = true
      await applyInitialSelection()
      return
    }
    if (selected.value) {
      const refreshed = themes.value.find((item) => item.id === selected.value?.id)
      if (refreshed) {
        selected.value = refreshed
        await loadVersions(refreshed.id)
      } else {
        // 当前主题已被删除：优先回落到线上主题，否则回到内置默认主题
        await selectFallback()
      }
    }
  } catch (error) {
    notifyError(error, '主题列表加载失败')
  } finally {
    loading.value = false
  }
}

let selectionInitialized = false

/** 首次加载：URL 指定主题 > 线上主题 > 内置默认主题 */
async function applyInitialSelection(): Promise<void> {
  const queryTheme = typeof route.query.theme === 'string' ? route.query.theme : ''
  const reveal = queryTheme.length > 0
  if (queryTheme && queryTheme !== 'default') {
    const found = themes.value.find((item) => item.id === queryTheme)
    if (found) {
      await selectTheme(found, reveal)
      return
    }
  }
  if (queryTheme === 'default') {
    selectDefault(reveal)
    return
  }
  const active = themes.value.find((item) => item.isActiveSiteTheme)
  if (active) await selectTheme(active, reveal)
  else selectDefault(reveal)
}

async function selectFallback(): Promise<void> {
  const active = themes.value.find((item) => item.isActiveSiteTheme)
  if (active) {
    await selectTheme(active)
    return
  }
  selectDefault()
}

/** 版本请求序号：快速切换主题时只接受最后一次响应，避免版本列表串台 */
let selectSeq = 0

async function loadVersions(themeId: string): Promise<void> {
  const seq = ++selectSeq
  versionsLoading.value = true
  try {
    const list = await themeApi.versions(themeId)
    if (seq !== selectSeq) return
    versions.value = list
  } catch (error) {
    if (seq !== selectSeq) return
    notifyError(error, '版本列表加载失败')
    versions.value = []
  } finally {
    if (seq === selectSeq) versionsLoading.value = false
  }
}

/** 选中项写入 `?theme=`，刷新 / 分享不丢上下文；default 表示内置默认主题 */
function syncSelectionToUrl(themeKey: string): void {
  const current = typeof route.query.theme === 'string' ? route.query.theme : ''
  if (current === themeKey) return
  void router.replace({ query: { ...route.query, theme: themeKey } })
}

async function selectTheme(theme: ThemeSummary, reveal = true): Promise<void> {
  const themeChanged = selected.value?.id !== theme.id
  selected.value = theme
  if (reveal) mobileDetailVisible.value = true
  if (themeChanged) {
    // 切换主题即换 AI 会话；重建抽屉同时中止进行中的流式请求
    aiSeed.value = null
    aiHasConversation.value = false
    aiSessionKey.value += 1
    versions.value = []
  }
  syncSelectionToUrl(theme.id)
  await loadVersions(theme.id)
}

function selectDefault(reveal = true): void {
  const changed = selected.value !== null
  if (changed) {
    selectSeq += 1
    aiSeed.value = null
    aiHasConversation.value = false
    aiSessionKey.value += 1
    versions.value = []
    versionsLoading.value = false
  }
  selected.value = null
  if (reveal) mobileDetailVisible.value = true
  syncSelectionToUrl('default')
}

// 浏览器前进 / 后退或手动改 URL 时同步选中项
watch(
  () => route.query.theme,
  (value) => {
    const key = typeof value === 'string' ? value : ''
    if (!key || key === selectedKey.value) return
    if (key === 'default') {
      selectDefault(false)
      return
    }
    const found = themes.value.find((item) => item.id === key)
    if (found) void selectTheme(found, false)
  },
)

async function promptCreateTheme(): Promise<ThemeSummary | null> {
  try {
    const { value } = await ElMessageBox.prompt('主题名称', '新建主题', {
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    const theme = await themeApi.create({ name: value.trim() })
    ElMessage.success('主题已创建')
    await loadThemes()
    await selectTheme(theme)
    return theme
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') notifyError(error, '创建失败')
    return null
  }
}

async function createTheme(): Promise<void> {
  await promptCreateTheme()
}

/** 从内置默认主题起步：创建主题后直接打开编辑器（starter 已预填） */
async function createThemeFromStarter(): Promise<void> {
  const theme = await promptCreateTheme()
  if (theme) openNewVersion()
}

function openNewVersion(): void {
  if (!selected.value) return
  editor.themeId = selected.value.id
  editor.versionId = ''
  editor.versionNo = (versions.value[0]?.versionNo ?? 0) + 1
  editor.html = versions.value[0]?.html || starterThemeHtml
  editor.css = versions.value[0]?.css || starterThemeCss
  editor.contract = versions.value[0]?.contract ?? starterThemeMeta.contract
  editorVisible.value = true
}

// el-table 的插槽 row 是 Element Plus 的 DefaultRow，与业务类型不兼容；这里沿用 any 接收
function openVersion(version: any): void {
  editor.themeId = version.themeId
  editor.versionId = version.id
  // 后端总按最新版本号 +1 分配，编辑旧版本时不能写成 version.versionNo + 1
  editor.versionNo = Math.max(versions.value[0]?.versionNo ?? 0, version.versionNo) + 1
  editor.html = version.html
  editor.css = version.css
  editor.contract = version.contract
  editorVisible.value = true
}

async function saveVersion(): Promise<ThemeVersionResponse | undefined> {
  if (!editor.themeId) return undefined
  // 非受控编辑器：先把未防抖的内容落盘，再读 editor 状态
  htmlEditorRef.value?.flush()
  cssEditorRef.value?.flush()
  saving.value = true
  try {
    const version = await themeApi.createVersion(editor.themeId, {
      html: editor.html,
      css: editor.css,
      contract: editor.contract,
    })
    ElMessage.success('已保存为新版本')
    editorVisible.value = false
    // 手动保存后，后续新对话默认从最新版本起
    aiSeed.value = null
    await loadThemes()
    return version
  } catch (error) {
    notifyError(error, '保存失败')
    return undefined
  } finally {
    saving.value = false
  }
}

async function saveAndPreview(): Promise<void> {
  const version = await saveVersion()
  if (version && selected.value) await preview(version)
}

function buildPreviewUrl(): void {
  if (previewDraft.value) {
    const params = new URLSearchParams({
      previewDraft: previewDraft.value.id,
      token: previewDraft.value.token,
    })
    if (useFixtures.value) params.set('fixtures', '1')
    previewUrl.value = `${BLOG_URL}/?${params.toString()}`
    return
  }
  if (!previewVersion.value) {
    previewUrl.value = ''
    return
  }
  previewErrors.value = []
  previewValidated.value = false
  const params = new URLSearchParams({
    previewTheme: previewVersion.value.id,
    token: previewToken.value,
  })
  if (useFixtures.value) params.set('fixtures', '1')
  previewUrl.value = `${BLOG_URL}/?${params.toString()}`
}

const previewToken = ref('')

async function preview(version: any): Promise<void> {
  if (!selected.value) return
  try {
    const token = await themeApi.previewToken(selected.value.id, version.id)
    previewToken.value = token.token
    previewDraft.value = null
    previewVersion.value = version as ThemeVersionResponse
    previewing.value = `v${version.versionNo}`
    previewErrors.value = []
    previewValidated.value = false
    useFixtures.value = true
    buildPreviewUrl()
    previewVisible.value = true
  } catch (error) {
    notifyError(error, '预览失败')
  }
}

const BLOG_ORIGIN = (() => {
  try {
    return new URL(BLOG_URL).origin
  } catch {
    return window.location.origin
  }
})()

function sendPreviewMessage(payload: Record<string, unknown>): void {
  previewIframe.value?.contentWindow?.postMessage(payload, BLOG_ORIGIN)
}

function sendPreviewValidate(): void {
  const id = `${Date.now()}-${Math.random().toString(16).slice(2)}`
  previewMessageId.value = id
  if (previewDraft.value) {
    sendPreviewMessage({
      type: 'blog-preview:validate',
      id,
      html: previewDraft.value.html,
      css: previewDraft.value.css,
    })
    return
  }
  if (!previewVersion.value) return
  sendPreviewMessage({
    type: 'blog-preview:validate',
    id,
    html: previewVersion.value.html,
    css: previewVersion.value.css,
  })
}

function onPreviewMessage(event: MessageEvent): void {
  if (event.origin !== BLOG_ORIGIN) return
  const data = event.data as Record<string, unknown> | null
  if (!data || typeof data !== 'object') return
  if (data.type === 'blog-preview:ready') {
    sendPreviewMessage({
      type: 'blog-preview:color-mode',
      mode: document.documentElement.classList.contains('dark') ? 'dark' : 'light',
    })
    sendPreviewValidate()
  } else if (data.type === 'blog-preview:validated' && data.id === previewMessageId.value) {
    previewValidated.value = true
    previewErrors.value = Array.isArray(data.errors)
      ? (data.errors as Array<{ line: number; message: string }>)
      : []
  }
}

watch(useFixtures, () => {
  if (previewVisible.value) buildPreviewUrl()
})

async function activate(version: any): Promise<void> {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(
      `应用「${selected.value.name}」v${version.versionNo} 到线上？应用后立即生效，可随时回滚。`,
      '确认应用',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await themeApi.activate(selected.value.id, version.id)
    ElMessage.success('已应用')
    await loadThemes()
  } catch (error) {
    notifyError(error, '应用失败')
  }
}

async function activateDefaultTheme(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '切换回内置默认主题？当前线上主题将被停用，其版本仍保留，可随时重新应用。',
      '使用内置默认主题',
      { type: 'warning' },
    )
  } catch {
    return
  }
  activatingDefault.value = true
  try {
    await themeApi.activateDefault()
    ElMessage.success('已切换为内置默认主题')
    await loadThemes()
  } catch (error) {
    notifyError(error, '切换失败')
  } finally {
    activatingDefault.value = false
  }
}

async function deleteTheme(target: ThemeSummary): Promise<void> {
  if (target.isActiveSiteTheme) {
    ElMessage.warning('线上主题不能删除，请先切换到其他主题或内置默认主题')
    return
  }
  const isSelected = selected.value?.id === target.id
  const versionText = isSelected
    ? `其 ${versions.value.length} 个版本将保留但不再显示；`
    : '其版本将保留但不再显示；'
  try {
    await ElMessageBox.confirm(
      `删除主题「${target.name}」？${versionText}名称可复用。`,
      '删除主题',
      { type: 'warning', confirmButtonText: '删除' },
    )
  } catch {
    return
  }
  deleting.value = true
  try {
    await themeApi.remove(target.id)
    ElMessage.success('主题已删除')
    if (isSelected) {
      aiVisible.value = false
      aiHasConversation.value = false
      previewVisible.value = false
      aiSessionKey.value += 1
    }
    // 当前主题被删时由 loadThemes 回落到线上主题 / 内置默认主题
    await loadThemes()
  } catch (error) {
    notifyError(error, '删除失败')
  } finally {
    deleting.value = false
  }
}

/** 主题项「⋯」菜单（当前只有删除，留给后续重命名 / 复制） */
function onThemeCommand(target: ThemeSummary, command: string | number | object): void {
  if (command === 'delete') void deleteTheme(target)
}

const htmlEditorRef = ref<InstanceType<typeof LargeTextEditor> | null>(null)
const cssEditorRef = ref<InstanceType<typeof LargeTextEditor> | null>(null)
const aiBaseHtml = computed(() => aiSeed.value?.html ?? versions.value[0]?.html ?? '')
const aiBaseCss = computed(() => aiSeed.value?.css ?? versions.value[0]?.css ?? '')
const aiBaseLabel = computed(
  () => aiSeed.value?.label ?? (versions.value[0] ? `v${versions.value[0].versionNo}` : '内置默认主题'),
)

/** 顶部「AI 对话」：续接当前会话（或从当前基线开启） */
function openAi(): void {
  aiVisible.value = true
}

/** 版本行「AI 迭代」：以该版本为基线强制开启新会话 */
async function startAiFromVersion(version: any): Promise<void> {
  if (aiHasConversation.value) {
    try {
      await ElMessageBox.confirm(
        `当前 AI 对话会被清空，确定从 v${version.versionNo} 重新迭代？`,
        '从该版本重新迭代',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  aiSeed.value = { html: version.html, css: version.css, label: `v${version.versionNo}` }
  aiHasConversation.value = false
  aiSessionKey.value += 1
  aiVisible.value = true
}

function fillFromAi(html: string, css: string, contract = starterThemeMeta.contract as number): void {
  if (!selected.value) {
    ElMessage.warning('请先选中一个主题')
    return
  }
  aiVisible.value = false
  editor.themeId = selected.value.id
  editor.versionId = ''
  editor.versionNo = (versions.value[0]?.versionNo ?? 0) + 1
  editor.html = html
  editor.css = css
  editor.contract = contract
  editorVisible.value = true
}

async function afterAiSaved(version: ThemeVersionResponse): Promise<void> {
  await loadThemes()
  // 已保存的产物成为后续新对话的基线；当前会话本身由后端当前主题继续
  aiSeed.value = { html: version.html, css: version.css, label: `v${version.versionNo}` }
}

async function afterAiCreated(theme: ThemeSummary): Promise<void> {
  aiVisible.value = false
  await loadThemes()
  const found = themes.value.find((item) => item.id === theme.id)
  if (found) await selectTheme(found)
}

function openPreviewDraft(draftId: string, token: string, html: string, css: string): void {
  aiVisible.value = false
  previewDraft.value = { id: draftId, token, html, css }
  previewVersion.value = null
  previewing.value = 'AI 草稿'
  previewErrors.value = []
  previewValidated.value = false
  buildPreviewUrl()
  previewVisible.value = true
}

onMounted(() => {
  window.addEventListener('message', onPreviewMessage)
  void loadThemes()
})

onBeforeUnmount(() => {
  window.removeEventListener('message', onPreviewMessage)
})
</script>

<template>
  <div class="flex flex-col gap-4 lg:flex-row lg:items-start">
    <!-- 左侧主题列表：固定宽度、独立滚动，详情始终在右侧 -->
    <el-card
      v-if="isDesktop || !mobileDetailVisible"
      shadow="never"
      class="w-full lg:sticky lg:top-0 lg:w-80 lg:shrink-0 xl:w-96"
    >
      <template #header>
        <div class="flex items-center justify-between gap-2">
          <span>主题</span>
          <div class="flex items-center gap-1">
            <el-button size="small" text :loading="loading" @click="loadThemes">刷新</el-button>
            <el-button v-perm="'admin:theme:write'" type="primary" size="small" @click="createTheme">
              新建
            </el-button>
          </div>
        </div>
      </template>

      <div class="space-y-2 lg:max-h-[calc(100vh-15rem)] lg:overflow-y-auto lg:pr-1">
        <button
          type="button"
          class="w-full rounded-lg border p-3 text-left transition-colors"
          :class="isDefaultSelected
            ? 'border-[var(--el-color-primary)] bg-[var(--el-color-primary-light-9)]'
            : 'border-[var(--el-border-color-lighter)] hover:border-[var(--el-color-primary-light-5)]'"
          @click="selectDefault()"
        >
          <div class="flex items-center justify-between gap-2">
            <span class="truncate font-medium">内置默认主题</span>
            <el-tag v-if="!hasActiveTheme" type="success" size="small">线上</el-tag>
          </div>
          <p class="mt-1 text-xs text-[var(--el-text-color-secondary)]">不存数据库 · 随代码发布</p>
        </button>

        <div v-for="theme in themes" :key="theme.id" class="flex items-stretch gap-1">
          <button
            type="button"
            class="min-w-0 flex-1 rounded-lg border p-3 text-left transition-colors"
            :class="selectedKey === theme.id
              ? 'border-[var(--el-color-primary)] bg-[var(--el-color-primary-light-9)]'
              : 'border-[var(--el-border-color-lighter)] hover:border-[var(--el-color-primary-light-5)]'"
            @click="selectTheme(theme)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="truncate font-medium">{{ theme.name }}</span>
              <el-tag v-if="theme.isActiveSiteTheme" type="success" size="small">线上</el-tag>
            </div>
            <p class="mt-1 text-xs text-[var(--el-text-color-secondary)]">
              版本 {{ theme.activeVersionNo ?? '—' }} · 契约 {{ theme.contract ?? '—' }}
            </p>
          </button>
          <el-dropdown
            v-perm="'admin:theme:delete'"
            trigger="click"
            @command="(command) => onThemeCommand(theme, command)"
          >
            <el-button text size="small" class="h-auto shrink-0 px-2" aria-label="主题操作">⋯</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="delete" :disabled="theme.isActiveSiteTheme">
                  {{ theme.isActiveSiteTheme ? '删除主题（线上不可删）' : '删除主题' }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <p v-if="!themes.length" class="px-1 pt-2 text-xs text-[var(--el-text-color-secondary)]">
          还没有数据库主题，可从默认主题新建。
        </p>
      </div>
    </el-card>

    <!-- 右侧详情：默认主题 / 选中主题的版本历史 -->
    <el-card v-if="isDesktop || mobileDetailVisible" shadow="never" class="min-w-0 flex-1">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div class="flex min-w-0 items-center gap-2">
            <el-button v-if="!isDesktop" size="small" text @click="mobileDetailVisible = false">
              ← 返回
            </el-button>
            <span class="truncate font-medium">
              {{ isDefaultSelected ? '内置默认主题' : selected?.name }}
            </span>
            <el-tag
              v-if="isDefaultSelected ? !hasActiveTheme : selected?.isActiveSiteTheme"
              type="success"
              size="small"
            >
              线上
            </el-tag>
          </div>

          <div class="flex items-center gap-2">
            <template v-if="isDefaultSelected">
              <el-button
                v-perm="'admin:theme:activate'"
                type="primary"
                size="small"
                :disabled="!hasActiveTheme"
                :loading="activatingDefault"
                @click="activateDefaultTheme"
              >
                {{ hasActiveTheme ? '使用默认主题' : '当前正在使用' }}
              </el-button>
            </template>
            <template v-else-if="selected">
              <el-button v-perm="'admin:theme:write'" type="primary" size="small" @click="openNewVersion">
                {{ versions.length || versionsLoading ? '基于最新版本编辑' : '创建第一个版本' }}
              </el-button>
              <el-button
                v-if="aiHasConversation"
                v-perm="'admin:theme:write'"
                size="small"
                @click="openAi"
              >
                继续 AI 对话
              </el-button>
              <el-button
                v-else-if="!versions.length && !versionsLoading"
                v-perm="'admin:theme:write'"
                size="small"
                @click="openAi"
              >
                AI 生成第一版
              </el-button>
              <el-button
                v-if="!isDesktop"
                v-perm="'admin:theme:delete'"
                size="small"
                type="danger"
                plain
                :loading="deleting"
                :disabled="selected.isActiveSiteTheme"
                @click="deleteTheme(selected)"
              >
                删除
              </el-button>
            </template>
          </div>
        </div>
      </template>

      <div v-if="isDefaultSelected" class="space-y-4">
        <el-alert
          v-if="!hasActiveTheme"
          type="info"
          :closable="false"
          show-icon
          title="博客当前使用内置默认主题"
        />
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="来源">代码内置（packages/theme-starter）</el-descriptions-item>
          <el-descriptions-item label="存储">不存数据库，随代码发布</el-descriptions-item>
          <el-descriptions-item label="定制">不可直接编辑；需要定制时从默认主题新建一个数据库主题</el-descriptions-item>
        </el-descriptions>
        <el-button v-perm="'admin:theme:write'" @click="createThemeFromStarter">
          从默认主题新建
        </el-button>
      </div>

      <template v-else-if="selected">
        <div class="mb-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-[var(--el-text-color-secondary)]">
          <span>版本 {{ selected.activeVersionNo ?? '—' }} · 契约 {{ selected.contract ?? '—' }}</span>
          <span>更新于 {{ new Date(selected.updatedAt).toLocaleString('zh-CN') }}</span>
          <span v-if="selected.isActiveSiteTheme" class="text-[var(--el-color-success)]">
            该主题正在线上生效
          </span>
          <span v-else>该主题未上线；应用某个版本后生效</span>
        </div>

        <el-empty
          v-if="!versionsLoading && !versions.length"
          :image-size="72"
          description="这个主题还没有版本"
        >
          <el-button v-perm="'admin:theme:write'" type="primary" @click="openNewVersion">
            从默认主题开始
          </el-button>
        </el-empty>

        <el-table v-else-if="isDesktop" v-loading="versionsLoading" :data="versions" size="small">
          <el-table-column prop="versionNo" label="版本" width="70" />
          <el-table-column v-if="hasContractMismatch" label="契约" width="110">
            <template #default="{ row }">
              <el-tag type="warning" size="small">v{{ row.contract }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag v-if="versionStatus(row)" :type="versionStatus(row)?.type ?? 'info'" size="small">
                {{ versionStatus(row)?.label ?? '' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="160">
            <template #default="{ row }">
              {{ new Date(row.createTime).toLocaleString('zh-CN') }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="240">
            <template #default="{ row }">
              <el-button link type="primary" @click="preview(row)">预览</el-button>
              <el-button v-perm="'admin:theme:write'" link type="primary" @click="openVersion(row)">
                编辑
              </el-button>
              <el-button
                v-perm="'admin:theme:write'"
                link
                type="primary"
                title="以该版本为基线开启新对话"
                @click="startAiFromVersion(row)"
              >
                AI 迭代
              </el-button>
              <el-button
                v-perm="'admin:theme:activate'"
                link
                type="primary"
                :disabled="isAppliedVersion(row.id)"
                @click="activate(row)"
              >
                应用
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 窄屏不用横向滚动表格：每个版本一张卡片，状态 / 时间 / 操作一次看全 -->
        <div v-else v-loading="versionsLoading" class="min-h-16 space-y-2">
          <div
            v-for="row in versions"
            :key="row.id"
            class="rounded-lg border border-[var(--el-border-color-lighter)] p-3"
          >
            <div class="flex items-center justify-between gap-2">
              <div class="flex items-center gap-2">
                <span class="font-medium">v{{ row.versionNo }}</span>
                <el-tag
                  v-if="versionStatus(row)"
                  :type="versionStatus(row)?.type ?? 'info'"
                  size="small"
                >
                  {{ versionStatus(row)?.label ?? '' }}
                </el-tag>
                <el-tag v-if="row.contract !== starterThemeMeta.contract" type="warning" size="small">
                  契约 v{{ row.contract }}
                </el-tag>
              </div>
              <span class="text-xs text-[var(--el-text-color-secondary)]">
                {{ new Date(row.createTime).toLocaleString('zh-CN') }}
              </span>
            </div>
            <div class="mt-2 flex flex-wrap gap-2">
              <el-button size="small" @click="preview(row)">预览</el-button>
              <el-button v-perm="'admin:theme:write'" size="small" @click="openVersion(row)">
                编辑
              </el-button>
              <el-button
                v-perm="'admin:theme:write'"
                size="small"
                title="以该版本为基线开启新对话"
                @click="startAiFromVersion(row)"
              >
                AI 迭代
              </el-button>
              <el-button
                v-perm="'admin:theme:activate'"
                size="small"
                type="primary"
                :disabled="isAppliedVersion(row.id)"
                @click="activate(row)"
              >
                应用
              </el-button>
            </div>
          </div>
        </div>
      </template>
    </el-card>

    <el-dialog
      v-model="editorVisible"
      :title="`编辑主题 · 将保存为 v${editor.versionNo}`"
      width="min(1100px, 96vw)"
      top="4vh"
      destroy-on-close
    >
      <p class="mb-3 text-xs text-[var(--el-text-color-secondary)]">
        版本不可变：保存会新增版本，不会覆盖历史。主题只允许 HTML + CSS，禁止脚本。
      </p>
      <el-tabs>
        <el-tab-pane label="HTML">
          <LargeTextEditor ref="htmlEditorRef" v-model="editor.html" lang="html" />
        </el-tab-pane>
        <el-tab-pane label="CSS">
          <LargeTextEditor ref="cssEditorRef" v-model="editor.css" lang="css" />
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <div class="flex items-center justify-between">
          <el-input-number
            v-model="editor.contract"
            :min="1"
            :max="starterThemeMeta.contract"
            size="small"
          />
          <div class="flex gap-2">
            <el-button @click="editorVisible = false">取消</el-button>
            <el-button :loading="saving" @click="saveAndPreview">保存并预览</el-button>
            <el-button type="primary" :loading="saving" @click="saveVersion">保存为新版本</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="previewVisible"
      :title="`预览 ${previewing}`"
      width="min(1200px, 96vw)"
      top="3vh"
      destroy-on-close
    >
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <el-button-group>
          <el-button
            v-for="width in [320, 375, 768, 1024]"
            :key="width"
            size="small"
            :type="previewWidth === width ? 'primary' : 'default'"
            @click="previewWidth = width"
          >
            {{ width }}
          </el-button>
        </el-button-group>
        <el-checkbox v-model="useFixtures" size="small">使用示例数据</el-checkbox>
        <span class="text-xs text-[var(--el-text-color-secondary)]">
          预览走真实运行时；未发布的草稿仅本链接可见
        </span>
      </div>
      <el-alert
        v-if="previewErrors.length"
        class="mb-3"
        type="error"
        :closable="false"
        title="主题契约校验未通过"
      >
        <div v-for="(error, index) in previewErrors" :key="index" class="text-xs">
          第 {{ error.line }} 行：{{ error.message }}
        </div>
      </el-alert>
      <el-alert
        v-else-if="previewValidated"
        class="mb-3"
        type="success"
        :closable="false"
        title="主题契约校验通过"
      />
      <div class="flex justify-center overflow-auto rounded-lg bg-[var(--el-fill-color-light)] p-3">
        <iframe
          ref="previewIframe"
          :src="previewUrl"
          :style="{ width: previewWidth + 'px', height: '72vh' }"
          class="max-w-full rounded-md border border-[var(--el-border-color-lighter)] bg-white"
          title="主题预览"
        ></iframe>
      </div>
    </el-dialog>

    <AiChatDrawer
      :key="aiSessionKey"
      v-model:visible="aiVisible"
      :theme-id="selected?.id ?? null"
      :base-html="aiBaseHtml"
      :base-css="aiBaseCss"
      :base-label="aiBaseLabel"
      @fill="fillFromAi"
      @saved="afterAiSaved"
      @created="afterAiCreated"
      @preview-draft="openPreviewDraft"
      @conversation-state="aiHasConversation = $event"
    />
  </div>
</template>
