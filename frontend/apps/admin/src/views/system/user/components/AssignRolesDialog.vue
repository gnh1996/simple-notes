<script setup lang="ts">
import type { RoleBrief, UserResponse } from '@simple-notes/api'
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'

import { roleApi, userApi } from '@/api'
import { notifyError } from '@/utils/feedback'

/** 分配角色（全量替换；选项来自 /roles/assignable，服务端已排除内置角色） */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ user: UserResponse }>()
const emit = defineEmits<{ saved: [] }>()

const loading = ref(false)
const submitting = ref(false)
const options = ref<RoleBrief[]>([])
const selected = ref<string[]>([])

/** 用户现有角色中不在可分配列表里的（内置角色，如超管），仅展示、不可编辑 */
const lockedRoles = computed(() =>
  (props.user.roles ?? []).filter((role) => !options.value.some((item) => item.id === role.id)),
)

watch(
  visible,
  async (open) => {
    if (!open) return
    loading.value = true
    try {
      options.value = (await roleApi.assignable()) ?? []
      selected.value = (props.user.roles ?? [])
        .filter((role) => options.value.some((item) => item.id === role.id))
        .map((role) => role.id)
    } catch (error) {
      notifyError(error, '可分配角色加载失败')
      visible.value = false
    } finally {
      loading.value = false
    }
  },
  // v-if 挂载的弹窗在首次打开时 visible 已为 true：立即执行一次，保证数据加载
  { immediate: true },
)

async function submit(): Promise<void> {
  submitting.value = true
  try {
    await userApi.assignRoles(props.user.id, { roleIds: selected.value })
    ElMessage.success('角色已更新，下一次请求即生效')
    visible.value = false
    emit('saved')
  } catch (error) {
    notifyError(error, '分配角色失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`分配角色 · ${user.nickname}`"
    width="480px"
    :close-on-click-modal="false"
  >
    <div v-loading="loading" class="min-h-24">
      <el-checkbox-group v-if="options.length" v-model="selected" class="flex flex-col gap-3">
        <el-checkbox
          v-for="role in options"
          :key="role.id"
          :value="role.id"
          class="!mr-0 h-auto"
        >
          <span class="font-medium">{{ role.name }}</span>
          <el-text size="small" type="info" class="ml-2">{{ role.code }}</el-text>
        </el-checkbox>
      </el-checkbox-group>
      <el-empty v-else-if="!loading" description="暂无可分配的角色" :image-size="64" />

      <div v-if="lockedRoles.length" class="mt-4 rounded bg-[var(--el-fill-color-light)] p-3">
        <el-text size="small" type="info">
          内置角色不可调整：{{ lockedRoles.map((role) => role.name).join('、') }}
        </el-text>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>
