# 主题契约规范（Theme Contract Spec）

> **状态：v0.6（2026-11-10，P0 + P1 已实现）**。表达式面、fixture、canonical skeleton、组件 `example`、错误修复提示、`$event` / 内置组件放宽、`Intl`、生成文档与规则覆盖检查均已落地；P2 按真实证据再做。
> **定位：规范性文档（normative）**。本文定义“什么算合法主题”“什么算契约变更”“改契约必须同步什么”。
> **机器源**：`frontend/apps/blog/src/contract.ts`（配合 `types.ts` / `runtime.ts`）。本文与机器源冲突时视为缺陷，按 §9 修正。
> **关系**：`docs/blog-runtime-theme.md` §3 描述契约的语义与设计；本文描述规范性规则、错误码、fixture 与变更协议；`docs/theme-contract-v2.md` 是 2A 的历史设计记录，规范内容以本文为准。
> **目标**：让契约文档能约束后续迭代，而不是停留在“参考说明”。
> **非目标**：不把本文写成实现细节；不承诺模板表达式是安全沙箱；不约束审美、信息架构与无障碍体验。

---

## 0. 怎么使用本文

1. **封闭世界**：主题只能使用本文与 `contract.ts` 登记的能力；未登记的一律非法，不按“Vue 应该支持”推断。
2. **可判定才入规范**：一条规则如果无法写成 fixture，就不能进入规范性契约，只能放进设计理由或评审清单。
3. **文档是投影，不是唯一真相**：能力清单以 `theme-contract.json` 为准；本文的规则表与生成段必须与它保持一致。
4. **变更必须过 §9 四件套**：改能力、改语法、改校验策略，都要同时更新契约、fixture、规范表与测试。
5. **默认主题不是规范**：`packages/theme-starter` 只是示例与兜底；照抄能过不代表契约完备。
6. **优先级**：生成成功率 > 维护成本 > 一致性 > 安全护栏。校验不是安全沙箱；不要为了“看起来安全”增加生成摩擦（见 §11 威胁模型）。

---

## 1. 术语

| 术语 | 含义 |
| --- | --- |
| 契约（contract） | 主题可依赖的**能力面**（数据 / 辅助 / 动作 / 组件 / 指令 / 变量 / 路由）+ **表达式面**（模板里允许的语法子集） |
| 能力面 | 名词：有哪些数据键、组件、props、slots、events、CSS 变量、路由、URL |
| 表达式面 | 动词：模板表达式里允许哪些标识符、全局、局部绑定、修饰符、内置组件 |
| 规则（rule） | 一条可判定的约束，有稳定 ID、错误码与 fixture |
| fixture | 一段最小主题片段，声明“应当通过”或“应当失败并返回指定错误码” |
| L0 / L1 / L2 | 变更等级，见 §3 |

---

## 2. 单一来源与投影链

```text
contract.ts + types.ts + runtime.ts
        │  pnpm sync:contract
        ▼
theme-contract.json ──────────────┬──► backend/src/main/resources/theme/
        │                         │       ThemeContract（启动 fail-fast）
        │                         │       ThemeTemplateValidator
        │                         │       AiThemePrompt
        │                         │       SiteSettingCatalog
        ├──► apps/blog/dist/      │
        └──► docs/theme-contract.generated.md（计划）◄── check:docs（计划）
                                   │
runtime.ts / theme.ts ────────────┘  validateTheme（加载前再审一次）
```

约束：

- `theme-contract.json` 是生成物，**禁止手改**；修改契约只改 `contract.ts` / `types.ts` / `runtime.ts`。
- 后端启动读不到或解析失败 → 启动失败，不允许“降级成手写清单”。
- `pnpm check:contract`（已并入 `pnpm typecheck`）检查生成物是否过期；CI 必须跑。
- 计划新增 `pnpm check:docs`：检查本文的生成段 / 手写规则表是否与 JSON 一致；不引入第二份手写清单。

---

## 3. 契约版本与变更等级

```text
runtime.contract = 1
theme.contract   = N
兼容条件：runtime.contract - theme.contract ∈ [0, 1]
不兼容：不渲染主题，回落内置默认主题，管理端告警
```

| 级别 | 内容 | 规则 | 主题是否需要迁移 |
| --- | --- | --- | --- |
| **L0 破坏** | 删除 / 改名数据字段、组件、props、slots、events、CSS 变量、URL 结构；**收窄**表达式白名单；改变 `state` 枚举语义 | 升 `RUNTIME_CONTRACT`；至少同时支持 `N` 与 `N-1`；写迁移说明 + 全量回归 | 是 |
| **L1 只增** | 新增组件、动作、数据字段、slot、CSS 变量；**扩宽**表达式白名单；新增路由 | 不升版本；旧主题不受影响 | 否 |
| **L2 实现** | 校验器实现、错误文案、管理端 UI、预览协议、生成脚本 | 不升版本；不得改变合法 / 非法边界 | 否 |

**判定优先看“旧主题会不会变非法”**，而不是看改动大小：

- 扩宽 `Math` 白名单：旧主题仍合法 → L1。
- 禁止 `$event`：如果旧主题用过 → 收窄 → L0。
- 修 `v-for` 解构误判：旧主题从“非法”变“合法” → 扩宽 → L1。
- 把 slot 作用域从 union 收紧到“按组件精确校验”：可能让旧主题变非法 → 先审计，按 L0 处理。

---

## 4. 能力面规则

> 状态：✅ 已实现；🔧 待实现；⚖️ 待决策；🚫 明确不承诺。
> 错误码用于后续统一：后端 `ApiException`、前端 `validateTheme`、构建期 `check:contract` 共享同一套码。

### 4.1 结构

| Rule | 约束 | 状态 | 错误码 | fixture |
| --- | --- | --- | --- | --- |
| `TC-STRUCT-001` | HTML 必须包含 `id="app"` 根节点 | ✅ | `THEME_APP_ROOT_MISSING` | `pass/struct-root.html` / `fail/struct-no-root.html` |
| `TC-STRUCT-002` | 禁止 `<script`、`v-html`、`javascript:`、`window.`、`document.`、`localStorage`、`fetch(`、`eval(`、`import(` | ✅ | `THEME_FORBIDDEN_CONTENT` | `fail/struct-script.html` |
| `TC-STRUCT-003` | HTML ≤ 64KB；CSS ≤ 256KB | ✅ | `THEME_HTML_TOO_LARGE` / `THEME_CSS_TOO_LARGE` | `fail/struct-size.html` |
| `TC-STRUCT-004` | HTML 实体先归一（`&lt;` / `&gt;` / `&quot;` / `&#39;` / `&nbsp;` / `&amp;`），三处扫描口径一致 | ✅ | —（归一后报具体规则） | `pass/struct-entity.html` |
| `TC-STRUCT-005` | `id` 属性允许 `id="app"` / `id='app'` / `id=app` | ✅ | `THEME_APP_ROOT_MISSING` | `pass/struct-root-unquoted.html` |
| `TC-STRUCT-006` | 禁用内容按归一后的文本做子串匹配；JS 语法大小写敏感（避免 `ownerDocument.` 误伤 `document.`） | 🔧 部分 | `THEME_FORBIDDEN_CONTENT` | `fail/struct-forbidden-text.html` |

> `TC-STRUCT-006` 是白名单方向：对 `window.` / `document.` 等只扫描表达式与 URL 上下文，而不是全文子串；实现成本较高，先记录为待办。当前已改为大小写敏感，仍会误伤正文中的小写 `document.`。

### 4.2 能力清单

| Rule | 约束 | 状态 | 错误码 |
| --- | --- | --- | --- |
| `TC-CAP-001` | `<blog-*>` 必须在 `CONTRACT_COMPONENTS` 中登记 | ✅ | `THEME_COMPONENT_UNKNOWN` |
| `TC-CAP-002` | `v-*` 必须在 `CONTRACT_DIRECTIVES` 中登记 | ✅ | `THEME_DIRECTIVE_UNKNOWN` |
| `TC-CAP-003` | 表达式中的自由标识符必须来自：数据键 ∪ 辅助函数 ∪ 动作 ∪ 局部绑定 ∪ 表达式全局白名单（§5） | ✅ | `THEME_IDENTIFIER_UNKNOWN` |
| `TC-CAP-004` | `route.name` 必须来自 `CONTRACT_ROUTES`；AI 完整主题必须实现 `home` 与 `post` 分支 | ✅（AI）/ 🔧（手动保存） | `THEME_ROUTE_UNKNOWN` / `THEME_ROUTE_BRANCH_MISSING` |
| `TC-CAP-005` | 字面量链接必须符合 `CONTRACT_URLS` 前缀 | 🔧 | `THEME_URL_UNKNOWN` |
| `TC-CAP-006` | 组件未知 props：是否拒绝待定；`class` / `style` / `data-*` / `aria-*` 永远放行 | ⚖️ | `THEME_PROP_UNKNOWN` |
| `TC-CAP-007` | 主题 CSS 只能样式化已登记的 `.bc-*` / `globalParts` | ✅ | `THEME_PART_UNKNOWN` |
| `TC-CAP-008` | 未知 slot 名 / 未知作用域字段拒绝（当前为 union 口径，按组件精确校验待评估） | ✅ / ⚖️ | `THEME_SLOT_UNKNOWN` / `THEME_SLOT_SCOPE_UNKNOWN` |

### 4.3 组件、props、events、slots

| Rule | 约束 | 状态 | 错误码 |
| --- | --- | --- | --- |
| `TC-COMP-001` | `requiredProps` 必须出现（`:prop` / `v-bind:prop` / `prop=`） | ✅ | `THEME_PROP_REQUIRED` |
| `TC-COMP-002` | 组件自定义事件必须在 `events` 中登记 | ✅ | `THEME_EVENT_UNKNOWN` |
| `TC-COMP-003` | 原生 DOM 事件（`click` / `input` / `change` / `keydown` / `submit` / `focus` / `blur` / `scroll` / `contextmenu` 等）在组件上按 Vue fallthrough 放行；未知事件仍拒绝 | ✅ | `THEME_EVENT_UNKNOWN` |
| `TC-COMP-004` | 组件标签属性解析必须容忍引号内的 `>`（如 `:post="a > b ? x : y"`），不得截断后误报缺少必填 prop | ✅ | `THEME_ATTR_PARSE` |
| `TC-COMP-005` | 组件 `parts` 与主题 CSS 的 `.bc-*` 双向一致 | ✅ | `THEME_PART_UNKNOWN` |
| `TC-COMP-006` | `root` slot 传内容 = 整体接管默认 DOM 与样式；`default` / 具名 slot = 只替换内部内容 | ✅ | — |

### 4.4 CSS

| Rule | 约束 | 状态 | 错误码 |
| --- | --- | --- | --- |
| `TC-CSS-001` | 禁止 `</style>` 逃逸 | ✅ | `THEME_CSS_ESCAPE` |
| `TC-CSS-002` | 禁止 `@import` | ✅ | `THEME_CSS_IMPORT` |
| `TC-CSS-003` | `url()` 仅允许 `/api/media/` 与 `data:` | ✅ | `THEME_CSS_URL` |
| `TC-CSS-004` | `theme.overrides` 只允许 `CONTRACT_CSS_VARS` 且值为安全 token | ✅ | `THEME_OVERRIDE_UNKNOWN` / `THEME_OVERRIDE_VALUE` |

---

## 5. 表达式面（Expression Surface）

这是当前最缺的一层：校验器实际执行的子集只存在于三份正则实现里。以下规则要进入 `contract.ts` 并投影到 `theme-contract.json`。

### 5.1 封闭集合

自由标识符的合法来源固定为：

```text
CONTRACT_DATA ∪ CONTRACT_HELPERS ∪ CONTRACT_ACTIONS
∪ 局部绑定（v-for / v-slot / 箭头函数参数）
∪ CONTRACT_EXPRESSION.globals
∪ CONTRACT_EXPRESSION.eventLocals（若启用 $event）
```

除此之外一律 `THEME_IDENTIFIER_UNKNOWN`。以下不算自由标识符：

- 字符串 / 模板字面量内部；
- `.` 后面的成员名（`post.title` 的 `title`）；
- 对象字面量的键（`{ active: isActive }` 的 `active`）；
- 箭头函数参数（`item => ...` 的 `item`）；
- `v-for` / `v-slot` 声明的局部变量。

> 注意：模板字面量里的 `${...}` 目前被整体当字符串删除，属于**漏检**；是否改为扫描内部表达式见 `TC-EXPR-010`（D7）。

### 5.2 局部绑定（必须支持）

| Rule | 写法 | 状态 | 错误码 |
| --- | --- | --- | --- |
| `TC-EXPR-001` | `v-for="item in posts"` / `v-for="(item, index) in posts"` | ✅ | — |
| `TC-EXPR-002` | `v-for="({ title, slug }, index) in posts"`（一层对象 / 数组解构、别名、简单默认值） | ✅ | `THEME_IDENTIFIER_UNKNOWN` |
| `TC-EXPR-003` | `v-slot="{ post }"` / `v-slot="{ post: p }"` | ✅ | — |
| `TC-EXPR-004` | `v-slot="{ pages = [] }"` / `v-slot="{ ...rest }"` | ✅ | `THEME_SLOT_SCOPE_UNKNOWN` |
| `TC-EXPR-005` | 箭头函数参数：`(item) => ...` / `item => ...` / `({ post: p }) => ...` | ✅ | — |
| `TC-EXPR-006` | 嵌套解构（`({ a: { b } })`）暂不承诺；需要时按 L1 追加 | 🚫 | — |

### 5.3 表达式全局白名单（P0 已实现）

`CONTRACT_EXPRESSION.globals` 首批：

```text
Math, Number, String, Boolean, Array, Object, JSON, Intl, Date,
parseInt, parseFloat, isNaN, isFinite,
encodeURI, encodeURIComponent, decodeURI, decodeURIComponent
```

明确**不加入**：

```text
Function, globalThis, self, top, parent, frames, window, document,
location, navigator, history, XMLHttpRequest, WebSocket, console
```

`RegExp` / `Map` / `Set` / `BigInt` / `Symbol` / `Error` 按真实使用再按 L1 追加。

状态：✅ 已实现 17 个（含 `Intl`）。

### 5.4 `$event` 策略（已放宽）

P0 曾只允许 6 种形状；按 §11 威胁模型，这种形状白名单没有实际安全收益，只会增加生成摩擦。P1 已改为：

- 事件表达式（`@` / `v-on`）里把 `$event` 当作普通局部变量，`$event.target.value`、`$event.key`、`$event` 传参等都可以；
- 不再维护 `eventLocalShapes` 白名单；
- 保留 `window.` / `document.` / `fetch(` 等**防误用**字符串检查；
- 插值 / `:prop` / `v-if` 里出现 `$event` 仍然拒绝（它不是这些上下文里的变量，属于真实错误）；
- 表单输入优先引导 `v-model="ui.x"`；阻止冒泡用 `.stop` / `.prevent`。

状态：✅ 已实现（事件表达式放开，非事件上下文仍拒绝）。

### 5.5 修饰符

| Rule | 允许 | 状态 |
| --- | --- | --- |
| `TC-EXPR-007` | 事件修饰符：`.stop` / `.prevent` / `.once` / `.self` / `.capture` / `.passive`；键盘：`.enter` / `.tab` / `.esc` / `.space` / `.up` / `.down` / `.left` / `.right` / `.delete` | 🔧 |
| `TC-EXPR-008` | `v-model` 修饰符：`.lazy` / `.number` / `.trim` | 🔧 |

修饰符不参与自由标识符扫描；当前实现天然放行，但需要文档化并用 fixture 锁住。

### 5.6 组件上的原生事件

三种选择：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| A. 全部拒绝（只能 `@go` / `@search` 等自定义事件） | 最保守、行为可预测 | 从零生成摩擦大；Vue 用户会意外 |
| B. 原生事件白名单放行 | 兼容 Vue fallthrough，摩擦小 | 多根组件会静默失效，需主题作者自担 |
| C. 仅对单根组件放行 | 行为最好 | 需要构建期分析组件根节点，成本高 |

建议 **B**，并在文档中明确：组件多根时原生监听不会自动落到根元素；需要精确控制时把监听放到内部元素。状态：✅ 已实现（白名单放行）。

### 5.7 内置组件（已放开）

P0 曾只放行 `<Transition>` / `<TransitionGroup>` 并拒绝 `Teleport` 等；按 §11 威胁模型，内置组件不是安全边界，继续拒绝只会误伤正常写法。P1 已移除拒绝逻辑：

- 不再拦截已知内置组件；`Transition` / `TransitionGroup` / `Teleport` / `KeepAlive` 等按 Vue 行为；
- 仍只校验 `<blog-*>` 组件、表达式、指令、slot 等契约能力；
- 保留 fixture，锁住放宽后不会误判。

| Rule | 约束 | 状态 |
| --- | --- | --- |
| `TC-EXPR-009` | 内置组件不拦截；只校验 `blog-*` 与表达式能力 | ✅ |

> `TC-EXPR-010`（模板字面量 `${}` 内部扫描）明确不做，保持当前宽松行为，见 §12 D7。

### 5.8 明确不承诺

- 本文的校验是“防误用 + 契约可维护”，不是安全沙箱；按 §11 威胁模型，不追求 AST / 隔离执行。
- 静态校验无法判断交互是否正确；运行时自检 `blog-preview:diagnose` 仍是必要补充。
- 不承诺任意 Vue 语法；只承诺本文列出的子集。

---

## 6. 错误码目录

错误码需同时出现在三处，且与规则 ID 一一对应：

| 错误码 | 规则 | 默认修复提示 |
| --- | --- | --- |
| `THEME_APP_ROOT_MISSING` | `TC-STRUCT-001` | 补 `<div id="app">` 根节点 |
| `THEME_FORBIDDEN_CONTENT` | `TC-STRUCT-002` | 删除脚本 / `v-html` / `window` / `document` / `fetch` / `eval` 等 |
| `THEME_HTML_TOO_LARGE` / `THEME_CSS_TOO_LARGE` | `TC-STRUCT-003` | 精简 HTML / CSS |
| `THEME_COMPONENT_UNKNOWN` | `TC-CAP-001` | 改用已登记组件，见 §4.3 |
| `THEME_DIRECTIVE_UNKNOWN` | `TC-CAP-002` | 改用已登记指令，见 §5 |
| `THEME_IDENTIFIER_UNKNOWN` | `TC-CAP-003` / `TC-EXPR-002` | 只使用数据键 / 辅助 / 动作 / 局部变量 / 全局白名单；`$event` 见 §5.4 |
| `THEME_PROP_REQUIRED` | `TC-COMP-001` | 补必填 prop |
| `THEME_EVENT_UNKNOWN` | `TC-COMP-002` / `TC-COMP-003` | 改用已登记事件或原生事件白名单 |
| `THEME_SLOT_UNKNOWN` | `TC-CAP-008` | 改用已登记 slot |
| `THEME_SLOT_SCOPE_UNKNOWN` | `TC-CAP-008` / `TC-EXPR-004` | 只用该 slot 作用域登记的字段 |
| `THEME_PART_UNKNOWN` | `TC-CAP-007` | 只样式化已登记的 `.bc-*` |
| `THEME_CSS_ESCAPE` / `THEME_CSS_IMPORT` / `THEME_CSS_URL` | `TC-CSS-001..003` | 按提示修正 CSS |
| `THEME_OVERRIDE_UNKNOWN` / `THEME_OVERRIDE_VALUE` | `TC-CSS-004` | 只覆盖登记变量并使用安全值 |
| `CONTRACT_JSON_STALE` | §2 | 运行 `pnpm sync:contract` |
| `CONTRACT_DOC_STALE` | `TC-DOC-002` | 重新生成文档段或更新规范表 |
| `CONTRACT_RULE_UNDOCUMENTED` | `TC-DOC-003` | 在 §4 / §5 补规则 |
| `CONTRACT_FIXTURE_MISSING` | `TC-DOC-004` | 补正 / 反 fixture |
| `THEME_CONTRACT_INCOMPATIBLE` | §3 | 升级主题契约或回退默认主题 |

> 当前 P0 已让三处扫描器的**消息文案**一致，并建立 fixture 同口径；结构化的 `code` 字段属于 P2，等有机器消费者 / 多语言 / 错误统计时再做。

---

## 7. Fixture 规范

### 7.1 目录

```text
frontend/scripts/fixtures/theme/
  cases.json          # rule id → pass / fail 文件 + 期望错误码
  pass/
    struct-root.html
    expr-globals.html
    expr-destructure.html
    expr-slot-defaults.html
    expr-event-narrow.html
    comp-native-event.html
  fail/
    struct-no-root.html
    struct-script.html
    expr-forbidden-event.html
    expr-unknown-global.html
    comp-missing-prop.html
    comp-unknown-event.html
    slot-scope-unknown.html
```

`cases.json` 形状建议：

```json
{
  "TC-EXPR-002": {
    "pass": ["pass/expr-destructure.html"],
    "fail": [{ "file": "fail/expr-destructure-bad.html", "code": "THEME_IDENTIFIER_UNKNOWN" }]
  }
}
```

### 7.2 规则

- 每条规范性规则至少一个 pass + 一个 fail；纯结构性规则（如实体归一）可以只有 pass。
- fixture 是最小片段，不复制整篇默认主题。
- `pnpm check:contract`（Node）与 `ThemeTemplateValidatorTest`（Java）读取**同一份** `cases.json`，确保三处扫描器同口径。
- `theme-starter` / `theme-capabilities.html` / `theme-headless.html` 继续作为回归 fixture，不替代规则 fixture。
- 新增规则先写 fixture，再改扫描器；不允许“先改实现，测试后补”。

状态：✅ P0 已落地（`frontend/scripts/fixtures/theme/cases.json`）。

---

## 8. 文档投影与自检（P1-C 已实现）

### 8.1 文档分层

| 文件 | 内容 | 是否生成 |
| --- | --- | --- |
| 本文 | 规范、规则 ID、变更协议、决策理由 | 手写（规则表可半生成） |
| `docs/theme-contract.generated.md` | 组件 / 类型 / 路由 / 变量 / 指令 / 表达式白名单 | ✅ 由 `sync:contract --emit` 生成 |
| `docs/theme-contract-rationale.md` | 为什么收窄 / 放开、被否决的方案 | 手写，不参与校验 |

### 8.2 自检规则（瘦身版）

| Rule | 检查 | 状态 |
| --- | --- | --- |
| `TC-DOC-001` | 生成物新鲜（已存在） | ✅ |
| `TC-DOC-002` | 文档生成段与 JSON 一致：`<!-- contract:begin -->` / `<!-- contract:end -->` 区间替换后无 diff | ✅ |
| `TC-DOC-003` | 规则 ID 唯一；`cases.json` 的规则 ID 必须出现在规范表中 | ✅ |
| `TC-DOC-004` | 禁止手改 `theme-contract.json` / 生成段 | ✅（人工约定，可加前缀校验） |

> 不做“扫描文档全文的组件名 / CSS 变量”这种全量比对：历史说明、示例、迁移记录多，误报成本高于收益。
> 生成段不要包含默认主题全文，避免 starter 偏置。

---

## 9. 变更协议（Definition of Done）

任何主题能力 / 语法 / 校验策略变更，必须完成：

1. **契约**：改 `contract.ts`（能力 + 表达式 + 路由 / 可写性 / note），必要时改 `types.ts` / `runtime.ts`。
2. **fixture**：加正 / 反 fixture，并在 `cases.json` 登记规则 ID 与错误码。
3. **规范**：更新本文规则表；生成段重新生成；必要时写 rationale。
4. **生成与测试**：`pnpm sync:contract` → `pnpm typecheck` → `./mvnw test`。
5. **版本**：按 §3 判断 L0 / L1 / L2；L0 必须写迁移说明与兼容窗口。
6. **AI 投影**：如果影响生成，更新 `ThemeContract.promptSection()` 与修复提示词。

反模式（评审直接拒绝）：

- 只改校验器，不改契约；
- 只改文档，不补 fixture；
- 用默认主题当规范；
- 把正则实现写进规范（规范只写可观察行为）；
- 新增能力但不投影给 AI，然后抱怨生成通过率低。

---

## 10. AI 投影要求

| 要求 | 说明 |
| --- | --- |
| 规则 ID | 提示词包含规则 ID；结构化错误码属 P2 |
| 表达式白名单 | `CONTRACT_EXPRESSION.globals` / `eventLocals` / `modifiers` 进入 `promptSection()`；内置组件不再拦截 |
| 可写状态 | `CONTRACT_DATA[*].writable` 必须投影，明确只有 `ui` 可 `v-model` |
| 路由可用性 | `CONTRACT_DATA[*].routes` 必须投影，避免在错误页面取 `posts` / `post` |
| 最小骨架 | 完整模式注入 canonical skeleton（不是 starter 全文），与补丁模式同口径 |
| 修复提示 | 错误信息带规则 ID + 文档锚点 + 替代写法（尤其 `$event`） |
| 评测 | 保留“从零生成 fixture”的通过率基线，作为契约有效性的回归指标 |

---

## 11. 威胁模型、非目标与已知边界

### 11.1 威胁模型

- **主题作者是受信任的管理员 / AI**，不是匿名输入；主题编辑权限受 RBAC 约束。
- 能编辑主题的人，本来就能编辑文章、页面、站点配置；模板沙箱阻止不了内部人。
- 现有正则不是安全边界：`site.constructor.constructor('...')()` 今天就能到 `Function`，CSP 也为了 Vue 运行时编译允许 `unsafe-eval`。
- AST 成员访问白名单最多增加绕过成本，不是隔离执行；成本高，收益低。
- 真正的安全边界在 **RBAC / 审计 / 部署隔离 / CSP**，不在模板扫描器。
- 因此：校验目标是**防误用 + 契约可维护 + 生成成功率**；明确不做 AST 成员访问白名单、模板沙箱、以及“为安全而收紧表达式子集”。

### 11.2 其他边界

- **不约束审美**：版式、信息架构、无障碍体验靠预览、`blog-preview:diagnose` 与评审。
- **不保证运行时正确**：静态校验只保证“不越界”，交互正确性靠运行时诊断。
- **不承诺完整 Vue**：只承诺本文列出的子集；Vue 新语法默认不支持，除非按 §9 追加。
- **不把“通过校验”当安全证明**：校验只覆盖本文列出的规则，不覆盖内部人滥用或部署配置问题。

---

## 12. 待决策项

| 编号 | 议题 | 结论 |
| --- | --- | --- |
| D1 | `$event` | ✅ P1 已放宽：事件表达式里当普通局部变量（§5.4） |
| D2 | 表达式全局白名单 | ✅ 17 个纯计算全局（含 `Intl`，§5.3） |
| D3 | 组件原生事件 | ✅ 方案 B（白名单放行，§5.6） |
| D4 | 内置组件 | ✅ P1 已移除拒绝逻辑，按 Vue 行为（§5.7） |
| D5 | 未知 props | ⏸ 有真实误用证据再做；`class` / `style` / `data-*` / `aria-*` 永远放行 |
| D6 | slot 作用域 union → 按组件精确 | ⏸ 有真实误用证据再做；收紧按 L0 处理 |
| D7 | 模板字面量 `${}` 扫描 | ❌ 不做，保持宽松（收紧只增摩擦，无安全收益） |
| D8 | AST 校验 / 模板沙箱 | ❌ 不做（威胁模型不成立；不是真隔离，成本高） |

---

## 13. 实施顺序

### P0：先对齐规范与校验（✅ 已实现）

1. `contract.ts` 增加 `CONTRACT_EXPRESSION`（globals / eventLocals / 修饰符 / 原生事件；当时的 `builtinComponents` 白名单已在 P1-B 移除）。
2. `sync:contract` 写入 JSON；`ThemeContract` 读取并投影到 `promptSection()`。
3. 三处扫描器改为只读同一份表达式登记；补 `$event` / globals / 解构 / slot 默认值。
4. 修 `TC-COMP-004`（引号内 `>` 截断）、`TC-COMP-003`（组件原生事件）。
5. 建 `fixtures/theme/cases.json` + pass/fail；JS 与 Java 测试共用。
6. 结构错 / fixture 消息文案统一（稳定错误码留 P2）。

### P1：生成质量优先（进行中）

按收益排序：

1. **生成质量包（✅ 已实现）**
   - canonical skeleton：`scripts/fixtures/theme/skeleton.html` + `skeleton.css`，随 `--emit` 进入 `theme-contract.json`，完整模式注入（不是 starter 全文）；
   - 核心组件 `example`：13 个组件已填，`check:contract` 与 Java 测试会把 example 包进 `#app` 后扫描；
   - 错误信息带修复建议：未知标识符 / 事件 / prop / slot / 全局 / 内置组件。
2. **契约降摩擦包（✅ 已实现）**
   - `$event` 已放宽为事件表达式普通局部变量（移除形状白名单，§5.4）；
   - 内置组件已移除拒绝逻辑，按 Vue 行为（§5.7）；
   - 表达式全局已增加 `Intl`；`RegExp` / `Map` / `Set` 按真实使用再加（§5.3）。
3. **文档维护包（瘦身，✅ 已实现）**
   - 生成 `docs/theme-contract.generated.md`，`check:contract` 检查新鲜度；
   - `cases.json` 规则 ID ↔ 规范表覆盖 + 规则 ID 去重。
4. **评测（待做）**
   - 用同一组 from-scratch 任务对比 P1 前后通过率，作为继续投入的依据。

### P2：有真实证据再做

1. 未知 props、`v-bind="obj"` / `v-on="{...}"` 对象语法。
2. slot 作用域按组件精确化（收紧按 L0）。
3. 结构化错误码 `code`（等机器消费者 / 多语言 / 错误统计）。
4. URL 前缀、事件修饰符校验（先只文档化）。

### 明确不做

1. **AST 成员访问白名单 / 模板沙箱**：威胁模型不成立，成本高，且不是真隔离。
2. **模板字面量 `${}` 内部扫描**：当前偏宽松，收紧只增 L0 迁移和生成摩擦。
3. **任何“为安全而牺牲生成”的规则**。如果未来真的需要“不可信主题”，应换隔离 iframe / 独立域 / 服务端预渲染 / 白名单 DSL，而不是继续加正则或 AST。

---

## 14. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-11-09 | 初稿：规范性规则表、表达式面、错误码、fixture 规范、变更协议；记录 D1–D8 待决策项 |
| 2026-11-09 | P0 实现：`CONTRACT_EXPRESSION`（globals / `$event` 窄形状 / 修饰符 / 原生事件 / 内置组件）；三处扫描器同口径；解构 / slot 默认值 / `>` 截断修复；`cases.json` + JS/Java 共用；提示词投影数据 routes / writable / selfFetch |
| 2026-11-10 | 按威胁模型重排 P1/P2：新增 §11.1（管理员 / AI 受信任，不做 AST / 沙箱）；P1 改为生成质量优先（skeleton / example / 错误提示 / 降低 `$event` 与内置组件摩擦 / 瘦身文档）；D7 模板字面量扫描与 D8 AST 明确不做；错误码、未知 props、slot 精确化移到 P2 |
| 2026-11-10 | P1-A 实现：canonical skeleton（fixture + JSON + 完整模式注入）、13 个组件 `example` 并纳入扫描、错误信息补修复建议；P1-B/P1-C 待做 |
| 2026-11-10 | P1-B 实现：`$event` 在事件表达式里放开（删除形状白名单）、内置组件不再拦截、表达式全局增加 `Intl`；fixture 与提示词同步 |
| 2026-11-10 | P1-C 实现：生成 `docs/theme-contract.generated.md` 并在 `check:contract` 校验新鲜度；规则 ID 去重 + `cases.json` 覆盖检查 |
