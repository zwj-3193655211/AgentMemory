# AgentMemory Bug 检测与修复日志

> 生成时间: 2026-03-22
> 审查范围: 全项目代码审查
> 审查人: Claude Code

---

## 📊 统计概览

| 分类 | 数量 |
|------|------|
| 🔴 严重问题 (P0) | 3 |
| 🟠 重要问题 (P1) | 5 |
| 🟡 一般问题 (P2) | 8 |
| 📢 建议优化 | 4 |
| **总计** | **20** |

---

## 🔴 严重问题 (P0)

### BUG-001: SQL 注入漏洞
**文件**: `backend/src/main/java/com/agentmemory/api/ApiServer.java`
**行号**: 170

**问题描述**:
```java
sql += " AND agent_type = '" + agentType + "'";
```
直接拼接用户输入到 SQL 语句，存在注入风险。

**影响**:
- 安全漏洞，虽然本地使用但仍是坏习惯
- 可能导致数据库异常

**修复方案**:
```java
// 修改前
sql += " AND agent_type = '" + agentType + "'";

// 修改后
String sql = "SELECT * FROM sessions WHERE deleted = false";
if (agentType != null) {
    sql += " AND agent_type = ?";
}
// 然后使用 PreparedStatement 设置参数
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 0.5h

---

### BUG-002: 资源泄漏风险
**文件**: `backend/src/main/java/com/agentmemory/service/FileWatcherService.java`
**行号**: 38, 57-94

**问题描述**:
1. `newCachedThreadPool()` 创建无界线程池，可能导致 OOM
2. `watchService.close()` 在异常时可能未执行

**影响**:
- 长时间运行可能耗尽系统资源
- 文件句柄泄漏

**修复方案**:
```java
// 1. 使用有界线程池
this.executor = new ThreadPoolExecutor(
    1, 10, 60, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(100),
    new ThreadPoolExecutor.CallerRunsPolicy()
);

// 2. 使用 try-with-resources
private void startWatcher(String agentType, Path directory) {
    try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
        // ... 现有代码
    } catch (Exception e) {
        log.error("监控目录失败: {}", directory, e);
    }
}
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 1h

---

### BUG-003: 数据库表结构不一致
**文件**: `backend/src/main/java/com/agentmemory/service/DatabaseService.java`
**行号**: 273-279, 103-154

**问题描述**:
- SQLite 和 PostgreSQL 的表结构定义不同
- 但 `ensureSessionExists()` 使用了 PostgreSQL 特定语法 (`INTERVAL`)

**影响**:
- SQLite 模式下运行时错误
- 数据迁移问题

**修复方案**:
```java
private void ensureSessionExists(Message message) {
    String sql;
    if (useSqlite) {
        sql = """
            INSERT INTO sessions (id, project_path, agent_type, message_count, expires_at)
            VALUES (?, ?, ?, 0, datetime('now', '+14 days'))
            """;
    } else {
        sql = """
            INSERT INTO sessions (id, project_path, agent_type, message_count, expires_at)
            VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP + INTERVAL '14 days')
            """;
    }
    // 继续执行...
}
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 1h

---

## 🟠 重要问题 (P1)

### BUG-004: JSON 转义不完整
**文件**: `backend/src/main/java/com/agentmemory/service/MemoryService.java`
**行号**: 267, 389

**问题描述**:
```java
memory.description.replace("\"", "\\\"")
```
只转义了双引号，未处理反斜杠、换行符等特殊字符。

**影响**:
- JSON 解析失败
- 数据库异常

**修复方案**:
```java
// 使用 ObjectMapper 转义
private String escapeJson(String text) {
    try {
        return objectMapper.writeValueAsString(text);
    } catch (Exception e) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 0.5h

---

### BUG-005: 内存无限增长
**文件**: `backend/src/main/java/com/agentmemory/service/FileWatcherService.java`
**行号**: 30

**问题描述**:
```java
private final Map<String, Long> filePositions = new ConcurrentHashMap<>();
```
文件位置映射永不过期和清理。

**影响**:
- 长时间运行内存占用持续增长
- 可能导致 OOM

**修复方案**:
```java
// 使用 Guava Cache 或定期清理
private final Cache<String, Long> filePositions = CacheBuilder.newBuilder()
    .maximumSize(1000)
    .expireAfterAccess(7, TimeUnit.DAYS)
    .build();

// 或添加定期清理任务
private void cleanupOldPositions() {
    filePositions.entrySet().removeIf(entry -> {
        Path file = Paths.get(entry.getKey());
        return !Files.exists(file);
    });
}
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 1h

---

### BUG-006: 项目路径提取脆弱
**文件**: `backend/src/main/java/com/agentmemory/service/FileWatcherService.java`
**行号**: 273-292

**问题描述**:
```java
int projectsIdx = path.indexOf("projects");
if (projectsIdx == -1) {
    return "";
}
```
硬编码 "projects" 关键字，不够灵活。

**影响**:
- 其他 Agent 不使用 "projects" 目录时失败
- 路径解析错误

**修复方案**:
```java
// 从 Agent 配置中获取日志基础路径
private String extractProjectPath(Path file, AgentInfo agent) {
    String basePath = agent.getLogBasePath();
    String filePath = file.toString();

    if (filePath.startsWith(basePath)) {
        String relative = filePath.substring(basePath.length());
        // 去掉会话文件名
        int sessionIdx = relative.indexOf("session-");
        if (sessionIdx > 0) {
            return relative.substring(0, sessionIdx - 1);
        }
    }
    return "";
}
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 1h

---

### BUG-007: 代码重复 - saveMemory 方法
**文件**: `backend/src/main/java/com/agentmemory/service/MemoryService.java`
**行号**: 215-236, 349-426

**问题描述**:
两个 `saveMemory` 方法功能重叠，代码重复。

**影响**:
- 维护困难
- 容易产生不一致

**修复方案**:
```java
// 提取公共逻辑
private void saveMemoryInternal(ExtractedMemory memory, MemoryType type,
                               String sessionId, String agentType, float[] embedding) {
    // 统一的保存逻辑
}

// 两个方法都调用这个内部方法
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 2h

---

### BUG-008: API Handler 代码重复
**文件**: `backend/src/main/java/com/agentmemory/api/ApiServer.java`
**行号**: 121-369

**问题描述**:
所有 Handler 类都有相同的 try-catch 和结果集处理模式。

**影响**:
- 代码冗余
- 难以维护

**修复方案**:
```java
// 提取通用查询方法
private <T> List<T> queryList(String sql, RowMapper<T> mapper) throws SQLException {
    try (Connection conn = databaseService.getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {

        List<T> list = new ArrayList<>();
        while (rs.next()) {
            list.add(mapper.mapRow(rs));
        }
        return list;
    }
}

// 使用
class AgentsHandler implements HttpHandler {
    public void handle(HttpExchange exchange) throws IOException {
        try {
            List<Agent> agents = queryList("SELECT * FROM agents", rs -> {
                Agent agent = new Agent();
                agent.setId(rs.getInt("id"));
                // ... 映射字段
                return agent;
            });
            sendJson(exchange, agents);
        } catch (SQLException e) {
            sendError(exchange, 500, e.getMessage());
        }
    }
}
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 4h

---

## 🟡 一般问题 (P2)

### BUG-009: 时间戳解析脆弱
**文件**: `backend/src/main/java/com/agentmemory/service/DatabaseService.java`
**行号**: 234-244

**问题描述**:
```java
if (ts == null || ts.isEmpty() || "0".equals(ts) || ts.length() < 10) {
    stmt.setNull(7, java.sql.Types.TIMESTAMP);
}
```
只检查长度，不验证格式。

**影响**:
- 解析异常被吞掉
- 数据可能不正确

**修复方案**:
```java
private Timestamp parseTimestamp(String ts) {
    if (ts == null || ts.isEmpty() || "0".equals(ts)) {
        return null;
    }

    try {
        // 尝试 ISO 8601 格式
        return Timestamp.from(Instant.parse(ts));
    } catch (DateTimeParseException e1) {
        try {
            // 尝试毫秒时间戳
            long millis = Long.parseLong(ts);
            return new Timestamp(millis);
        } catch (NumberFormatException e2) {
            log.warn("无法解析时间戳: {}", ts);
            return null;
        }
    }
}
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 0.5h

---

### BUG-010: 错误处理不充分
**文件**: 多处

**问题描述**:
多处 catch 块只记录日志，不向上抛出或重试。

**影响**:
- 静默失败
- 用户不知道发生了错误

**示例**:
```java
// FileWatcherService.java:220
} catch (Exception e) {
    log.debug("记忆处理失败: {}", e.getMessage());
    // 用户不知道失败
}
```

**修复方案**:
- 添加失败计数器
- 超过阈值时告警
- 关键操作应该重试

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 3h

---

### BUG-011: 配置硬编码
**文件**: `backend/src/main/java/com/agentmemory/service/SessionProcessor.java`
**行号**: 29-30

**问题描述**:
```java
private static final int INCREMENTAL_THRESHOLD = 30;
private static final int MAX_CACHE_SIZE = 100;
```

**影响**:
- 无法根据环境调整
- 需要重新编译

**修复方案**:
```java
// 添加到 ApplicationConfig
private int incrementalThreshold = config.getIncrementalThreshold();
private int maxCacheSize = config.getMaxCacheSize();
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 1h

---

### BUG-012: 缺少重试机制
**文件**: `backend/src/main/java/com/agentmemory/service/EmbeddingClient.java`

**问题描述**:
调用 Embedding 服务失败后没有重试。

**影响**:
- 网络抖动导致永久失败
- 用户体验差

**修复方案**:
```java
public <T> T callWithRetry(String endpoint, Object request, Class<T> responseType) {
    int maxRetries = 3;
    int retryDelay = 1000; // ms

    for (int i = 0; i < maxRetries; i++) {
        try {
            return callApi(endpoint, request, responseType);
        } catch (Exception e) {
            if (i == maxRetries - 1) {
                throw e;
            }
            log.warn("API 调用失败，重试 {}/{}", i + 1, maxRetries);
            try {
                Thread.sleep(retryDelay * (i + 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("重试中断", ie);
            }
        }
    }
    throw new RuntimeException("重试失败");
}
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 2h

---

### BUG-013: 前端 API 调用重复
**文件**: `frontend/src/App.vue`
**行号**: 540-550, 554-570

**问题描述**:
```javascript
const loadData = async () => {
    const [agentsRes, sessionsRes, statsRes] = await Promise.all([...])
}

const loadMemoryData = async () => {
    const [errorsRes, profilesRes, ...] = await Promise.all([...])
}
```
两次加载都是独立的，可能重复请求 stats。

**影响**:
- 不必要的网络请求
- 性能浪费

**修复方案**:
合并为单次加载，使用状态管理。

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 1h

---

### BUG-014: 批量操作缺失
**文件**: `backend/src/main/java/com/agentmemory/service/MemoryService.java`
**行号**: 179-210

**问题描述**:
逐条查询相似度，效率低。

**影响**:
- 性能问题
- 数据库负载高

**修复方案**:
```java
// 使用 PostgreSQL 的批量向量相似度计算
String sql = """
    SELECT title, 1 - (embedding <=> ?::vector) as similarity
    FROM %s
    WHERE deleted = false
    AND embedding IS NOT NULL
    ORDER BY similarity DESC
    LIMIT ?
    """.formatted(type.getTableName());
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 2h

---

### BUG-015: SessionContext 缓存策略不当
**文件**: `backend/src/main/java/com/agentmemory/service/SessionProcessor.java`
**行号**: 204-214

**问题描述**:
只在达到 MAX_CACHE_SIZE 时清理，而非基于时间或访问频率。

**影响**:
- 活跃会话可能被清理
- 不活跃会话占用内存

**修复方案**:
使用 LRU 缓存或基于时间的过期策略。

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 1.5h

---

### BUG-016: 文件读取位置持久化缺失
**文件**: `backend/src/main/java/com/agentmemory/service/FileWatcherService.java`
**行号**: 30

**问题描述**:
重启后 `filePositions` 清空，会重新读取所有文件。

**影响**:
- 重启后性能下降
- 可能重复处理消息

**修复方案**:
```java
// 启动时从数据库恢复上次读取位置
private void loadFilePositions() {
    // 查询每个会话的最后一条消息时间戳
    // 映射到文件位置
}

// 定期保存到数据库
private void persistFilePositions() {
    // 保存到数据库或配置文件
}
```

**状态**: ⏳ 待修复
**负责人**: -
**预计工时**: 3h

---

## 📢 建议优化

### OPT-001: 添加单元测试
**优先级**: 中
**预计工时**: 16h

建议添加测试覆盖：
- MemoryClassifier 分类逻辑
- MemoryExtractor 提取逻辑
- DatabaseService 数据库操作
- ApiServer 端点

---

### OPT-002: 使用 Lint 工具
**优先级**: 低
**预计工时**: 4h

建议集成：
- SonarQube (代码质量)
- Checkstyle (代码风格)
- SpotBugs (Bug 检测)

---

### OPT-003: 添加 API 文档
**优先级**: 低
**预计工时**: 6h

建议：
- 添加 OpenAPI/Swagger 注解
- 生成 API 文档
- 提供前端调用示例

---

### OPT-004: 性能监控
**优先级**: 低
**预计工时**: 8h

建议添加：
- Prometheus metrics
- 慢查询日志
- 内存使用监控

---

## 📝 修复进度追踪

| ID | 标题 | 状态 | 负责人 | 完成日期 |
|----|------|------|--------|----------|
| BUG-001 | SQL 注入漏洞 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-002 | 资源泄漏风险 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-003 | 数据库表结构不一致 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-004 | JSON 转义不完整 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-005 | 内存无限增长 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-006 | 项目路径提取脆弱 | ⏳ 用户已优化 | - | - |
| BUG-007 | 代码重复 - saveMemory | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-008 | API Handler 代码重复 | ✅ 已添加通用方法 | Claude Code | 2026-03-22 |
| BUG-009 | 时间戳解析脆弱 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-010 | 错误处理不充分 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-011 | 配置硬编码 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-012 | 缺少重试机制 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-013 | 前端 API 调用重复 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-014 | 批量操作缺失 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-015 | SessionContext 缓存策略 | ✅ 已修复 | Claude Code | 2026-03-22 |
| BUG-016 | 文件读取位置持久化 | ✅ 已修复 | Claude Code | 2026-03-22 |

---

## 🔖 快速修复检查清单

按推荐优先级排序：

### 第一批（安全性和稳定性）
- [x] BUG-001: SQL 注入漏洞
- [x] BUG-002: 资源泄漏风险
- [x] BUG-003: 数据库表结构不一致

### 第二批（代码质量）
- [x] BUG-004: JSON 转义不完整
- [x] BUG-005: 内存无限增长
- [x] BUG-006: 项目路径提取脆弱（用户已优化）

### 第三批（可维护性）
- [x] BUG-007: 代码重复 - saveMemory
- [x] BUG-008: API Handler 代码重复（已添加通用方法）
- [x] BUG-009: 时间戳解析脆弱
- [x] BUG-010: 错误处理不充分

### 第四批（优化）
- [x] BUG-011: 配置硬编码
- [x] BUG-012: 缺少重试机制
- [x] BUG-013: 前端 API 调用重复
- [x] BUG-014: 批量操作缺失
- [x] BUG-015: SessionContext 缓存策略
- [x] BUG-016: 文件读取位置持久化

---

**文档版本**: 1.0
**最后更新**: 2026-03-22
**下次审查**: 修复完成后
