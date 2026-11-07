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

// ---- 内容模块类型别名 ----

export type TagBrief = Schema['TagBrief']
export type NoteFolderResponse = Schema['NoteFolderResponse']
export type NoteFolderRequest = Schema['NoteFolderRequest']
export type NoteSummary = Schema['NoteSummary']
export type NoteDetail = Schema['NoteDetail']
export type CreateNoteRequest = Schema['CreateNoteRequest']
export type UpdateNoteRequest = Schema['UpdateNoteRequest']
export type PageResponseNoteSummary = Schema['PageResponseNoteSummary']

export type PostSummary = Schema['PostSummary']
export type PostDetail = Schema['PostDetail']
export type CreatePostRequest = Schema['CreatePostRequest']
export type UpdatePostRequest = Schema['UpdatePostRequest']
export type PublishRequest = Schema['PublishRequest']
export type PageResponsePostSummary = Schema['PageResponsePostSummary']

export type AttachmentResponse = Schema['AttachmentResponse']
export type StorageStatusResponse = Schema['StorageStatusResponse']
export type UpdateStorageModeRequest = Schema['UpdateStorageModeRequest']
export type PageResponseAttachmentResponse = Schema['PageResponseAttachmentResponse']
export type TrashItemResponse = Schema['TrashItemResponse']

export type TagAdminSummary = Schema['TagAdminSummary']
export type TagContentResponse = Schema['TagContentResponse']
export type TagContentCandidate = Schema['TagContentCandidate']
export type TagNameRequest = Schema['TagNameRequest']
export type TagAssignRequest = Schema['TagAssignRequest']
export type PageResponseTagAdminSummary = Schema['PageResponseTagAdminSummary']
export type PageResponseTagContentResponse = Schema['PageResponseTagContentResponse']
export type PageResponseTagContentCandidate = Schema['PageResponseTagContentCandidate']

export type PublishedPostSummary = Schema['PublishedPostSummary']
export type PublishedPostDetail = Schema['PublishedPostDetail']
export type PageResponsePublishedPostSummary = Schema['PageResponsePublishedPostSummary']
export type TagCountResponse = Schema['TagCountResponse']
export type ArchiveResponse = Schema['ArchiveResponse']

// ---- 站点配置 / 主题 / 独立页面 ----

export type SiteSettingItem = Schema['SiteSettingItem']
export type SiteSettingsResponse = Schema['SiteSettingsResponse']
export type UpdateSiteSettingsRequest = Schema['UpdateSiteSettingsRequest']
export type SiteResponse = Schema['SiteResponse']
export type SitePageResponse = Schema['SitePageResponse']
export type SitePageRequest = Schema['SitePageRequest']

/** 服务端版本自证（`GET /api/version`，匿名；docs/release.md） */
export type VersionResponse = Schema['VersionResponse']

export type ThemeSummary = Schema['ThemeSummary']
export type ThemeVersionResponse = Schema['ThemeVersionResponse']
export type CreateThemeRequest = Schema['CreateThemeRequest']
export type CreateThemeVersionRequest = Schema['CreateThemeVersionRequest']
export type PreviewTokenResponse = Schema['PreviewTokenResponse']

export interface UserListQuery {
  page?: number
  size?: number
  /** 模糊匹配登录名 / 昵称 / 邮箱 */
  keyword?: string
}

export interface NoteListQuery {
  page?: number
  size?: number
  keyword?: string
  folderId?: string
  tagId?: string
  pinnedOnly?: boolean
}

export interface PostListQuery {
  page?: number
  size?: number
  keyword?: string
  status?: 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED'
  hasUnpublishedChanges?: boolean
}

export type BlogSortField = 'displayAt' | 'firstPublishedAt' | 'lastPublishedAt'

export interface BlogListSortQuery {
  pinnedFirst?: boolean
  direction?: 'asc' | 'desc'
  sortBy?: BlogSortField
}

export interface PublishedPostDetailQuery extends BlogListSortQuery {
  tagId?: string
  year?: number
  month?: number
  q?: string
}

export interface PublishedPostListQuery extends BlogListSortQuery {
  page?: number
  size?: number
  tagId?: string
  year?: number
  month?: number
}

export interface TagListQuery {
  page?: number
  size?: number
  keyword?: string
}

export interface MediaListQuery {
  page?: number
  size?: number
  /** 模糊匹配原始文件名 */
  keyword?: string
}

export interface TagContentQuery {
  kind: 'NOTE' | 'POST'
  page?: number
  size?: number
  keyword?: string
}

function queryString(params: object): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value))
    }
  }
  const qs = search.toString()
  return qs ? `?${qs}` : ''
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
    list: (query: UserListQuery = {}) =>
      client.get<PageResponseUserResponse>(`/admin/users${queryString(query)}`),
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

/** 随手记文件夹（`/api/admin/note-folders`） */
export function createNoteFolderApi(client: ApiClient) {
  return {
    list: () => client.get<NoteFolderResponse[]>('/admin/note-folders'),
    create: (body: NoteFolderRequest) =>
      client.post<NoteFolderResponse>('/admin/note-folders', body),
    update: (id: string, body: NoteFolderRequest) =>
      client.put<NoteFolderResponse>(`/admin/note-folders/${id}`, body),
    remove: (id: string) => client.delete<void>(`/admin/note-folders/${id}`),
  }
}

/** 随手记（`/api/admin/notes`，仅 kind=NOTE） */
export function createNoteApi(client: ApiClient) {
  return {
    list: (query: NoteListQuery = {}) =>
      client.get<PageResponseNoteSummary>(`/admin/notes${queryString(query)}`),
    get: (id: string) => client.get<NoteDetail>(`/admin/notes/${id}`),
    create: (body: CreateNoteRequest) => client.post<NoteDetail>('/admin/notes', body),
    update: (id: string, body: UpdateNoteRequest) =>
      client.put<NoteDetail>(`/admin/notes/${id}`, body),
    remove: (id: string) => client.delete<void>(`/admin/notes/${id}`),
    /** 笔记转博客草稿（不可逆；没有反向接口） */
    promote: (id: string) => client.post<PostDetail>(`/admin/notes/${id}/promote`),
  }
}

/** 博客文章管理（`/api/admin/posts`，仅 kind=POST） */
export function createPostApi(client: ApiClient) {
  return {
    list: (query: PostListQuery = {}) =>
      client.get<PageResponsePostSummary>(`/admin/posts${queryString(query)}`),
    get: (id: string) => client.get<PostDetail>(`/admin/posts/${id}`),
    create: (body: CreatePostRequest) => client.post<PostDetail>('/admin/posts', body),
    update: (id: string, body: UpdatePostRequest) =>
      client.put<PostDetail>(`/admin/posts/${id}`, body),
    /** 首次发布 / 更新发布：发布内容来自已保存草稿 */
    publish: (id: string, version: number) =>
      client.post<PostDetail>(`/admin/posts/${id}/publish`, { version }),
    unpublish: (id: string) => client.post<PostDetail>(`/admin/posts/${id}/unpublish`),
    remove: (id: string) => client.delete<void>(`/admin/posts/${id}`),
  }
}

/** 回收站（笔记 / 文章分开，权限码独立） */
export function createTrashApi(client: ApiClient) {
  return {
    notes: () => client.get<TrashItemResponse[]>('/admin/notes/trash'),
    restoreNote: (id: string) => client.post<void>(`/admin/notes/${id}/restore`),
    posts: () => client.get<TrashItemResponse[]>('/admin/posts/trash'),
    restorePost: (id: string) => client.post<void>(`/admin/posts/${id}/restore`),
  }
}

/** 标签管理（`/api/admin/tags`） */
export function createTagApi(client: ApiClient) {
  return {
    /** 编辑器联想：返回全部启用标签 */
    options: () => client.get<TagBrief[]>('/admin/tags/options'),
    list: (query: TagListQuery = {}) =>
      client.get<PageResponseTagAdminSummary>(`/admin/tags${queryString(query)}`),
    create: (body: TagNameRequest) => client.post<TagAdminSummary>('/admin/tags', body),
    rename: (id: string, body: TagNameRequest) =>
      client.put<TagAdminSummary>(`/admin/tags/${id}`, body),
    /** 有使用量时需 force=true（前端确认警告后重试） */
    remove: (id: string, force = false) =>
      client.delete<void>(`/admin/tags/${id}${force ? '?force=true' : ''}`),
    contents: (id: string, query: TagContentQuery) =>
      client.get<PageResponseTagContentResponse>(
        `/admin/tags/${id}/contents${queryString(query)}`,
      ),
    candidates: (id: string, query: TagContentQuery) =>
      client.get<PageResponseTagContentCandidate>(
        `/admin/tags/${id}/candidates${queryString(query)}`,
      ),
    /** 只写当前草稿；已发布文章需在文章编辑页更新发布 */
    assign: (id: string, body: TagAssignRequest) =>
      client.post<void>(`/admin/tags/${id}/contents`, body),
    removeContent: (id: string, contentId: string) =>
      client.delete<void>(`/admin/tags/${id}/contents/${contentId}`),
  }
}

/** 媒体库（`/api/admin/media`） */
export function createMediaApi(client: ApiClient) {
  return {
    list: (query: MediaListQuery = {}) =>
      client.get<PageResponseAttachmentResponse>(`/admin/media${queryString(query)}`),
    upload: (file: File) => {
      const form = new FormData()
      form.set('file', file)
      return client.request<AttachmentResponse>('/admin/media', {
        method: 'POST',
        body: form,
      })
    },
    remove: (id: string) => client.delete<void>(`/admin/media/${id}`),
    storage: () => client.get<StorageStatusResponse>('/admin/media/storage'),
    switchStorage: (body: UpdateStorageModeRequest) =>
      client.put<StorageStatusResponse>('/admin/media/storage', body),
  }
}

/** 公开博客（`/api/blog/**`，匿名可读） */
export function createBlogApi(client: ApiClient) {
  return {
    list: (query: PublishedPostListQuery = {}) =>
      client.get<PageResponsePublishedPostSummary>(`/blog/posts${queryString(query)}`),
    detail: (slug: string, query: PublishedPostDetailQuery = {}) =>
      client.get<PublishedPostDetail>(`/blog/posts/${encodeURIComponent(slug)}${queryString(query)}`),
    tags: () => client.get<TagCountResponse[]>('/blog/tags'),
    archives: (query: BlogListSortQuery = {}) =>
      client.get<ArchiveResponse[]>(`/blog/archives${queryString(query)}`),
    search: (query: PublishedPostListQuery & { q?: string } = {}) =>
      client.get<PageResponsePublishedPostSummary>(`/blog/search${queryString(query)}`),
    site: () => client.get<SiteResponse>('/blog/site'),
    page: (slug: string) => client.get<SitePageResponse>(`/blog/pages/${encodeURIComponent(slug)}`),
  }
}

/** 站点设置与独立页面（`/api/admin/site/**`） */
export function createSiteApi(client: ApiClient) {
  return {
    settings: () => client.get<SiteSettingsResponse>('/admin/site/settings'),
    updateSettings: (body: UpdateSiteSettingsRequest) =>
      client.put<SiteSettingsResponse>('/admin/site/settings', body),
    pages: () => client.get<SitePageResponse[]>('/admin/site/pages'),
    createPage: (body: SitePageRequest) =>
      client.post<SitePageResponse>('/admin/site/pages', body),
    updatePage: (id: string, body: SitePageRequest) =>
      client.put<SitePageResponse>(`/admin/site/pages/${id}`, body),
    removePage: (id: string) => client.delete<void>(`/admin/site/pages/${id}`),
  }
}

/** 服务端版本自证（`/api/version`：匿名，不含敏感信息；docs/release.md） */
export function createMetaApi(client: ApiClient) {
  return {
    version: () => client.get<VersionResponse>('/version'),
  }
}

/** 主题管理（`/api/admin/themes`）：版本不可变，应用 / 回滚都走 activate */
export function createThemeApi(client: ApiClient) {
  return {
    list: () => client.get<ThemeSummary[]>('/admin/themes'),
    create: (body: CreateThemeRequest) => client.post<ThemeSummary>('/admin/themes', body),
    versions: (id: string) => client.get<ThemeVersionResponse[]>(`/admin/themes/${id}/versions`),
    version: (id: string, versionId: string) =>
      client.get<ThemeVersionResponse>(`/admin/themes/${id}/versions/${versionId}`),
    createVersion: (id: string, body: CreateThemeVersionRequest) =>
      client.post<ThemeVersionResponse>(`/admin/themes/${id}/versions`, body),
    activate: (id: string, versionId: string) =>
      client.post<ThemeSummary>(`/admin/themes/${id}/versions/${versionId}/activate`),
    previewToken: (id: string, versionId: string) =>
      client.post<PreviewTokenResponse>(`/admin/themes/${id}/versions/${versionId}/preview-token`),
  }
}

/**
 * 汇总实例：`createSimpleNotesApi(client)` 一次拿到全部接口分组。
 */
export function createSimpleNotesApi(client: ApiClient) {
  return {
    meta: createMetaApi(client),
    auth: createAuthApi(client),
    users: createUserApi(client),
    roles: createRoleApi(client),
    menus: createMenuApi(client),
    noteFolders: createNoteFolderApi(client),
    notes: createNoteApi(client),
    posts: createPostApi(client),
    tags: createTagApi(client),
    trash: createTrashApi(client),
    media: createMediaApi(client),
    blog: createBlogApi(client),
    site: createSiteApi(client),
    themes: createThemeApi(client),
  }
}

export type SimpleNotesApi = ReturnType<typeof createSimpleNotesApi>
