<script setup lang="ts">
import { ArrowDown, Expand, Fold, Moon, Sunny, SwitchButton, User } from '@element-plus/icons-vue'
import { useBreakpoint } from '@simple-notes/shared'
import { ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useTheme } from '@/composables/useTheme'
import { useAuthStore } from '@/stores/auth'
import { APP_VERSION, fetchServerVersion } from '@/utils/version'

import AppBreadcrumb from './AppBreadcrumb.vue'

defineProps<{ collapsed: boolean }>()
const emit = defineEmits<{ toggleCollapse: []; openMenu: [] }>()

const router = useRouter()
const auth = useAuthStore()
const { isMobile } = useBreakpoint()
const { isDark, toggleTheme } = useTheme()

const avatarText = computed(() => auth.displayName.slice(0, 1).toUpperCase() || 'U')

const serverVersion = ref('')

onMounted(async () => {
  const info = await fetchServerVersion()
  serverVersion.value = info?.version ?? ''
})

async function onCommand(command: string): Promise<void> {
  if (command === 'profile') {
    await router.push({ name: 'Profile' })
    return
  }
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '退出登录', { type: 'warning' })
    } catch {
      return
    }
    await auth.logout()
    await router.replace({ name: 'Login' })
  }
}
</script>

<template>
  <header
    class="flex h-14 shrink-0 items-center justify-between gap-2 border-b border-[var(--el-border-color-lighter)] bg-[var(--el-bg-color)] px-3"
  >
    <div class="flex min-w-0 items-center gap-2">
      <el-icon
        class="cursor-pointer text-lg text-[var(--el-text-color-regular)] hover:text-[var(--el-color-primary)]"
        @click="isMobile ? emit('openMenu') : emit('toggleCollapse')"
      >
        <Expand v-if="isMobile || collapsed" />
        <Fold v-else />
      </el-icon>
      <AppBreadcrumb v-if="!isMobile" class="min-w-0" />
    </div>

    <div class="flex shrink-0 items-center gap-2">
      <el-tooltip :content="isDark ? '切换到浅色模式' : '切换到深色模式'" placement="bottom">
        <el-icon
          class="cursor-pointer text-lg text-[var(--el-text-color-regular)] hover:text-[var(--el-color-primary)]"
          @click="toggleTheme"
        >
          <Sunny v-if="isDark" />
          <Moon v-else />
        </el-icon>
      </el-tooltip>

      <el-dropdown trigger="click" @command="onCommand">
        <div class="flex cursor-pointer items-center gap-2 rounded px-1.5 py-1 hover:bg-[var(--el-fill-color-light)]">
          <el-avatar :size="28" class="shrink-0 bg-[var(--el-color-primary)] text-sm">
            {{ avatarText }}
          </el-avatar>
          <span class="hidden max-w-32 truncate text-sm text-[var(--el-text-color-primary)] sm:inline">
            {{ auth.displayName }}
          </span>
          <el-icon class="text-xs text-[var(--el-text-color-secondary)]"><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">
              <el-icon><User /></el-icon>个人中心
            </el-dropdown-item>
            <el-dropdown-item command="logout" divided>
              <el-icon><SwitchButton /></el-icon>退出登录
            </el-dropdown-item>
            <div class="px-4 py-1 text-xs text-[var(--el-text-color-secondary)]">
              管理端 v{{ APP_VERSION }} · 服务端 {{ serverVersion ? `v${serverVersion}` : '未知' }}
            </div>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>
