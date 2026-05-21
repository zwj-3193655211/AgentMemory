# AgentMemory 需求文档

---

## 1. 项目概述

### 1.1 项目背景

随着 AI 编程助手（如 Claude Code、iFlow、Qwen 等）的广泛使用，用户与这些 Agent 的对话历史分散存储在不同位置，难以统一管理和检索。大量有价值的知识（如错误解决方案、最佳实践、技能方法）分散在会话日志中，无法有效沉淀和复用。

### 1.2 项目目标

开发一个**本地 Agent 语义化记忆引擎**，实现以下目标：
- 自动监控多个 CLI Agent 的会话日志
- 实时存储对话消息到数据库
- 支持语义化检索历史对话
- 自动分类提取有价值的知识到五大记忆库

### 1.3 应用场景

| 场景 | 描述 |
|------|------|
| 问题排查 | 遇到问题时，自动搜索历史对话中的解决方案 |
| 知识沉淀 | 将成功的解决方法自动分类存储 |
| 项目管理 | 记录项目技术栈和关键决策 |
| 技能学习 | 积累编程技能和最佳实践 |

---

## 2. 功能需求

### 2.1 核心功能

#### 2.1.1 自动监控功能

| 需求编号 | 功能描述 | 优先级 |
|----------|----------|--------|
| FR-001 | 支持监控 Claude Code 会话日志 | 高 |
| FR-002 | 支持监控 iFlow CLI 会话日志 | 高 |
| FR-003 | 支持监控 Qwen/Qoder 会话日志 | 高 |
| FR-004 | 支持监控 OpenClaw 会话日志 | 高 |
| FR-005 | 支持监控 Nanobot 会话日志 | 中 |
| FR-006 | 支持监控 Crush CLI 会话日志 | 中 |
| FR-007 | 实时检测文件变更并增量同步 | 高 |
| FR-008 | 支持配置监控目录和解析器类型 | 高 |

#### 2.1.2 语义检索功能

| 需求编号 | 功能描述 | 优先级 |
|----------|----------|--------|
| FR-009 | 支持基于向量的语义搜索 | 高 |
| FR-010 | 支持多记忆库联合检索 | 高 |
| FR-011 | 支持按 Agent 类型过滤搜索 | 中 |
| FR-012 | 支持搜索结果按相似度排序 | 高 |
| FR-013 | 支持搜索结果导出（JSON/CSV） | 中 |

#### 2.1.3 智能分类功能

| 需求编号 | 功能描述 | 优先级 |
|----------|----------|--------|
| FR-014 | 自动分类错误纠正记录 | 高 |
| FR-015 | 自动分类用户偏好信息 | 高 |
| FR-016 | 自动分类最佳实践经验 | 高 |
| FR-017 | 自动分类项目上下文 | 高 |
| FR-018 | 自动分类技能沉淀 | 高 |
| FR-019 | 支持语义去重避免重复存储 | 高 |
| FR-020 | 支持 LLM 辅助提取结构化信息 | 中 |

#### 2.1.4 数据管理功能

| 需求编号 | 功能描述 | 优先级 |
|----------|----------|--------|
| FR-021 | 支持自动清理过期数据（默认14天） | 高 |
| FR-022 | 支持手动删除指定记录 | 中 |
| FR-023 | 支持数据统计展示 | 高 |
| FR-024 | 支持会话压缩和摘要生成 | 中 |

### 2.2 用户界面需求

| 需求编号 | 功能描述 | 优先级 |
|----------|----------|--------|
| FR-025 | 提供 Web 前端界面 | 高 |
| FR-026 | 支持五大记忆库独立展示 | 高 |
| FR-027 | 支持搜索框实时搜索 | 高 |
| FR-028 | 支持会话列表展示 | 高 |
| FR-029 | 支持消息详情查看 | 高 |
| FR-030 | 支持统计仪表盘 | 中 |

### 2.3 API 接口需求

| 需求编号 | 接口描述 | 优先级 |
|----------|----------|--------|
| FR-031 | 提供 Agent 管理 API | 高 |
| FR-032 | 提供会话管理 API | 高 |
| FR-033 | 提供消息管理 API | 高 |
| FR-034 | 提供搜索 API | 高 |
| FR-035 | 提供统计 API | 高 |
| FR-036 | 提供五大记忆库 CRUD API | 高 |

---

## 3. 非功能需求

### 3.1 性能需求

| 需求编号 | 描述 | 指标 |
|----------|------|------|
| NFR-001 | 语义搜索响应时间 | < 500ms |
| NFR-002 | 消息写入吞吐量 | > 100条/秒 |
| NFR-003 | 支持并发用户数 | > 50 |
| NFR-004 | 数据库查询响应 | < 100ms |

### 3.2 可靠性需求

| 需求编号 | 描述 | 指标 |
|----------|------|------|
| NFR-005 | 服务可用性 | 99.9% |
| NFR-006 | 数据持久化 | 不丢失 |
| NFR-007 | 异常恢复 | 自动重启 |

### 3.3 可维护性需求

| 需求编号 | 描述 |
|----------|------|
| NFR-008 | 代码注释覆盖率 > 30% |
| NFR-009 | 日志记录完整 |
| NFR-010 | 支持配置文件管理 |

### 3.4 安全性需求

| 需求编号 | 描述 |
|----------|------|
| NFR-011 | 数据库密码通过环境变量配置 |
| NFR-012 | 防止 SQL 注入攻击 |
| NFR-013 | CORS 跨域安全配置 |

---

## 4. 数据需求

### 4.1 五大记忆库数据结构

| 记忆库 | 核心字段 | 保留期 |
|--------|----------|--------|
| error_corrections | title, problem, cause, solution, tags | 30天 |
| user_profiles | title, category, items | 永久 |
| best_practices | title, scenario, practice, tags | 30天 |
| project_contexts | title, project_path, tech_stack | 永久 |
| skills | title, skill_type, description, tags | 永久 |

### 4.2 会话和消息数据结构

| 表名 | 核心字段 | 说明 |
|------|----------|------|
| sessions | id, agent_type, project_path, message_count | 会话信息 |
| messages | id, session_id, role, content, embedding | 消息内容 |

---

## 5. 接口需求

### 5.1 REST API 接口列表

| API 路径 | HTTP 方法 | 功能描述 |
|----------|-----------|----------|
| /api/agents | GET | 获取 Agent 列表 |
| /api/agents | POST | 添加/更新 Agent |
| /api/sessions | GET | 获取会话列表 |
| /api/messages/{sessionId} | GET | 获取会话消息 |
| /api/search | POST | 语义搜索 |
| /api/stats | GET | 获取统计信息 |
| /api/errors | CRUD | 错误纠正记忆库 |
| /api/profiles | CRUD | 用户画像记忆库 |
| /api/practices | CRUD | 最佳实践记忆库 |
| /api/contexts | CRUD | 项目上下文库 |
| /api/skills | CRUD | 技能沉淀库 |

---

## 6. 部署与集成需求

### 6.1 环境依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Java | 17+ | 后端服务 |
| Python | 3.8+ | Embedding 服务 |
| Node.js | 18+ | 前端服务 |
| PostgreSQL | 16+ | 数据库 |
| pgvector | 0.5.0+ | 向量检索 |

### 6.2 部署方式

- **开发环境**：本地运行，使用 SQLite 数据库
- **生产环境**：Docker 容器化部署，使用 PostgreSQL

---

## 附录：需求追踪矩阵

| 需求编号 | 对应模块 | 状态 |
|----------|----------|------|
| FR-001 | FileWatcherService | ✅ |
| FR-002 | FileWatcherService | ✅ |
| FR-003 | FileWatcherService | ✅ |
| FR-004 | FileWatcherService | ✅ |
| FR-005 | FileWatcherService | ✅ |
| FR-006 | CrushDatabaseWatcher | ✅ |
| FR-007 | FileWatcherService | ✅ |
| FR-008 | ApiServer | ✅ |
| FR-009 | MemoryService | ✅ |
| FR-010 | ApiServer | ✅ |
| FR-011 | ApiServer | ✅ |
| FR-012 | MemoryService | ✅ |
| FR-013 | ApiServer | ✅ |
| FR-014 | MemoryClassifier | ✅ |
| FR-015 | MemoryClassifier | ✅ |
| FR-016 | MemoryClassifier | ✅ |
| FR-017 | MemoryClassifier | ✅ |
| FR-018 | MemoryClassifier | ✅ |
| FR-019 | MemoryService | ✅ |
| FR-020 | EmbeddingClient | ✅ |
| FR-021 | CleanupService | ✅ |
| FR-022 | ApiServer | ✅ |
| FR-023 | ApiServer | ✅ |
| FR-024 | SessionCompressionService | ✅ |
| FR-025 | Vue Frontend | ✅ |
| FR-026 | Vue Frontend | ✅ |
| FR-027 | Vue Frontend | ✅ |
| FR-028 | Vue Frontend | ✅ |
| FR-029 | Vue Frontend | ✅ |
| FR-030 | Vue Frontend | ✅ |
| FR-031 | ApiServer | ✅ |
| FR-032 | ApiServer | ✅ |
| FR-033 | ApiServer | ✅ |
| FR-034 | ApiServer | ✅ |
| FR-035 | ApiServer | ✅ |
| FR-036 | ApiServer | ✅ |

---

**文档版本**: v1.0  
**创建日期**: 2026-05-21  
**作者**: [你的姓名]  
**班级**: [你的班级]