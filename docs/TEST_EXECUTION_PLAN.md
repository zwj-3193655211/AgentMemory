# AgentMemory 自动化测试执行方案

> 可被 Agent 直接执行的测试方案
> 创建日期: 2026-03-23

---

## 📋 方案概述

本方案定义了一系列可被 Agent 自动执行的测试任务，用于验证 AgentMemory 的核心功能是否正常工作。

**测试目标**: 发现影响实际使用的 bug，并提供修复建议

**测试范围**:

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

---

## 🚀 执行指令

**测试数量**: 共 14 个测试用例
- P0 核心测试: 5 个
- P1 API CRUD 测试: 7 个
- P1 功能测试: 2 个

**给 Agent 的执行指令**:

```
请按照以下顺序执行测试，并在每个测试后记录结果：
1. 执行所有 P0 级别的测试（核心功能）
2. 如果 P0 通过，执行 P1 级别测试
3. 如果发现失败，停止并生成 bug 报告，包含：
   - 失败的测试步骤
   - 错误信息
   - 可能的原因分析
   - 建议的修复方向
```

---

## ✅ P0 级别测试 - 核心功能（必须全部通过）

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
- 如果步骤1失败 → 检查 Docker 是否安装，检查 docker-compose.yml 配置
- 如果步骤4失败 → 检查数据库密码配置 (application.conf)，检查数据库日志

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
# Windows: timeout /t 30
# Linux: sleep 30

# 4. 再次检查服务健康状态
curl -s http://localhost:8080/api/health
```

**预期结果**:
- 步骤4: 返回 JSON 响应，包含 `status: "ok"`

**失败处理**:
- 检查日志: `tail -f ~/.agentmemory/logs/agentmemory.log`
- 检查端口占用: `netstat -ano | findstr :8080`
- 检查 Java 版本: `java -version` (需要 JDK 17+)

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
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "DELETE FROM sessions WHERE id = '$TEST_SESSION_ID';"
```

**预期结果**:
- 步骤3: 返回包含测试消息的 JSON 数组
- 步骤4: 数据库中能查到记录，content 字段正确

**失败处理**:
- 检查 API 路由配置 (ApiServer.java)
- 检查数据库表结构 (messages 表是否存在)
- 查看 DatabaseService 日志

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

**失败处理**:
- 如果步骤2失败 → pgvector 扩展未安装，运行 `database/install_vector.sql`
- 如果步骤1报错 → 检查 EmbeddingClient 配置，检查 embedding API 是否可用

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

**失败处理**:
- 检查 FileWatcherService 是否启动
- 检查监控路径配置 (application.conf)
- 查看文件监控日志: `grep FileWatcher ~/.agentmemory/logs/agentmemory.log`

---

## 📊 P1 级别测试 - 重要功能（建议执行）

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

# 验证更新
echo "\n验证更新内容:"
curl -s http://localhost:8080/api/profiles/$PROFILE_ID | grep "Neovim"

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

**失败处理**:
- 检查 UserProfilesHandler 路由 (ApiServer.java:636-760)
- 检查 user_profiles 表结构
- 查看 API 日志: `grep "POST /api/profiles" ~/.agentmemory/logs/agentmemory.log`

---

### TEST-P1-API-002: 错误纠正（ErrorCorrections）CRUD 测试

**目的**: 验证错误纠正的增删查改

**执行步骤**:
```bash
# ===== CREATE =====
echo "1. CREATE - 创建错误纠正记录"
CREATE_RESULT=$(curl -s -X POST http://localhost:8080/api/errors \
  -H "Content-Type: application/json" \
  -d '{
    "title": "数据库连接失败",
    "problem": "Connection refused",
    "cause": "数据库未启动",
    "solution": "运行 docker-compose up -d"
  }')
ERROR_ID=$(echo "$CREATE_RESULT" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "创建的 ID: $ERROR_ID"

# ===== READ =====
echo "\n2. READ - 读取错误纠正"
curl -s http://localhost:8080/api/errors/$ERROR_ID | head -20

# ===== UPDATE =====
echo "\n3. UPDATE - 更新错误纠正"
curl -s -X PUT http://localhost:8080/api/errors/$ERROR_ID \
  -H "Content-Type: application/json" \
  -d '{
    "title": "数据库连接失败（已修正）",
    "problem": "Connection refused",
    "cause": "数据库未启动或端口占用",
    "solution": "1. 检查 docker ps\n2. 运行 docker-compose up -d"
  }'

# ===== DELETE =====
echo "\n4. DELETE - 删除错误纠正"
curl -s -X DELETE http://localhost:8080/api/errors/$ERROR_ID
```

**预期结果**:
- 所有操作正常完成，无 500 错误

---

### TEST-P1-API-003: 最佳实践（BestPractices）CRUD 测试

**目的**: 验证最佳实践的增删查改

**执行步骤**:
```bash
# ===== CREATE =====
echo "1. CREATE - 创建最佳实践"
PRACTICE_RESULT=$(curl -s -X POST http://localhost:8080/api/practices \
  -H "Content-Type: application/json" \
  -d '{
    "title": "数据库查询优化实践",
    "category": "性能优化",
    "content": "使用索引避免全表扫描，为 WHERE 子句的列创建合适的索引。"
  }')
PRACTICE_ID=$(echo "$PRACTICE_RESULT" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# ===== READ =====
echo "\n2. READ - 读取最佳实践"
curl -s http://localhost:8080/api/practices/$PRACTICE_ID

# ===== UPDATE =====
echo "\n3. UPDATE - 更新最佳实践"
curl -s -X PUT http://localhost:8080/api/practices/$PRACTICE_ID \
  -H "Content-Type: application/json" \
  -d '{
    "title": "数据库查询优化实践",
    "category": "性能优化",
    "content": "1. 使用索引避免全表扫描\n2. 避免 SELECT *\n3. 使用 LIMIT 限制结果集"
  }'

# ===== DELETE =====
curl -s -X DELETE http://localhost:8080/api/practices/$PRACTICE_ID
```

---

### TEST-P1-API-004: 项目上下文（ProjectContexts）CRUD 测试

**目的**: 验证项目上下文的增删查改

**执行步骤**:
```bash
# ===== CREATE =====
echo "1. CREATE - 创建项目上下文"
CONTEXT_RESULT=$(curl -s -X POST http://localhost:8080/api/contexts \
  -H "Content-Type: application/json" \
  -d '{
    "title": "AgentMemory 项目",
    "category": "技术栈",
    "content": "后端: Java 17 + PostgreSQL\n前端: Vue 3\n向量: pgvector"
  }')
CONTEXT_ID=$(echo "$CONTEXT_RESULT" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# ===== READ =====
echo "\n2. READ - 读取项目上下文"
curl -s http://localhost:8080/api/contexts/$CONTEXT_ID

# ===== UPDATE =====
echo "\n3. UPDATE - 更新项目上下文"
curl -s -X PUT http://localhost:8080/api/contexts/$CONTEXT_ID \
  -H "Content-Type: application/json" \
  -d '{
    "title": "AgentMemory 项目",
    "category": "技术栈",
    "content": "后端: Java 21 + PostgreSQL + pgvector\n前端: Vue 3 + Element Plus"
  }'

# ===== DELETE =====
curl -s -X DELETE http://localhost:8080/api/contexts/$CONTEXT_ID
```

---

### TEST-P1-API-005: 技能沉淀（Skills）CRUD 测试

**目的**: 验证技能沉淀的增删查改

**执行步骤**:
```bash
# ===== CREATE =====
echo "1. CREATE - 创建技能记录"
SKILL_RESULT=$(curl -s -X POST http://localhost:8080/api/skills \
  -H "Content-Type: application/json" \
  -d '{
    "title": "SQL 查询优化",
    "category": "数据库",
    "content": "使用 EXPLAIN ANALYZE 分析查询计划，找出慢查询原因。"
  }')
SKILL_ID=$(echo "$SKILL_RESULT" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# ===== READ =====
echo "\n2. READ - 读取技能"
curl -s http://localhost:8080/api/skills/$SKILL_ID

# ===== UPDATE =====
echo "\n3. UPDATE - 更新技能"
curl -s -X PUT http://localhost:8080/api/skills/$SKILL_ID \
  -H "Content-Type: application/json" \
  -d '{
    "title": "SQL 查询优化",
    "category": "数据库",
    "content": "1. 使用 EXPLAIN ANALYZE\n2. 创建合适的索引\n3. 避免N+1查询"
  }'

# ===== DELETE =====
curl -s -X DELETE http://localhost:8080/api/skills/$SKILL_ID
```

---

### TEST-P1-API-006: 会话（Sessions）查询测试

**目的**: 验证会话查询功能

**执行步骤**:
```bash
# 获取最近的会话列表（默认50条）
echo "1. LIST - 获取会话列表"
curl -s "http://localhost:8080/api/sessions?limit=10" | head -50

# 按Agent类型筛选
echo "\n2. FILTER - 按Agent类型筛选"
curl -s "http://localhost:8080/api/sessions?agent_type=claude&limit=5" | head -50

# 获取单个会话
echo "\n3. GET - 获取单个会话"
# 先获取一个会话ID
SESSION_ID=$(docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -t -c "SELECT id FROM sessions WHERE deleted = false ORDER BY created_at DESC LIMIT 1;" 2>/dev/null | tr -d ' ')
echo "会话ID: $SESSION_ID"

if [ ! -z "$SESSION_ID" ]; then
  curl -s "http://localhost:8080/api/sessions/$SESSION_ID"
fi
```

---

### TEST-P1-API-007: 消息（Messages）完整 CRUD 测试

**目的**: 验证消息的增删查改

**执行步骤**:
```bash
# 创建测试会话
TEST_SESSION="msg-test-$(date +%s)"
curl -s -X POST http://localhost:8080/api/sessions \
  -H "Content-Type: application/json" \
  -d "{\"id\": \"$TEST_SESSION\", \"agent_name\": \"test-api\"}"

# ===== CREATE =====
echo "1. CREATE - 创建消息"
MSG_RESULT=$(curl -s -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d "{
    \"session_id\": \"$TEST_SESSION\",
    \"role\": \"user\",
    \"content\": \"API测试消息\"
  }")
echo "$MSG_RESULT" | head -10

# ===== READ =====
echo "\n2. READ - 读取会话消息"
curl -s "http://localhost:8080/api/messages/$TEST_SESSION" | head -30

# ===== DELETE =====
echo "\n3. DELETE - 删除消息（软删除）"
# 注意：当前API可能不支持单条消息删除，这里测试软删除
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "UPDATE messages SET deleted = true WHERE session_id = '$TEST_SESSION';"

# 验证软删除
echo "\n验证软删除:"
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "SELECT COUNT(*) FROM messages WHERE session_id = '$TEST_SESSION' AND deleted = false;"

# 清理测试会话
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "DELETE FROM messages WHERE session_id = '$TEST_SESSION';"
docker exec -it agentmemory-db psql -U agentmemory -d agentmemory \
  -c "DELETE FROM sessions WHERE id = '$TEST_SESSION';"
```

**预期结果**:
- 消息创建成功
- 能读取到消息列表
- 软删除后 deleted = true

---

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

## 🐛 Bug 报告模板

当测试失败时，Agent 应按以下格式生成报告：

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
   - 位置: `backend/src/main/resources/application.conf`
   - 检查点: [配置参数]

2. **检查代码**: [相关文件和行号]
   - 文件: `src/main/java/com/agentmemory/xxx.java`
   - 行号: 约 xx 行
   - 建议修改: [具体修改建议]

3. **检查依赖**: [相关依赖]
   - 检查: [依赖版本是否正确]

### 参考日志
```
[相关日志片段]
```
```

---

## 📈 测试报告模板

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

## 通过测试列表

- ✅ TEST-P0-001: 数据库连接测试
- ✅ TEST-P0-002: 服务启动测试
- ...

## 建议的优先修复项

1. **高优先级**: TEST-P0-003 失败，影响消息存储功能
   - 建议修复时间: 30分钟
   - 相关文件: DatabaseService.java:145-150

## 测试覆盖率

### 功能覆盖率
- 核心功能: 80% (4/5 测试通过)
- 数据库操作: 100% (3/3 测试通过)

### API CRUD 覆盖率
- **用户画像**: 100% ✅ (Create/Read/Update/Delete 全覆盖)
- **错误纠正**: 100% ✅ (Create/Read/Update/Delete 全覆盖)
- **最佳实践**: 100% ✅ (Create/Read/Update/Delete 全覆盖)
- **项目上下文**: 100% ✅ (Create/Read/Update/Delete 全覆盖)
- **技能沉淀**: 100% ✅ (Create/Read/Update/Delete 全覆盖)
- **会话管理**: 100% ✅ (List/Get/Delete 全覆盖)
- **消息管理**: 100% ✅ (Create/Read/SoftDelete 全覆盖)

### API 端点覆盖率
- 总计: 13 个 API 端点
- 已测试: 13 个 ✅ (100%)
```

---

## 🎯 执行建议

**给 Agent 的提示**:

1. **执行顺序**: 严格按照 P0 → P1 的顺序执行
2. **失败即停**: 如果任何 P0 测试失败，停止后续测试并生成 bug 报告
3. **超时处理**: 每个测试步骤最多等待 30 秒，超时视为失败
4. **清理数据**: 测试完成后清理产生的测试数据
5. **详细日志**: 记录每个步骤的执行时间和输出，方便定位问题

---

## 📝 使用说明

**如何使用此方案**:

```
给 Agent 的指令:

"请执行 C:\Users\31936\Desktop\AgentMemory\docs\TEST_EXECUTION_PLAN.md
中定义的测试方案，并生成测试报告。如果发现 bug，请提供详细的修复建议。"
```

**预期输出**:
- 测试执行过程（每个步骤的输出）
- 最终测试报告（通过/失败情况）
- 如果有失败，详细的 bug 报告和修复建议

---

**方案版本**: 1.0
**最后更新**: 2026-03-23
