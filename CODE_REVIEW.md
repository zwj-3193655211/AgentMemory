# 代码审查报告

**审查日期**: 2026-03-23
**项目**: AgentMemory
**审查范围**: 后端核心服务模块

---

## 🔴 严重问题

### 1. 并发安全问题 - SessionProcessor.java (第38-103行)

**位置**: `SessionProcessor.java:38-103`

**问题描述**:
```java
// 第38行：LinkedList 不是线程安全的！
private final LinkedList<String> lruQueue;
private final Object lruLock = new Object();
```

虽然使用了 `lruLock` 来保护 LRU 队列操作，但在其他地方（如第100-102行）访问 `lruQueue` 时只在部分代码块中使用了同步。这可能导致并发修改异常。

**影响**: 在高并发场景下可能导致 `ConcurrentModificationException` 或数据不一致

**修复建议**:
```java
// 方案1: 使用线程安全的集合
private final ConcurrentLinkedDeque<String> lruQueue = new ConcurrentLinkedDeque<>();

// 方案2: 确保所有访问都在同步块中
synchronized (lruLock) {
    lruQueue.remove(sessionId);
    lruQueue.addLast(sessionId);
}
```

**优先级**: 🔴 高 - 可能导致运行时异常

---

### 2. SQL注入风险 - MemoryService.java (第223-224行)

**位置**: `MemoryService.java:223-224`

**问题描述**:
```java
String sql = String.format("""
    SELECT title, 1 - (embedding <=> '%s'::vector) as similarity
    FROM %s
    WHERE embedding IS NOT NULL AND (deleted = false OR deleted IS NULL)
    ORDER BY similarity DESC
    LIMIT 10
    """, vecStr, type.getTableName());
```

虽然 `vecStr` 是通过 `toArrayString` 生成的，但 `type.getTableName()` 直接拼接到SQL中，存在潜在的SQL注入风险。

**影响**: 如果 `MemoryType` 枚举被篡改或扩展，可能导致SQL注入

**修复建议**:
```java
// 添加表名白名单验证
private static final Set<String> ALLOWED_TABLES = Set.of(
    "error_corrections", "user_profiles", "best_practices",
    "project_contexts", "skills"
);

private boolean isDuplicate(String title, MemoryType type) {
    String tableName = type.getTableName();

    // 白名单验证
    if (!ALLOWED_TABLES.contains(tableName)) {
        throw new IllegalArgumentException("Invalid table name: " + tableName);
    }

    // ... 其余代码
}
```

**优先级**: 🔴 高 - 安全问题

---

## 🟡 中等问题

### 3. 数据库兼容性问题 - DatabaseService.java (第343-356行)

**位置**: `DatabaseService.java:343-356`

**问题描述**:
SQLite 和 PostgreSQL 的 UPSERT 语法中，对 `excluded` 关键字的大小写敏感：
- SQLite 使用小写的 `excluded`
- PostgreSQL 使用大写的 `EXCLUDED`

虽然代码已经处理了这个差异，但注释不够清晰，容易被开发者误修改。

**修复建议**:
```java
// 添加详细的警告注释
// 注意：SQLite 和 PostgreSQL 对 excluded 关键字大小写敏感不同
//       SQLite: excluded (小写)
//       PostgreSQL: EXCLUDED (大写)
//       修改时请务必保持正确的大小写！
if (useSqlite) {
    sql = """
        ...
        ON CONFLICT (id) DO UPDATE SET
            project_path = COALESCE(sessions.project_path, excluded.project_path),
            agent_type = excluded.agent_type
        """;
} else {
    sql = """
        ...
        ON CONFLICT (id) DO UPDATE SET
            project_path = COALESCE(sessions.project_path, EXCLUDED.project_path),
            agent_type = EXCLUDED.agent_type
        """;
}
```

**优先级**: 🟡 中 - 维护性问题

---

### 4. 硬编码配置 - 多个文件

**位置**:
- `SessionCompressionService.java:24-26`
- `ApplicationConfig.java:70-76`

**问题描述**:
```java
// SessionCompressionService.java
private int windowSize = 50;           // 滑动窗口大小
private int summaryThreshold = 100;     // 触发压缩的阈值
private boolean autoCompress = true;    // 是否自动压缩

// ApplicationConfig.java
public int getIncrementalThreshold() {
    return 30;  // 硬编码
}

public int getMaxCacheSize() {
    return 100;
}
```

这些配置应该从配置文件中读取，而不是硬编码在代码中。

**修复建议**:
```java
// 在 application.conf 中添加
session {
  compression {
    windowSize = 50
    summaryThreshold = 100
    autoCompress = true
  }
  processing {
    incrementalThreshold = 30
    maxCacheSize = 100
  }
}

// 在 ApplicationConfig 中添加读取方法
public int getWindowSize() {
    return config.hasPath("session.compression.windowSize")
        ? config.getInt("session.compression.windowSize") : 50;
}
```

**优先级**: 🟡 中 - 灵活性问题

---

### 5. 潜在的内存泄漏 - SessionProcessor.java (第76-127行)

**位置**: `SessionProcessor.java:76-127`

**问题描述**:
```java
ReentrantLock sessionLock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
// ... 使用锁
sessionLocks.remove(sessionId);  // 只在会话完成时移除
```

如果会话永远不会完成（长时间运行的会话），锁会一直保留在 `sessionLocks` 中，导致内存泄漏。

**修复建议**:
```java
// 添加定期清理机制
@Scheduled(fixedRate = 3600000)  // 每小时执行一次
public void cleanupStaleLocks() {
    long now = System.currentTimeMillis();
    sessionLocks.entrySet().removeIf(entry -> {
        SessionContext ctx = sessionCache.get(entry.getKey());
        if (ctx == null) {
            return true;  // 会话不存在，移除锁
        }
        // 会话超过1小时未活动，移除锁
        return now - ctx.getLastActivityTime().toEpochMilli() > 3600000;
    });
}
```

**优先级**: 🟡 中 - 长期运行问题

---

## 🟢 轻微问题

### 6. 异常处理不够细化 - MemoryService.java (第276-279行)

**位置**: `MemoryService.java:276-279`

**问题描述**:
```java
} catch (SQLException e) {
    log.error("保存记忆失败: {}", memory.title, e);
}
```

所有 SQL 异常都使用相同的处理方式，无法针对不同类型的错误采取不同的策略。

**修复建议**:
```java
} catch (SQLException e) {
    if (isUniqueConstraintViolation(e)) {
        log.debug("记忆已存在，跳过: {}", memory.title);
    } else if (isConnectionError(e)) {
        log.error("数据库连接失败，稍后重试: {}", memory.title, e);
        // 可以添加到重试队列
    } else {
        log.error("保存记忆失败: {}", memory.title, e);
    }
}

private boolean isUniqueConstraintViolation(SQLException e) {
    return e.getMessage() != null && e.getMessage().contains("unique constraint");
}

private boolean isConnectionError(SQLException e) {
    return e instanceof SQLNonTransientConnectionException;
}
```

**优先级**: 🟢 低 - 可维护性

---

### 7. 资源管理改进 - DatabaseService.java

**位置**: `DatabaseService.java:495-499`

**问题描述**:
虽然有 `close()` 方法，但没有实现 `AutoCloseable` 接口，无法使用 try-with-resources 语法。

**修复建议**:
```java
public class DatabaseService implements AutoCloseable {
    // ... 现有代码

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
            log.info("数据库连接池已关闭");
        }
    }
}

// 使用示例
try (DatabaseService dbService = new DatabaseService(config)) {
    dbService.init();
    // ... 使用数据库
}  // 自动关闭
```

**优先级**: 🟢 低 - 代码质量

---

### 8. 时间戳解析可以更健壮 - DatabaseService.java (第445-467行)

**位置**: `DatabaseService.java:445-467`

**当前实现**: 已支持 ISO 8601、毫秒时间戳、秒时间戳

**可选改进**: 添加对更多时间格式的支持，如：
- "yyyy-MM-dd HH:mm:ss"
- "yyyy/MM/dd HH:mm:ss"
- 带时区的时间格式

**优先级**: 🟢 低 - 功能增强

---

## ✅ 优秀实践

### 1. 并发控制设计
- ✅ 使用会话级锁 (`ReentrantLock`)，避免全局锁竞争
- ✅ 使用 `ConcurrentHashMap` 支持高并发
- ✅ 使用 `volatile` 保证可见性

### 2. 容错机制
- ✅ 实现指数退避重试机制 (`EmbeddingClient`)
- ✅ 多级降级策略 (LLM → 关键词 → 默认值)
- ✅ 优雅的错误处理和日志记录

### 3. 安全实践
- ✅ 使用 `ObjectMapper` 进行安全的 JSON 转义
- ✅ SQL 参数化查询（大部分地方）
- ✅ 输入验证和长度限制

### 4. 架构设计
- ✅ 清晰的模块化设计
- ✅ 职责分离（Service、DAO、Model 分离）
- ✅ 接口和实现分离（LLMProvider 设计）

### 5. 代码质量
- ✅ 使用 SLF4J 进行结构化日志
- ✅ 详细的 JavaDoc 注释
- ✅ 使用现代 Java 特性（switch 表达式、records 等）

---

## 修复优先级建议

### 立即修复 (P0)
1. **并发安全问题** (问题 #1) - 可能导致运行时异常
2. **SQL注入风险** (问题 #2) - 安全问题

### 尽快修复 (P1)
3. **硬编码配置** (问题 #4) - 影响灵活性
4. **内存泄漏风险** (问题 #5) - 长期运行稳定性

### 逐步改进 (P2)
5. **数据库兼容性注释** (问题 #3) - 维护性
6. **异常处理细化** (问题 #6) - 可维护性
7. **资源管理改进** (问题 #7) - 代码质量

### 可选优化 (P3)
8. **时间戳解析增强** (问题 #8) - 功能增强

---

## 总体评价

**代码质量**: ⭐⭐⭐⭐ (4/5)

**优点**:
- 架构设计合理，模块化清晰
- 并发控制考虑周全（除个别问题）
- 容错机制完善
- 代码可读性好

**需改进**:
- 部分并发安全问题需要修复
- 配置管理需要更加灵活
- 资源清理机制需要完善

**总结**: 这是一个设计良好的项目，主要问题集中在并发安全和配置灵活性方面。修复 P0 和 P1 问题后，代码质量将达到生产级别。
