<script setup lang="ts">
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { authApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { notifyError } from '@/utils/feedback'

const router = useRouter()
const auth = useAuthStore()

const activeTab = ref('basic')

// ---- 基本设置（昵称可改；用户名 / 邮箱 v1 不支持自助修改） ----
const profileForm = reactive({ nickname: '' })
const savingProfile = ref(false)

watch(
  () => auth.user?.nickname,
  (nickname) => {
    profileForm.nickname = nickname ?? ''
  },
  { immediate: true },
)

async function saveProfile(): Promise<void> {
  if (!profileForm.nickname.trim()) {
    ElMessage.warning('昵称不能为空')
    return
  }
  savingProfile.value = true
  try {
    await authApi.updateProfile({ nickname: profileForm.nickname.trim() })
    await auth.fetchMe()
    ElMessage.success('昵称已更新')
  } catch (error) {
    notifyError(error, '更新失败')
  } finally {
    savingProfile.value = false
  }
}

// ---- 修改密码（需旧密码；成功后撤销该用户除当前外的全部会话） ----
const passwordFormRef = ref<FormInstance>()
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const changingPassword = ref(false)

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度需为 8-64 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

async function changePassword(): Promise<void> {
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid) return

  changingPassword.value = true
  try {
    await authApi.changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword,
    })
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordFormRef.value?.clearValidate()
    ElMessage.success('密码已修改，其他设备的会话已失效')
  } catch (error) {
    notifyError(error, '修改密码失败')
  } finally {
    changingPassword.value = false
  }
}

// ---- 会话安全 ----
const loggingOutAll = ref(false)

async function logoutAll(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将撤销该账号的全部登录会话（所有端、全部设备，含当前），确定继续吗？',
      '退出所有设备',
      { type: 'warning', confirmButtonText: '全部退出', confirmButtonClass: 'el-button--danger' },
    )
  } catch {
    return
  }
  loggingOutAll.value = true
  try {
    await auth.logoutAll()
    ElMessage.success('已退出所有设备')
    await router.replace({ name: 'Login' })
  } catch (error) {
    notifyError(error, '操作失败')
  } finally {
    loggingOutAll.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-3xl space-y-4">
    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="基本设置" name="basic">
          <el-form label-width="80px" class="mt-2 max-w-md">
            <el-form-item label="用户名">
              <el-input :model-value="auth.user?.username ?? ''" disabled />
            </el-form-item>
            <el-form-item label="昵称">
              <el-input v-model="profileForm.nickname" maxlength="32" placeholder="显示名" />
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input :model-value="auth.user?.email ?? ''" disabled placeholder="未设置" />
            </el-form-item>
            <el-form-item label="角色">
              <template v-if="auth.roles.length">
                <el-tag
                  v-for="role in auth.roles"
                  :key="role.id"
                  size="small"
                  effect="plain"
                  class="mr-1"
                >
                  {{ role.name }}
                </el-tag>
              </template>
              <span v-else class="text-sm text-[var(--el-text-color-secondary)]">无角色</span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingProfile" @click="saveProfile">
                保存昵称
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="修改密码" name="password">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="修改成功后，该账号在其他设备的登录会话将全部失效"
            class="my-3"
          />
          <el-form
            ref="passwordFormRef"
            :model="passwordForm"
            :rules="passwordRules"
            label-width="80px"
            class="max-w-md"
          >
            <el-form-item label="当前密码" prop="oldPassword">
              <el-input
                v-model="passwordForm.oldPassword"
                type="password"
                show-password
                autocomplete="current-password"
              />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input
                v-model="passwordForm.newPassword"
                type="password"
                show-password
                placeholder="8-64 位"
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input
                v-model="passwordForm.confirmPassword"
                type="password"
                show-password
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="changingPassword" @click="changePassword">
                修改密码
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="会话安全" name="session">
          <div class="mt-2 space-y-3">
            <p class="text-sm text-[var(--el-text-color-secondary)]">
              凭证（JWT）由服务端会话记录承载，可随时撤销。若怀疑账号泄露，请先修改密码，再退出所有设备。
            </p>
            <el-button type="danger" plain :loading="loggingOutAll" @click="logoutAll">
              退出所有设备
            </el-button>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
