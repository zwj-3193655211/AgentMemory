# Findings: AgentMemory ↔ Obsidian 联动调研

> 调研日期：2026-07-08 · 调研人：pi agent

## 1. AgentMemory 数据侧

### 数据库（PostgreSQL + pgvector，端口 5500 映射 5432）
5 张记忆库表 + 会话/消息表（详见 `database/init.sql`）：

| 表 | 关键字段 | 保留期 |
|----|----------|--------|
| `error_corrections` | title, problem, cause, solution, example, tags[], agent_type, session_id, embedding(512) | 30 天 |
| `user_profiles` | title, category, items(JSONB), confidence, embedding | 永久 |
| `best_practices` | title, scenario, practice, rationale, tags[], source_session, embedding | 30 天 |
| `project_contexts` | title, project_name, project_path, tech_stack[], key_decisions(JSONB), structure(JSONB), embedding | 永久 |
| `skills` | title, skill_type, description, steps(JSONB), tags[], embedding | 永久 |
| `sessions` | id, agent_type, project_path, title, summary, message_count, started/ended_at | 14 天 |
| `messages` | id, session_id, role, content, raw_json, embedding(1536) | 14 天 |

### REST API（`backend/src/main/java/com/agentmemory/api/ApiServer.java`）
- `GET /api/{errors|profiles|practices|contexts|skills}` — 列表
- `GET /api/{...}/{id}` — 单条
- `GET /api/{...}/export` — 整库 JSON 导出（`exportAsJson`，文件名带时间戳）
- `GET /api/sessions[/export]` — 会话
- `POST /api/search` — 语义搜索（embedding 服务 :8100）
- `GET /api/stats`、`GET /api/health`
- 前端默认 API 端口为 **8082**（`frontend/src/services/api.ts` 中 `VITE_API_BASE || http://localhost:8082/api`），AGENTS.md 写 8080，需以实际运行为准

### 现状
- Docker / 后端当前**未运行**（`/api/stats` 无响应）
- 前端 Vue3+Element Plus，有 Dashboard 图表（ECharts）

## 2. Obsidian 侧

### 环境
- Obsidian 已安装（`D:\tools\Obsidian`，版本 1.12.4）
- `%APPDATA%\obsidian\obsidian.json`：`{"vaults":{...},"cli":true}` —— **CLI 功能已在应用侧启用**，但 `obsidian` 命令不在 PATH，需安装 CLI 工具（npm 包或官方 binary）
- 两个 vault：
  - `C:\Users\31936\SuperMemory`（**当前打开**，obsidian.json ts=1773242880815）
  - `C:\Users\31936\Desktop\个人知识库`（AI / blog / 保研学习计划 / 开发 / 文稿 / 日记 / 算法）

### SuperMemory vault 已有 agent_memory 生态（手工维护，agent 写入）
```
agent_memory/
├── 00-设计文档.md / agent-公约.md / agent-重大错误记录.md
├── knowledge/docs/（iflow/openclaw/shared-skills、lessons-learned）
├── knowledge/projects/（博查搜索、浏览器控制、博客平台）
├── long-term/entities/（实体库.base + 说明）
├── long-term/history/ + preferences/user-preferences.md
├── plans/（plan书写规范、SuperMemory-iflow/openclaw-plan、任务计划索引.base）
└── sessions/（会话索引.base、2026-03-14-iflow.md、2026-03-15-openclaw.md）
根：记忆库索引.base（SuperMemory 总索引，schema+records+views 结构）
```
> 注意：vault 内已有 .base 文件是 **旧版 schema（schema.fields/records）**，与 obsidian-bases skill 文档的 **新版 schema（filters/formulas/views）** 不同。新版 Obsidian Bases 用 filters+views。生成新 .base 时用新版语法；旧文件不动。

### 可复用 skills
| Skill | 用途 |
|-------|------|
| obsidian-markdown | 生成双链笔记（wikilink、frontmatter、callout） |
| json-canvas | 生成 .canvas 知识图谱（nodes/edges，16 位 hex id） |
| obsidian-bases | 生成 .base 数据库视图（filters/formulas/views） |
| obsidian-cli | 若安装 CLI，可直接读写 vault 验证 |

## 4. 后续补充调研（2026-08-08，记忆重构）

### 各 Agent 记忆文件盘点（8 个主力 agent）
| Agent | 记忆位置 | 格式 | 解析器 |
|-------|----------|------|--------|
| hermes | `~/.hermes/memories/USER.md` + `MEMORY.md` | Markdown，§ 分隔 | Markdown-P |
| pi | `~/.pi/agent/sessions/*/*.jsonl` | JSONL 事件流 | JSONL-P |
| claude code | `~/.claude/AGENTS.md`、`rules/`、`agents/` | Markdown 规则 | Markdown-P |
| workbuddy | `~/.workbuddy/USER.md`（frontmatter）+ `memory/*.md` | Markdown | Fm-Markdown-P |
| minimax code | `~/.minimax/memory/user.md` | 结构化 Markdown | Structured-MD-P |
| mavis | `~/.mavis/agents/mavis/agent.md` + `sqlite.db` | Markdown + SQLite（daily 日志价值低） | MD + SQLite-P |
| marvis | `~/.marvis/database/memory.db`（user_profile 表 59 条） | SQLite | SQLite-P |
| codex | `~/.codex/AGENTS.md` + `memories_1.sqlite` | Markdown + SQLite | MD + SQLite-P |

### 关键结论
- SQLite 源（marvis/codex/mavis）需复制临时文件只读打开（避免锁冲突，复用 CrushDatabaseWatcher 模式）
- mavis daily 日志是纯会话轨迹（无实质内容），仅取 agent.md 定义文件
- marvis user_profile 表已有 59 条结构化画像，直接可导
- 用户确认：项目上下文表直接删除；经验合并单页 tab；技能候选在技能页内 tab
- 追加需求：完成后需优化压缩提示词 + 用 Qwen3.5-2B 测试压缩效果

## 3. 联动方案要点（知识图谱）

### 核心思路
AgentMemory DB 是**数据源**（结构化、可语义搜索）；Obsidian 是**可视化 + 长期记忆层**（双链图谱、Canvas、Base 检索）。双向同步：

```
AgentMemory (PG+pgvector)
   │  API 导出 / 直接查库
   ▼
导出脚本 (Python)
   │  生成双链笔记 .md + .canvas + .base
   ▼
SuperMemory vault/agent_memory/agentmemory/
   │  Obsidian 原生：图谱视图 + Canvas + Bases
   ▼
（反向）FileWatcher 监控 vault → 手工笔记导入 DB + embedding → /api/search 可检索
```

### 关系建模（决定图谱形状）
- 节点类型：项目（project_contexts）、技能（skills）、错误（error_corrections）、实践（best_practices）、会话（sessions）、用户画像（user_profiles）
- 关系边（wikilink / canvas edge）：
  - 记忆 ↔ 会话：`source_session` / `session_id` → "产生于"
  - 项目 ↔ 技能/错误/实践：共享 project_name / tags → "应用于"/"解决"
  - 共享 tags：互为相关
  - user_profiles ↔ 项目：用户偏好上下文

### 幂等与同步策略
- 文件名含 DB 主键 id（如 `错误-<id>.md`），重复导出覆盖不重复
- 时间戳记录上次同步点（增量）

### 风险/注意
- messages 表 embedding 维度 1536，记忆库 512 —— 导出时不需要 embedding 列
- 会话/错误/实践有 14/30 天保留期 —— 导出时可选不过滤，让 Obsidian 长期留存
- CLI 未装：不阻塞主路径（直接写文件即可），仅验证时可选装
