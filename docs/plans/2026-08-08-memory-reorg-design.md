# 设计文档：记忆系统重构（Agent 记忆导入 + 会话管理 + 经验合并）

**日期**: 2026-08-08
**状态**: 已确认（用户审批通过）
**范围**: 用户画像 / 项目上下文 / 实践经验 / 技能沉淀 四大模块重构

---

## 1. 背景与目标

原系统从会话内容自动提取五大记忆库，但存在核心问题：
1. **用户画像**：重复劳动 —— 各 agent（hermes/pi/claude/workbuddy/minimax/mavis/marvis/codex）已有自己的记忆文件，系统应直接读取而非重新提取
2. **项目上下文**：偏离初衷 —— 本意是按项目分类会话，实际做成了"提取项目技术栈/决策"，应回归会话管理
3. **实践经验 + 错误纠正**：本质同源（错误场景下的正确做法 vs 最佳实践），应合并
4. **技能沉淀**：正则提取质量差（"这不合理呀…"被误判为技能），需 LLM 提取 + 人工确认
5. **Agent 容纳不完整**：系统只检测了 10 个 agent，但 8 个主力 agent（hermes/pi/claude/workbuddy/minimax/mavis/marvis/codex）需要全面接入（agents 表 + 会话导入 + 记忆同步 + 前端展示）

**目标**：
- 8 个 agent 全面接入系统（agents 表注册 + 会话导入 + 记忆同步 + 前端展示）
- 用户画像 = 各 agent 记忆文件的统一索引 + 语义搜索入口
- 项目维度 = 会话分组管理（标题懒生成 + 压缩 + 删除闭环）
- 实践经验 = 单表双类型（best_practice / error_correction）
- 技能 = LLM 候选 + 人工确认，流程化步骤描述

---

## 2. 设计决策（用户确认）

| # | 决策 | 选择 |
|---|------|------|
| 1 | 用户画像数据流 | A：同步导入落库（user_profiles 做索引缓存）|
| 2 | 扫描触发方式 | C：定时扫描 + 手动触发 |
| 3 | project_contexts 处理 | A1：废弃该表，项目维度用 sessions.project_path |
| 4 | 会话标题生成 | C：懒生成（首次展示时生成 + 缓存）|
| 5 | 经验合并展示 | A1：单页面 + 类型 tab |
| 6 | 技能提取 | B3：LLM 提取 + 人工确认 |
| 7 | project_contexts 数据 | 直接删除 |
| 8 | 技能候选入口 | 技能页面内 tab |

---

## 3. Agent 全接入清单（8 个主力 agent）

| Agent | 检测目录 | 会话存储 | 会话解析器 | 记忆文件 | 画像解析器 |
|-------|----------|----------|------------|----------|------------|
| hermes | `~/.hermes` | `state.db`（sessions 68 + messages 7532，SQLite）| 新增 SqliteSessionParser | `memories/USER.md`, `MEMORY.md` | §分隔 Markdown |
| pi | `~/.pi/agent/sessions` | `*/*.jsonl`（JSONL 事件流）| 已有 parsePiMessage ✅ | 无独立 USER.md（从会话提取）| JSONL Parser |
| claude code | `~/.claude/projects` | `*/*.jsonl`（JSONL）| 已有 parseClaudeMessage ✅ | `AGENTS.md`, `rules/*.md`, `agents/*.md` | Markdown Parser |
| workbuddy | `~/.workbuddy/projects` | `*.jsonl` | 已有 WorkBuddyWatcher ✅ | `USER.md`（frontmatter）, `memory/*.md` | Fm-Markdown Parser |
| minimax code | `~/.minimax/agents/coder/sessions` | 会话（暂空，JSONL 待格式确认）| 新增 JsonlSessionParser | `memory/user.md`（结构化）| Structured-MD Parser |
| mavis | `~/.mavis/sqlite.db` | `session_messages`（610 条，SQLite）| 新增 SqliteSessionParser | `agents/mavis/agent.md` | Markdown Parser |
| marvis | `~/.marvis/database/memory.db` | `conversations`/`conversation_detail`（10557 条，SQLite）| 新增 SqliteSessionParser | `user_profile` 表（59 条）| SQLite Parser |
| codex | `~/.codex/sessions` | `rollout-*.jsonl`（CLI/桌面同一格式）| 已有 parseCodexMessage ✅ | `AGENTS.md` + `memories_1.sqlite` | MD + SQLite Parser |

**设计要点**：
- 映射表可配置（配置文件新增 `agent_memory_sources` 段），新增 agent 只需加一条配置
- 会话解析分两类：**JSONL 流式**（FileWatcherService 扩展 parserType）和 **SQLite 轮询**（新增 SessionDbWatcher 或扩展 AgentMemorySyncService）
- SQLite 源（hermes/mavis/marvis/codex memories）通过复制临时文件只读打开（避免锁冲突，复用 CrushDatabaseWatcher 模式）
- 会话导入后复用现有记忆提取流水线（MemoryClassifier + Extractor + LLM）
- pi 会话文件大：每项目目录只取最新 jsonl，限制 10 个项目

---

## 4. 数据库变更

### 4.1 user_profiles（改造）
```sql
ALTER TABLE user_profiles ADD COLUMN source_agent VARCHAR(50);
ALTER TABLE user_profiles ADD COLUMN source_path TEXT;
-- items JSONB 存解析条目；embedding 保持
```

### 4.2 experiences（新建，替代 error_corrections + best_practices）
```sql
CREATE TABLE experiences (
    id VARCHAR(100) PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    type VARCHAR(20) NOT NULL,          -- 'best_practice' | 'error_correction'
    scenario TEXT NOT NULL,             -- 原 problem/scenario
    practice TEXT NOT NULL,             -- 原 solution/practice
    rationale TEXT,                     -- 原 cause/rationale
    example TEXT,
    tags TEXT[],
    source_session VARCHAR(100),
    embedding vector(1024),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN DEFAULT false
);
-- 迁移：error_corrections → type='error_correction'；best_practices → type='best_practice'
```

### 4.3 skills（加字段）
```sql
ALTER TABLE skills ADD COLUMN status VARCHAR(20) DEFAULT 'approved';  -- pending/approved/rejected
ALTER TABLE skills ADD COLUMN extracted_by VARCHAR(20) DEFAULT 'manual'; -- llm/manual
```

### 4.4 project_contexts（废弃）
- 直接 DROP TABLE；前端 Contexts.vue 移除

### 4.5 project_contexts（废弃）
- 直接 DROP TABLE；前端 Contexts.vue 移除

### 4.6 sessions（已含 title，无需改结构，仅加懒生成逻辑）

### 4.7 agents 表（扩展支持 8 个 agent）
```sql
-- agents 表已有 name/display_name/log_base_path/cli_path/parser_type/enabled
-- 新增记忆源相关字段（可选，或存配置文件中）
ALTER TABLE agents ADD COLUMN memory_sources JSONB;  -- [{"path":"...","format":"markdown"|"sqlite"|"jsonl"}]
ALTER TABLE agents ADD COLUMN session_db_path TEXT;  -- SQLite 会话库路径（hermes/mavis/marvis）
```

---

## 5. 后端服务设计

### 5.1 AgentMemorySyncService（新，定时 + 手动，全面接入 8 agent）
```
extends ScheduledServiceBase（每 60 分钟）
+ syncNow() 手动触发（API）
+ syncAgentSessions(agent) 会话导入
流程（分两类）：
  1. 画像记忆同步：遍历 agents 表 memory_sources
    → 检查 mtime/hash（记录在 sync_state 表）
    → 已变更：调对应 Parser 解析
    → 条目 upsert 到 user_profiles（按 source_agent+内容 hash 去重）
    → 新条目生成 embedding
  2. 会话导入（SQLite 型 agent：hermes/mavis/marvis）
    → 复制临时库 → 读 sessions/messages 表 → 增量导入 sessions/messages
    → 新会话参与记忆提取流水线（MemoryClassifier）
  记录 sync 统计（成功/失败/新增/更新数）
失败隔离：单源失败不影响其他源，错误入日志
JSONL 型 agent（pi/claude/workbuddy/codex/minimax）继续由 FileWatcherService/Watcher 实时导入
```

### 5.1b FileWatcherService 扩展（新增 parserType）
- 新增 `parseMavisMessage`（若走 JSONL）、`parseMinimaxMessage`（格式确认后）
- SQLite 型（hermes/mavis/marvis）由 AgentMemorySyncService 轮询导入，不实时监控

### 5.1c AgentDetectorService 扩展
- 新增检测：hermes（~/.hermes）、mavis（~/.mavis）、marvis（~/.marvis）、minimax（~/.minimax）
- 现有：iflow/claude/codex/openclaw/nanobot/qwen/qoder/crush/workbuddy/pi 已检测

### 5.2 会话标题懒生成
```
API: GET /api/sessions/{id}/title
  若 sessions.title 非空 → 返回缓存
  否则 → 取会话前 N 条 user 消息 → LLM 生成（或规则截断）→ 写回 → 返回
前端首次渲染项目视图时批量触发缺失标题
```

### 5.3 会话删除（软删除）
```
API: DELETE /api/sessions/{id}/messages
  → UPDATE messages SET deleted=true（原压缩摘要保留）
前端 Compression.vue 增加"删除原消息"按钮
```

### 5.4 experiences API（合并端点）
```
GET/POST/PUT/DELETE /api/experiences（type 参数过滤）
兼容旧端点：/api/errors、/api/practices 重定向或保留别名
```

### 5.5 skills 状态 API
```
GET /api/skills?status=pending
POST /api/skills/{id}/approve
POST /api/skills/{id}/reject
GET /api/skills/pending-count（前端红点）
清理：>7 天的 pending 自动删除（CleanupService 扩展）
```

### 5.6 agents 全接入 API
```
GET /api/agents → 8 个 agent 状态（含解析器/会话数/记忆源）
POST /api/agents/{id}/sync → 单 agent 手动同步（画像+会话）
POST /api/agents/{id}/import-sessions → 强制重导会话（SQLite 型）
```

---

## 6. 前端变更

| 视图 | 变更 |
|------|------|
| Profiles.vue | 显示 source_agent 徽标、手动"立即同步"按钮、来源文件链接 |
| 新增 ProjectView.vue | 按 project_path 分组的会话树 + 标题懒生成触发 |
| Errors.vue + Practices.vue | 合并为 Experiences.vue，顶部 tab（全部/最佳实践/错误纠正）|
| Skills.vue | 加"技能候选"tab（pending 列表 + 确认/忽略按钮 + 红点计数）|
| Compression.vue | 按项目筛选 + "删除原消息"按钮 |
| App.vue 导航 | 移除"错误纠正"独立菜单（并入实践经验）、移除"项目上下文"菜单（改为"项目会话"）|
| 新增 Agents.vue（或改造 Settings）| 8 个 agent 状态卡：检测状态/会话数/记忆源/最后同步时间 + 手动同步按钮 |

---

## 7. 压缩提示词优化（追加需求）

在完成上述重构后：
1. 审查 `application.conf` 中 `llm.extractionPrompt` + `SemanticCompressor` 的摘要提示词
2. 用新模型（Qwen3.5-2B 本地 / config 中的 llm_providers）优化：
   - 摘要结构：目标/决策/问题/结论 四段式
   - 压缩质量评估：对比压缩前后信息召回率
3. 测试：选取 3-5 个真实长会话，对比优化前后摘要质量

---

## 8. 错误处理与风险

| 风险 | 缓解 |
|------|------|
| SQLite 文件锁（marvis/codex 运行中）| 复制临时文件后只读打开（复用 Crush 模式）|
| pi/claude 会话文件巨大 | 只读最新文件、限量（如每源最近 10 个会话）|
| 格式解析失败 | 单源隔离，失败记录日志，不中断整体 |
| 向量维度 512→1024 | 迁移脚本 + 重新生成 embedding（复用 migrate_embedding_dimension.sql）|
| 旧数据迁移丢失 | experiences 迁移前备份 error_corrections/best_practices |

---

## 9. 交付物清单

- [ ] 数据库迁移脚本 `database/migrate_memory_reorg.sql`
- [ ] 后端：AgentMemorySyncService + Parsers + API 变更
- [ ] 前端：Experiences.vue / ProjectView.vue / Skills 候选 tab / Profiles 同步按钮
- [ ] 配置：agent_memory_sources 映射 + 压缩提示词优化
- [ ] 测试：迁移验证、同步功能、压缩质量对比
- [ ] 文档：AGENTS.md / README 更新
