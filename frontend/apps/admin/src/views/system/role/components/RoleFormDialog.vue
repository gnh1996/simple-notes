<script setup lang="ts">
import type { RoleResponse } from '@simple-notes/api'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { roleApi } from '@/api'
import { notifyError } from '@/utils/feedback'

/** 新建角色（角色码创建后不可改） / 编辑角色（名称 / 描述 / 排序 / 状态） */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ role?: RoleResponse | null }>()
const emit = defineEmits<{ saved: [] }>()

const isEdit = computed(() => Boolean(props.role))

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ code: '', name: '', description: '', sort: 0, status: 1 })

const rules = computed<FormRules>(() => ({
  code: isEdit.value
    ? []
    : [
        { required: true, message: '请输入角色码', trigger: 'blur' },
        {
          pattern: /^[A-Z][A-Z0-9_]*$/,
          message: '大写字母 / 数字 / 下划线，且以字母开头',
          trigger: 'blur',
        },
      ],
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
}))

watch(
  visible,
  (open) => {
    if (!open) return
    form.code = props.role?.code ?? ''
    form.name = props.role?.name ?? ''
    form.description = props.role?.description ?? ''
    form.sort = props.role?.sort ?? 0
    form.status = props.role?.status ?? 1
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
    if (isEdit.value && props.role) {
      await roleApi.update(props.role.id, {
        name: form.name,
        description: form.description.trim() || undefined,
        sort: form.sort,
        status: form.status,
      })
      ElMessage.success('角色已更新')
    } else {
      await roleApi.create({
        code: form.code,
        name: form.name,
        description: form.description.trim() || undefined,
        sort: form.sort,
      })
      ElMessage.success('角色已创建，请按需为其授权')
    }
    visible.value = false
    emit('saved')
  } catch (error) {
    notifyError(error, isEdit.value ? '更新失败' : '创建失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑角色' : '新建角色'"
    width="480px"
    :close-on-click-modal="false"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item v-if="!isEdit" label="角色码" prop="code">
        <el-input v-model="form.code" placeholder="如 CREATOR（创建后不可修改）" />
      </el-form-item>
      <el-form-item v-else label="角色码">
        <el-input :model-value="form.code" disabled />
      </el-form-item>

      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" placeholder="如 联合创作人" />
      </el-form-item>

      <el-form-item label="描述" prop="description">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          maxlength="200"
          show-word-limit
          placeholder="可选"
        />
      </el-form-item>

      <el-form-item label="排序">
        <el-input-number v-model="form.sort" :min="0" :max="9999" />
        <el-text size="small" type="info" class="ml-3">数字小的在前</el-text>
      </el-form-item>

      <el-form-item v-if="isEdit" label="状态">
        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        <el-text v-if="form.status === 0" size="small" type="warning" class="ml-3">
          停用后该角色的授权立即不生效
        </el-text>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ isEdit ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>
