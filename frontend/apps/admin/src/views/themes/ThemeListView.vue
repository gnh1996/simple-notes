<script setup lang="ts">
import type { ThemeSummary, ThemeVersionResponse } from '@simple-notes/api'
import { starterThemeMeta } from '@simple-notes/theme-starter'
import starterThemeCss from '@simple-notes/theme-starter/theme.css?raw'
import starterThemeHtml from '@simple-notes/theme-starter/theme.html?raw'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { themeApi } from '@/api'
import { notifyError } from '@/utils/feedback'

const BLOG_URL = (import.meta.env.VITE_BLOG_URL as string | undefined) ?? 'http://localhost:8080'

const themes = ref<ThemeSummary[]>([])
const selected = ref<ThemeSummary | null>(null)
const versions = ref<ThemeVersionResponse[]>([])
const loading = ref(false)
const saving = ref(false)

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

const selectedId = computed(() => selected.value?.id ?? '')

/**
 * 版本是否“已应用”：只有当前线上主题的生效版本才算。
 * 非线上主题仍保留自己的 activeVersionId（用于切回时回滚到上次版本），
 * 若只比较 activeVersionId，会把这些版本误标为已应用并禁用「应用」按钮，
 * 导致无法切换回用过的主题。
 */
function isAppliedVersion(versionId: string): boolean {
  return !!selected.value?.isActiveSiteTheme && versionId === selected.value.activeVersionId
}

async function loadThemes(): Promise<void> {
  loading.value = true
  try {
    themes.value = await themeApi.list()
    if (!selected.value && themes.value.length) {
      await selectTheme(themes.value[0])
    } else if (selected.value) {
      const refreshed = themes.value.find((item) => item.id === selected.value?.id)
      if (refreshed) selected.value = refreshed
    }
  } catch (error) {
    notifyError(error, '主题列表加载失败')
  } finally {
    loading.value = false
  }
}

async function selectTheme(theme: ThemeSummary): Promise<void> {
  selected.value = theme
  try {
    versions.value = await themeApi.versions(theme.id)
  } catch (error) {
    notifyError(error, '版本列表加载失败')
    versions.value = []
  }
}

async function createTheme(): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('主题名称', '新建主题', {
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    const theme = await themeApi.create({ name: value.trim() })
    ElMessage.success('主题已创建')
    selected.value = theme
    await loadThemes()
    await selectTheme(theme)
  } catch (error) {
    if (error !== 'cancel') notifyError(error, '创建失败')
  }
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

function openVersion(version: any): void {
  editor.themeId = version.themeId
  editor.versionId = version.id
  editor.versionNo = version.versionNo + 1
  editor.html = version.html
  editor.css = version.css
  editor.contract = version.contract
  editorVisible.value = true
}

async function saveVersion(): Promise<void> {
  if (!editor.themeId) return
  saving.value = true
  try {
    await themeApi.createVersion(editor.themeId, {
      html: editor.html,
      css: editor.css,
      contract: editor.contract,
    })
    ElMessage.success('已保存为新版本')
    editorVisible.value = false
    await loadThemes()
    if (selected.value) await selectTheme(selected.value)
  } catch (error) {
    notifyError(error, '保存失败')
  } finally {
    saving.value = false
  }
}

async function preview(version: any): Promise<void> {
  if (!selected.value) return
  try {
    const token = await themeApi.previewToken(selected.value.id, version.id)
    previewing.value = `v${version.versionNo}`
    previewUrl.value = `${BLOG_URL}/?previewTheme=${encodeURIComponent(version.id)}&token=${encodeURIComponent(token.token)}`
    previewVisible.value = true
  } catch (error) {
    notifyError(error, '预览失败')
  }
}

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
    if (selected.value) await selectTheme(selected.value)
  } catch (error) {
    notifyError(error, '应用失败')
  }
}

onMounted(loadThemes)
</script>

<template>
  <div class="space-y-4">
    <el-card shadow="never">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-3">
          <span>主题</span>
          <div class="flex items-center gap-2">
            <el-button size="small" :loading="loading" @click="loadThemes">刷新</el-button>
            <el-button type="primary" size="small" @click="createTheme">新建主题</el-button>
          </div>
        </div>
      </template>

      <el-skeleton v-if="loading && !themes.length" :rows="4" animated />
      <el-empty v-else-if="!themes.length" description="还没有主题，先新建一个" :image-size="80" />

      <div v-else class="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        <button
          v-for="theme in themes"
          :key="theme.id"
          type="button"
          class="rounded-lg border p-4 text-left transition-colors"
          :class="selectedId === theme.id
            ? 'border-[var(--el-color-primary)] bg-[var(--el-color-primary-light-9)]'
            : 'border-[var(--el-border-color-lighter)] hover:border-[var(--el-color-primary-light-5)]'"
          @click="selectTheme(theme)"
        >
          <div class="flex items-center justify-between gap-2">
            <span class="font-medium">{{ theme.name }}</span>
            <el-tag v-if="theme.isActiveSiteTheme" type="success" size="small">线上</el-tag>
          </div>
          <p class="mt-2 text-xs text-[var(--el-text-color-secondary)]">
            版本 {{ theme.activeVersionNo ?? '—' }} · 契约 {{ theme.contract ?? '—' }}
          </p>
        </button>
      </div>
    </el-card>

    <el-card v-if="selected" shadow="never">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-3">
          <span>{{ selected.name }} · 版本历史</span>
          <el-button type="primary" size="small" @click="openNewVersion">基于最新版本编辑</el-button>
        </div>
      </template>

      <el-table :data="versions" size="small">
        <el-table-column prop="versionNo" label="版本" width="80" />
        <el-table-column prop="contract" label="契约" width="80" />
        <el-table-column prop="format" label="格式" width="80" />
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">
            {{ new Date(row.createTime).toLocaleString('zh-CN') }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="isAppliedVersion(row.id)" type="success" size="small">
              已应用
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="preview(row)">预览</el-button>
            <el-button link type="primary" @click="openVersion(row)">编辑</el-button>
            <el-button
              link
              type="success"
              :disabled="isAppliedVersion(row.id)"
              @click="activate(row)"
            >
              应用
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="editorVisible"
      :title="`编辑主题 · 将保存为 v${editor.versionNo}`"
      width="min(1100px, 96vw)"
      top="4vh"
    >
      <p class="mb-3 text-xs text-[var(--el-text-color-secondary)]">
        版本不可变：保存会新增版本，不会覆盖历史。主题只允许 HTML + CSS，禁止脚本。
      </p>
      <el-tabs>
        <el-tab-pane label="HTML">
          <el-input v-model="editor.html" type="textarea" :rows="20" spellcheck="false" />
        </el-tab-pane>
        <el-tab-pane label="CSS">
          <el-input v-model="editor.css" type="textarea" :rows="20" spellcheck="false" />
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <div class="flex items-center justify-between">
          <el-input-number v-model="editor.contract" :min="1" :max="1" size="small" />
          <div class="flex gap-2">
            <el-button @click="editorVisible = false">取消</el-button>
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
        <span class="text-xs text-[var(--el-text-color-secondary)]">
          预览走真实运行时；未发布的草稿仅本链接可见
        </span>
      </div>
      <div class="flex justify-center overflow-auto rounded-lg bg-[var(--el-fill-color-light)] p-3">
        <iframe
          :src="previewUrl"
          :style="{ width: previewWidth + 'px', height: '72vh' }"
          class="max-w-full rounded-md border border-[var(--el-border-color-lighter)] bg-white"
          title="主题预览"
        ></iframe>
      </div>
    </el-dialog>
  </div>
</template>
