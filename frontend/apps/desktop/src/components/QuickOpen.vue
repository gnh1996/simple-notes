<script setup lang="ts">
import type { QuickAction, QuickItem } from '../types/quick'
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps<{
  open: boolean
  items: QuickItem[]
  actions: QuickAction[]
  session: boolean
}>()

const emit = defineEmits<{
  close: []
  select: [item: QuickItem]
  action: [id: QuickAction['id']]
}>()

const query = ref('')
const input = ref<HTMLInputElement>()
const cursor = ref(0)

const filtered = computed(() => {
  const value = query.value.trim().toLowerCase()
  if (!value) {
    return props.items.slice(0, 12)
  }
  return props.items
    .filter((item) => `${item.title} ${item.subtitle}`.toLowerCase().includes(value))
    .slice(0, 20)
})

const actions = computed(() => {
  const value = query.value.trim().toLowerCase()
  if (!value) {
    return props.actions.filter((action) => action.id !== 'login' || !props.session)
  }
  return props.actions
    .filter((action) => `${action.title} ${action.hint ?? ''}`.toLowerCase().includes(value))
    .filter((action) => action.id !== 'login' || !props.session)
})

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      return
    }
    query.value = ''
    cursor.value = 0
    await nextTick()
    input.value?.focus()
  },
)

watch(filtered, () => {
  cursor.value = 0
})

function choose(index: number): void {
  const item = filtered.value[index]
  if (item) {
    emit('select', item)
  }
}

function onKeydown(event: KeyboardEvent): void {
  const total = filtered.value.length + actions.value.length
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
    return
  }
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    cursor.value = total ? (cursor.value + 1) % total : 0
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    cursor.value = total ? (cursor.value - 1 + total) % total : 0
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    if (cursor.value < filtered.value.length) {
      choose(cursor.value)
      return
    }
    const action = actions.value[cursor.value - filtered.value.length]
    if (action) {
      emit('action', action.id)
    }
  }
}

function pick(index: number): number {
  return index
}

function shortTime(value: number): string {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(
    date.getMinutes(),
  ).padStart(2, '0')}`
}
</script>

<template>
  <div v-if="open" class="fixed inset-0 z-50 flex items-start justify-center bg-black/50 px-4 pt-[14vh]" @click.self="emit('close')">
    <div class="w-full max-w-2xl overflow-hidden rounded-xl border border-app-border bg-app-panel shadow-2xl">
      <div class="flex items-center gap-2 border-b border-app-border px-3">
        <span class="text-app-muted">⌕</span>
        <input
          ref="input"
          v-model="query"
          class="h-11 min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-app-muted"
          placeholder="搜索标题、文件名或操作…"
          spellcheck="false"
          @keydown="onKeydown"
        />
        <kbd class="rounded border border-app-border px-1 text-[10px] text-app-muted">Esc</kbd>
      </div>

      <div class="max-h-[60vh] overflow-y-auto p-1.5">
        <template v-if="filtered.length">
          <p class="px-2 py-1 text-[10px] uppercase tracking-wide text-app-muted">文档</p>
          <button
            v-for="(item, index) in filtered"
            :key="item.key"
            class="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs"
            :class="cursor === pick(index) ? 'bg-brand-500/10 text-brand-400' : 'hover:bg-app-bg'"
            type="button"
            @mouseenter="cursor = pick(index)"
            @click="emit('select', item)"
          >
            <span
              class="h-1.5 w-1.5 shrink-0 rounded-full"
              :class="item.source === 'file' ? 'bg-amber-400' : item.source === 'local' ? 'bg-violet-400' : 'bg-zinc-400'"
            />
            <span class="min-w-0 flex-1 truncate">{{ item.title }}</span>
            <span class="shrink-0 text-[10px] text-app-muted">{{ item.subtitle }}</span>
            <span class="shrink-0 text-[10px] text-app-muted">{{ shortTime(item.updatedAt) }}</span>
            <span v-if="item.opened" class="shrink-0 rounded bg-app-bg px-1 text-[10px] text-app-muted">已打开</span>
          </button>
        </template>

        <template v-if="actions.length">
          <p class="px-2 py-1 text-[10px] uppercase tracking-wide text-app-muted">操作</p>
          <button
            v-for="(action, index) in actions"
            :key="action.id"
            class="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs"
            :class="cursor === filtered.length + index ? 'bg-brand-500/10 text-brand-400' : 'hover:bg-app-bg'"
            type="button"
            @mouseenter="cursor = filtered.length + index"
            @click="emit('action', action.id)"
          >
            <span class="min-w-0 flex-1 truncate">{{ action.title }}</span>
            <span v-if="action.hint" class="shrink-0 text-[10px] text-app-muted">{{ action.hint }}</span>
          </button>
        </template>

        <p v-if="!filtered.length && !actions.length" class="px-3 py-8 text-center text-xs text-app-muted">
          没有匹配的文档或操作。
        </p>
      </div>
    </div>
  </div>
</template>
