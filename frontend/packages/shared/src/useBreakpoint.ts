import { computed, getCurrentScope, onScopeDispose, ref, type Ref } from 'vue'

/** 全项目统一的断点定义（px），与 Tailwind 默认断点对齐 */
export const BREAKPOINTS = {
  md: 768,
  lg: 1024,
} as const

function useMediaQuery(query: string): Ref<boolean> {
  const mql = window.matchMedia(query)
  const matches = ref(mql.matches)

  const onChange = (event: MediaQueryListEvent) => {
    matches.value = event.matches
  }
  mql.addEventListener('change', onChange)

  // 在 effect scope（组件 setup）内使用时自动清理监听
  if (getCurrentScope()) {
    onScopeDispose(() => mql.removeEventListener('change', onChange))
  }

  return matches
}

export interface BreakpointState {
  /** 视口宽度 < 768px */
  isMobile: Ref<boolean>
  /** 768px <= 视口宽度 < 1024px */
  isTablet: Ref<boolean>
  /** 视口宽度 >= 1024px */
  isDesktop: Ref<boolean>
}

/**
 * 基于**视口宽度**的断点判断 —— 全项目唯一的“移动端 / 桌面端”判断来源。
 *
 * 不要使用 UA / 设备类型来判断：平板、折叠屏、可拖拽缩放的桌面窗口都会误判。
 * 推荐用法：同一路由 + 共享逻辑（store / composable / api），只按断点分叉展示层：
 *
 * ```vue
 * <DesktopTable v-if="!isMobile" :data="notes" />
 * <MobileCardList v-else :data="notes" />
 * ```
 */
export function useBreakpoint(): BreakpointState {
  const isMobile = useMediaQuery(`(max-width: ${BREAKPOINTS.md - 1}px)`)
  const isDesktop = useMediaQuery(`(min-width: ${BREAKPOINTS.lg}px)`)
  const isTablet = computed(() => !isMobile.value && !isDesktop.value)

  return { isMobile, isTablet, isDesktop }
}
