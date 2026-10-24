<script setup lang="ts">
import type { SitePageResponse, SiteSettingItem } from '@simple-notes/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { siteApi } from '@/api'
import { notifyError } from '@/utils/feedback'

const activeTab = ref<'settings' | 'pages'>('settings')

// 生成类型里的 key / group 等为可选（契约允许可空），页面内统一收敛为必填视图模型
interface SettingRow {
  key: string
  type: string
  group: string
  label: string
  defaultValue: string
  publicReadable: boolean
  allowedValues: string[] | null
  value: unknown
}

function toRow(item: SiteSettingItem): SettingRow {
  return {
    key: item.key ?? '',
    type: item.type ?? 'STRING',
    group: item.group ?? '其他',
    label: item.label ?? item.key ?? '',
    defaultValue: item.defaultValue ?? '',
    publicReadable: item.publicReadable ?? false,
    allowedValues: item.allowedValues ?? null,
    value: item.value,
  }
}

// ---- 站点配置 ----

const loading = ref(false)
const saving = ref(false)
const version = ref(0)
const items = ref<SettingRow[]>([])
const drafts = reactive<Record<string, any>>({})

const groups = computed(() => {
  const map = new Map<string, SettingRow[]>()
  for (const item of items.value) {
    const group = item.group || '其他'
    if (!map.has(group)) map.set(group, [])
    map.get(group)!.push(item)
  }
  return [...map.entries()].map(([name, rows]) => ({ name, rows }))
})

function normalize(item: SettingRow): unknown {
  if (item.value === null || item.value === undefined) return ''
  if (item.type === 'JSON') return JSON.stringify(item.value, null, 2)
  return item.value
}

async function loadSettings(): Promise<void> {
  loading.value = true
  try {
    const result = await siteApi.settings()
    version.value = result.version ?? 0
    items.value = (result.items ?? []).map(toRow)
    for (const key of Object.keys(drafts)) delete drafts[key]
    for (const item of items.value) drafts[item.key] = normalize(item)
  } catch (error) {
    notifyError(error, '站点设置加载失败')
  } finally {
    loading.value = false
  }
}

async function saveSettings(): Promise<void> {
  const values: Record<string, unknown> = {}
  for (const item of items.value) {
    let value = drafts[item.key]
    if (item.type === 'JSON') {
      try {
        value = JSON.parse(String(value ?? 'null'))
      } catch {
        ElMessage.error(`${item.label} 不是合法 JSON`)
        return
      }
    }
    values[item.key] = value
  }
  saving.value = true
  try {
    const result = await siteApi.updateSettings({ version: version.value, values })
    version.value = result.version ?? 0
    items.value = (result.items ?? []).map(toRow)
    for (const item of items.value) drafts[item.key] = normalize(item)
    ElMessage.success('已保存')
  } catch (error) {
    notifyError(error, '保存失败')
    await loadSettings()
  } finally {
    saving.value = false
  }
}

// ---- 独立页面 ----

const pages = ref<SitePageResponse[]>([])
const pageDialog = ref(false)
const pageSaving = ref(false)
const pageForm = reactive({
  id: '',
  slug: '',
  title: '',
  content: '',
  visible: true,
  sort: 0,
})

async function loadPages(): Promise<void> {
  try {
    pages.value = await siteApi.pages()
  } catch (error) {
    notifyError(error, '页面列表加载失败')
  }
}

function openCreatePage(): void {
  pageForm.id = ''
  pageForm.slug = ''
  pageForm.title = ''
  pageForm.content = ''
  pageForm.visible = true
  pageForm.sort = 0
  pageDialog.value = true
}

function openEditPage(page: any): void {
  pageForm.id = page.id
  pageForm.slug = page.slug
  pageForm.title = page.title
  pageForm.content = page.content
  pageForm.visible = page.visible
  pageForm.sort = page.sort
  pageDialog.value = true
}

async function savePage(): Promise<void> {
  pageSaving.value = true
  const body = {
    slug: pageForm.slug.trim(),
    title: pageForm.title.trim(),
    content: pageForm.content,
    visible: pageForm.visible,
    sort: pageForm.sort,
  }
  try {
    if (pageForm.id) {
      await siteApi.updatePage(pageForm.id, body)
    } else {
      await siteApi.createPage(body)
    }
    ElMessage.success('已保存')
    pageDialog.value = false
    await loadPages()
  } catch (error) {
    notifyError(error, '保存失败')
  } finally {
    pageSaving.value = false
  }
}

async function removePage(page: any): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除页面「${page.title}」？该操作保存即生效。`, '确认删除', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await siteApi.removePage(page.id)
    ElMessage.success('已删除')
    await loadPages()
  } catch (error) {
    notifyError(error, '删除失败')
  }
}

onMounted(async () => {
  await Promise.all([loadSettings(), loadPages()])
})
</script>

<template>
  <div class="space-y-4">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="站点配置" name="settings">
        <el-card shadow="never">
          <template #header>
            <div class="flex flex-wrap items-center justify-between gap-3">
              <span>配置项</span>
              <div class="flex items-center gap-2">
                <el-button size="small" :loading="loading" @click="loadSettings">重新加载</el-button>
                <el-button type="primary" size="small" :loading="saving" @click="saveSettings">
                  保存
                </el-button>
              </div>
            </div>
          </template>

          <el-skeleton v-if="loading" :rows="6" animated />

          <div v-else class="space-y-6">
            <section v-for="group in groups" :key="group.name" class="space-y-3">
              <h3 class="text-sm font-semibold text-[var(--el-text-color-primary)]">
                {{ group.name }}
              </h3>
              <div class="grid gap-4 md:grid-cols-2">
                <el-form-item
                  v-for="item in group.rows"
                  :key="item.key"
                  :label="item.label"
                  class="!mb-0"
                >
                  <el-select
                    v-if="item.allowedValues?.length"
                    v-model="drafts[item.key]"
                    class="w-full"
                  >
                    <el-option
                      v-for="option in item.allowedValues"
                      :key="option"
                      :label="option"
                      :value="option"
                    />
                  </el-select>
                  <el-input-number
                    v-else-if="item.type === 'NUMBER'"
                    v-model="drafts[item.key]"
                    class="w-full"
                    :min="1"
                    :max="50"
                  />
                  <el-input
                    v-else-if="item.type === 'JSON'"
                    v-model="drafts[item.key]"
                    type="textarea"
                    :rows="6"
                    :placeholder="item.defaultValue"
                  />
                  <el-input v-else v-model="drafts[item.key]" clearable />
                  <p class="mt-1 text-xs text-[var(--el-text-color-secondary)]">
                    {{ item.key }} · 默认值：{{ item.defaultValue || '（空）' }}
                  </p>
                </el-form-item>
              </div>
            </section>
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="独立页面" name="pages">
        <el-card shadow="never">
          <template #header>
            <div class="flex items-center justify-between">
              <span>页面（保存即线上）</span>
              <el-button type="primary" size="small" @click="openCreatePage">新建页面</el-button>
            </div>
          </template>

          <el-table :data="pages" size="small">
            <el-table-column prop="title" label="标题" min-width="160" />
            <el-table-column prop="slug" label="slug" width="140" />
            <el-table-column label="可见" width="80">
              <template #default="{ row }">
                <el-tag :type="row.visible ? 'success' : 'info'" size="small">
                  {{ row.visible ? '可见' : '仅直链' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sort" label="排序" width="80" />
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEditPage(row)">编辑</el-button>
                <el-button link type="danger" @click="removePage(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="pageDialog"
      :title="pageForm.id ? '编辑页面' : '新建页面'"
      width="min(760px, 92vw)"
    >
      <el-form label-position="top">
        <div class="grid gap-3 md:grid-cols-2">
          <el-form-item label="标题">
            <el-input v-model="pageForm.title" />
          </el-form-item>
          <el-form-item label="slug（小写字母 / 数字 / 连字符）">
            <el-input v-model="pageForm.slug" placeholder="about" />
          </el-form-item>
        </div>
        <el-form-item label="内容（Markdown）">
          <el-input v-model="pageForm.content" type="textarea" :rows="14" />
        </el-form-item>
        <div class="flex flex-wrap items-center gap-6">
          <el-form-item label="可见">
            <el-switch v-model="pageForm.visible" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="pageForm.sort" :min="0" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="pageDialog = false">取消</el-button>
        <el-button type="primary" :loading="pageSaving" @click="savePage">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
