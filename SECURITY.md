# 安全策略

## 支持范围

项目处于预发布阶段（pre-1.0），只对 **main 分支的最新提交** 提供安全修复。

## 报告漏洞

请通过 GitHub 的 **Private vulnerability reporting**（仓库 → Security → Advisories → Report a vulnerability）私下报告，**不要开公开 issue**。

报告时请尽量附上：

- 影响版本（`git describe` 或 `/api/version` 的输出）
- 复现步骤与请求样例（**不要**贴真实的 JWT / 数据库口令）
- 影响面判断（未授权访问 / 越权 / 数据泄露 / RCE 等）

## 已知设计边界（不算漏洞）

- root 初始口令 `admin@123456` 是迁移内置的公开值：部署时必须设置 `ROOT_INITIAL_PASSWORD`（缺失且 root 仍为默认口令时启动失败），首次登录后应改密
- Web 端 JWT 存 localStorage，XSS 即等于凭证泄露：这是已接受的取舍，缓解手段见 `docs/auth-rbac.md` §9
- 单实例部署：登录限流 / 授权快照 / AI 会话都在进程内，多副本需自行加共享缓存
- 媒体文件默认 `LOCAL` 存储且 `/api/media/{id}` 公开可读，敏感文件不应放入公开媒体库
