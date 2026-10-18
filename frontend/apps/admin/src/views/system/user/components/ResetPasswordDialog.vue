<script setup lang="ts">
import type { UserResponse } from '@simple-notes/api'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import { userApi } from '@/api'
import { notifyError } from '@/utils/feedback'

/** 重置密码（成功后该用户全部会话被撤销） */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ user: UserResponse }>()
const emit = defineEmits<{ saved: [] }>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ newPassword: '', confirmPassword: '' })

const rules: FormRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度需为 8-64 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

watch(
  visible,
  (open) => {
    if (!open) return
    form.newPassword = ''
    form.confirmPassword = ''
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
    await userApi.resetPassword(props.user.id, { newPassword: form.newPassword })
    ElMessage.success('密码已重置，该用户全部会话已失效')
    visible.value = false
    emit('saved')
  } catch (error) {
    notifyError(error, '重置密码失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`重置密码 · ${user.nickname}`"
    width="440px"
    :close-on-click-modal="false"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="重置后该用户的全部登录会话将立即失效"
      class="mb-4"
    />
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="新密码" prop="newPassword">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="8-64 位"
        />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="再次输入"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">重置</el-button>
    </template>
  </el-dialog>
</template>
