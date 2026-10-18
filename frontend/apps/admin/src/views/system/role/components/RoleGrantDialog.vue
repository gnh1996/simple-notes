<script setup lang="ts">
import type { ClientType, MenuCatalogNode, RoleResponse } from '@simple-notes/api'
import { ElMessage, ElTree, type TreeOptionProps } from 'element-plus'
import { computed, nextTick, ref, watch } from 'vue'

import { menuApi, roleApi } from '@/api'
import { notifyError } from '@/utils/feedback'

/**
 * 角色授权（docs/auth-rbac.md §3.5）：
 *
 * - 完整目录树按端分 Tab（ENTRY 根一并展示、可勾选：勾选 = 可登录该端）；
 * - 节点独立勾选、无级联继承（check-strictly）；父节点半选态 = 部分子节点已勾选（手工计算）；
 * - 分支「全选 / 清空」快捷操作；停用节点不可勾选、已有授权保留（运行时视同未授予）。
 *
 * 实现注：每个客户端页签各有一棵 el-tree（按端持有实例引用），
 * 保存时汇总各树勾选结果（单树共用会因切页签重置勾选态）。
 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ role: RoleResponse }>()
const emit = defineEmits<{ saved: [] }>()

const CLIENT_LABELS: Record<ClientType, string> = {
  ADMIN: '管理端',
  BLOG: '博客端',
  DESKTOP: '桌面端',
}
const CLIENT_ORDER: ClientType[] = ['ADMIN', 'BLOG', 'DESKTOP']
const TYPE_LABELS: Record<string, string> = { ENTRY: '入口', MENU: '菜单', ACTION: '操作' }
const TYPE_TAGS: Record<string, 'success' | 'primary' | 'warning'> = {
  ENTRY: 'success',
  MENU: 'primary',
  ACTION: 'warning',
}

type TreeInstance = InstanceType<typeof ElTree>

const treeRefs = new Map<ClientType, TreeInstance>()
const loading = ref(false)
const submitting = ref(false)
const roots = ref<MenuCatalogNode[]>([])
const activeClient = ref<ClientType>('ADMIN')

function setTreeRef(client: ClientType, instance: unknown): void {
  if (instance) {
    treeRefs.set(client, instance as TreeInstance)
  } else {
    treeRefs.delete(client)
  }
}

const clients = computed(() =>
  CLIENT_ORDER.filter((client) => roots.value.some((root) => root.client === client)),
)

function treeDataOf(client: ClientType): MenuCatalogNode[] {
  const root = roots.value.find((item) => item.client === client)
  return root ? [root] : []
}

const treeData = computed(() => treeDataOf(activeClient.value))

const treeProps: TreeOptionProps = {
  label: 'name',
  children: 'children',
  disabled: (data) => (data as MenuCatalogNode).status === 0,
}

watch(
  visible,
  async (open) => {
    if (!open) return
    loading.value = true
    try {
      roots.value = (await menuApi.list()) ?? []
      activeClient.value = clients.value[0] ?? 'ADMIN'
      await nextTick()
      for (const client of clients.value) {
        treeRefs.get(client)?.setCheckedKeys(props.role.permissionIds ?? [])
      }
      await nextTick()
      syncHalfStates()
    } catch (error) {
      notifyError(error, '权限目录加载失败')
      visible.value = false
    } finally {
      loading.value = false
    }
  },
  // v-if 挂载的弹窗在首次打开时 visible 已为 true：立即执行一次，保证数据加载
  { immediate: true },
)

/** 停用节点 id（保存时保留其已有授权，避免"不可勾选 = 静默撤销"） */
function disabledIds(nodes: MenuCatalogNode[]): string[] {
  return nodes.flatMap((node) => [
    ...(node.status === 0 ? [node.id] : []),
    ...disabledIds(node.children ?? []),
  ])
}

function toKeySet(keys: Array<string | number>): Set<string> {
  return new Set(keys.map(String))
}

/** 汇总全部端页签的勾选结果 */
function collectCheckedIds(): Set<string> {
  const ids = new Set<string>()
  for (const client of clients.value) {
    const keys = (treeRefs.get(client)?.getCheckedKeys() ?? []) as Array<string | number>
    for (const key of toKeySet(keys)) ids.add(key)
  }
  return ids
}

/** 半选态：未勾选但子树存在勾选节点的分支 → 半选（勾选无级联，需手工计算） */
function syncHalfStates(): void {
  const tree = treeRefs.get(activeClient.value)
  if (!tree) return
  const checked = toKeySet((tree.getCheckedKeys() ?? []) as Array<string | number>)

  const visit = (node: MenuCatalogNode): boolean => {
    const hasCheckedDescendant = (node.children ?? []).reduce(
      (found, child) => visit(child) || found,
      false,
    )
    const instance = tree.getNode(node.id) as { setChecked: (value: boolean | string) => void } | null
    if (instance && !checked.has(node.id)) {
      instance.setChecked(hasCheckedDescendant ? 'half' : false)
    }
    return hasCheckedDescendant || checked.has(node.id)
  }
  for (const root of treeData.value) visit(root)
}

/** 分支全选 / 清空（跳过停用节点） */
function setBranch(node: MenuCatalogNode, checked: boolean): void {
  const tree = treeRefs.get(activeClient.value)
  if (!tree) return
  const ids: string[] = []
  const collect = (item: MenuCatalogNode) => {
    if (item.status !== 0) ids.push(item.id)
    for (const child of item.children ?? []) collect(child)
  }
  collect(node)

  const keys = toKeySet((tree.getCheckedKeys() ?? []) as Array<string | number>)
  for (const id of ids) {
    if (checked) keys.add(id)
    else keys.delete(id)
  }
  tree.setCheckedKeys([...keys])
  syncHalfStates()
}

async function submit(): Promise<void> {
  submitting.value = true
  try {
    const keys = collectCheckedIds()
    const preserved = disabledIds(roots.value)
    await roleApi.grant(props.role.id, { menuIds: [...new Set([...keys, ...preserved])] })
    ElMessage.success('授权已保存，下一次请求即生效')
    visible.value = false
    emit('saved')
  } catch (error) {
    notifyError(error, '保存授权失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`修改授权 · ${role.name}`"
    width="640px"
    top="6vh"
    :close-on-click-modal="false"
  >
    <div v-loading="loading" class="min-h-72">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="节点独立勾选、无继承：勾选「入口」可登录对应端，勾选「菜单」可见页面，勾选「操作」才可执行写操作"
        class="mb-3"
      />

      <el-tabs v-model="activeClient">
        <el-tab-pane
          v-for="client in clients"
          :key="client"
          :label="CLIENT_LABELS[client]"
          :name="client"
        >
          <el-scrollbar max-height="46vh">
            <el-tree
              :ref="(instance) => setTreeRef(client, instance)"
              :data="treeDataOf(client)"
              node-key="id"
              show-checkbox
              check-strictly
              default-expand-all
              :props="treeProps"
              :expand-on-click-node="false"
              @check="syncHalfStates"
            >
              <template #default="{ data }">
                <div class="group flex min-w-0 flex-1 items-center gap-2 pr-2">
                  <span class="truncate">{{ data.name }}</span>
                  <el-text size="small" type="info" class="shrink-0">{{ data.code }}</el-text>
                  <el-tag size="small" effect="plain" :type="TYPE_TAGS[data.type]">
                    {{ TYPE_LABELS[data.type] }}
                  </el-tag>
                  <el-tag v-if="data.status === 0" size="small" type="danger" effect="plain">
                    停用
                  </el-tag>
                  <span class="ml-auto hidden shrink-0 gap-2 group-hover:flex">
                    <el-button link type="primary" size="small" @click.stop="setBranch(data, true)">
                      全选
                    </el-button>
                    <el-button link type="primary" size="small" @click.stop="setBranch(data, false)">
                      清空
                    </el-button>
                  </span>
                </div>
              </template>
            </el-tree>
          </el-scrollbar>
        </el-tab-pane>
      </el-tabs>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存授权</el-button>
    </template>
  </el-dialog>
</template>
