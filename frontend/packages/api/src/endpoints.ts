/**
 * 类型化接口封装：类型来自后端 OpenAPI 契约（`generated/schema.d.ts`，禁止手写）。
 *
 * 新增 / 变更接口的正确流程：
 *   1. 后端改接口 → 2. `pnpm gen:api` 重新生成类型 → 3. 在此补 / 改封装函数。
 */
import type { ApiClient } from './client'
import type { components } from './generated/schema'

export type Schema = components['schemas']

// ---- 契约类型别名（应用侧从这里引用，不直接依赖生成文件的结构） ----

export type ClientType = Schema['LoginRequest']['client']
export type MenuNode = Schema['MenuNode']
export type MenuCatalogNode = Schema['MenuCatalogNode']
export type LoginResponse = Schema['LoginResponse']
export type MeResponse = Schema['MeResponse']
export type RoleBrief = Schema['RoleBrief']
export type RoleResponse = Schema['RoleResponse']
export type UserResponse = Schema['UserResponse']
export type UserSummary = Schema['UserSummary']
export type PageResponseUserResponse = Schema['PageResponseUserResponse']

export type LoginRequest = Schema['LoginRequest']
export type PasswordChangeRequest = Schema['PasswordChangeRequest']
export type ProfileUpdateRequest = Schema['ProfileUpdateRequest']

export type CreateUserRequest = Schema['CreateUserRequest']
export type UpdateUserRequest = Schema['UpdateUserRequest']
export type AssignRolesRequest = Schema['AssignRolesRequest']
export type ResetPasswordRequest = Schema['ResetPasswordRequest']

export type CreateRoleRequest = Schema['CreateRoleRequest']
export type UpdateRoleRequest = Schema['UpdateRoleRequest']
export type GrantPermissionsRequest = Schema['GrantPermissionsRequest']

export type UpdateMenuRequest = Schema['UpdateMenuRequest']

export interface UserListQuery {
  page?: number
  size?: number
  /** 模糊匹配登录名 / 昵称 / 邮箱 */
  keyword?: string
}

/** 认证与个人中心（`/api/auth/**`） */
export function createAuthApi(client: ApiClient) {
  return {
    login: (body: LoginRequest) => client.post<LoginResponse>('/auth/login', body),
    logout: () => client.post<void>('/auth/logout'),
    /** 撤销该用户全部会话（所有端、全部设备，含当前） */
    logoutAll: () => client.post<void>('/auth/logout-all'),
    me: () => client.get<MeResponse>('/auth/me'),
    changePassword: (body: PasswordChangeRequest) => client.put<void>('/auth/password', body),
    updateProfile: (body: ProfileUpdateRequest) =>
      client.put<UserSummary>('/auth/profile', body),
  }
}

/** 用户管理（`/api/admin/users`） */
export function createUserApi(client: ApiClient) {
  return {
    list: (query: UserListQuery = {}) => {
      const search = new URLSearchParams()
      if (query.page != null) search.set('page', String(query.page))
      if (query.size != null) search.set('size', String(query.size))
      if (query.keyword) search.set('keyword', query.keyword)
      const qs = search.toString()
      return client.get<PageResponseUserResponse>(`/admin/users${qs ? `?${qs}` : ''}`)
    },
    create: (body: CreateUserRequest) => client.post<UserResponse>('/admin/users', body),
    update: (id: string, body: UpdateUserRequest) =>
      client.put<UserResponse>(`/admin/users/${id}`, body),
    /** 全量替换角色集合（内置角色拒绝；超管角色持有者拒绝） */
    assignRoles: (id: string, body: AssignRolesRequest) =>
      client.put<UserResponse>(`/admin/users/${id}/roles`, body),
    resetPassword: (id: string, body: ResetPasswordRequest) =>
      client.put<void>(`/admin/users/${id}/password`, body),
    remove: (id: string) => client.delete<void>(`/admin/users/${id}`),
  }
}

/** 角色管理（`/api/admin/roles`） */
export function createRoleApi(client: ApiClient) {
  return {
    list: () => client.get<RoleResponse[]>('/admin/roles'),
    /** 可分配角色（服务端已排除内置角色，供用户管理页「分配角色」弹窗） */
    assignable: () => client.get<RoleBrief[]>('/admin/roles/assignable'),
    create: (body: CreateRoleRequest) => client.post<RoleResponse>('/admin/roles', body),
    update: (id: string, body: UpdateRoleRequest) =>
      client.put<RoleResponse>(`/admin/roles/${id}`, body),
    /** 全量替换权限节点集合（内置角色拒绝） */
    grant: (id: string, body: GrantPermissionsRequest) =>
      client.put<RoleResponse>(`/admin/roles/${id}/permissions`, body),
    remove: (id: string) => client.delete<void>(`/admin/roles/${id}`),
  }
}

/** 菜单目录（`/api/admin/menus`：角色编辑器与菜单管理共用） */
export function createMenuApi(client: ApiClient) {
  return {
    list: () => client.get<MenuCatalogNode[]>('/admin/menus'),
    /** 改名 / 调序 / 启停（结构性字段与增删不提供） */
    update: (id: string, body: UpdateMenuRequest) =>
      client.put<MenuCatalogNode>(`/admin/menus/${id}`, body),
  }
}

/**
 * 汇总实例：`createSimpleNotesApi(client)` 一次拿到全部接口分组。
 */
export function createSimpleNotesApi(client: ApiClient) {
  return {
    auth: createAuthApi(client),
    users: createUserApi(client),
    roles: createRoleApi(client),
    menus: createMenuApi(client),
  }
}

export type SimpleNotesApi = ReturnType<typeof createSimpleNotesApi>
