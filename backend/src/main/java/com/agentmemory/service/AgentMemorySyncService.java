package com.agentmemory.service;

import com.agentmemory.model.AgentMemoryEntry;
import com.agentmemory.model.Message;
import com.agentmemory.service.memorysync.JsonlMemoryParser;
import com.agentmemory.service.memorysync.MarkdownMemoryParser;
import com.agentmemory.service.memorysync.MemoryParser;
import com.agentmemory.service.memorysync.SqliteMemoryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Agent 记忆同步服务
 *
 * 两大职责：
 * 1. 画像同步：扫描各 agent 记忆文件（USER.md / memory.db / 会话流）→ user_profiles 表
 * 2. 会话导入：SQLite 型 agent（hermes/mavis/marvis）的会话批量导入 sessions/messages
 *
 * JSONL 型 agent（pi/claude/workbuddy/codex/minimax）由 FileWatcherService 实时监控，本服务不重复导入。
 */
public class AgentMemorySyncService extends ScheduledServiceBase {

    private static final Logger log = LoggerFactory.getLogger(AgentMemorySyncService.class);
    private final DatabaseService databaseService;

    private final MarkdownMemoryParser markdownParser = new MarkdownMemoryParser();
    private final JsonlMemoryParser jsonlParser = new JsonlMemoryParser();

    public AgentMemorySyncService(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @Override
    protected String getServiceName() { return "AgentMemorySyncService"; }
    @Override
    protected long getInitialDelaySeconds() { return TimeUnit.MINUTES.toSeconds(2); }
    @Override
    protected long getPeriodSeconds() { return TimeUnit.MINUTES.toSeconds(60); }
    @Override
    protected Logger getLogger() { return log; }

    @Override
    protected void executeTask() {
        syncAllProfiles();
        importAllSqliteSessions();
    }

    // ==================== 画像同步 ====================

    /**
     * 同步所有 agent 记忆文件到 user_profiles（手动触发入口）
     */
    public Map<String, Object> syncAllProfiles() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object[]> sources = buildProfileSources();
        int total = 0, errors = 0;
        List<String> errorDetails = new ArrayList<>();

        for (Map.Entry<String, Object[]> entry : sources.entrySet()) {
            String agent = entry.getKey();
            String path = ((String) entry.getValue()[0]).replace("~", System.getProperty("user.home"));
            String format = (String) entry.getValue()[1];
            try {
                int n = syncProfileSource(agent, path, format);
                total += n;
                log.info("同步 {}: 新增 {} 条画像", agent, n);
            } catch (Exception e) {
                errors++;
                errorDetails.add(agent + ": " + e.getMessage());
                log.error("同步 {} 失败: {}", agent, e.getMessage());
            }
        }

        result.put("totalSynced", total);
        result.put("errors", errors);
        result.put("errorDetails", errorDetails);
        return result;
    }

    /** 构建画像源配置（agent -> [paths, format]） */
    private Map<String, Object[]> buildProfileSources() {
        Map<String, Object[]> sources = new HashMap<>();
        sources.put("hermes", new Object[]{ "~/.hermes/memories", "markdown-dir" });
        sources.put("claude", new Object[]{ "~/.claude/AGENTS.md", "markdown" });
        sources.put("workbuddy", new Object[]{ "~/.workbuddy/USER.md", "markdown" });
        sources.put("minimax", new Object[]{ "~/.minimax/memory/user.md", "markdown" });
        sources.put("marvis", new Object[]{ "~/.marvis/database/memory.db", "sqlite" });
        return sources;
    }

    private int syncProfileSource(String agent, String path, String format) throws Exception {
        if (format.equals("jsonl")) {
            return syncJsonlProfileSource(agent, path);
        }
        if (format.equals("markdown-dir")) {
            // 目录：扫描所有 .md 文件（hermes memories/ 含 USER.md + MEMORY.md）
            Path dir = Paths.get(path);
            if (!Files.isDirectory(dir)) return 0;
            int n = 0;
            try (var stream = Files.list(dir)) {
                for (Path f : stream.filter(p -> p.toString().endsWith(".md")).toList()) {
                    n += syncProfileSource(agent, f.toString(), "markdown");
                }
            }
            return n;
        }
        if (!Files.exists(Paths.get(path))) {
            log.debug("记忆文件不存在，跳过: {}", path);
            return 0;
        }
        List<AgentMemoryEntry> entries;
        if (format.equals("sqlite")) {
            // marvis: user_profile 表
            entries = new SqliteMemoryParser("user_profile", "content").parse(path);
        } else {
            entries = markdownParser.parse(path);
        }
        int n = 0;
        try (Connection conn = databaseService.getConnection()) {
            for (AgentMemoryEntry e : entries) {
                e.setAgent(agent);
                e.setSourcePath(path);
                if (upsertProfile(conn, e)) n++;
            }
        }
        return n;
    }

    /** JSONL 源：取每个项目会话目录下最新的 jsonl，提取 user 消息作为画像 */
    private int syncJsonlProfileSource(String agent, String dirPath) throws Exception {
        Path dir = Paths.get(dirPath);
        if (!Files.isDirectory(dir)) return 0;
        int n = 0;
        List<Path> subs;
        try (var stream = Files.list(dir)) {
            subs = stream.filter(Files::isDirectory).sorted(Comparator.comparing(p -> p.getFileName().toString())).limit(10).toList();
        }
        for (Path sub : subs) {
            Optional<Path> newest;
            try (var files = Files.list(sub)) {
                newest = files.filter(f -> f.toString().endsWith(".jsonl"))
                        .max(Comparator.comparingLong(f -> f.toFile().lastModified()));
            }
            if (newest.isPresent()) {
                List<AgentMemoryEntry> entries = jsonlParser.parse(newest.get().toString());
                try (Connection conn = databaseService.getConnection()) {
                    for (AgentMemoryEntry e : entries) {
                        e.setAgent(agent);
                        if (upsertProfile(conn, e)) n++;
                    }
                }
            }
        }
        return n;
    }

    /** upsert 画像条目（同 agent 同内容去重） */
    private boolean upsertProfile(Connection conn, AgentMemoryEntry e) throws SQLException, com.fasterxml.jackson.core.JsonProcessingException {
        // 去重：同 agent + 内容前缀匹配
        String prefix = e.getContent().substring(0, Math.min(40, e.getContent().length()));
        String checkSql = "SELECT COUNT(*) FROM user_profiles WHERE source_agent = ? AND items::text LIKE ?";
        try (PreparedStatement st = conn.prepareStatement(checkSql)) {
            st.setString(1, e.getAgent());
            st.setString(2, "%" + prefix + "%");
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) return false;
            }
        }
        String sql = """
            INSERT INTO user_profiles (id, title, category, items, source_agent, source_path)
            VALUES (?, ?, ?, ?::jsonb, ?, ?)
            """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, UUID.randomUUID().toString());
            String title = e.getContent().length() > 50 ? e.getContent().substring(0, 50) : e.getContent();
            st.setString(2, title.replace('\n', ' '));
            st.setString(3, e.getCategory());
            // 用 JSON 序列化避免转义问题
            String itemsJson = "[{\"content\":" + new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(e.getContent()) + "}]";
            st.setString(4, itemsJson);
            st.setString(5, e.getAgent());
            st.setString(6, e.getSourcePath());
            st.executeUpdate();
            return true;
        }
    }

    // ==================== SQLite 会话导入 ====================

    /**
     * 导入所有 SQLite 型 agent 的会话（hermes/mavis/marvis）
     */
    public Map<String, Object> importAllSqliteSessions() {
        Map<String, Object> result = new HashMap<>();
        Map<String, String> dbs = new HashMap<>();
        dbs.put("hermes", "~/.hermes/state.db");
        dbs.put("mavis", "~/.mavis/sqlite.db");
        dbs.put("marvis", "~/.marvis/database/memory.db");

        int totalMessages = 0, totalSessions = 0, errors = 0;
        List<String> errorDetails = new ArrayList<>();

        for (Map.Entry<String, String> entry : dbs.entrySet()) {
            String agent = entry.getKey();
            String dbPath = entry.getValue().replace("~", System.getProperty("user.home"));
            try {
                int[] counts = importSessionsFromDb(agent, dbPath);
                totalMessages += counts[0];
                totalSessions += counts[1];
                log.info("会话导入 {}: {} 消息, {} 会话", agent, counts[0], counts[1]);
            } catch (Exception e) {
                errors++;
                errorDetails.add(agent + ": " + e.getMessage());
                log.error("会话导入 {} 失败: {}", agent, e.getMessage());
            }
        }

        result.put("totalMessages", totalMessages);
        result.put("totalSessions", totalSessions);
        result.put("errors", errors);
        result.put("errorDetails", errorDetails);
        return result;
    }

    /**
     * 从 SQLite 库导入会话（复制临时文件避免锁冲突）
     * @return [消息数, 会话数]
     */
    public int[] importSessionsFromDb(String agent, String dbPath) throws Exception {
        Path tmp = Files.createTempFile("agentmem_sessions_", ".db");
        Files.copy(Paths.get(dbPath), tmp, StandardCopyOption.REPLACE_EXISTING);
        int[] counts = new int[]{0, 0};
        try (Connection src = DriverManager.getConnection("jdbc:sqlite:" + tmp)) {
            switch (agent) {
                case "hermes" -> counts = importHermes( src);
                case "mavis" -> counts = importMavis(src);
                case "marvis" -> counts = importMarvis(src);
                default -> log.warn("未知 SQLite agent: {}", agent);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return counts;
    }

    /** hermes: sessions + messages 表 */
    private int[] importHermes(Connection src) throws Exception {
        int messages = 0, sessions = 0;
        // 1. 导入会话
        String sessionSql = "SELECT id, COALESCE(started_at, datetime('now')) AS started FROM sessions";
        try (PreparedStatement st = src.prepareStatement(sessionSql); ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (id == null || id.isBlank()) continue;
                databaseService.saveSessionIfNotExists("hermes_" + id, "hermes", extractProjectFromSession(id), null);
                sessions++;
            }
        }
        // 2. 导入消息（分批，只导入 user/assistant）
        String msgSql = "SELECT id, session_id, role, content, timestamp FROM messages WHERE role IN ('user','assistant')";
        try (PreparedStatement st = src.prepareStatement(msgSql); ResultSet rs = st.executeQuery()) {
            int batch = 0;
            while (rs.next()) {
                Message m = new Message();
                m.setId("hermes_" + rs.getString("id"));
                m.setSessionId("hermes_" + rs.getString("session_id"));
                m.setRole(rs.getString("role"));
                m.setContent(rs.getString("content"));
                m.setTimestamp(normalizeTimestamp(rs.getString("timestamp")));
                m.setAgentType("hermes");
                m.setProjectName(extractProjectFromSession(rs.getString("session_id")));
                databaseService.saveMessage(m);
                messages++;
                if (++batch % 500 == 0) {
                    log.info("  hermes 已导入 {} 条消息", batch);
                }
            }
        }
        return new int[]{messages, sessions};
    }

    /** mavis: sessions + session_messages 表 */
    private int[] importMavis(Connection src) throws Exception {
        int messages = 0, sessions = 0;
        String sessionSql = "SELECT session_id, agent_name FROM sessions";
        try (PreparedStatement st = src.prepareStatement(sessionSql); ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("session_id");
                if (id == null || id.isBlank()) continue;
                databaseService.saveSessionIfNotExists("mavis_" + id, "mavis", null, null);
                sessions++;
            }
        }
        String msgSql = "SELECT id, session_id, role, data, timestamp FROM session_messages";
        try (PreparedStatement st = src.prepareStatement(msgSql); ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                String role = rs.getString("role");
                if (role == null) continue;
                String data = rs.getString("data");
                String content = extractTextFromMavisData(data);
                if (content == null || content.isBlank()) continue;
                Message m = new Message();
                m.setId("mavis_" + rs.getString("id"));
                m.setSessionId("mavis_" + rs.getString("session_id"));
                m.setRole(role);
                m.setContent(content);
                m.setTimestamp(normalizeTimestamp(rs.getString("timestamp")));
                m.setAgentType("mavis");
                databaseService.saveMessage(m);
                messages++;
            }
        }
        return new int[]{messages, sessions};
    }

    /** marvis: conversations + conversation_detail 表 */
    private int[] importMarvis(Connection src) throws Exception {
        int messages = 0, sessions = 0;
        String sessionSql = "SELECT conversation_id, MAX(created_at) AS ts FROM conversation_detail GROUP BY conversation_id";
        try (PreparedStatement st = src.prepareStatement(sessionSql); ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("conversation_id");
                if (id == null || id.isBlank()) continue;
                databaseService.saveSessionIfNotExists("marvis_" + id, "marvis", null, null);
                sessions++;
            }
        }
        // 只导入 user/assistant 文本消息（type='human'/'ai'），跳过 tool 调用
        // msg_id 可能为 null，用 row_number 合成唯一 id
        String msgSql = """
            SELECT row_number() OVER (ORDER BY chat_at, rowid) AS rn,
                   conversation_id, role, message_json, chat_at
            FROM conversation_detail WHERE type IN ('human','ai')
            """;
        try (PreparedStatement st = src.prepareStatement(msgSql); ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                String content = extractTextFromMarvisJson(rs.getString("message_json"));
                if (content == null || content.isBlank()) continue;
                Message m = new Message();
                m.setId("marvis_" + rs.getString("conversation_id") + "_" + rs.getLong("rn"));
                m.setSessionId("marvis_" + rs.getString("conversation_id"));
                m.setRole(rs.getString("role"));
                m.setContent(content);
                m.setTimestamp(normalizeTimestamp(rs.getString("chat_at")));
                m.setAgentType("marvis");
                databaseService.saveMessage(m);
                messages++;
            }
        }
        return new int[]{messages, sessions};
    }

    // ==================== 辅助方法 ====================

    /** 将各种时间戳格式转为 ISO 8601 UTC 格式（DatabaseService 可解析） */
    private String normalizeTimestamp(String ts) {
        if (ts == null || ts.isBlank() || "0".equals(ts)) return null;
        try {
            // 1. 纯数字时间戳（秒/毫秒），含浮点（如 1780822146.9565885）
            if (ts.matches("\\d{10,13}(\\.\\d+)?")) {
                double v = Double.parseDouble(ts);
                long millis;
                if (v < 1e12) {
                    millis = (long) (v * 1000);  // 秒级
                } else {
                    millis = (long) v;            // 毫秒级
                }
                return new java.sql.Timestamp(millis).toInstant().toString();
            }
            // 2. ISO 无时区（如 2026-08-08T05:59:42.850952）→ 追加 Z
            if (ts.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")) {
                return ts + "Z";
            }
            // 3. ISO 带小数无时区（如 2026-08-08T05:59:42.850952）
            if (ts.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+")) {
                return ts + "Z";
            }
            // 4. 已是完整 ISO（含时区）
            return ts;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从会话 id 猜测项目路径（hermes 会话 id 含项目线索时） */
    private String extractProjectFromSession(String sessionId) {
        if (sessionId == null) return null;
        return null; // 保持简单，不强制项目分组
    }

    /** mavis data 字段是 JSON 字符串，提取 msg_content 或 content 文本 */
    private String extractTextFromMavisData(String data) {
        if (data == null) return null;
        String t = data.trim();
        if (t.startsWith("{")) {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(t);
                String text = node.path("msg_content").asText("");
                if (text.isBlank()) text = node.path("content").path("text").asText("");
                if (text.isBlank()) text = node.path("content").asText("");
                return text;
            } catch (Exception ignored) {
                return t;
            }
        }
        return t;
    }

    /** marvis message_json 提取文本（content 字段或 tool_calls 参数） */
    private String extractTextFromMarvisJson(String json) {
        if (json == null) return null;
        String t = json.trim();
        if (t.startsWith("{")) {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(t);
                String text = node.path("content").asText("");
                if (text.isBlank() || "null".equals(text)) {
                    // content 为 null 时提取 tool_calls 的 function arguments
                    if (node.path("tool_calls").isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (var c : node.path("tool_calls")) {
                            String args = c.path("function").path("arguments").asText("");
                            if (!args.isBlank()) {
                                sb.append("[tool] ").append(args).append("\n");
                            }
                        }
                        text = sb.toString();
                    }
                }
                return text.isBlank() ? null : text;
            } catch (Exception ignored) {
                return t;
            }
        }
        return t;
    }
}
