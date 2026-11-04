<script setup lang="ts">
import { Service as AuthService } from '../../bindings/cc.naihao/simplenotes/desktop/internal/auth'
import type { Session } from '../../bindings/cc.naihao/simplenotes/desktop/internal/auth/models'
import { describeError } from '../lib/errors'
import { logError } from '../lib/logger'
import { ref } from 'vue'

const emit = defineEmits<{
  'logged-in': [session: Session]
  cancel: []
}>()

const serverUrl = ref('http://localhost:8080')
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

async function submit(): Promise<void> {
  if (busy.value) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    const session = await AuthService.Login({
      serverUrl: serverUrl.value,
      username: username.value,
      password: password.value,
    })
    if (!session) {
      error.value = '登录失败：未返回会话'
      return
    }
    emit('logged-in', session)
  } catch (err) {
    const info = describeError(err, '登录失败')
    logError('auth.login', err, info.message)
    error.value = info.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="w-full text-app-fg">
    <form
      class="w-full max-w-sm rounded-xl border border-app-border bg-app-panel p-6 shadow-sm"
      @submit.prevent="submit"
    >
      <h1 class="text-lg font-semibold">登录并同步</h1>
      <p class="mt-1 text-xs text-app-muted">
        登录后只同步索引（标题 / 文件夹），打开某篇才拉取正文；本地笔记与文件不受影响。
      </p>

      <label class="mt-5 block text-xs text-app-muted">
        服务器地址
        <input
          v-model="serverUrl"
          class="mt-1 w-full rounded border border-app-border bg-app-bg px-3 py-2 text-sm text-app-fg outline-none focus:border-brand-400"
          placeholder="http://localhost:8080"
          autocomplete="off"
          spellcheck="false"
        />
      </label>

      <label class="mt-3 block text-xs text-app-muted">
        用户名
        <input
          v-model="username"
          class="mt-1 w-full rounded border border-app-border bg-app-bg px-3 py-2 text-sm text-app-fg outline-none focus:border-brand-400"
          autocomplete="username"
        />
      </label>

      <label class="mt-3 block text-xs text-app-muted">
        密码
        <input
          v-model="password"
          type="password"
          class="mt-1 w-full rounded border border-app-border bg-app-bg px-3 py-2 text-sm text-app-fg outline-none focus:border-brand-400"
          autocomplete="current-password"
        />
      </label>

      <button
        class="mt-5 w-full rounded bg-brand-500 px-3 py-2 text-sm font-medium text-white hover:bg-brand-400 disabled:opacity-60"
        type="submit"
        :disabled="busy"
      >
        {{ busy ? '登录中…' : '登录' }}
      </button>

      <p v-if="error" class="mt-3 text-xs text-red-400">{{ error }}</p>

      <button
        class="mt-3 w-full rounded border border-app-border px-3 py-2 text-xs text-app-muted hover:bg-app-bg"
        type="button"
        @click="emit('cancel')"
      >
        继续使用本地模式
      </button>

      <p class="mt-4 text-[11px] leading-relaxed text-app-muted">
        地址可填 <code>http://host:port</code>，会自动补 <code>/api</code>；令牌只存本地
        SQLite，不会进入页面脚本。
      </p>
    </form>
  </div>
</template>
