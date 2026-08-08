# 记忆系统重构 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 AgentMemory 记忆系统重构为「8 个 agent 记忆源统一导入用户画像 + 按项目管理会话 + 经验合并 + 技能人工确认」，并优化压缩提示词。

**Architecture:** Java 后端（AgentDetectorService 扩展检测 8 agent + AgentMemorySyncService 定时/手动同步画像与会话 + FileWatcherService 扩展解析器）+ PostgreSQL 重构（experiences 新表、project_contexts 删除、skills 状态字段、agents 表加记忆源字段）+ Vue3 前端（Experiences 合并页、ProjectView、Agents 状态页、技能候选 tab）。

**Tech Stack:** Java 17 + Maven、PostgreSQL + pgvector、Vue 3 + Element Plus、SQLite JDBC（读 hermes/mavis/marvis/codex 库）

**设计文档:** `docs/plans/2026-08-08-memory-reorg-design.md`（已审批）

**构建/验证方式:** 项目无测试框架，遵循现有模式 `mvn clean package -DskipTests` + 启动后端 + `curl` API 验证。

---

## Phase 0: 环境与基线

### Task 0.1: 提交当前未提交的模型配置修改（建立干净基线）

**Files:**
- Modify: `backend/src/main/resources/application.conf`（已改，Qwen3.5-2B）
- Modify: `embedding_service/config.json`、`embedding_service/embed_server.py`（已改）
- Add: `docs/plans/2026-08-08-memory-reorg-design.md`、`task_plan.md`、`findings.md`、`progress.md`

**Step 1: 查看当前状态**
Run: `cd D:/Desktop_Archive/AgentMemory && git status --short`
Expected: 上述文件 M/?? 状态

**Step 2: 提交**
```bash
git add -A
git commit -m "chore: 切换默认模型为 Qwen3.5-2B/Qwen3-Embedding-0.6B，添加记忆重构设计文档"
```

---

## Phase 1: 数据库迁移

### Task 1.1: 编写迁移脚本 `database/migrate_memory_reorg.sql`

**Files:**
- Create: `database/migrate_memory_reorg.sql`

**Step 1: 编写脚本**（完整内容，含 agents 表记忆源字段）

```sql
-- 1. user_profiles 加来源字段
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS source_agent VARCHAR(50);
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS source_path TEXT;
CREATE INDEX IF NOT EXISTS idx_profiles_source ON user_profiles(source_agent, source_path);

-- 2. experiences 新表
CREATE TABLE IF NOT EXISTS experiences (...同前设计...);

-- 3. skills 加状态字段
ALTER TABLE skills ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'approved';
ALTER TABLE skills ADD COLUMN IF NOT EXISTS extracted_by VARCHAR(20) DEFAULT 'manual';

-- 4. 迁移旧数据到 experiences（error_corrections/best_practices）

-- 5. 删除旧表（project_contexts 直接删除）
DROP TABLE IF EXISTS project_contexts;

-- 6. agents 表加记忆源字段
ALTER TABLE agents ADD COLUMN IF NOT EXISTS memory_sources JSONB;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS session_db_path TEXT;

-- 7. 注册 4 个新 agent（hermes/mavis/marvis/minimax，若无）
INSERT INTO agents (name, display_name, parser_type, enabled) VALUES
    ('hermes', 'Hermes', 'sqlite', true),
    ('mavis', 'Mavis', 'sqlite', true),
    ('marvis', 'Marvis', 'sqlite', true),
    ('minimax', 'MiniMax Code', 'jsonl', true)
ON CONFLICT (name) DO NOTHING;
```

**Step 2: 备份旧数据（可选但推荐）**
```bash
# 迁移前导出旧数据备份
curl -s -o /tmp/ec_backup.json http://localhost:8080/api/errors/export
curl -s -o /tmp/bp_backup.json http://localhost:8080/api/practices/export
```

**Step 3: 执行迁移**
```bash
# 通过 docker exec 执行（需先启动数据库）
docker exec -i $(docker ps -qf name=agentmemory) psql -U agentmemory -d agent_memory -f - < database/migrate_memory_reorg.sql
```
Expected: 无报错，显示 ALTER/CREATE/DROP 成功

**Step 4: 验证**
```sql
SELECT type, COUNT(*) FROM experiences GROUP BY type;
-- Expected: 两行（best_practice / error_correction）各有计数
```

**Step 5: Commit**
```bash
git add database/migrate_memory_reorg.sql
git commit -m "feat(db): 记忆重构迁移脚本（experiences 合并、project_contexts 删除、skills 状态字段）"
```

---

## Phase 2: 后端 — Agent 记忆同步服务

### Task 2.1: 创建模型类 `AgentMemoryEntry` 和 Parser 接口

**Files:**
- Create: `backend/src/main/java/com/agentmemory/model/AgentMemoryEntry.java`
- Create: `backend/src/main/java/com/agentmemory/service/memorysync/MemoryParser.java`
- Create: `backend/src/main/java/com/agentmemory/service/memorysync/MarkdownMemoryParser.java`
- Create: `backend/src/main/java/com/agentmemory/service/memorysync/SqliteMemoryParser.java`
- Create: `backend/src/main/java/com/agentmemory/service/memorysync/JsonlMemoryParser.java`
- Create: `backend/src/main/java/com/agentmemory/service/memorysync/StructuredMarkdownParser.java`
- Create: `backend/src/main/java/com/agentmemory/service/memorysync/FrontmatterMarkdownParser.java`

**Step 1: 创建 AgentMemoryEntry**

```java
package com.agentmemory.model;

/** Agent 记忆源解析出的画像条目 */
public class AgentMemoryEntry {
    public String agent;        // hermes/pi/claude/workbuddy/minimax/mavis/marvis/codex
    public String category;     // 偏好/工具/项目/沟通 等
    public String content;      // 条目正文
    public String sourcePath;   // 原始文件路径
}
```

**Step 2: 创建 MemoryParser 接口**

```java
package com.agentmemory.service.memorysync;

import com.agentmemory.model.AgentMemoryEntry;
import java.util.List;

/** 统一解析接口：每种记忆格式一个实现 */
public interface MemoryParser {
    List<AgentMemoryEntry> parse(String sourcePath) throws Exception;
}
```

**Step 3: 实现 MarkdownMemoryParser（hermes §分隔 / claude rules / workbuddy memory / minimax user.md 通用）**

```java
package com.agentmemory.service.memorysync;

import com.agentmemory.model.AgentMemoryEntry;
import java.nio.file.*;
import java.util.*;

/** Markdown 解析：按 § 分隔或按行切分，过滤 markdown 装饰 */
public class MarkdownMemoryParser implements MemoryParser {
    @Override
    public List<AgentMemoryEntry> parse(String sourcePath) throws Exception {
        String content = Files.readString(Paths.get(sourcePath));
        List<AgentMemoryEntry> entries = new ArrayList<>();
        String[] sections = content.split("§");
        for (String sec : sections) {
            String cleaned = clean(sec);
            if (cleaned.length() > 20) {
                AgentMemoryEntry e = new AgentMemoryEntry();
                e.content = cleaned;
                e.category = inferCategory(cleaned);
                entries.add(e);
            }
        }
        return entries;
    }

    private String clean(String s) {
        // 去除 frontmatter、标题符号、空行
        String t = s.replaceAll("(?s)^---.*?---", "").trim();
        t = t.replaceAll("^#+\\s*", "").replaceAll("\\*\\*", "").trim();
        return t;
    }

    private String inferCategory(String content) {
        if (content.contains("偏好") || content.contains("风格") || content.contains("prefer")) return "偏好";
        if (content.contains("工具") || content.contains("tool")) return "工具";
        if (content.contains("项目") || content.contains("project")) return "项目";
        if (content.contains("沟通")) return "沟通";
        return "通用";
    }
}
```

**Step 4: 实现 SqliteMemoryParser（marvis user_profile / codex memories 表）**

```java
package com.agentmemory.service.memorysync;

import com.agentmemory.model.AgentMemoryEntry;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** SQLite 解析：只读打开，提取画像类表 */
public class SqliteMemoryParser implements MemoryParser {
    private final String table;
    private final String contentCol;

    public SqliteMemoryParser(String table, String contentCol) {
        this.table = table;
        this.contentCol = contentCol;
    }

    @Override
    public List<AgentMemoryEntry> parse(String sourcePath) throws Exception {
        // 复制临时文件避免锁冲突
        Path tmp = Files.createTempFile("agentmem_sync_", ".db");
        Files.copy(Paths.get(sourcePath), tmp, StandardCopyOption.REPLACE_EXISTING);
        List<AgentMemoryEntry> entries = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tmp)) {
            String sql = "SELECT " + contentCol + " FROM " + table + " LIMIT 500";
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    String c = rs.getString(1);
                    if (c != null && c.trim().length() > 20) {
                        AgentMemoryEntry e = new AgentMemoryEntry();
                        e.content = c.trim();
                        e.category = "画像";
                        entries.add(e);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return entries;
    }
}
```

**Step 5: 实现 JsonlMemoryParser（pi 会话流，只提取 user 消息）**

```java
package com.agentmemory.service.memorysync;

import com.agentmemory.model.AgentMemoryEntry;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** JSONL 解析：pi 会话事件流，提取 user 文本消息合并为画像条目 */
public class JsonlMemoryParser implements MemoryParser {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<AgentMemoryEntry> parse(String sourcePath) throws Exception {
        List<AgentMemoryEntry> entries = new ArrayList<>();
        List<String> userMsgs = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(sourcePath))) {
            if (line.isBlank()) continue;
            JsonNode node = mapper.readTree(line);
            if ("message".equals(node.path("type").asText())
                    && "user".equals(node.path("message").path("role").asText())) {
                JsonNode content = node.path("message").path("content");
                StringBuilder text = new StringBuilder();
                if (content.isArray()) {
                    for (JsonNode c : content) {
                        if ("text".equals(c.path("type").asText())) text.append(c.path("text").asText());
                    }
                }
                if (text.length() > 20) userMsgs.add(text.toString());
            }
        }
        // 合并用户消息为画像（避免碎片化）
        String joined = String.join("\n", userMsgs);
        if (joined.length() > 50) {
            AgentMemoryEntry e = new AgentMemoryEntry();
            e.content = joined.substring(0, Math.min(joined.length(), 5000));
            e.category = "会话偏好";
            entries.add(e);
        }
        return entries;
    }
}
```

**Step 6: 编译验证**
Run: `cd D:/Desktop_Archive/AgentMemory/backend && mvn compile -q`
Expected: BUILD SUCCESS

**Step 7: Commit**
```bash
git add backend/src/main/java/com/agentmemory/model/AgentMemoryEntry.java backend/src/main/java/com/agentmemory/service/memorysync/
git commit -m "feat: Agent 记忆源解析器（Markdown/SQLite/JSONL）"
```

---

### Task 2.2: 创建 `AgentMemorySyncService`

**Files:**
- Create: `backend/src/main/java/com/agentmemory/service/AgentMemorySyncService.java`
- Modify: `backend/src/main/java/com/agentmemory/AgentMemoryApplication.java`（注册服务）

**Step 1: 实现服务**（继承 ScheduledServiceBase，复用 CrushDatabaseWatcher 模式）

```java
package com.agentmemory.service;

import com.agentmemory.model.AgentMemoryEntry;
import com.agentmemory.service.memorysync.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Agent 记忆同步服务
 * 定时（60 分钟）+ 手动触发，扫描各 agent 记忆文件 → user_profiles
 */
public class AgentMemorySyncService extends ScheduledServiceBase {

    private static final Logger log = LoggerFactory.getLogger(AgentMemorySyncService.class);
    private final DatabaseService databaseService;
    private final Map<String, MemoryParser> parserByFormat = Map.of(
        "markdown", new MarkdownMemoryParser(),
        "sqlite", new SqliteMemoryParser("user_profile", "content"),
        "jsonl", new JsonlMemoryParser()
    );

    public AgentMemorySyncService(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @Override protected String getServiceName() { return "AgentMemorySyncService"; }
    @Override protected long getInitialDelaySeconds() { return TimeUnit.MINUTES.toSeconds(2); }
    @Override protected long getPeriodSeconds() { return TimeUnit.MINUTES.toSeconds(60); }
    @Override protected Logger getLogger() { return log; }

    /** 记忆源配置：agent -> 文件路径 */
    private List<AgentMemoryEntry> parseSource(String agent, String path, String format) throws Exception {
        MemoryParser parser = parserByFormat.get(format);
        if (parser == null) return List.of();
        List<AgentMemoryEntry> entries = parser.parse(path);
        for (AgentMemoryEntry e : entries) {
            e.agent = agent;
            e.sourcePath = path;
        }
        return entries;
    }

    @Override
    protected void executeTask() {
        syncAll();
    }

    /** 手动同步入口（API 调用） */
    public Map<String, Object> syncAll() {
        Map<String, Object> result = new HashMap<>();
        // 8 个 agent 源配置（路径基于 ~ 展开）
        Map<String, Object[]> sources = Map.of(
            "hermes", new Object[]{ "~/.hermes/memories/USER.md", "markdown" },
            "claude", new Object[]{ "~/.claude/AGENTS.md", "markdown" },
            "workbuddy", new Object[]{ "~/.workbuddy/USER.md", "markdown" },
            "minimax", new Object[]{ "~/.minimax/memory/user.md", "markdown" },
            "marvis", new Object[]{ "~/.marvis/database/memory.db", "sqlite" },
            "pi", new Object[]{ "~/.pi/agent/sessions", "jsonl" }
        );
        int total = 0, errors = 0;
        for (var entry : sources.entrySet()) {
            try {
                String path = ((String) entry.getValue()[0]).replace("~", System.getProperty("user.home"));
                String format = (String) entry.getValue()[1];
                int n = syncOne(entry.getKey(), path, format);
                total += n;
                log.info("同步 {}: 新增/更新 {} 条", entry.getKey(), n);
            } catch (Exception e) {
                errors++;
                log.error("同步 {} 失败: {}", entry.getKey(), e.getMessage());
            }
        }
        result.put("totalSynced", total);
        result.put("errors", errors);
        return result;
    }

    private int syncOne(String agent, String path, String format) throws Exception {
        if (format.equals("jsonl")) {
            // 目录：取每个项目会话目录下最新的 jsonl
            Path dir = Paths.get(path);
            if (!Files.isDirectory(dir)) return 0;
            int n = 0;
            try (var stream = Files.list(dir)) {
                for (Path sub : stream.limit(10).toList()) {
                    if (!Files.isDirectory(sub)) continue;
                    try (var files = Files.list(sub)) {
                        Optional<Path> newest = files.filter(f -> f.toString().endsWith(".jsonl"))
                            .max(Comparator.comparingLong(f -> f.toFile().lastModified()));
                        if (newest.isPresent()) n += syncOne(agent, newest.get().toString(), "jsonl");
                    }
                }
            }
            return n;
        }
        if (!Files.exists(Paths.get(path))) return 0;
        List<AgentMemoryEntry> entries = parseSource(agent, path, format);
        int n = 0;
        try (Connection conn = databaseService.getConnection()) {
            for (AgentMemoryEntry e : entries) {
                if (upsertProfile(conn, e)) n++;
            }
        }
        return n;
    }

    private boolean upsertProfile(Connection conn, AgentMemoryEntry e) throws SQLException {
        // 同源同内容去重
        String checkSql = "SELECT id FROM user_profiles WHERE source_agent = ? AND items::text LIKE ? LIMIT 1";
        try (PreparedStatement st = conn.prepareStatement(checkSql)) {
            st.setString(1, e.agent);
            st.setString(2, "%" + e.content.substring(0, Math.min(30, e.content.length())) + "%");
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return false; // 已存在
            }
        }
        String sql = "INSERT INTO user_profiles (id, title, category, items, source_agent, source_path) VALUES (?, ?, ?, ?::jsonb, ?, ?)";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, UUID.randomUUID().toString());
            st.setString(2, e.content.length() > 50 ? e.content.substring(0, 50) : e.content);
            st.setString(3, e.category);
            st.setString(4, "[{\"content\":\"" + e.content.replace("\"", "\\\"") + "\"}]");
            st.setString(5, e.agent);
            st.setString(6, e.sourcePath);
            st.executeUpdate();
            return true;
        }
    }
}
```

**Step 2: 注册服务到 AgentMemoryApplication.java**

找到服务初始化区（约 50-51 行附近），添加：
```java
this.memorySyncService = new AgentMemorySyncService(databaseService);
```
启动区（约 96 行 compressionService.start() 后）：
```java
memorySyncService.start();
```

**Step 3: 编译验证**
Run: `cd D:/Desktop_Archive/AgentMemory/backend && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**
```bash
git add backend/src/main/java/com/agentmemory/service/AgentMemorySyncService.java backend/src/main/java/com/agentmemory/AgentMemoryApplication.java
git commit -m "feat: AgentMemorySyncService 定时+手动同步 8 个 agent 记忆源"
```

---

### Task 2.3: 新增同步 API 端点

**Files:**
- Modify: `backend/src/main/java/com/agentmemory/api/ApiServer.java`（注册 + 实现 handler）

**Step 1: 注册端点**（`server.createContext` 区，约 99-108 行）
```java
server.createContext("/api/sync", new MemorySyncHandler());
```

**Step 2: 实现 handler**（添加在 ApiServer 类内）

```java
class MemorySyncHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        wrapHandler(exchange, () -> {
            String method = exchange.getRequestMethod();
            if ("POST".equals(method)) {
                Map<String, Object> result = memorySyncService.syncAll();
                sendJson(exchange, result);
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        });
    }
}
```

**Step 3: 给 ApiServer 注入 memorySyncService**（构造器加参数）

**Step 4: 编译 + 启动验证**
Run:
```bash
cd D:/Desktop_Archive/AgentMemory/backend && mvn clean package -DskipTests -q
# 启动数据库和 embedding 后：
curl -X POST http://localhost:8080/api/sync
```
Expected: `{"totalSynced":N,"errors":0}`

**Step 5: Commit**
```bash
git add backend/src/main/java/com/agentmemory/api/ApiServer.java
git commit -m "feat(api): POST /api/sync 手动触发记忆同步"
```

### Task 2.4: AgentDetectorService 扩展（检测 hermes/mavis/marvis/minimax）

**Files:**
- Modify: `backend/src/main/java/com/agentmemory/service/AgentDetectorService.java`

**Step 1: 在 detectAgents() 中追加检测**
```java
addIfNotNull(agents, detectAgent("Hermes", "hermes", ".hermes"));
addIfNotNull(agents, detectAgent("Mavis", "mavis", ".mavis"));
addIfNotNull(agents, detectAgent("Marvis", "marvis", ".marvis"));
addIfNotNull(agents, detectAgent("MiniMax Code", "minimax", ".minimax"));
```

**Step 2: 确保检测到的 agent 写入 agents 表（含 memory_sources 配置）**
- SetupHandler 中为每个 agent 写入 memory_sources JSONB：
  - hermes: `[{"path":"~/.hermes/memories/USER.md","format":"markdown"},{"path":"~/.hermes/memories/MEMORY.md","format":"markdown"}]` + `session_db_path=~/.hermes/state.db`
  - mavis: `session_db_path=~/.mavis/sqlite.db`
  - marvis: `[{"path":"~/.marvis/database/memory.db","format":"sqlite","table":"user_profile"}]` + `session_db_path=~/.marvis/database/memory.db`
  - minimax: `[{"path":"~/.minimax/memory/user.md","format":"markdown"}]`

**Step 3: 编译验证**
Run: `cd D:/Desktop_Archive/AgentMemory/backend && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**
```bash
git add backend/src/main/java/com/agentmemory/service/AgentDetectorService.java
 git commit -m "feat: AgentDetectorService 扩展检测 hermes/mavis/marvis/minimax"
```

---

## Phase 3: 后端 — 会话管理 API

### Task 3.1: 会话标题懒生成

**Files:**
- Modify: `backend/src/main/java/com/agentmemory/api/ApiServer.java`

**Step 1: 在 SessionsHandler 增加 title 子路径**（`/api/sessions/{id}/title`）

```java
// 在 SessionsHandler.handle() 中，path 匹配逻辑之前加：
if (path.matches("/api/sessions/[^/]+/title")) {
    handleTitle(exchange, parseIdFromPath(path, "/api/sessions/"));
    return;
}

private void handleTitle(HttpExchange exchange, String sessionId) throws IOException, SQLException {
    // 1. 查缓存
    String cached = getSessionTitle(sessionId);
    if (cached != null && !cached.isBlank()) { sendJson(exchange, Map.of("title", cached)); return; }
    // 2. 取前 5 条 user 消息
    List<String> userMsgs = getFirstUserMessages(sessionId, 5);
    // 3. LLM 生成 或 规则截断（LLM 不可用时）
    String title;
    if (!userMsgs.isEmpty()) {
        String first = userMsgs.get(0);
        title = first.length() > 30 ? first.substring(0, 30) : first; // 规则兜底
        // TODO: 若 llmClient 可用则调用 llmClient 生成更佳标题
    } else {
        title = "未命名会话 " + sessionId.substring(0, 8);
    }
    // 4. 写回缓存
    updateSessionTitle(sessionId, title);
    sendJson(exchange, Map.of("title", title));
}
```

**Step 2: 实现辅助方法**（getSessionTitle/updateSessionTitle/getFirstUserMessages）

**Step 3: 编译验证**
Run: `cd D:/Desktop_Archive/AgentMemory/backend && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**
```bash
git add backend/src/main/java/com/agentmemory/api/ApiServer.java
git commit -m "feat(api): 会话标题懒生成（GET /api/sessions/{id}/title）"
```

---

### Task 3.2: 会话消息软删除

**Files:**
- Modify: `backend/src/main/java/com/agentmemory/api/ApiServer.java`

**Step 1: 在 SessionsHandler 增加 DELETE 子路径**（`/api/sessions/{id}/messages`）

```java
if (path.matches("/api/sessions/[^/]+/messages") && "DELETE".equals(exchange.getRequestMethod())) {
    handleDeleteMessages(exchange, parseIdFromPath(path, "/api/sessions/"));
    return;
}

private void handleDeleteMessages(HttpExchange exchange, String sessionId) throws IOException, SQLException {
    try (Connection conn = databaseService.getConnection();
         PreparedStatement st = conn.prepareStatement(
             "UPDATE messages SET deleted = true, expires_at = NOW() WHERE session_id = ?")) {
        st.setString(1, sessionId);
        int n = st.executeUpdate();
        sendJson(exchange, Map.of("deleted", n));
    }
}
```

**Step 2: 编译验证**
Run: `cd D:/Desktop_Archive/AgentMemory/backend && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**
```bash
git add backend/src/main/java/com/agentmemory/api/ApiServer.java
git commit -m "feat(api): DELETE /api/sessions/{id}/messages 软删除原消息"
```

---

### Task 3.3: experiences API（合并端点）

**Files:**
- Modify: `backend/src/main/java/com/agentmemory/api/ApiServer.java`

**Step 1: 注册**（约 99-108 行 createContext 区）
```java
server.createContext("/api/experiences", new ExperiencesHandler());
```

**Step 2: 实现 ExperiencesHandler**（仿照 ErrorCorrectionsHandler，改表名/字段）

```java
class ExperiencesHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        wrapHandler(exchange, () -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.endsWith("/export")) { handleExport(exchange); return; }
            String type = getQueryParam(exchange, "type"); // best_practice / error_correction
            switch (method) {
                case "GET" -> {
                    if (path.matches("/api/experiences/[^/]+")) handleGetSingle(exchange, parseIdFromPath(path, "/api/experiences/"));
                    else handleList(exchange, type);
                }
                case "POST" -> handleCreate(exchange);
                case "PUT" -> handleUpdate(exchange, parseIdFromPath(path, "/api/experiences/"));
                case "DELETE" -> handleDelete(exchange, parseIdFromPath(path, "/api/experiences/"));
                default -> sendError(exchange, 405, "Method Not Allowed");
            }
        });
    }
    // handleList 支持 type 过滤；字段映射 scenario/practice/rationale/example/tags
}
```

**Step 3: 编译验证**
Run: `cd D:/Desktop_Archive/AgentMemory/backend && mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**
```bash
git add backend/src/main/java/com/agentmemory/api/ApiServer.java
git commit -m "feat(api): experiences 合并端点（GET/POST/PUT/DELETE + type 过滤 + export）"
```

---

### Task 3.4: skills 状态 API

**Files:**
- Modify: `backend/src/main/java/com/agentmemory/api/ApiServer.java`

**Step 1: SkillsHandler 增加 status 支持**

```java
// GET 列表：支持 ?status=pending 过滤
String status = getQueryParam(exchange, "status");
// SQL: ... WHERE deleted = false [AND status = ?]

// 确认/忽略端点
if (path.matches("/api/skills/[^/]+/approve")) { handleStatusChange(exchange, id, "approved"); return; }
if (path.matches("/api/skills/[^/]+/reject")) { handleStatusChange(exchange, id, "rejected"); return; }
if (path.matches("/api/skills/pending-count")) { sendJson(exchange, Map.of("count", getPendingSkillCount())); return; }
```

**Step 2: 编译验证**
Run: `cd D:/Desktop_Archive/AgentMemory/backend && mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**
```bash
git add backend/src/main/java/com/agentmemory/api/ApiServer.java
git commit -m "feat(api): skills 状态流转（pending/approve/reject + pending-count）"
```

---

## Phase 4: 前端

### Task 4.1: Experiences.vue（合并 Errors + Practices）

**Files:**
- Create: `frontend/src/views/Experiences.vue`
- Modify: `frontend/src/App.vue`（替换 Errors/Practices，菜单改"实践经验"）
- Delete: `frontend/src/views/Errors.vue`、`frontend/src/views/Practices.vue`

**Step 1: 创建 Experiences.vue**（核心结构）

```vue
<template>
  <div class="experiences-view">
    <div class="panel-header">
      <h2>实践经验</h2>
      <el-button type="primary" @click="openCreate()"><el-icon><Plus/></el-icon> 新增</el-button>
      <el-button @click="exportData"><el-icon><Download/></el-icon> 导出</el-button>
    </div>
    <!-- 类型 tab -->
    <el-tabs v-model="activeType" @tab-change="loadData">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="最佳实践" name="best_practice" />
      <el-tab-pane label="错误纠正" name="error_correction" />
    </el-tabs>
    <el-table :data="items">
      <el-table-column prop="title" label="标题" min-width="200" />
      <el-table-column prop="type" label="类型" width="110">
        <template #default="{row}">
          <el-tag :type="row.type === 'error_correction' ? 'danger' : 'success'">
            {{ row.type === 'error_correction' ? '错误纠正' : '最佳实践' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="scenario" label="场景" min-width="200" show-overflow-tooltip />
      <el-table-column prop="practice" label="做法" min-width="250" show-overflow-tooltip />
      <el-table-column label="操作" width="150">
        <template #default="{row}">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
<script setup lang="ts">
// API 路径改为 /api/experiences?type=xxx
// 复制 Practices.vue 的 CRUD 逻辑，字段名替换
</script>
```

**Step 2: 修改 App.vue**
- 删除 Errors/Practices 导入和 v-if 块
- 菜单："错误纠正" + "实践经验" → 单个"实践经验"（activeMenu='experiences'）
- `<Experiences v-if="activeMenu === 'experiences'" ref="experiencesRef" />`

**Step 3: 删除旧文件并构建验证**
Run: `cd D:/Desktop_Archive/AgentMemory/frontend && npm run build 2>&1 | tail -5`
Expected: 构建成功，无 import 错误

**Step 4: Commit**
```bash
git add frontend/src/views/Experiences.vue frontend/src/App.vue
git rm frontend/src/views/Errors.vue frontend/src/views/Practices.vue
git commit -m "feat(ui): 实践经验合并页（类型 tab 切换）"
```

---

### Task 4.2: 技能候选 tab（Skills.vue 改造）

**Files:**
- Modify: `frontend/src/views/Skills.vue`

**Step 1: 加 tab 结构**（现有列表 + 候选列表）

```vue
<el-tabs v-model="activeTab" @tab-change="handleTabChange">
  <el-tab-pane label="技能库" name="approved" />
  <el-tab-pane name="pending">
    <template #label>技能候选 <el-badge v-if="pendingCount > 0" :value="pendingCount" /></template>
  </el-tab-pane>
</el-tabs>
<!-- approved 显示原列表；pending 显示候选：标题/类型/步骤预览 + 确认/忽略按钮 -->
```

**Step 2: 候选确认逻辑**
```ts
const approveSkill = async (id: string) => {
  await api.post(`/skills/${id}/approve`)
  await loadPending()
}
const rejectSkill = async (id: string) => {
  await api.post(`/skills/${id}/reject`)
  await loadPending()
}
```

**Step 3: 构建验证**
Run: `cd D:/Desktop_Archive/AgentMemory/frontend && npm run build 2>&1 | tail -5`
Expected: 成功

**Step 4: Commit**
```bash
git add frontend/src/views/Skills.vue
git commit -m "feat(ui): 技能候选 tab（确认/忽略 + 红点计数）"
```

---

### Task 4.3: 项目视图 ProjectView.vue + 导航

**Files:**
- Create: `frontend/src/views/ProjectView.vue`
- Modify: `frontend/src/App.vue`

**Step 1: 创建 ProjectView.vue**（按 project_path 分组会话树 + 标题懒生成触发）

```vue
<template>
  <div class="project-view">
    <div class="panel-header"><h2>项目会话</h2></div>
    <el-tree :data="projects" :props="{label: 'name', children: 'sessions'}">
      <template #default="{data}">
        <span v-if="data.sessions">{{ data.name }} ({{ data.sessions.length }})</span>
        <span v-else @click="loadTitle(data)">{{ data.title || data.id.slice(0,8) + '…' }}</span>
      </template>
    </el-tree>
  </div>
</template>
<script setup lang="ts">
// GET /api/sessions?limit=500 → 前端按 projectPath 分组
// 展示时若 title 为空 → GET /api/sessions/{id}/title 懒生成并缓存
</script>
```

**Step 2: App.vue 菜单**：把"项目上下文"菜单改为"项目会话"（activeMenu='projects'），移除 Contexts.vue

**Step 3: 删除 Contexts.vue**
Run: `git rm frontend/src/views/Contexts.vue`

**Step 4: 构建验证**
Run: `cd D:/Desktop_Archive/AgentMemory/frontend && npm run build 2>&1 | tail -5`
Expected: 成功

**Step 5: Commit**
```bash
git add frontend/src/views/ProjectView.vue frontend/src/App.vue
git commit -m "feat(ui): 项目会话视图（按 project_path 分组 + 标题懒生成）"
```

---

### Task 4.4: Profiles.vue 同步按钮 + 来源徽标

**Files:**
- Modify: `frontend/src/views/Profiles.vue`

**Step 1: 头部加"立即同步"按钮**
```vue
<el-button type="success" @click="syncNow" :loading="syncing">
  <el-icon><Refresh/></el-icon> 同步记忆
</el-button>
```

**Step 2: 表格加 source_agent 列**（el-tag 徽标：hermes/pi/claude/... 不同色）

**Step 3: syncNow 逻辑**
```ts
const syncNow = async () => {
  syncing.value = true
  try { const r = await api.post('/sync'); ElMessage.success(`同步完成：${r.totalSynced} 条`) }
  finally { syncing.value = false; await loadData() }
}
```

**Step 4: 构建验证 + Commit**
```bash
cd D:/Desktop_Archive/AgentMemory/frontend && npm run build 2>&1 | tail -3
git add frontend/src/views/Profiles.vue
git commit -m "feat(ui): 用户画像手动同步按钮 + 来源 agent 徽标"
```

---

### Task 4.6: Agents 状态页（8 agent 全景）

**Files:**
- Create: `frontend/src/views/Agents.vue`
- Modify: `frontend/src/App.vue`（导航加"Agent 接入"菜单）

**Step 1: 创建 Agents.vue**（状态卡网格）
```vue
<template>
  <div class="agents-view">
    <div class="panel-header">
      <h2>Agent 接入</h2>
      <el-button type="success" @click="syncAll">全部同步</el-button>
    </div>
    <el-row :gutter="16">
      <el-col :span="8" v-for="a in agents" :key="a.id">
        <el-card>
          <template #header>
            <el-tag :type="a.enabled ? 'success' : 'info'">{{ a.enabled ? '已启用' : '未启用' }}</el-tag>
            {{ a.displayName }}
          </template>
          <p>解析器: {{ a.parserType }}</p>
          <p>会话数: {{ a.sessionCount ?? '-' }}</p>
          <p>记忆源: {{ a.memorySources?.length ?? 0 }} 个</p>
          <el-button size="small" @click="syncAgent(a)">同步</el-button>
          <el-button size="small" type="primary" @click="importSessions(a)">导入会话</el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
```

**Step 2: 逻辑**：GET /api/agents（含 memory_sources/sessionCount）；POST /api/agents/{id}/sync、POST /api/agents/{id}/import-sessions

**Step 3: App.vue 导航加"Agent 接入"**（activeMenu='agents'）

**Step 4: 构建验证 + Commit**
```bash
cd D:/Desktop_Archive/AgentMemory/frontend && npm run build 2>&1 | tail -3
git add frontend/src/views/Agents.vue frontend/src/App.vue
git commit -m "feat(ui): Agent 接入状态页（8 agent 全景 + 手动同步）"
```

### Task 4.7: 压缩页按项目筛选 + 删除原消息（原 Task 4.5 号）

**Files:**
- Modify: `frontend/src/views/Compression.vue`

**Step 1: 加项目筛选下拉**（从 sessions 提取去重 project_path）

**Step 2: 会话行加"删除原消息"按钮**
```ts
const deleteMessages = async (sessionId: string) => {
  await ElMessageBox.confirm('删除后不可恢复（摘要保留），确定？', '警告', { type: 'warning' })
  await api.delete(`/sessions/${sessionId}/messages`)
  ElMessage.success('已删除')
}
```

**Step 3: 构建验证 + Commit**
```bash
cd D:/Desktop_Archive/AgentMemory/frontend && npm run build 2>&1 | tail -3
git add frontend/src/views/Compression.vue
git commit -m "feat(ui): 压缩页按项目筛选 + 删除原消息"
```

---

## Phase 5: 压缩提示词优化 + 新模型测试

### Task 5.1: 优化压缩提示词

**Files:**
- Modify: `backend/src/main/java/com/agentmemory/service/SemanticCompressor.java`（找到 summarize 提示词）
- Modify: `backend/src/main/resources/application.conf`（llm.extractionPrompt）

**Step 1: 找到现有提示词**
Run: `grep -n "prompt\|Prompt\|summarize" backend/src/main/java/com/agentmemory/service/SemanticCompressor.java | head`

**Step 2: 重写摘要提示词为四段式结构**

```java
private static final String SUMMARY_PROMPT = """
你是专业的对话压缩助手。将以下对话压缩为结构化摘要。

必须输出格式：
【目标】本次会话要解决的问题（1-2 句）
【决策】做出的关键决策及理由（要点式）
【问题】遇到的问题与解决方案（要点式）
【结论】最终结论/产出物（1-3 句）

要求：
- 保留所有关键技术细节、命令、路径、参数
- 忽略寒暄、重复、中间过程的无效尝试
- 中文输出，总长度不超过原文的 20%

对话内容：
{content}
""";
```

**Step 3: 替换 SemanticCompressor 中的提示词引用**

**Step 4: 编译验证 + Commit**
```bash
cd D:/Desktop_Archive/AgentMemory/backend && mvn compile -q
git add backend/src/main/java/com/agentmemory/service/SemanticCompressor.java backend/src/main/resources/application.conf
git commit -m "feat: 压缩摘要提示词优化（目标/决策/问题/结论四段式）"
```

---

### Task 5.2: 用 Qwen3.5-2B 测试压缩效果

**Files:**
- Create: `docs/compression_test_report.md`（测试报告）

**Step 1: 准备测试会话**（选取 3-5 个真实长会话）
```bash
# 从 DB 导出长会话消息
curl -s "http://localhost:8080/api/sessions?limit=50" | python -c "..." # 挑 message_count 大的
```

**Step 2: 配置 LLM provider 为 Qwen3.5-2B**
- 前端 Settings → LLM Provider 添加：provider=local/ollama, baseUrl=localhost:11434, model=qwen3.5:2b
- 或直接调 SemanticCompressor 的 summarize

**Step 3: 执行压缩并记录**
```bash
curl -X POST "http://localhost:8080/api/compression/compress" -d '{"sessionId":"xxx","type":"HYBRID"}'
```

**Step 4: 对比评估**（对照表：优化前/后摘要 vs 原文）
评估维度：信息保留率、结构清晰度、长度压缩比、幻觉率

**Step 5: 写报告 + Commit**
```bash
git add docs/compression_test_report.md
git commit -m "docs: Qwen3.5-2B 压缩效果测试报告"
```

---

## Phase 6: 集成验证与收尾

### Task 6.1: 端到端验证

**Step 1: 全量构建**
```bash
cd D:/Desktop_Archive/AgentMemory/backend && mvn clean package -DskipTests -q
cd D:/Desktop_Archive/AgentMemory/frontend && npm run build 2>&1 | tail -3
```

**Step 2: 启动全栈**（start.bat 或手动按顺序）
```bash
docker-compose up -d          # 数据库
python embed_server.py        # embedding :8100
java -jar backend/target/...  # 后端 :8080
npm run dev                   # 前端 :5173
```

**Step 3: 功能验证清单**
```bash
curl http://localhost:8080/api/health                          # 健康
curl -X POST http://localhost:8080/api/sync                    # 记忆同步（画像+会话）
curl http://localhost:8080/api/agents                          # 8 agent 状态
curl -X POST http://localhost:8080/api/agents/hermes/import-sessions  # hermes 会话导入（SQLite）
curl -X POST http://localhost:8080/api/agents/mavis/import-sessions   # mavis 会话导入
curl -X POST http://localhost:8080/api/agents/marvis/import-sessions  # marvis 会话导入
curl "http://localhost:8080/api/experiences?type=error_correction"  # 经验过滤
curl http://localhost:8080/api/skills/pending-count            # 技能候选数
curl "http://localhost:8080/api/sessions/{id}/title"           # 标题懒生成
curl -X DELETE "http://localhost:8080/api/sessions/{id}/messages"  # 删除原消息
curl -X POST "http://localhost:8080/api/search" -H "Content-Type: application/json" -d '{"query":"画像","top_k":5}'  # 搜索含 agent 画像
# 验证导入计数：
#   hermes: sessions 68 → messages 7532 已入 sessions/messages 表
#   mavis: session_messages 610 已入
#   marvis: conversation_detail 10557 已入（或按需限量）
```

**Step 4: 浏览器验收**（前端各页面操作：同步、tab 切换、候选确认、项目分组）

**Step 5: Commit**
```bash
git add -A
git commit -m "chore: 端到端验证通过"
```

### Task 6.2: 更新文档

**Files:**
- Modify: `AGENTS.md`（新表结构、新服务、新端点）
- Modify: `README.md`（记忆系统说明）

**Step 1: 更新 AGENTS.md 数据库 schema 部分**（experiences 表、user_profiles 新字段、skills 状态字段、删除 project_contexts 相关）

**Step 2: 更新服务列表**（AgentMemorySyncService 加入服务清单）

**Step 3: Commit**
```bash
git add AGENTS.md README.md
git commit -m "docs: 更新记忆系统重构后的架构文档"
```

---

## 注意事项

1. **数据库需先启动**：所有 API 验证前先 `docker-compose up -d`
2. **旧端点兼容**：/api/errors、/api/practices 保留别名（重定向到 /api/experiences），避免前端遗漏
3. **向量维度**：experiences/user_profiles 用 vector(1024)（Qwen3-Embedding-0.6B），迁移后需重新生成 embedding
4. **SQLite 锁**：hermes/mavis/marvis/codex 运行中读库 → 先复制临时文件（parser 内已实现）
5. **mavis daily 日志跳过**：仅取 sqlite.db 的 session_messages 表
6. **pi 会话文件大**：每项目目录只取最新 jsonl，限制 10 个项目
7. **每个 Task 结束后提交**，保持小步提交可回滚
8. **hermes 会话量大**（7532 条消息）：首次导入分批（每批 500 条），带进度；只导入非 tool 消息（user/assistant）
9. **marvis 会话量大**（conversation_detail 10557 行）：按 conversation_id 分组，仅导入文本类消息（type='text'），图片/工具输出跳过
10. **minimax 会话暂空**：parser 按 JSONL 预留（与 pi 同型），待有数据后自动生效
11. **AgentMemorySyncService 的 SQLite 会话导入**：实现 `importSessionsFromDb(agent, dbPath)` —— 复制临时库 → 读 sessions/messages（hermes: sessions/messages 表；mavis: sessions/session_messages 表；marvis: conversations/conversation_detail 表）→ 增量导入（按 session_id + 时间戳去重）→ 触发记忆提取流水线
