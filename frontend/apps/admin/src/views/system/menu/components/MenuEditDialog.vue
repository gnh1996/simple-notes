<script setup lang="ts">
import type { MenuCatalogNode } from '@simple-notes/api'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import { menuApi } from '@/api'
import { notifyError } from '@/utils/feedback'

/** 编辑菜单节点：仅改名 / 调序 / 启停；结构性字段（码 / 端 / 类型 / 父节点）只读 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ node: MenuCatalogNode; parentName?: string }>()
const emit = defineEmits<{ saved: [] }>()

const TYPE_LABELS: Record<string, string> = { ENTRY: '入口', MENU: '菜单', ACTION: '操作' }
const CLIENT_LABELS: Record<string, string> = {
  ADMIN: '管理端',
  BLOG: '博客端',
  DESKTOP: '桌面端',
}

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ name: '', sort: 0, status: 1 })

const rules: FormRules = {
  name: [{ required: true, message: '请输入节点名称', trigger: 'blur' }],
}

watch(
  visible,
  (open) => {
    if (!open) return
    form.name = props.node.name ?? ''
    form.sort = props.node.sort ?? 0
    form.status = props.node.status ?? 1
    formRef.value?.clearValidate()
  },
  // v-if 挂载的弹窗在首次打开时 visible 已为 true：立即执行一次，保证数据加载
  { immediate: true },
)

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await menuApi.update(props.node.id, {
      name: form.name,
      sort: form.sort,
      status: form.status,
    })
    ElMessage.success('菜单节点已更新')
    visible.value = false
    emit('saved')
  } catch (error) {
    notifyError(error, '更新失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="编辑菜单节点" width="480px" :close-on-click-modal="false">
    <el-descriptions :column="1" size="small" border class="mb-4">
      <el-descriptions-item label="权限码">
        <span class="font-mono text-xs">{{ node.code }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="端 / 类型">
        {{ CLIENT_LABELS[node.client ?? ''] ?? node.client }} ·
        {{ TYPE_LABELS[node.type ?? ''] ?? node.type }}
      </el-descriptions-item>
      <el-descriptions-item label="父节点">{{ parentName || '无（端根）' }}</el-descriptions-item>
    </el-descriptions>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" maxlength="64" />
      </el-form-item>
      <el-form-item label="排序">
        <el-input-number v-model="form.sort" :min="0" :max="9999" />
        <el-text size="small" type="info" class="ml-3">同层内数字小的在前</el-text>
      </el-form-item>
      <el-form-item label="状态">
        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        <el-text v-if="form.status === 0" size="small" type="warning" class="ml-3">
          停用后不可勾选，已有授权运行时视同未授予
        </el-text>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>
