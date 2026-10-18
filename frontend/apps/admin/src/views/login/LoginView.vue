<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { rebuildMenuRoutes } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/feedback'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  try {
    await auth.login(form.username, form.password)
  } catch (error) {
    // 语义码对应文案由后端返回（凭据错误 / 账号禁用 / 无入口权限 / 限流）
    ElMessage.error(errorMessage(error, '登录失败'))
    return
  }

  try {
    await auth.fetchMe()
    rebuildMenuRoutes(auth.menus)
  } catch {
    // me 拉取失败不阻塞进入（守卫会再兜底），但正常路径不应发生
  }
  ElMessage.success('登录成功')
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  await router.replace(redirect)
}
</script>

<template>
  <div class="login-page relative flex min-h-screen items-center justify-center overflow-hidden p-4">
    <!-- 装饰光斑（品牌色） -->
    <div class="login-blob login-blob--a" />
    <div class="login-blob login-blob--b" />

    <div
      class="relative z-10 w-full max-w-[400px] rounded-2xl border border-[var(--el-border-color-lighter)] bg-[var(--el-bg-color)] p-8 shadow-2xl"
    >
      <div class="mb-8 flex flex-col items-center gap-3">
        <img src="/favicon.svg" alt="Simple Notes" class="h-12 w-12" />
        <div class="text-center">
          <h1 class="text-xl font-semibold text-[var(--el-text-color-primary)]">Simple Notes</h1>
          <p class="mt-1 text-sm text-[var(--el-text-color-secondary)]">管理后台</p>
        </div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent="submit">
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            :prefix-icon="User"
            autocomplete="username"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          class="w-full"
          :loading="auth.loginLoading"
          @click="submit"
        >
          登 录
        </el-button>
      </el-form>

      <p class="mt-6 text-center text-xs text-[var(--el-text-color-secondary)]">
        仅限授权用户访问 · 首次登录后请及时修改初始密码
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  background:
    radial-gradient(1200px 600px at 15% -10%, var(--color-brand-100), transparent 60%),
    radial-gradient(900px 500px at 110% 110%, var(--color-brand-200), transparent 55%),
    var(--el-bg-color-page);
}

html.dark .login-page {
  background:
    radial-gradient(1200px 600px at 15% -10%, color-mix(in oklab, var(--color-brand-800) 45%, transparent), transparent 60%),
    radial-gradient(900px 500px at 110% 110%, color-mix(in oklab, var(--color-brand-700) 35%, transparent), transparent 55%),
    var(--el-bg-color-page);
}

.login-blob {
  position: absolute;
  border-radius: 9999px;
  filter: blur(80px);
  opacity: 0.35;
  pointer-events: none;
}

.login-blob--a {
  width: 320px;
  height: 320px;
  top: -80px;
  left: -60px;
  background: var(--color-brand-400);
}

.login-blob--b {
  width: 360px;
  height: 360px;
  right: -100px;
  bottom: -120px;
  background: var(--color-brand-300);
}
</style>
