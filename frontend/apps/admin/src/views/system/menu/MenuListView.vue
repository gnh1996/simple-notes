<script setup lang="ts">
import type { ClientType, MenuCatalogNode } from '@simple-notes/api'
import { CLIENT_TYPE_LABELS, enabledStatusLabel, labelOf, MENU_TYPE_LABELS } from '@simple-notes/shared'
import { Refresh } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'

import { menuApi } from '@/api'
import { notifyError } from '@/utils/feedback'

import MenuEditDialog from './components/MenuEditDialog.vue'

const CLIENT_ORDER: ClientType[] = ['ADMIN', 'BLOG', 'DESKTOP']
const TYPE_TAGS: Record<string, 'success' | 'primary' | 'warning'> = {
  ENTRY: 'success',
  MENU: 'primary',
  ACTION: 'warning',
}

const loading = ref(false)
const roots = ref<MenuCatalogNode[]>([])
const activeClient = ref<ClientType>('ADMIN')

const editVisible = ref(false)
const editingNode = ref<MenuCatalogNode | null>(null)
const editingParentName = ref('')

const clients = computed(() =>
  CLIENT_ORDER.filter((client) => roots.value.some((root) => root.client === client)),
)
const treeData = computed(() => {
  const root = roots.value.find((item) => item.client === activeClient.value)
  return root ? [root] : []
})

async function load(): Promise<void> {
  loading.value = true
  try {
    roots.value = (await menuApi.list()) ?? []
    if (!clients.value.includes(activeClient.value)) {
      activeClient.value = clients.value[0] ?? 'ADMIN'
    }
  } catch (error) {
    notifyError(error, '权限目录加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 在端内树中查父节点名称（用于编辑弹窗展示） */
function findParentName(nodes: MenuCatalogNode[], targetId: string, parentName = ''): string {
  for (const node of nodes) {
    if (node.id === targetId) return parentName
    const found = findParentName(node.children ?? [], targetId, node.name)
    if (found !== '') return found
  }
  return ''
}

function openEdit(node: MenuCatalogNode): void {
  editingNode.value = node
  editingParentName.value = findParentName(treeData.value, node.id)
  editVisible.value = true
}
</script>

<template>
  <div class="space-y-4">
    <el-card shadow="never" body-class="py-4">
      <div class="flex flex-wrap items-center gap-2">
        <el-text size="small" type="info">
          权限目录是前后端权限码的唯一来源：改名 / 调序 / 启停即时生效，无需发版；结构性字段由迁移管控
        </el-text>
        <div class="flex-1" />
        <el-button :icon="Refresh" circle title="刷新" @click="load" />
      </div>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="activeClient">
        <el-tab-pane
          v-for="client in clients"
          :key="client"
          :label="CLIENT_TYPE_LABELS[client]"
          :name="client"
        >
          <el-table
            v-loading="loading"
            :data="treeData"
            row-key="id"
            default-expand-all
            :tree-props="{ children: 'children' }"
          >
            <el-table-column prop="name" label="名称" min-width="220">
              <template #default="{ row }">
                <span :class="{ 'text-[var(--el-text-color-disabled)]': row.status === 0 }">
                  {{ row.name }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="权限码" min-width="220">
              <template #default="{ row }">
                <span class="font-mono text-xs text-[var(--el-text-color-secondary)]">
                  {{ row.code }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="TYPE_TAGS[row.type]">
                  {{ labelOf(MENU_TYPE_LABELS, row.type) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sort" label="排序" width="80" align="center" />
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small" effect="light">
                  {{ enabledStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="right" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-perm="'admin:menu:update'"
                  link
                  type="primary"
                  @click="openEdit(row as MenuCatalogNode)"
                >
                  编辑
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <MenuEditDialog
      v-if="editingNode"
      v-model="editVisible"
      :node="editingNode"
      :parent-name="editingParentName"
      @saved="load"
    />
  </div>
</template>
