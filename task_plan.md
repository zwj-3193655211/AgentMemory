# Task Plan: AgentMemory ↔ Obsidian 联动，生成知识图谱

## Goal
将 AgentMemory 五大记忆库（错误纠正/用户画像/实践经验/项目上下文/技能沉淀）+ 会话数据导出为 Obsidian 双链笔记与 Canvas 知识图谱，并打通反向链路（Obsidian 笔记导入 AgentMemory 语义搜索），实现双向联动。

## Current Phase
Phase 0（调研已完成，待用户确认方案）

## 调研结论摘要
- AgentMemory：PostgreSQL + pgvector，5 张记忆库表 + sessions/messages，REST API（含 /api/*/export JSON 导出、/api/search 语义搜索）。后端当前未运行、Docker 未启动。
- Obsidian：已安装；有两个 vault —— `C:\Users\31936\SuperMemory`（当前打开，已含 agent_memory/ 手工双链结构）和 `C:\Users\31936\Desktop\个人知识库`。
- obsidian.json 已启用 `"cli": true`，但 `obsidian` 命令行不可用（需安装 obsidian-cli npm 包或本地 binary）。
- 已有 skills 可复用：obsidian-bases（.base 索引）、json-canvas（.canvas 图谱）、obsidian-markdown（双链笔记）、obsidian-cli（若装上）。

## Phases

### Phase 0: 方案确认（当前）
- [x] 调研 AgentMemory 数据模型与 API（5 大记忆库、sessions、messages、export 端点）
- [x] 调研 Obsidian 环境（vault 位置、现有 agent_memory 结构、CLI 状态）
- [x] 识别可复用的 obsidian skills（bases / json-canvas / markdown / cli）
- [x] 输出调研结论与任务规划（本文件 + findings.md）
- [ ] 与用户确认联动方向与范围（双向 or 仅导出、目标 vault）
- **Status:** in_progress

### Phase 1: 环境准备
- [ ] 启动 Docker PostgreSQL + 后端 + 前端（验证 API 可用）
- [ ] 安装 obsidian CLI（npm i -g obsidian-cli 或下载官方 CLI），验证 `obsidian search` 可用
- [ ] 确定目标 vault 路径（默认 SuperMemory）与数据子目录 `agent_memory/agentmemory/`
- **Status:** pending

### Phase 2: 数据导出器（AgentMemory → Obsidian 笔记）
- [ ] 编写导出脚本（Python，复用/仿照 embed_server.py 风格），从 API 拉取 5 大记忆库 + 会话
- [ ] 每条记录生成一个 .md 笔记：frontmatter（id/tags/agent/date/source）+ 正文（problem/solution、practice/rationale、steps、key_decisions、items 等）
- [ ] 双链生成规则：
  - 共享 tags → 互为 wikilink
  - session_id → 会话笔记链接
  - project_contexts ↔ best_practices/error_corrections/skills 按 project 关联
  - 记忆笔记 → 会话笔记 → 会话内消息
- [ ] 幂等性：以 DB 主键 id 为文件名后缀，重复导出不产生重复文件
- **Status:** pending

### Phase 3: 知识图谱可视化
- [ ] 用 json-canvas skill 生成 `.canvas` 知识图谱文件：
  - 节点：项目上下文（分组）、技能、错误纠正、实践经验、会话
  - 边：带 label（"解决"、"使用"、"产生于"、"应用于"）与方向
  - 颜色区分类型（记忆库/会话/项目）
- [ ] 图谱文件放入 vault，验证在 Obsidian Canvas 中可打开、可缩放
- **Status:** pending

### Phase 4: Obsidian 检索层（.base 索引）
- [ ] 用 obsidian-bases skill 生成 .base 文件：错误库、实践库、技能库、项目库、会话库
- [ ] 更新 `记忆库索引.base` 总入口，挂载新模块
- [ ] 验证表格视图、筛选、公式（如近30天、按 agent 分组）
- **Status:** pending

### Phase 5: 反向联动（Obsidian → AgentMemory，可选增强）
- [ ] 让 AgentMemory FileWatcherService 监控 vault 的 agent_memory 目录，导入手工笔记
- [ ] 或写导入脚本：解析 vault 内 .md（frontmatter + wikilinks）→ 写入 5 大记忆库 + embedding
- [ ] 打通 `/api/search` 语义搜索对 Obsidian 笔记的检索
- **Status:** pending

### Phase 6: 自动化与交付
- [ ] 定时同步（Windows 计划任务 / start.bat 挂钩）
- [ ] 写文档：README 段 + AGENTS.md 更新
- [ ] 端到端验证：数据 → 笔记 → 图谱 → 索引 → 搜索
- **Status:** pending

## Decisions
| # | 决策 | 理由 |
|---|------|------|
| 1 | 首选 SuperMemory vault | 当前打开、已有 agent_memory 生态 |
| 2 | 导出脚本用 Python | 项目已有 embedding_service Python 依赖，且无需编译 |
| 3 | 图谱用 .canvas（json-canvas skill）| Obsidian 原生支持，无需第三方插件 |
| 4 | 双链为主、.base 为辅 | 图谱视图靠双链驱动，.base 提供数据库式检索 |
| 5 | 画像：同步导入落库 | 文件为源，user_profiles 做索引缓存，可向量搜索 |
| 6 | 扫描：定时+手动 | 小时级自动 + 前端按钮立即同步 |
| 7 | project_contexts 废弃，直接删除数据 | 回归会话管理初衷，项目维度用 sessions.project_path |
| 8 | 会话标题：懒生成+缓存 | 不阻塞导入、不浪费 token |
| 9 | 经验合并：单表单页+type tab | 字段重合，type 区分两视角 |
| 10 | 技能：LLM提取+人工确认，页内 tab | 提高质量，用户把控 |
| 11 | 8 个 agent 记忆源全部纳入 | hermes/pi/claude/workbuddy/minimax/mavis/marvis/codex |
| 12 | 压缩提示词优化 + 新模型测试 | 用户追加需求 |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| 后端/Docker 未运行，API 无法 curl | 1 | Phase 1 先启动服务再验证 |
| obsidian CLI 不在 PATH | 1 | Phase 1 安装 obsidian-cli |
