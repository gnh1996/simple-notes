# 主题 AI 对话（Theme AI Chat）

> **状态：v2.1 · 2026-11-08（已实现）：Spring AI 自动装配 + ChatClient + 后端当前主题 + 首轮完整 / 后续补丁 + 虚拟线程 SSE**
> 范围：admin 端对话式主题定制；关联 `docs/blog-runtime-theme.md`（§3 契约 / §7 管理端 / §8 校验）、`docs/tech-stack.md`（Spring AI 选型）。
> 非目标：tool calling、RAG / 向量库、多 provider 路由、无头浏览器截图回灌（P3）。
> 版本回顾：
> - v1：手工装配 `OpenAiChatModel.builder()`，正则取 html/css fenced 块，前端持有 history，`new Thread` + 固定 SSE 超时。
> - v2：Spring AI 自动装配 + `ChatClient` + 后端 ChatMemory + 整篇主题 JSON（`ThemeDraft`）+ provider-native `outputSchema`。
> - v2.1：保留 v2 的接入/线程/记忆基础，**把“整篇主题 JSON”改成“首轮完整 + 后续补丁”**；当前主题单独存服务端，ChatMemory 只存摘要，避免 JSON 转义、输出截断与上下文膨胀。

## 0. 决策摘要

| 决策点 | 结论 |
| --- | --- |
| 模型接入 | Spring AI 2.0 自动装配（`spring-ai-starter-model-openai`，`spring.ai.model.chat=openai`）；`ChatClient`（`AiConfig`）为唯一入口，连接信息只有 `spring.ai.openai.*` 一份来源（`AI_*` 桥接），缺 key 时 `AiEnvironmentPostProcessor` 排除 OpenAI + Builder 装配，启动不受影响 |
| 配置 | `simple-notes.ai.*` 只保留编排参数（`AI_TIMEOUT / AI_MAX_ROUNDS / AI_MAX_HISTORY_TURNS / AI_MAX_TOKENS`）；其余 OpenAI 模型类型显式 `none` |
| 会话态 | `conversationId = "ai-theme:" + userId + ":" + (themeId|none) + ":" + sessionId`；`ChatMemory` 只存压缩后的摘要/计划/用户指令，**完整当前主题**存 `AiThemeSessionStore`（Caffeine，2h 无访问过期） |
| 输出协议 | **有当前主题 / 前端 `baseHtml` / 勾选 starter 时，从第一轮就走补丁**（小 JSON `{summary, plan, ops[]}`）；只有“没有主题且没勾 starter”才完整生成 html/css。这样绝大多数请求不需要模型输出整篇主题 |
| 校验修复 | 生成 → 解析/应用补丁 → `AiThemePrompt.validate`（体积 + 根节点预检 + `ThemeTemplateValidator`）→ 失败且轮数未用尽则 `repairing` + 修复轮；补丁修复只回传上一版补丁 + 错误，不重发整篇主题 |
| 预览 | 临时草稿缓存（Caffeine，TTL 对齐 `preview-ttl`，不走 Flyway）：`POST /ai-drafts/{draftId}/preview-token` 签发，`GET /?previewDraft=&token=` 整页预览；未入库中间态不污染版本表 |
| 定版 | 复用既有链路：填入编辑器 → `createVersion` → 预览 → 人工 `activate`；AI 永不自动应用；`contract` 随 `done` 回传，前端保存时使用 |
| 工具 | 不引入 tool calling（§5）；截图回灌留待 P3 |
| 限流 | 无配额；仅单用户单并发 + 服务端超时 + 前端可中断 |
| 调试 | 服务端按轮次输出结构化日志；前端显示 `mode / finishReason / truncated / outputChars / errorKind`（§8） |

---

## 1. 配置

`backend/src/main/resources/application.yml`：

```yaml
simple-notes.ai:
  timeout: "${AI_TIMEOUT:300s}"
  max-rounds: "${AI_MAX_ROUNDS:2}"
  max-history-turns: "${AI_MAX_HISTORY_TURNS:10}"
  max-tokens: "${AI_MAX_TOKENS:16384}"
spring.ai.model:
  chat: openai
  embedding: none
  image: none
  moderation: none
  audio:
    speech: none
    transcription: none
spring.ai.openai:
  base-url: "${AI_BASE_URL:}"   # 三键任一为空即“未配置”：OpenAI/Builder 装配跳过，AI 接口 400，启动照常
  api-key: "${AI_API_KEY:}"
  timeout: "${AI_TIMEOUT:300s}"
  max-retries: 1
  chat.model: "${AI_MODEL:}"
```

值来源模板见 `application-local.yml.example`（`AI_*`）。密钥只走环境变量 / 本地值文件，不入库、不落日志（`AiThemeService.friendly/mask` 脱敏）。
`AI_MAX_TOKENS` 默认 `0` = 不发送 `max_tokens`，使用端点默认。DeepSeek `deepseek-flash`：thinking 模式默认 64K（reasoning 计入），非 thinking 模式默认 8K；需要更高/更低上限时显式设置（如 `65536`）。
`AI_THINKING`（`enabled` / `disabled` / 空=不发送）和 `AI_REASONING_EFFORT`（`none` / `low` / `high` / `max`，空=不发送）用于 DeepSeek / Qwen 等 thinking 开关；主题生成通常关闭 thinking 更快更省。
推理模型（`deepseek-flash` / `deepseek-reasoner` / Qwen thinking / OpenAI o 系列等）的 reasoning token 也计入输出预算；若 `AI_THINKING=disabled` 且 `AI_MAX_TOKENS=0`，DeepSeek 非 thinking 默认只有 8K，整篇主题建议显式调大。
DeepSeek `deepseek-flash` 主题生成推荐：`AI_THINKING: disabled` + `AI_MAX_TOKENS: 65536`（快、无 reasoning 预算竞争）；需要更强推理时改回 `AI_THINKING: ""` / `AI_MAX_TOKENS: 0`（thinking 默认 64K）。

---

## 2. 对话协议

### 2.1 请求

`POST /api/admin/themes/ai-chat/stream`（`admin:theme:write`，`Accept: text/event-stream`）：

```json
{
  "sessionId": "前端每段对话生成的 UUID",
  "themeId": "当前主题 id（可空；参与 conversationId，切换主题即换会话）",
  "history": [{ "role": "user|assistant", "content": "..." }],
  "prompt": "本轮指令（4000 字符内）",
  "baseHtml": "首轮完整生成时的参考主题（可空）",
  "baseCss": "同上",
  "includeStarter": true
}
```

* `history` 仅作种子：后端 memory 中不存在该会话时才一次性写入（防重启丢上下文）；命中 memory 时忽略。
* `baseHtml/baseCss` 只用于初始化当前主题；有 `AiThemeSessionStore` 当前主题时忽略。
* **补丁优先级**：当前主题 → 前端 `baseHtml` → 勾选 starter 的默认主题，三者任一存在都直接进入补丁模式；只有三者都没有才完整生成。`starter` 因此是“起点主题”，不是额外附带的参考。
* 前端 `includeStarter` 只在真正产出有效主题后才置 false；失败重试仍可带 starter。

### 2.2 事件

| 事件 | 载荷 | 说明 |
| --- | --- | --- |
| `token` | `{ type, round, delta }` | 增量文本（补丁 JSON 时前端隐藏原文，只推进度） |
| `round` | `{ round, status: "repairing", errors[] }` | 校验失败进入修复轮 |
| `done` | 见下 | 每轮结束都发；`errors` 为空即通过并写入 `draftId` |
| `error` | `{ type: "error", message }` | 网关鉴权 / 限流 / 超时等可读错误（key 不回显） |

`done` 载荷：

```json
{
  "html": "当前完整主题 HTML",
  "css": "当前完整主题 CSS",
  "summary": "本轮 1-3 句说明",
  "plan": ["本轮要点"],
  "contract": 1,
  "draftId": "draft-uuid / null",
  "errors": ["校验错误"],
  "attempts": [{ "round": 1, "errors": [] }],
  "mode": "full | patch",
  "finishReason": "stop | length | ...",
  "truncated": false,
  "outputChars": 12345,
  "reasoningChars": 0,
  "usage": { "promptTokens": 0, "completionTokens": 0, "totalTokens": 0 },
  "errorKind": "none | parse | validate"
}
```

单用户单并发：同一用户已有进行中生成时返回 409（`AiThemeService.inFlight`；重启即清）。

### 2.3 服务端提示词（`AiThemePrompt`）

* `CONTRACT_RULES`：契约白名单摘要（组件 / 指令 / 数据键 / 动作 / CSS 变量 / 页面分支 / 体积 / 移动端），与 `contract.ts` + `ThemeTemplateValidator` 同名；改契约时同步改此处（见 §6 自检缺口）。
* **完整模式**（`fullSystemPrompt`）：只在“没有当前主题 / 没有 base / 没勾 starter”时使用；先输出 `<plan>`，再输出恰好一个 ````html` 与 ````css`。
* **补丁模式**（`patchSystemPrompt`）：有当前主题（含 starter 起点）时使用；只输出单个 JSON `{summary, plan, ops}`；当前主题放 `<reference-html>` / `<reference-css>`，每轮只注入一次。
* 补丁操作（`ThemePatch.ThemeOp`）：

| op | 字段 | 用途 |
| --- | --- | --- |
| `setCssVar` | `name` + `value` | 只允许 `CONTRACT_RULES` 第 8 条白名单变量；替换 CSS 中首个同名定义（通常是 `:root`），没有定义时追加 `:root { ... }`；深色专用值用 `appendCss` 指定 `[data-color-mode]` 选择器 |
| `appendCss` | `css` | 追加 CSS；适合改组件间距、圆角、颜色等局部样式 |
| `replace` | `target=html/css` + `find` + `content` | 从当前主题原样复制一段**唯一**片段替换；结构改动的首选 |
| `replaceCss` | `css` | 完整重写 CSS（仅在整体换风格时） |
| `replaceHtml` | `html` | 完整重写 HTML（仅在整体换风格时） |

* 修复轮：
  - 完整模式 `repairFullPrompt(errors, lastOutput)`：把上一版完整输出和错误一起喂回；
  - 补丁模式 `repairPatchPrompt(errors, lastPatchJson)`：只喂回上一版补丁 JSON 和错误，当前主题仍在 system prompt 里；
  - `temperature` 从 0.7 降到 0.3。
* 补丁模式下若用户要求彻底重写整篇主题且改动很大，允许模型直接输出完整 ````html` / ````css`（fenced），不要塞进 JSON 字符串；`extractPatch` 失败后会由完整主题解析兜底，按整体替换处理。

---

## 3. 产物解析与校验

* `AiThemePrompt.extractFull`：从完整输出取**最后**一个 ```html 与 ```css；兼容旧模型仍输出 `{"html": "...", "css": "..."}` 的 JSON。
* `AiThemePrompt.extractPatch`：按序尝试 fenced JSON → 全文 → **从任意 `{` 起括号配平**的对象（summary 开头、前后散文、未知字段、换行都能容错）。
* `AiThemePatchApplier`：把 `ops` 顺序应用到当前主题副本；失败返回上一版主题 + 可读错误，**不会**改动会话中的当前主题。
* `AiThemePrompt.validate`：先体积（HTML 64KB / CSS 256KB），再 `id="app"` 根节点预检，最后 `ThemeTemplateValidator.validateHtml/validateCss`；`ApiException` 消息拆成行级错误。
* 只有 `errors` 为空时才更新 `AiThemeSessionStore` 与 `AiDraftCache`；失败轮不污染当前主题。

纯函数测试：`AiThemePromptTest`（完整/补丁/记忆压缩/默认主题回归）+ `AiThemePatchApplierTest`（各 op、唯一片段、非法变量、应用后校验）+ `CaffeineChatMemoryRepositoryTest`（大消息压缩/会话隔离）。

---

## 4. 草稿预览与定版

* `POST /api/admin/themes/ai-drafts/{draftId}/preview-token`（`admin:theme`）→ 复用 `PreviewTokenService` HMAC（`draft-uuid.exp`）。
* `BlogPageController` 的 `previewDraft + token` 分支：`AiDraftCache` 命中则组装壳（`themeId=draft`，ETag 带 draftId，`no-store + noindex`）；过期返回 404 纯文本。
* 定版：前端 `填入编辑器` 或 `存为新版本`（调 `createVersion`）→ 预览 → 人工 `apply`。版本不可变模型不变（见 `docs/blog-runtime-theme.md` §6.1）。
* A 档渲染自检（2026-11-08）：抽屉 hidden iframe 挂载草稿整页（`fixtures=1`），`ready` 后发 `blog-preview:diagnose`，
  runtime 回 `blog-preview:diagnosed{report}`——静态校验 + 活 DOM 信号（挂载、横向溢出、未解析插值、契约变量计算值、window error/rejection）。
  用户点「把渲染问题发给 AI」时按当前 `mode` 拼成完整修复或补丁修复指令；不自动回灌、不烧 token。无截图（P3 不变）。

---

## 5. 框架与状态选型

* 自动装配：`spring.ai.model.chat=openai`；连接信息只有 `spring.ai.openai.*` 一份来源（`AI_*` 桥接，无 dummy 占位）。
  缺 key 时 `AiEnvironmentPostProcessor` 把 `OpenAiChatAutoConfiguration` + `ChatClientAutoConfiguration` 整个排除——
  后者必须一起排：它的 `chatClientBuilder` 是 prototype 强依赖 `ChatModel`，留在候选里一旦被 `AiConfig` 索取就启动即崩
  （`AiAutoConfigTest.skipsWithoutKey` 锁定）。其余 OpenAI 模型类型在 `application.yml` 显式 `none`。
  是否可用看 `ChatClient` bean 在不在（`ObjectProvider.getIfAvailable()`），接口层 400。
* 唯一入口 `AiConfig` 走自动装配注册（`AutoConfiguration.imports` + `@AutoConfigureAfter`）+ `defaultAdvisors(记忆)`。
  手工 `OpenAiChatModel.builder()` 三坑（v1 `AiClientBuildTest`）随删除而消失；`SimpleLoggerAdvisor` 已移除（会把整篇主题写进日志）。
* 后端 Memory：`ChatMemory`（`MessageWindowChatMemory`）+ 自研 `CaffeineChatMemoryRepository`（2h 访问过期 / 上限 1000）。
  v2.1 在 `saveAll` 前压缩大消息：整篇 HTML/CSS 只留摘要/计划/用户指令，当前主题由 `AiThemeSessionStore` 单独保存。
* 不用 LangChain4j：单兼容端 + prompt 封闭域，多 provider 抽象用不上（v1 结论保留）。
* 不用 tool calling：封闭域 prompt + 后端校验/补丁循环已覆盖；tool 流式分片与本 SSE 协议叠加排错翻倍；兼容端小模型 tool 支持参差。P3 截图回灌时再加只读 tool。
* 不用 provider-native 结构化输出：v2 的 `outputSchema` 只能保证 JSON 形状，不能保证整篇主题内容不截断；v2.1 的完整模式走 fenced html/css，
  补丁模式走小 JSON + 手工解析；彻底重写时允许直接 fenced 完整主题，避免把整篇 CSS 塞进 JSON 字符串。`max_tokens` 由 `AI_MAX_TOKENS` 控制（默认 0 = 端点默认），`finishReason=length` 时 `done.truncated=true`。
* SSE 线程：虚拟线程池（`newThreadPerTaskExecutor`，Java 25）替代 `new Thread`；`SseEmitter` 超时 = `timeout × maxRounds + 30s`；
  前端中断 → emitter 回调 → `Future.cancel(true)` 中断虚拟线程（仍属“写失败/回调发现”，不是独立取消接口）。

---

## 6. 已知缺口与后续

1. `AiThemePrompt.CONTRACT_RULES` 是契约的手工摘要，`pnpm check:contract` 管不到；新增组件 / 数据键时必须人肉同步（与 `ThemeTemplateValidator` 的手工集合同类问题）。
2. `done.html/css` 全量下发（≤320KB），弱网下 SSE 单帧大；后续可只下发 `draftId` + 分片拉取。
3. 会话记忆在后端（`MessageWindowChatMemory` 默认 20 条 + Caffeine 2h 过期），长会话仍可能丢早期版式决策；后续可做“首轮目标 + 最近补丁”快照压缩。
4. 补丁能力边界：`replaceHtml` / `replaceCss` 仍是完整文件，适合整体换风格但不适合超大主题；`replace` 要求唯一片段，模型可能因片段不唯一失败；
   `setCssVar` 替换首个同名定义（通常是 `:root`），深色模式专用变量需模型用 `appendCss` 指定 `[data-color-mode]` 选择器。后续可加结构化 DOM section / 变量 scope。
5. P3：无头浏览器三档视口截图回喂（届时加只读 `capturePreview` tool）。A 档确定性文本报告已落地（§4），截图仍不做：弱模型多模态参差 + token 贵 + flaky。
6. 编辑器卡顿（2026-11-07 Chrome trace 实测）：`el-input textarea` 全受控，65KB 下每键 100-400ms；
   改为 `LargeTextEditor.vue`（非受控 textarea + 复用 `el-textarea__inner` 样式 + 250ms debounce + 保存前 `flush()`），
   中位数 120ms→37ms；剩余为原生大文本框重排成本，不再投入优化。
   2026-11-08 追查 CSS tab 拖滚动条整窗冻死：与输入链路无关（scroll 风暴 0.7ms/200 次、强制布局 0.2ms/50 次、
   无 longtask、debounce 回写不重置 scrollTop、最长行仅 85 字符、无祖先 filter/transform）——卡在合成侧。
   硬指标：本机 WebGL MAX_TEXTURE_SIZE=16384，整块内容层 30615px（dpr 1.25 下约 38269 物理像素）远超单纹理上限。
   先按 300 行分页验证假设成立（冻死消失），随后换成正解：CodeMirror 6 视口虚拟化（只渲染可视区数十行 DOM，
   全文仍单文档、无分页概念），MIT 协议、按需引入 state/view/commands/lang-css/lang-html/language/theme-one-dark，
   走 catalog。HTML/CSS 分 `lang` 配高亮，明暗主题跟 `useTheme().isDark`（亮色贴 el-textarea 观感、暗色 oneDark）。
   编辑器弹窗补 `destroy-on-close`（关闭即销毁 2 个 CM 实例；文本状态在父级 `editor` 对象里不受影响，重开秒建）。
7. 抽屉体验（2026-11-08）：滚动改为条件吸底（用户上滑不再强制 `scrollTo`，+「回到底部」按钮）；plan 渲染为步骤时间线；
   说明文字走轻量散文渲染（先转义后套自有标签，无外部 HTML 进入）；补丁模式流式 JSON 原文不展示，只显示“正在生成编辑指令…”与最终 summary。
8. 会话生命周期：前端「新对话」重新生成 `sessionId`；切换主题时清空当前对话（`themeId` 参与后端会话 key），避免旧主题上下文串台。

---

## 7. 超时分层（v2.1）

慢模型输出整套主题（说明 + 产物几十 KB）要几分钟，`AI_TIMEOUT` 默认 300s（单轮总限时）。
okhttp 是整呼叫超时（connect/read/总时长一并算，流式读靠它）；超时只有两层：

| 层 | 配置 | 作用 |
| --- | --- | --- |
| 底层 client | `spring.ai.openai.timeout`（= `AI_TIMEOUT`）+ `max-retries: 1` | 整呼叫总超时，流式读靠它 |
| `SseEmitter` | `AI_TIMEOUT × maxRounds + 30s` | 前后端 SSE 连接上限 |

`friendly()` 沿 cause 链识别 401 / 429 / 404 / 超时（顶层多为 `Stream failed` 包裹），key 不回显。

### 7.1 截断与 reasoning

`finishReason=LENGTH` 时不再盲目进入修复轮（修复会再次撞上限、继续烧 token）：

* `outputChars == 0` 且 `reasoningChars > 0`：模型把输出预算全用在 reasoning，正文没开始。换非推理模型 / 关闭 thinking，或提高输出上限。
* `outputChars > 0` 但仍截断：正文在完成前被截断。提高 `AI_MAX_TOKENS`、缩小本轮改动范围，或换输出上限更大的模型。
* 前端 `done` 卡片显示 `输出被截断 (LENGTH)` 与 `reasoning N 字符`；后端日志同时给出 `AI_MAX_TOKENS` 与 usage。

---

## 8. 调试日志

服务端 logger：`cc.naihao.simplenotes.site.AiThemeService`。默认 INFO；需要看模型输出头/尾时临时开 DEBUG：

```bash
LOGGING_LEVEL_CC_NAIHAO_SIMPLENOTES_SITE_AITHEMESERVICE=DEBUG
```

INFO 覆盖：

* `AI 主题开始`：user（脱敏）/ session / theme / mode / model / firstRound / history / 各段字符数 / starterAsBase / maxTokens；
* `AI 第 N 轮开始`：system/user 字符数、temperature、maxTokens；
* `AI 第 N 轮完成`：durationMs、outputChars、reasoningChars、finishReason、usage（prompt/completion/total）；
* `AI 第 N 轮结果`：parseFailed、html/css 字符数、summary 长度、plan 数、errors；
* `AI 补丁应用`：ops 数、成功应用数、errors；
* `AI 主题完成`：valid、attempts、draftId、html/css 字符数、finishReason、truncated、reasoningChars、usage；
* 取消 / SSE 超时 / 断开 / 修复轮 / 会话种子 / 网关错误（含堆栈）。

DEBUG 额外输出每轮模型原始输出的头尾各 600 字符；不会输出 API Key，也不会输出整篇主题。

前端 `done` 卡片显示 `mode / outputChars / truncated / finishReason`，便于判断是“解析失败”还是“主题校验失败”（`errorKind`）。
