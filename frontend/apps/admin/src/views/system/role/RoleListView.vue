<script setup lang="ts">
import { Plus, Refresh } from '@element-plus/icons-vue'
import type { RoleResponse } from '@simple-notes/api'
import { enabledStatusLabel } from '@simple-notes/shared'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'

import { roleApi } from '@/api'
import { formatDateTime, notifyError } from '@/utils/feedback'

import RoleFormDialog from './components/RoleFormDialog.vue'
import RoleGrantDialog from './components/RoleGrantDialog.vue'

const loading = ref(false)
const rows = ref<RoleResponse[]>([])

const formVisible = ref(false)
const editingRole = ref<RoleResponse | null>(null)
const grantVisible = ref(false)
const grantTarget = ref<RoleResponse | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = (await roleApi.list()) ?? []
  } catch (error) {
    notifyError(error, '角色列表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate(): void {
  editingRole.value = null
  formVisible.value = true
}

function openEdit(row: RoleResponse): void {
  editingRole.value = row
  formVisible.value = true
}

function openGrant(row: RoleResponse): void {
  grantTarget.value = row
  grantVisible.value = true
}

async function remove(row: RoleResponse): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除角色「${row.name}（${row.code}）」吗？`, '删除角色', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  try {
    await roleApi.remove(row.id)
    ElMessage.success('角色已删除')
    await load()
  } catch (error) {
    notifyError(error, '删除失败')
  }
}
</script>

<template>
  <div class="space-y-4">
    <el-card shadow="never" body-class="py-4">
      <div class="flex flex-wrap items-center gap-2">
        <el-text size="small" type="info">
          角色 = 权限码集合；用户可持有多个角色，权限取并集
        </el-text>
        <div class="flex-1" />
        <el-button :icon="Refresh" circle title="刷新" @click="load" />
        <el-button v-perm="'admin:role:create'" type="primary" :icon="Plus" @click="openCreate">
          新建角色
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="code" label="角色码" min-width="150">
          <template #default="{ row }">
            <span class="font-mono text-sm font-medium">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column label="描述" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="80" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small" effect="light">
              {{ enabledStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.builtin" type="warning" size="small" effect="plain">内置</el-tag>
            <span v-else class="text-[var(--el-text-color-secondary)]">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="right" fixed="right">
          <template #default="{ row }">
            <el-button
              v-perm="'admin:role:update'"
              link
              type="primary"
              :disabled="row.builtin"
              @click="openEdit(row as RoleResponse)"
            >
              编辑
            </el-button>
            <el-button
              v-perm="'admin:role:grant'"
              link
              type="primary"
              :disabled="row.builtin"
              @click="openGrant(row as RoleResponse)"
            >
              修改授权
            </el-button>
            <el-button
              v-perm="'admin:role:delete'"
              link
              type="danger"
              :disabled="row.builtin"
              @click="remove(row as RoleResponse)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="!loading && rows.length === 0"
        description="暂无角色"
        :image-size="80"
        class="py-4"
      />
    </el-card>

    <RoleFormDialog v-model="formVisible" :role="editingRole" @saved="load" />
    <RoleGrantDialog v-if="grantTarget" v-model="grantVisible" :role="grantTarget" @saved="load" />
  </div>
</template>
