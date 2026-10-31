<script setup lang="ts">
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import type { UserResponse } from '@simple-notes/api'
import { enabledStatusLabel } from '@simple-notes/shared'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { userApi } from '@/api'
import { formatDateTime, notifyError } from '@/utils/feedback'

import AssignRolesDialog from './components/AssignRolesDialog.vue'
import ResetPasswordDialog from './components/ResetPasswordDialog.vue'
import UserFormDialog from './components/UserFormDialog.vue'

const loading = ref(false)
const rows = ref<UserResponse[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, keyword: '' })

const formVisible = ref(false)
const editingUser = ref<UserResponse | null>(null)
const assignVisible = ref(false)
const assignTarget = ref<UserResponse | null>(null)
const resetVisible = ref(false)
const resetTarget = ref<UserResponse | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await userApi.list({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
    })
    rows.value = result.items ?? []
    total.value = result.total ?? 0
  } catch (error) {
    notifyError(error, '用户列表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function search(): void {
  query.page = 1
  void load()
}

function resetSearch(): void {
  query.keyword = ''
  search()
}

function openCreate(): void {
  editingUser.value = null
  formVisible.value = true
}

function openEdit(row: UserResponse): void {
  editingUser.value = row
  formVisible.value = true
}

function openAssign(row: UserResponse): void {
  assignTarget.value = row
  assignVisible.value = true
}

function openReset(row: UserResponse): void {
  resetTarget.value = row
  resetVisible.value = true
}

async function remove(row: UserResponse): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除用户「${row.nickname}（${row.username}）」吗？删除后其登录名不可复用。`,
      '删除用户',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' },
    )
  } catch {
    return
  }
  try {
    await userApi.remove(row.id)
    ElMessage.success('用户已删除')
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
        <el-input
          v-model="query.keyword"
          placeholder="搜索用户名 / 昵称 / 邮箱"
          clearable
          class="w-64"
          :prefix-icon="Search"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" @click="search">搜索</el-button>
        <el-button @click="resetSearch">重置</el-button>
        <div class="flex-1" />
        <el-button :icon="Refresh" circle title="刷新" @click="load" />
        <el-button v-perm="'admin:user:create'" type="primary" :icon="Plus" @click="openCreate">
          新建用户
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column prop="username" label="用户名" min-width="140">
          <template #default="{ row }">
            <span class="font-medium">{{ row.username }}</span>
            <el-tag v-if="row.root" type="warning" effect="plain" size="small" class="ml-2">
              超管
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="nickname" label="昵称" min-width="120" show-overflow-tooltip />
        <el-table-column label="邮箱" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.email || '—' }}</template>
        </el-table-column>
        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <template v-if="row.roles?.length">
              <el-tag
                v-for="role in row.roles"
                :key="role.id"
                size="small"
                effect="plain"
                class="mr-1"
              >
                {{ role.name }}
              </el-tag>
            </template>
            <span v-else class="text-[var(--el-text-color-secondary)]">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small" effect="light">
              {{ enabledStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后登录" width="170">
          <template #default="{ row }">{{ formatDateTime(row.lastLoginTime) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="270" align="right" fixed="right">
          <template #default="{ row }">
            <el-button v-perm="'admin:user:update'" link type="primary" @click="openEdit(row as UserResponse)">
              编辑
            </el-button>
            <el-button
              v-perm="'admin:user:assign-role'"
              link
              type="primary"
              :disabled="row.root"
              title="超管角色不可调整"
              @click="openAssign(row as UserResponse)"
            >
              分配角色
            </el-button>
            <el-button
              v-perm="'admin:user:reset-password'"
              link
              type="warning"
              :disabled="row.root"
              title="超管账号不可重置密码"
              @click="openReset(row as UserResponse)"
            >
              重置密码
            </el-button>
            <el-button
              v-perm="'admin:user:delete'"
              link
              type="danger"
              :disabled="row.root"
              title="超管账号不可删除"
              @click="remove(row as UserResponse)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-4 flex justify-end">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @change="load"
        />
      </div>
    </el-card>

    <UserFormDialog v-model="formVisible" :user="editingUser" @saved="load" />
    <AssignRolesDialog v-if="assignTarget" v-model="assignVisible" :user="assignTarget" @saved="load" />
    <ResetPasswordDialog v-if="resetTarget" v-model="resetVisible" :user="resetTarget" />
  </div>
</template>
