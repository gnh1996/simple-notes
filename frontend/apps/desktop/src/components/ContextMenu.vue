<script setup lang="ts">
import type { ContextMenuItem } from '../types/context-menu'
import { nextTick, ref, watch } from 'vue'

/**
 * 通用右键菜单：Teleport 到 body，自动贴边、支持分隔 / 禁用 / 危险项。
 *
 * <p>菜单打开后焦点落在容器上，方向键在可用项之间移动，Esc 关闭。
 */
const props = defineProps<{
  open: boolean
  x: number
  y: number
  items: ContextMenuItem[]
  minWidth?: number
}>()

const emit = defineEmits<{
  select: [key: string]
  close: []
}>()

const box = ref<HTMLElement | null>(null)
const position = ref({ left: 0, top: 0 })
const ready = ref(false)

const EDGE = 8

function clamp(): void {
  const element = box.value
  if (!element) {
    return
  }
  const rect = element.getBoundingClientRect()
  const maxLeft = Math.max(EDGE, window.innerWidth - rect.width - EDGE)
  const maxTop = Math.max(EDGE, window.innerHeight - rect.height - EDGE)
  position.value = {
    left: Math.min(Math.max(EDGE, props.x), maxLeft),
    top: Math.min(Math.max(EDGE, props.y), maxTop),
  }
}

function move(step: number): void {
  const buttons = Array.from(box.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
  if (!buttons.length) {
    return
  }
  const current = document.activeElement
  const index = current instanceof HTMLButtonElement ? buttons.indexOf(current) : -1
  const next = index < 0 ? (step > 0 ? 0 : buttons.length - 1) : (index + step + buttons.length) % buttons.length
  buttons[next].focus()
}

function choose(item: ContextMenuItem): void {
  if (item.disabled || item.separator) {
    return
  }
  emit('select', item.key)
  emit('close')
}

watch(
  () => [props.open, props.x, props.y] as const,
  async ([open]) => {
    if (!open) {
      ready.value = false
      return
    }
    position.value = { left: props.x, top: props.y }
    ready.value = false
    await nextTick()
    clamp()
    ready.value = true
    box.value?.focus()
  },
  { immediate: true },
)

</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-[60]" @click="emit('close')" @contextmenu.prevent="emit('close')">
      <div
        ref="box"
        class="absolute rounded-lg border border-app-border bg-app-panel p-1 shadow-xl outline-none"
        :style="{
          left: `${position.left}px`,
          top: `${position.top}px`,
          minWidth: `${minWidth ?? 168}px`,
          visibility: ready ? 'visible' : 'hidden',
        }"
        role="menu"
        tabindex="-1"
        @click.stop
        @keydown.esc.stop.prevent="emit('close')"
        @keydown.down.prevent="move(1)"
        @keydown.up.prevent="move(-1)"
      >
        <template v-for="item in items" :key="item.key">
          <div v-if="item.separator" class="my-1 h-px bg-app-border" />
          <button
            v-else
            class="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs disabled:cursor-not-allowed disabled:opacity-40"
            :class="item.danger ? 'text-red-400 hover:bg-app-bg' : 'hover:bg-app-bg'"
            type="button"
            role="menuitem"
            :disabled="item.disabled"
            @click="choose(item)"
          >
            <span class="min-w-0 flex-1 truncate">{{ item.label }}</span>
            <span v-if="item.hint" class="shrink-0 text-[10px] text-app-muted">{{ item.hint }}</span>
          </button>
        </template>
      </div>
    </div>
  </Teleport>
</template>
