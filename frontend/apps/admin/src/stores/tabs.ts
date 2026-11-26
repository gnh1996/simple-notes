import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 页签项：path 为唯一键（与路由 path 一致） */
export interface TabItem {
  path: string
  title: string
  /** 固定页签（首页），不可关闭 */
  affix?: boolean
  /** 标题由页面回填（文档标题），打开页签时不再被路由 meta.title 覆盖 */
  customTitle?: boolean
}

/** 关闭操作结果：调用方据此决定是否需要跳转 */
export interface TabCloseResult {
  /** 是否真的关闭了页签（固定页签 / 关闭前拦截时为 false） */
  closed: boolean
  /** 关闭后若原激活页已不在，建议跳转的路径 */
  next?: string
}

/** 关闭前拦截：返回 false 取消本次关闭（如未保存内容） */
export type CloseGuard = () => boolean | Promise<boolean>

const HOME_TAB: TabItem = { path: '/dashboard', title: '首页', affix: true }

/** 多标签页（页签栏）状态：随本次会话存续，不做持久化 */
export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<TabItem[]>([{ ...HOME_TAB }])
  /** path → 关闭拦截器（由当前挂载的页面注册，不参与渲染因此无需响应式） */
  const guards = new Map<string, CloseGuard>()

  function open(tab: TabItem): void {
    const exists = tabs.value.find((item) => item.path === tab.path)
    if (!exists) {
      tabs.value.push(tab)
      return
    }
    // 页面回填过文档标题时保留，避免每次进入都把页签改回「编辑文章」
    if (!exists.customTitle && exists.title !== tab.title) {
      exists.title = tab.title
    }
  }

  /** 页面回填页签标题（打开的文章 / 笔记标题），用于区分同名的编辑页签 */
  function setTitle(path: string, title: string): void {
    const tab = tabs.value.find((item) => item.path === path)
    const next = title.trim()
    if (!tab || !next) return
    tab.title = next
    tab.customTitle = true
  }

  /** 新建内容保存后 URL 变化：原位改写页签，避免留下 /xxx/new 幽灵页签 */
  function replacePath(from: string, to: string, title?: string): void {
    if (from === to) {
      if (title) setTitle(to, title)
      return
    }
    const fromIndex = tabs.value.findIndex((item) => item.path === from)
    if (fromIndex === -1) {
      if (title) setTitle(to, title)
      return
    }
    const toIndex = tabs.value.findIndex((item) => item.path === to)
    if (toIndex !== -1) {
      if (title) setTitle(to, title)
      tabs.value.splice(fromIndex, 1)
      return
    }
    const tab = tabs.value[fromIndex]
    if (!tab) return
    tab.path = to
    const next = title?.trim()
    if (next) {
      tab.title = next
      tab.customTitle = true
    }
  }

  /** 注册关闭前拦截器；返回注销函数（页面卸载时必须调用） */
  function registerCloseGuard(path: string, guard: CloseGuard): () => void {
    guards.set(path, guard)
    return () => {
      if (guards.get(path) === guard) guards.delete(path)
    }
  }

  async function canClose(paths: string[]): Promise<boolean> {
    for (const path of paths) {
      const guard = guards.get(path)
      if (guard && !(await guard())) return false
    }
    return true
  }

  function closablePaths(): string[] {
    return tabs.value.filter((item) => !item.affix).map((item) => item.path)
  }

  /** 关闭页签；返回关闭后应激活的页签路径（固定页签不可关闭，返回 closed=false） */
  async function close(path: string): Promise<TabCloseResult> {
    const index = tabs.value.findIndex((item) => item.path === path)
    const tab = tabs.value[index]
    if (!tab || tab.affix) return { closed: false }
    if (!(await canClose([path]))) return { closed: false }
    const currentIndex = tabs.value.findIndex((item) => item.path === path)
    if (currentIndex === -1) return { closed: false }
    tabs.value.splice(currentIndex, 1)
    return {
      closed: true,
      next: tabs.value[Math.min(currentIndex, tabs.value.length - 1)]?.path,
    }
  }

  async function closeOthers(path: string): Promise<TabCloseResult> {
    const targets = closablePaths().filter((item) => item !== path)
    if (targets.length === 0) return { closed: false }
    if (!(await canClose(targets))) return { closed: false }
    tabs.value = tabs.value.filter((item) => item.affix || item.path === path)
    return { closed: true, next: path }
  }

  async function closeRight(path: string): Promise<TabCloseResult> {
    const index = tabs.value.findIndex((item) => item.path === path)
    if (index === -1) return { closed: false }
    const targets = tabs.value
      .slice(index + 1)
      .filter((item) => !item.affix)
      .map((item) => item.path)
    if (targets.length === 0) return { closed: false }
    if (!(await canClose(targets))) return { closed: false }
    const currentIndex = tabs.value.findIndex((item) => item.path === path)
    if (currentIndex === -1) return { closed: false }
    tabs.value.splice(currentIndex + 1)
    return { closed: true, next: path }
  }

  async function closeAll(): Promise<TabCloseResult> {
    const targets = closablePaths()
    if (targets.length === 0) return { closed: false }
    if (!(await canClose(targets))) return { closed: false }
    tabs.value = tabs.value.filter((item) => item.affix)
    return { closed: true }
  }

  return {
    tabs,
    open,
    setTitle,
    replacePath,
    registerCloseGuard,
    close,
    closeOthers,
    closeRight,
    closeAll,
  }
})
