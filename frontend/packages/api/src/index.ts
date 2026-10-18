/**
 * @simple-notes/api —— Web 端（admin / blog）共用的 API 客户端。
 *
 * 契约单一来源 = 后端 OpenAPI（springdoc 导出 `/v3/api-docs`）：
 *   - 类型：`pnpm gen:api` → `src/generated/schema.d.ts`（openapi-typescript，禁止手写）
 *   - 端点封装：`src/endpoints.ts`（按后端接口域分组）
 *   - 传输层：`src/client.ts`（轻量 fetch 封装，Bearer 注入由应用侧 prepareRequest 完成）
 */
export * from './client'
export * from './endpoints'
