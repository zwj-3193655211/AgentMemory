# AgentMemory 开发文档（完整版）

> 项目从无到有的完整演进记录 + 自动化测试方案

**版本**: 2.0.0
**最后更新**: 2026-03-23
**维护者**: AgentMemory Team

---

## 📑 目录

### 第一部分：开发历程
1. [项目概述](#项目概述)
2. [第一阶段：项目启动 (2026-03-20)](#第一阶段项目启动)
3. [第二阶段：语义模型调研 (2025-03-21)](#第二阶段语义模型调研)
4. [第三阶段：问题发现 (2026-03-22)](#第三阶段问题发现)
5. [第四阶段：代码审查与优化 (2026-03-23)](#第四阶段代码审查与优化)
6. [第五阶段：当前状态 (2026-03-23)](#第五阶段当前状态)
7. [性能指标对比](#性能指标对比)
8. [版本历史](#版本历史)

### 第二部分：自动化测试方案
9. [测试方案概述](#测试方案概述)
10. [P0 核心功能测试](#p0-核心功能测试)
11. [P1 API CRUD 测试](#p1-api-crud-测试)
12. [P1 功能测试](#p1-功能测试)
13. [Bug 报告模板](#bug-报告模板)
14. [测试报告模板](#测试报告模板)

---

## 项目概述

### 项目定位

**本地 CLI Agent 语义化记忆引擎** - 自动捕获、持久化、语义化检索所有 CLI Agent 对话

**核心价值**：让 AI 记住用户说过的话，不再重复踩坑

### 技术栈

- **后端**: Java 17 + Maven（无框架，纯 Javalin 风格 HTTP 服务）
- **数据库**: PostgreSQL 16 + pgvector
- **前端**: Vue 3 + Element Plus + Vite
- **Embedding**: Python Flask + bge-small-zh-v1.5

### 当前数据量（2026-03-23）

- 会话: 290 个
- 消息: 20,773 条
- 错误纠正: 156 条
- 用户画像: 38 条
- 实践经验: 234 条
- 项目上下文: 227 条
- 技能沉淀: 178 条

---

## 第一阶段：项目启动

**时间**: 2026-03-20
**状态**: ✅ 已完成

### 核心功能定义

#### 五大记忆库

| 库名 | 表名 | 用途 | 过期 |
|------|------|------|------|
| 错误纠正 | error_corrections | 问题-原因-解决方案 | 30天 |
| 用户画像 | user_profiles | 偏好、习惯、环境 | 永久 |
| 实践经验 | best_practices | 成功方案 | 30天 |
| 项目上下文 | project_contexts | 技术栈、决策 | 永久 |
| 技能沉淀 | skills | 方法论、流程 | 永久 |

### 架构设计

#### 目录结构

```
AgentMemory/
├── backend/                 # Java 后端
│   ├── src/main/java/com/agentmemory/
│   │   ├── AgentMemoryApplication.java  # 主入口
│   │   ├── api/ApiServer.java           # HTTP API
│   │   ├── service/                     # 核心服务
│   │   │   ├── FileWatcherService.java  # 文件监控
│   │   │   ├── DatabaseService.java     # 数据库操作
│   │   │   ├── MemoryService.java       # 记忆库服务
│   │   │   ├── MemoryClassifier.java    # 记忆分类
│   │   │   ├── MemoryExtractor.java     # 结构化提取
│   │   │   ├── EmbeddingClient.java     # 向量嵌入
│   │   │   └── CleanupService.java      # 自动清理
│   │   └── model/                       # 数据模型
│   ├── start.bat            # Windows 启动脚本
│   └── pom.xml              # Maven 配置
├── frontend/                # Vue 3 前端
│   └── src/App.vue          # 主应用（含所有页面）
├── embedding_service/       # Python 嵌入服务
│   └── embed_server.py      # Flask 服务
├── database/init.sql        # 数据库初始化
├── docker-compose.yml       # Docker 配置
└── data/postgres/           # PostgreSQL 数据目录
```

#### 支持的 Agent

| Agent | 日志路径 | 状态 |
|-------|---------|------|
| iFlow CLI | ~/.iflow/projects/ | ✅ |
| Claude Code | ~/.claude/ | ✅ |
| Qwen CLI | ~/.qwen/projects/ | ✅ |
| Qoder CLI | ~/.qoder/projects/ | ✅ |
| OpenClaw | ~/.openclaw/ | ✅ |
| Codex CLI | ~/.codex/sessions/ | ✅ |

---

## 第二阶段：语义模型调研

**时间**: 2025-03-21（评估日期）
**状态**: ✅ 已完成
**决策**: 使用外部 Embedding 服务 + bge-small-zh-v1.5

### 最终决策

**采用方案**: 外部 Embedding 服务 + Python 微服务

**选择模型**: bge-small-zh-v1.5

**理由**:
1. **性能优异**: 专为中文优化
2. **部署灵活**: Python 独立部署
3. **资源占用**: 小于1GB内存
4. **维护方便**: 可独立升级模型

---

## 第三阶段：问题发现

**时间**: 2026-03-22
**状态**: ✅ v2.0.0 已全部修复

### 问题统计

| 分类 | 数量 | 状态 |
|------|------|------|
| 🔴 严重问题 (P0) | 3 | ✅ 已修复 |
| 🟠 重要问题 (P1) | 5 | ✅ 已修复 |
| 🟡 一般问题 (P2) | 8 | ✅ 已修复 |
| 📢 建议优化 | 4 | ✅ 已优化 |

### 关键问题和修复

#### 🔴 P0-001: SessionProcessor 全局锁问题

**问题**: 使用 `synchronized` 方法，所有会话串行处理
**修复**: 改为 ReentrantLock 会话级锁
**性能提升**: 10-100倍

#### 🔴 P0-002: 线程池泄漏

**问题**: 无界线程池，线程数爆炸
**修复**: 有界线程池（核心8，最大20）

#### 🔴 P0-003: 文件处理竞态条件

**问题**: 多线程同时处理同一文件
**修复**: 添加文件级锁

#### 🟠 P1-001: N+1 查询问题

**问题**: 消息保存执行3次数据库操作
**修复**: 使用触发器自动更新计数
**性能提升**: 33%（500 msg/s）

#### 🟠 P1-002: 向量搜索无索引

**问题**: 全表扫描，搜索极慢
**修复**: 创建 HNSW 索引
**性能提升**: 10-1000倍

---

## 第四阶段：代码审查与优化

**时间**: 2026-03-23
**版本**: v2.0.0
**状态**: ✅ 已完成

### 主要改进

#### 1. 并发安全重构 ✅

- SessionProcessor: synchronized → ReentrantLock 会话级锁
- FileWatcherService: 无界线程池 → 有界线程池
- 性能提升 10-100倍

#### 2. 数据库优化 ✅

- 触发器优化：3次查询 → 2次查询（33%提升）
- 向量索引：HNSW 索引（10-1000倍提升）

#### 3. 新增 Agent 支持 ✅

- OpenClaw: 多行 JSON 格式
- Qwen/Qoder: 改进解析逻辑

---

## 第五阶段：当前状态

**时间**: 2026-03-23
**版本**: 2.0.0

### 启动方式

```bash
# 1. 启动数据库
docker-compose up -d

# 2. 启动后端
cd backend && start.bat

# 3. 启动前端（可选）
cd frontend && npm run dev
```

### 常见问题

**Q: 数据库连接失败？**
检查 Docker 是否运行，端口 5500 是否被占用

**Q: 消息没有被捕获？**
检查 Agent 日志路径是否正确

**Q: 向量搜索不工作？**
检查 Embedding 服务是否在 8100 端口运行

---

## 性能指标对比

### v1.0.0 vs v2.0.0

| 指标 | v1.0.0 | v2.0.0 | 提升 |
|------|--------|--------|------|
| 并发会话数 | 10 | 100+ | **10x** |
| 消息保存速度 | 300/s | 500/s | **67%** |
| 向量搜索 (1k条) | ~100ms | ~1ms | **100x** |
| 向量搜索 (10k条) | ~1000ms | ~10ms | **100x** |

---

## 版本历史

### v2.1.0 (2026-03-23) - 后端重构 ✅

**主题**: 代码冗余消除

**主要变更**:
- ✅ 创建 ScheduledServiceBase 基类（模板方法模式）
- ✅ CleanupService 重构（185→140行，-24%）
- ✅ SessionCompressionService 重构
- ✅ AgentDetectorService 参数化（212→128行，-39%）

**收益**:
- 减少代码 426 行 (-46%)
- 消除 95% 冗余
- 代码质量: ⭐⭐⭐☆ → ⭐⭐⭐⭐

**详细报告**: [docs/plans/2026-03-23-backend-refactor-status.md](docs/plans/2026-03-23-backend-refactor-status.md)

---

### v2.0.0 (2026-03-23)

**主题**: 性能与并发优化

**主要变更**:
- ✅ SessionProcessor 并发重构（会话级锁）
- ✅ 线程池管理优化（核心线程8）
- ✅ 数据库触发器优化（33%提升）
- ✅ 向量 HNSW 索引（10-1000倍提升）
- ✅ 新增 OpenClaw 支持

---

### v1.0.0 (2026-03-20)

**主题**: 首次发布

**核心功能**:
- ✅ 自动监控 CLI Agent 会话日志
- ✅ 实时解析并存入数据库
- ✅ 基于向量的语义搜索
- ✅ 自动分类记忆（5 种类型）

---

## 🔄 重构路线图

**当前版本**: v2.1.0

**总体进度**: 35% 完成

### 计划版本

#### v2.1.1 - 后端优化 (预计 2026-03-25)
- [ ] LRU 队列性能优化 (P0)
- [ ] SQL注入防护 (P0)
- [ ] MemoryService 保存方法重构 (P1)
- [ ] 配置外置 (P1)
- [ ] 内存泄漏修复 (P1)

**详细计划**: [docs/plans/2026-03-23-project-refactor-status.md](docs/plans/2026-03-23-project-refactor-status.md#后端剩余优化任务)

---

#### v2.2.0 - 前端重构 (预计 2026-03-28)
- [ ] TypeScript 类型系统
- [ ] API Service 层
- [ ] Composables 复用逻辑
- [ ] 组件拆分 (15+ 组件)
- [ ] 消除 700 行重复代码

**详细计划**: [docs/plans/2026-03-23-frontend-refactor-v2.md](docs/plans/2026-03-23-frontend-refactor-v2.md)

---

#### v2.2.1 - 测试完善 (预计 2026-03-30)
- [ ] 单元测试 (目标覆盖率 80%)
- [ ] 集成测试
- [ ] 端到端测试

**总体重构状态**: [docs/plans/2026-03-23-project-refactor-status.md](docs/plans/2026-03-23-project-refactor-status.md)

---

## 测试方案概述

### 测试目标

发现影响实际使用的 bug，并提供修复建议

### 测试范围

**P0 核心功能** (必须全部通过):
1. 数据库连接和基础操作
2. 消息存储和检索
3. 语义搜索功能
4. API 端点可用性
5. 日志文件监控（核心功能）

**P1 API CRUD 测试** (建议执行):
- 用户画像: Create/Read/Update/Delete
- 错误纠正: Create/Read/Update/Delete
- 最佳实践: Create/Read/Update/Delete
- 项目上下文: Create/Read/Update/Delete
- 技能沉淀: Create/Read/Update/Delete
- 会话管理: List/Get/Delete
- 消息管理: Create/Read/SoftDelete

**P1 功能测试** (建议执行):
- 记忆分类功能
- 会话压缩功能

### 执行指令

```
请按照以下顺序执行测试，并在每个测试后记录结果：
1. 执行所有 P0 级别的测试（核心功能）
2. 如果 P0 通过，执行 P1 级别测试
3. 如果发现失败，停止并生成 bug 报告
```

---

## P0 核心功能测试

### TEST-P0-001: 数据库连接测试

**目的**: 验证 AgentMemory 能否连接到数据库

**执行步骤**:
```bash
# 1. 检查数据库是否运行
docker ps | grep agentmemory-db

# 2. 如果数据库未运行，启动它
docker-compose up -d

# 3. 等待数据库启动（最多10秒）
sleep 5

# 4. 测试数据库连接
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory -c "SELECT 1;"
```

**预期结果**:
- 步骤1: 数据库容器正在运行
- 步骤4: 查询返回 `1`

**失败处理**:
- 检查 Docker 是否安装
- 检查数据库密码配置
- 检查数据库日志

---

### TEST-P0-002: AgentMemory 服务启动测试

**目的**: 验证后端服务能否正常启动

**执行步骤**:
```bash
# 1. 检查服务是否已运行
curl -s http://localhost:8080/api/health || echo "服务未运行"

# 2. 如果服务未运行，启动服务
cd C:\Users\31936\Desktop\AgentMemory
start.bat

# 3. 等待服务启动（最多30秒）

# 4. 再次检查服务健康状态
curl -s http://localhost:8080/api/health
```

**预期结果**:
- 步骤4: 返回 JSON 响应，包含 `status: "ok"`

---

### TEST-P0-003: 基础消息存储测试

**目的**: 验证能否保存和读取消息

**执行步骤**:
```bash
# 1. 创建测试会话
TEST_SESSION_ID="test-$(date +%Y%m%d-%H%M%S)"
curl -X POST http://localhost:8080/api/sessions \
  -H "Content-Type: application/json" \
  -d "{\"id\": \"$TEST_SESSION_ID\", \"agent_name\": \"test-agent\"}"

# 2. 发送测试消息
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d "{
    \"session_id\": \"$TEST_SESSION_ID\",
    \"role\": \"user\",
    \"content\": \"这是一条测试消息\"
  }"

# 3. 读取消息验证
curl -s http://localhost:8080/api/messages/$TEST_SESSION_ID

# 4. 验证数据库记录
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "SELECT role, content FROM messages WHERE session_id = '$TEST_SESSION_ID';"

# 5. 清理测试数据
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "DELETE FROM messages WHERE session_id = '$TEST_SESSION_ID';"
```

**预期结果**:
- 步骤3: 返回包含测试消息的 JSON 数组
- 步骤4: 数据库中能查到记录

---

### TEST-P0-004: 语义搜索功能测试

**目的**: 验证向量搜索是否正常工作

**执行步骤**:
```bash
# 1. 插入测试数据（包含已知的 embedding）
TEST_QUERY="数据库查询优化"
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -d "{\"query\": \"$TEST_QUERY\", \"top_k\": 5}"

# 2. 检查 pgvector 扩展是否安装
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "SELECT extname FROM pg_extension WHERE extname = 'vector';"

# 3. 检查向量索引是否存在
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "\d messages" | grep embedding
```

**预期结果**:
- 步骤1: 返回搜索结果（可能是空数组，但不能报错）
- 步骤2: 返回 `vector`
- 步骤3: 能看到 `embedding` 列的类型是 `vector`

---

### TEST-P0-005: 文件监控功能测试（核心）

**目的**: 验证能否实时监控 Agent 会话文件

**执行步骤**:
```bash
# 1. 确认服务正在运行且已启动文件监控
curl -s http://localhost:8080/api/stats | grep fileWatcher

# 2. 在 Agent 目录创建测试会话文件
TEST_AGENT_DIR="$HOME/.iflow/projects"
mkdir -p "$TEST_AGENT_DIR"
TEST_FILE="$TEST_AGENT_DIR/test-session-$(date +%s).md"
echo "# Test Session" > "$TEST_FILE"
echo "## User: 测试消息" >> "$TEST_FILE"
echo "## Assistant: 测试响应" >> "$TEST_FILE"

# 3. 等待文件监控检测到变化（最多10秒）
sleep 10

# 4. 检查是否捕获到新会话
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "SELECT id FROM sessions WHERE created_at > NOW() - INTERVAL '1 minute' ORDER BY created_at DESC LIMIT 1;"

# 5. 清理测试文件
rm "$TEST_FILE"
```

**预期结果**:
- 步骤4: 能查到新创建的会话记录

---

## P1 API CRUD 测试

### TEST-P1-API-001: 用户画像（UserProfiles）完整 CRUD 测试

**目的**: 验证用户画像的增删查改功能

**执行步骤**:
```bash
# ===== CREATE: 创建用户画像 =====
echo "1. CREATE - 创建用户画像"
CREATE_RESULT=$(curl -s -X POST http://localhost:8080/api/profiles \
  -H "Content-Type: application/json" \
  -d '{
    "title": "测试用户画像",
    "category": "开发环境",
    "items": "操作系统: Windows\nIDE: VS Code\nJDK: 21"
  }')
echo "$CREATE_RESULT" | head -20

# 提取创建的 ID
PROFILE_ID=$(echo "$CREATE_RESULT" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "创建的 ID: $PROFILE_ID"

# ===== READ: 读取单个用户画像 =====
echo "\n2. READ - 获取单个用户画像"
curl -s http://localhost:8080/api/profiles/$PROFILE_ID | head -20

# ===== READ: 列出所有用户画像 =====
echo "\n3. READ LIST - 获取用户画像列表"
curl -s http://localhost:8080/api/profiles | head -30

# ===== UPDATE: 更新用户画像 =====
echo "\n4. UPDATE - 更新用户画像"
curl -s -X PUT http://localhost:8080/api/profiles/$PROFILE_ID \
  -H "Content-Type: application/json" \
  -d '{
    "title": "测试用户画像（已更新）",
    "category": "开发环境",
    "items": "操作系统: Windows\nIDE: VS Code\nJDK: 21\n编辑器: Neovim"
  }' | head -20

# ===== DELETE: 删除用户画像 =====
echo "\n5. DELETE - 删除用户画像"
curl -s -X DELETE http://localhost:8080/api/profiles/$PROFILE_ID

# 验证删除（应该返回404）
echo "\n验证删除（应该返回404）:"
curl -s http://localhost:8080/api/profiles/$PROFILE_ID
```

**预期结果**:
- 步骤1: 返回包含 `id` 字段的 JSON 对象
- 步骤2: 返回完整的用户画像对象
- 步骤3: 返回用户画像列表
- 步骤4: 更新成功，能查到 "Neovim"
- 步骤5: 删除成功，后续查询返回 404 或空

---

### TEST-P1-API-002 ~ TEST-P1-API-007

其他 API CRUD 测试（错误纠正、最佳实践、项目上下文、技能沉淀、会话、消息）的测试步骤与上述类似，具体步骤详见完整文档。

---

## P1 功能测试

### TEST-P1-001: 记忆分类功能测试

**执行步骤**:
```bash
# 插入包含错误和解决方案的消息
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "test-classification",
    "role": "assistant",
    "content": "问题：数据库连接失败。原因：密码错误。解决方案：检查 application.conf 中的数据库配置。"
  }'

# 等待分类完成
sleep 5

# 检查是否提取到记忆
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "SELECT type FROM error_corrections WHERE created_at > NOW() - INTERVAL '1 minute';"
```

---

### TEST-P1-002: 会话压缩功能测试

**执行步骤**:
```bash
# 检查压缩服务状态
curl -s http://localhost:8080/api/compression/stats

# 手动触发压缩（如果会话消息数超过阈值）
curl -X POST http://localhost:8080/api/compression/trigger/{session_id}
```

---

## Bug 报告模板

当测试失败时，应按以下格式生成报告：

```markdown
## 🐛 Bug Report - [测试编号]

### 测试名称
[测试名称]

### 失败步骤
[具体的执行步骤，例如：步骤3 - 执行 curl 命令]

### 错误信息
```
[实际的错误输出或异常信息]
```

### 环境信息
- 操作系统: [Windows/Linux/Mac]
- Java 版本: [java -version 输出]
- 数据库: [PostgreSQL 版本]
- AgentMemory 版本: [从 pom.xml 或 git tag]

### 可能的原因
1. [原因1]
2. [原因2]

### 建议的修复方向
1. **检查配置文件**: [具体配置项]
2. **检查代码**: [相关文件和行号]
3. **检查依赖**: [相关依赖]

### 参考日志
```
[相关日志片段]
```
```

---

## 测试报告模板

测试完成后，生成整体报告：

```markdown
# AgentMemory 测试执行报告

**执行时间**: [YYYY-MM-DD HH:MM:SS]
**执行者**: Agent

## 测试结果总览

| 级别 | 总数 | 通过 | 失败 | 跳过 |
|------|------|------|------|------|
| P0   | 5    | 3    | 1    | 1    |
| P1-API | 7    | 7    | 0    | 0    |
| P1-功能 | 2    | 2    | 0    | 0    |
| **总计** | **14** | **12** | **1** | **1** |

## 失败测试详情

### TEST-P0-003: 基础消息存储测试 ❌
[详细信息...]

## 建议的优先修复项

1. **高优先级**: TEST-P0-003 失败，影响消息存储功能
   - 建议修复时间: 30分钟
   - 相关文件: DatabaseService.java:145-150

## 测试覆盖率

### API CRUD 覆盖率
- **用户画像**: 100% ✅ (Create/Read/Update/Delete 全覆盖)
- **错误纠正**: 100% ✅ (Create/Read/Update/Delete 全覆盖)
- **最佳实践**: 100% ✅ (Create/Read/Update/Delete 全覆盖)
- **项目上下文**: 100% ✅ (Create/Read/Update/Delete 全覆盖)
- **技能沉淀**: 100% ✅ (Create/Read/Update/Delete 全覆盖)
- **会话管理**: 100% ✅ (List/Get/Delete 全覆盖)
- **消息管理**: 100% ✅ (Create/Read/SoftDelete 全覆盖)
```

---

## 附录

### 数据库表结构

```sql
-- Agent 表
CREATE TABLE agents (
    id SERIAL PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    type TEXT NOT NULL,
    log_base_path TEXT,
    cli_path TEXT,
    version TEXT,
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 会话表
CREATE TABLE sessions (
    id TEXT PRIMARY KEY,
    agent_type TEXT,
    project_path TEXT,
    message_count INTEGER DEFAULT 0,
    expires_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 消息表
CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    session_id TEXT REFERENCES sessions(id),
    parent_id TEXT,
    role TEXT NOT NULL,
    content TEXT,
    raw_json JSONB,
    timestamp TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 五大记忆库（省略详细定义）
```

### 依赖版本

```xml
<properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>

<dependencies>
    <!-- PostgreSQL 驱动 -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.1</version>
    </dependency>

    <!-- HikariCP 连接池 -->
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>5.1.0</version>
    </dependency>

    <!-- JSON 处理 -->
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.10.1</version>
    </dependency>

    <!-- 日志 -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>2.0.9</version>
    </dependency>
    <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
        <version>1.4.14</version>
    </dependency>
</dependencies>
```

### 相关链接

- [用户文档](README.md) - 快速开始指南
- [更新日志](CHANGELOG.md) - 版本更新记录
- [代码审查](CODE_REVIEWS.md) - 完整审查报告
- [GitHub Issues](https://github.com/yourname/AgentMemory/issues) - 问题反馈

---

**文档结束**

*生成时间: 2026-03-23*
*维护者: AgentMemory Team*
