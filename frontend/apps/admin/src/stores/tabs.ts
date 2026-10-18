import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 页签项：path 为唯一键（与路由 path 一致） */
export interface TabItem {
  path: string
  title: string
  /** 固定页签（首页），不可关闭 */
  affix?: boolean
}

const HOME_TAB: TabItem = { path: '/dashboard', title: '首页', affix: true }

/** 多标签页（页签栏）状态：随本次会话存续，不做持久化 */
export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<TabItem[]>([{ ...HOME_TAB }])

  function open(tab: TabItem): void {
    const exists = tabs.value.find((item) => item.path === tab.path)
    if (exists) {
      if (exists.title !== tab.title) {
        exists.title = tab.title
      }
      return
    }
    tabs.value.push(tab)
  }

  /** 关闭页签；返回关闭后应激活的页签路径（固定页签不可关闭，返回 undefined） */
  function close(path: string): string | undefined {
    const index = tabs.value.findIndex((item) => item.path === path)
    if (index === -1) return undefined
    if (tabs.value[index]?.affix) return undefined
    tabs.value.splice(index, 1)
    return tabs.value[Math.min(index, tabs.value.length - 1)]?.path
  }

  function closeOthers(path: string): void {
    tabs.value = tabs.value.filter((item) => item.affix || item.path === path)
  }

  function closeAll(): void {
    tabs.value = tabs.value.filter((item) => item.affix)
  }

  return { tabs, open, close, closeOthers, closeAll }
})
