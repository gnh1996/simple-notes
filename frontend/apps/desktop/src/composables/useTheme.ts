import { computed, ref } from 'vue'

import { Service as SystemService } from '../../bindings/cc.naihao/simplenotes/desktop/internal/system'

/**
 * 桌面端主题（全应用唯一来源）：
 *
 * - `html.dark` / `html.light` 类驱动 `styles/main.css` 的令牌与 Vditor 主题；
 * - 默认跟随系统，用户在账户菜单选择后写入 localStorage 覆盖；
 * - `index.html` 有一段同逻辑的内联脚本，在样式生效前先应用，避免首帧闪白。
 */
export type ThemeMode = 'system' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'sn.desktop.theme'

function storedMode(): ThemeMode | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value === 'light' || value === 'dark' || value === 'system' ? value : null
  } catch {
    return null
  }
}

function systemTheme(): ResolvedTheme {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

const mode = ref<ThemeMode>(storedMode() ?? 'system')
const resolved = ref<ResolvedTheme>(mode.value === 'system' ? systemTheme() : mode.value)

function apply(): void {
  resolved.value = mode.value === 'system' ? systemTheme() : mode.value
  document.documentElement.classList.toggle('dark', resolved.value === 'dark')
  document.documentElement.classList.toggle('light', resolved.value === 'light')
  document.documentElement.style.colorScheme = resolved.value
  syncWindowTheme(resolved.value === 'dark')
}

/** 原生标题栏 / 边框跟随应用主题；浏览器调试（无 Wails binding）时忽略。 */
function syncWindowTheme(dark: boolean): void {
  try {
    void SystemService.SetWindowTheme(dark).catch(() => {})
  } catch {
    /* pnpm dev:desktop 在浏览器里没有 Wails 运行时 */
  }
}

/** 在 app 挂载前调用，保证首屏与 index.html 内联脚本结果一致。 */
export function initTheme(): void {
  apply()
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (mode.value === 'system') {
      apply()
      return
    }
    // 手动模式下 Wails 仍会按新的系统主题重设原生标题栏；稍后重新下发应用主题。
    window.setTimeout(() => syncWindowTheme(resolved.value === 'dark'), 50)
  })
}

export function useTheme() {
  const isDark = computed(() => resolved.value === 'dark')

  function setMode(next: ThemeMode): void {
    mode.value = next
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // 隐私模式等拿不到 localStorage 时只影响持久化，不影响本次切换。
    }
    apply()
  }

  return { mode, isDark, setMode }
}
