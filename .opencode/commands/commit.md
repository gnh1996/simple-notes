---
description: 根据未提交修改总结 commit message
---

结合本地未提交修改总结 commit message。

要求：
- 遵循仓库既有风格：Conventional Commits，中文描述，格式为 type(scope): 描述
- 只依据下面的变更内容，不编造；信息不足以判断意图时，明确指出不确定点
- 若包含多组正交改动，提示是否应拆分提交
- 输出 1 条推荐信息（可直接用于 git commit -m），必要时附 1 条备选
- 未跟踪的新文件只有文件名，如需了解内容可自行读取

当前工作区状态：
!`git status --short`

变更概览（已暂存 + 未暂存）：
!`git diff HEAD --stat`

已暂存部分：
!`git diff --cached --stat`

需要看具体改动时，自行执行 `git diff HEAD -- <path>` 或 `git diff --cached -- <path>` 按文件查看，不要臆测未见细节。

补充要求：$ARGUMENTS
