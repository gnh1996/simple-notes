<script setup lang="ts">
import type { UserResponse } from '@simple-notes/api'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { userApi } from '@/api'
import { notifyError } from '@/utils/feedback'

/** 新建用户（登录名 + 初始密码 + 昵称 + 邮箱；角色另行分配） / 编辑用户（昵称 / 邮箱 / 状态） */const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ user?: UserResponse | null }>()
const emit = defineEmits<{ saved: [] }>()

const isEdit = computed(() => Boolean(props.user))
const isRootUser = computed(() => Boolean(props.user?.root))

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  username: '',
  password: '',
  nickname: '',
  email: '',
  status: 1,
})

const rules = computed<FormRules>(() => ({
  username: isEdit.value
    ? []
    : [
        { required: true, message: '请输入用户名', trigger: 'blur' },
        {
          pattern: /^[a-zA-Z0-9][a-zA-Z0-9_-]{2,31}$/,
          message: '3-32 位字母 / 数字 / 下划线 / 中划线，且以字母或数字开头',
          trigger: 'blur',
        },
      ],
  password: isEdit.value
    ? []
    : [
        { required: true, message: '请输入初始密码', trigger: 'blur' },
        { min: 8, max: 64, message: '密码长度需为 8-64 位', trigger: 'blur' },
      ],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}))

watch(
  visible,
  (open) => {
    if (!open) return
    form.username = props.user?.username ?? ''
    form.password = ''
    form.nickname = props.user?.nickname ?? ''
    form.email = props.user?.email ?? ''
    form.status = props.user?.status ?? 1
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
    if (isEdit.value && props.user) {
      await userApi.update(props.user.id, {
        nickname: form.nickname,
        email: form.email.trim() || undefined,
        status: form.status,
      })
      ElMessage.success('用户已更新')
    } else {
      await userApi.create({
        username: form.username,
        password: form.password,
        nickname: form.nickname,
        email: form.email.trim() || undefined,
      })
      ElMessage.success('用户已创建，请按需为其分配角色')
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
    :title="isEdit ? '编辑用户' : '新建用户'"
    width="480px"
    :close-on-click-modal="false"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item v-if="!isEdit" label="用户名" prop="username">
        <el-input
          v-model="form.username"
          autocomplete="off"
          placeholder="登录名（小写字母 / 数字 / _ / -）"
        />
      </el-form-item>
      <el-form-item v-else label="用户名">
        <el-input :model-value="form.username" disabled />
      </el-form-item>

      <el-form-item v-if="!isEdit" label="初始密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="8-64 位"
        />
      </el-form-item>

      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="form.nickname" placeholder="显示名" />
      </el-form-item>

      <el-form-item label="邮箱" prop="email">
        <el-input v-model="form.email" placeholder="可选" />
      </el-form-item>

      <el-form-item v-if="isEdit" label="状态">
        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" :disabled="isRootUser" />
        <el-text v-if="isRootUser" size="small" type="info" class="ml-3">
          超管账号不可禁用
        </el-text>
        <el-text
          v-else-if="form.status === 0"
          size="small"
          type="warning"
          class="ml-3"
        >
          禁用后该用户全部会话将立即失效
        </el-text>
      </el-form-item>

      <el-form-item v-if="!isEdit" label="角色">
        <el-text size="small" type="info">角色在创建后通过「分配角色」单独设置</el-text>
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
