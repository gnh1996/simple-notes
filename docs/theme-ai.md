# 主题 AI 对话（Theme AI Chat）

> **状态：v1 · 2026-11-07（已实现：Spring AI 2.0 流式对话 + 后端校验修复循环 + 临时草稿预览 + 定版复用既有版本链路）**
> 范围：admin 端对话式主题定制；关联 `docs/blog-runtime-theme.md`（§3 契约 / §7 管理端 / §8 校验）、`docs/tech-stack.md`（Spring AI 选型）。
> 非目标：tool calling、RAG/向量库、多 provider 路由、token 级流式结构化输出、无头浏览器截图回灌（P3）。

## 0. 决策摘要

| 决策点 | 结论 |
| --- | --- |
| 模型接入 | Spring AI 2.0（`spring-ai-starter-model-openai`），OpenAI-compatible，`baseUrl` 覆盖任意兼容端 |
| 配置 | 配置文件：`simple-notes.ai.*`（`AI_BASE_URL / AI_API_KEY / AI_MODEL / AI_TIMEOUT / AI_MAX_ROUNDS / AI_MAX_HISTORY_TURNS`）；自动装配关闭（`spring.ai.model.chat=none`），模型由 `AiThemeService` 懒加载手工构建，key 缺失时 AI 接口 400、不影响启动 |
| 会话态 | 无服务端记忆：history + 快照随请求发送，前端持有；后端只做单轮编排 |
| 输出协议 | SSE `token / round / done / error`；LLM 输出 markdown 说明 + ````html / ````css fenced 块，后端抽取后校验 |
| 校验修复 | 后端循环：生成 → `AiThemePrompt.validate`（体积 + `ThemeTemplateValidator` 双检）→ 失败且轮数未用尽则 `repairing` 事件 + 修复轮；`attempts` 随 `done` 回放，前端可见 |
| 预览 | 临时草稿缓存（Caffeine，TTL 对齐 `preview-ttl`，不走 Flyway）：`POST /ai-drafts/{draftId}/preview-token` 签发，`GET /?previewDraft=&token=` 整页预览；未入库中间态不污染版本表 |
| 定版 | 复用既有链路：填入编辑器 → `createVersion` → 预览 → 人工 `activate`；AI 永不自动应用 |
| 工具 | 不引入 tool calling（§5）；截图回灌留待 P3 |
| 限流 | 无配额；仅单用户单并发 + 服务端超时 + 前端可中断 |

---

## 1. 配置

`backend/src/main/resources/application.yml`：

```yaml
simple-notes.ai:
  base-url: "${AI_BASE_URL:}"
  api-key: "${AI_API_KEY:}"
  model: "${AI_MODEL:}"
  timeout: "${AI_TIMEOUT:90s}"
  max-rounds: "${AI_MAX_ROUNDS:2}"
  max-history-turns: "${AI_MAX_HISTORY_TURNS:10}"
spring.ai.model.chat: none   # 关自动装配，见 §5
```

值来源模板见 `application-local.yml.example`（`AI_*`）。密钥只走环境变量 / 本地值文件，不入库、不落日志（`AiThemeService.friendly/mask` 脱敏）。

## 2. 对话协议

### 2.1 请求

`POST /api/admin/themes/ai-chat/stream`（`admin:theme:write`，`Accept: text/event-stream`）：

```json
{
  "history": [{ "role": "user|assistant", "content": "..." }],
  "prompt": "本轮指令（4000 字符内）",
  "baseHtml": "当前快照（可选）",
  "baseCss": "当前快照（可选）",
  "includeStarter": true
}
```

* `history` 最多 20 条（≈10 轮），超出由前端裁剪。
* 首轮 `includeStarter=true` 时后端附带 starter 全文（`blog/default-theme/` 兜底副本，后端 classpath 直读）；后续轮只带快照，省 token。

### 2.2 事件

| 事件 | 载荷 | 说明 |
| --- | --- | --- |
| `token` | `{ type, round, delta }` | 增量文本（含说明与代码块原文） |
| `round` | `{ round, status: "repairing", errors[] }` | 校验失败进入修复轮 |
| `done` | `{ html, css, contract, draftId, errors[], attempts[{round, errors[]}] }` | `errors` 为空即通过，`draftId` 写入草稿缓存；未通过则 `draftId=null`，前端可继续对话 |
| `error` | `{ type: "error", message }` | 网关鉴权失败等可读错误（key 不回显） |

单用户单并发：同一用户已有进行中生成时返回 409（`AiThemeService.inFlight`，Caffeine 级内存实现；重启即清）。

### 2.3 服务端提示词（`AiThemePrompt`）

* `SYSTEM_RULES`：契约白名单摘要（组件 / 指令 / 数据键 / 动作 / CSS 变量 / 页面分支 / 体积 / 移动端），与 `contract.ts` + `ThemeTemplateValidator` 同名；改契约时同步改此处（见 §6 自检缺口）。
* 输出格式强制：1-3 句说明 + 恰好一个 ````html` 与一个 ````css` 块；修改轮输出完整文件（非 diff）。
* 修复轮：`repairPrompt(errors)` 把行号错误喂回，`temperature` 降到 0.3。

## 3. 产物解析与校验

`AiThemePrompt.extract` 取**最后**一个同名代码块（模型重写时以终版为准）；缺块即错误。
`AiThemePrompt.validate`：先体积（64KB/256KB），再 `ThemeTemplateValidator.validateHtml/validateCss`，
`ApiException` 消息拆分为行级错误返回（纯函数，`AiThemePromptTest` 覆盖，含默认主题全量回归）。

## 4. 草稿预览与定版

* `POST /api/admin/themes/ai-drafts/{draftId}/preview-token`（`admin:theme`）→ 复用 `PreviewTokenService` HMAC（`draft-uuid.exp`）。
* `BlogPageController` 新增 `previewDraft + token` 分支：`AiDraftCache` 命中则组装壳（`themeId=draft`，ETag 带 draftId，`no-store + noindex`）；过期返回 404 纯文本。
* 定版：前端 `填入编辑器`（进既有编辑弹窗）或 `存为新版本`（调 `createVersion`）→ 预览 → 人工 `apply`。版本不可变模型不变（见 `docs/blog-runtime-theme.md` §6.1）。
* A 档渲染自检（2026-11-08）：抽屉 hidden iframe 挂载草稿整页（`fixtures=1`），`ready` 后发 `blog-preview:diagnose`，
  runtime 回 `blog-preview:diagnosed{report}`——静态校验 + 活 DOM 信号（挂载、横向溢出、未解析插值、契约变量计算值、window error/rejection）。
  用户点「把渲染问题发给 AI」拼成修复指令走既有 `sendPrompt`；不自动回灌、不烧 token。无截图（P3 不变）。

## 5. 框架选型记录（Spring AI 2.0 vs LangChain4j vs 手写）

* 采用 Spring AI 2.0：Boot 4.1 基线兼容（BOM `2.0.2`，`dependencyManagement` 独立于 Boot BOM）；`OpenAiChatOptions.builder().baseUrl().apiKey().model()` 直连兼容端；`model.stream(prompt)` 的 `Flux<ChatResponse>` 经 `SseEmitter` 转发。
* 不用自动装配：key 缺失要 fail-soft（不影响启动），starter 自带 chat/image/embedding/audio-speech/audio-transcription/moderation 六组装配，
  缺 key 时都会启动失败，故 `spring.ai.model.*` 六开关全部 `none` + `AiThemeService` 懒加载单例（double-checked）。
  风险：Spring AI 新增模型装配时要同步加开关（启动失败会直接暴露）。
* 手工装配三坑（2026-11-07 实测，`AiClientBuildTest` 锁定）：
  1. `ClientOptions.apiKey(String)` 不计入 `effectiveCredential`，必须用 `credential(BearerTokenCredential.create(key))`；
  2. `OpenAiChatModel.build()` 还会按 options 自建 async client，只传 `openAiClient` 不够，同步/异步两个 client 都要显式传入；
  3. httpClient 无默认值（`'httpClient' is required`），用 `SpringAiOpenAiHttpClient.builder().timeout(...).build()` 显式给。
* 不用 LangChain4j：其 `ChatMemory` 是服务端态，与“无状态”设计拧巴；多 provider 抽象当前用不上。
* 不用 tool calling：封闭域 prompt + 后端校验循环已覆盖；tool 流式分片与本 SSE 协议叠加排错翻倍；兼容端小模型 tool 支持参差。P3 截图回灌时再加只读 tool。

## 6. 已知缺口与后续

1. `AiThemePrompt.SYSTEM_RULES` 是契约的手工摘要，`pnpm check:contract` 管不到；新增组件 / 数据键时必须人肉同步（与 `ThemeTemplateValidator` 的手工集合同类问题）。
2. `done.html/css` 全量下发（≤320KB），弱网下 SSE 单帧大；后续可只下发 `draftId` + 分片拉取。
3. 多轮 history 截断按条数，代码块只留占位（`historyTextOf` 已剥 fenced 块，plan 去标签留要点）；长会话仍可能丢早期版式决策；后续可做快照压缩（只保留最近快照 + 首轮目标）。
4. P3：无头浏览器三档视口截图回喂（届时加只读 `capturePreview` tool）。A 档确定性文本报告已落地（§4），截图仍不做：弱模型多模态参差 + token 贵 + flaky。
5. 编辑器卡顿（2026-11-07 Chrome trace 实测）：`el-input textarea` 全受控，65KB 下每键 100-400ms；
   改为 `LargeTextEditor.vue`（非受控 textarea + 复用 `el-textarea__inner` 样式 + 250ms debounce + 保存前 `flush()`），
   中位数 120ms→37ms；剩余为原生大文本框重排成本，不再投入优化。
   2026-11-08 追查 CSS tab 拖滚动条整窗冻死：与输入链路无关（scroll 风暴 0.7ms/200 次、强制布局 0.2ms/50 次、
   无 longtask、debounce 回写不重置 scrollTop、最长行仅 85 字符、无祖先 filter/transform）——卡在合成侧。
   硬指标：本机 WebGL MAX_TEXTURE_SIZE=16384，整块内容层 30615px（dpr 1.25 下约 38269 物理像素）远超单纹理上限。
   先按 300 行分页验证假设成立（冻死消失），随后换成正解：CodeMirror 6 视口虚拟化（只渲染可视区数十行 DOM，
   全文仍单文档、无分页概念），MIT 协议、按需引入 state/view/commands/lang-css/lang-html/language/theme-one-dark，
   走 catalog。HTML/CSS 分 `lang` 配高亮，明暗主题跟 `useTheme().isDark`（亮色贴 el-textarea 观感、暗色 oneDark）。
   编辑器弹窗补 `destroy-on-close`（关闭即销毁 2 个 CM 实例；文本状态在父级 `editor` 对象里不受影响，重开秒建）。
6. 抽屉体验（2026-11-08，零依赖三项）：滚动改为条件吸底（用户上滑不再强制 `scrollTo`，+「回到底部」按钮）；
   `<plan>` 块渲染为步骤时间线（后端 `SYSTEM_RULES` / `repairPrompt` 强制先输出 plan，`AiThemePromptTest.promptsRequirePlan` 锁定）；
   说明文字走轻量散文渲染（先转义后套自有标签，无外部 HTML 进入），fenced 代码块不再在正文重复展示（看产物卡片）。

## 7. 超时分层（2026-11-07 实测）

大 prompt（首轮附带 starter 约 40KB）首 token 经常超过 60s。超时有三层，
只有落到底层 client 的才管流式读：

| 层 | 配置 | 作用 |
| --- | --- | --- |
| 底层 client | `ClientOptions.builder().timeout(AI_TIMEOUT).maxRetries(1)` | connect/read/write/request 全量，流式读靠它（默认 60s，`Options.timeout` 盖不住） |
| `defaultOptions` | `OpenAiChatOptions.timeout(AI_TIMEOUT)` | 非流式调用兜底 |
| `SseEmitter` | `AI_TIMEOUT + 30s` | 前后端 SSE 连接上限 |

`friendly()` 把超时翻译为可读提示（含秒数），key 不回显。
