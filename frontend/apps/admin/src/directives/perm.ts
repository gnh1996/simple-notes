import type { App, Directive } from 'vue'

import { useAuthStore } from '@/stores/auth'

/**
 * 按钮 / 操作级权限指令（docs/auth-rbac.md §6.2）：`v-perm="'admin:user:create'"`。
 *
 * - 无码即移除 DOM（前端只负责"看得见"，后端接口为唯一终审）；
 * - 值为数组时语义为"任一"（对齐后端 `@authz.canAny`）；
 * - `root`（超管）自动放行，无需逐码声明。
 */
const perm: Directive<HTMLElement, string | readonly string[]> = {
  mounted(el, binding) {
    const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
    const auth = useAuthStore()
    if (!codes.some((code) => auth.can(code))) {
      el.remove()
    }
  },
}

export function setupDirectives(app: App): void {
  app.directive('perm', perm)
}
