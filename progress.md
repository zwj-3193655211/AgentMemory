# Progress: AgentMemory ↔ Obsidian 联动

## Session Log

### 2026-07-08 — 调研阶段
- [x] 读取 4 个 obsidian skills（bases / json-canvas / cli / markdown）
- [x] 读取 `database/init.sql`：确认 5 大记忆库 + sessions/messages 表结构
- [x] 读取 `ApiServer.java`：确认 REST 路由（/api/errors|profiles|practices|contexts|skills、/export、/search、/stats）
- [x] 检查运行状态：Docker 未启动、后端未运行（/api/stats 无响应）、obsidian CLI 不在 PATH
- [x] 发现两个 vault：`C:\Users\31936\SuperMemory`（当前打开）、`C:\Users\31936\Desktop\个人知识库`
- [x] 查看 SuperMemory vault 现有 agent_memory 生态（手工双链笔记 + 旧版 .base）
- [x] 确认工具链：Python 3.13.7、Node v23.9.0、npm 11.6.0 可用
- [x] 创建 task_plan.md / findings.md / progress.md

## 待办（下一步，待用户确认）
- [ ] 确认联动方向：仅导出 or 双向同步？
- [ ] 确认目标 vault：SuperMemory（推荐）
- [ ] 确认范围：5 大记忆库全量 or 先做项目+技能+错误？

## 测试结果
| 测试 | 结果 | 备注 |
|------|------|------|
| curl /api/stats | ❌ 无响应 | 后端未启动 |
| docker ps | ❌ daemon 未运行 | 需启动 Docker Desktop |
| obsidian CLI | ❌ 命令不存在 | obsidian.json 已启用 cli:true，需装 CLI 工具 |
