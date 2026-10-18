import { computed, ref } from 'vue'

/**
 * 暗色主题（全应用唯一来源）：
 *
 * - `html.dark` 类驱动 Element Plus 暗色变量（theme-chalk/dark/css-vars.css）；
 * - 首次访问跟随系统偏好，用户切换后写入 localStorage 覆盖；
 * - index.html 有一段同逻辑的内联脚本，在样式加载前先行应用，避免刷新闪白。
 */
export type ThemeMode = 'light' | 'dark'

const STORAGE_KEY = 'sn.admin.theme'

const mode = ref<ThemeMode>(resolveInitialTheme())

export function resolveInitialTheme(): ThemeMode {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (stored === 'light' || stored === 'dark') return stored
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function apply(mode: ThemeMode): void {
  document.documentElement.classList.toggle('dark', mode === 'dark')
  document.documentElement.style.colorScheme = mode
}

/** 在 app 挂载前调用，保证首屏与 index.html 内联脚本结果一致 */
export function initTheme(): void {
  apply(mode.value)
}

export function useTheme() {
  const isDark = computed(() => mode.value === 'dark')

  function setTheme(next: ThemeMode): void {
    mode.value = next
    localStorage.setItem(STORAGE_KEY, next)
    apply(next)
  }

  function toggleTheme(): void {
    setTheme(isDark.value ? 'light' : 'dark')
  }

  return { mode, isDark, setTheme, toggleTheme }
}
