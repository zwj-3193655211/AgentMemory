package com.agentmemory.service;

import com.agentmemory.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Crush 数据库监控服务
 * 定期检查 Crush 的 SQLite 数据库，发现新会话后自动导入
 */
public class CrushDatabaseWatcher extends ScheduledServiceBase {

    private static final Logger log = LoggerFactory.getLogger(CrushDatabaseWatcher.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final DatabaseService databaseService;
    private final MemoryService memoryService;

    private String crushDbPath;
    private long lastCheckTime;  // 上次检查时间（用于增量检查）

    public CrushDatabaseWatcher(DatabaseService databaseService, String crushDbPath) {
        this.databaseService = databaseService;
        this.memoryService = new MemoryService(databaseService);
        this.crushDbPath = crushDbPath;
        this.lastCheckTime = 0;  // 从 0 开始，导入所有历史会话
    }

    @Override
    protected String getServiceName() {
        return "CrushDatabaseWatcher";
    }

    @Override
    protected long getInitialDelaySeconds() {
        return 10;  // 启动 10 秒后开始第一次检查
    }

    @Override
    protected long getPeriodSeconds() {
        return 30;  // 每 30 秒检查一次
    }

    @Override
    protected void executeTask() {
        try {
            checkForNewSessions();
        } catch (Exception e) {
            log.warn("检查 Crush 数据库失败: {}", e.getMessage());
        }
    }

    /**
     * 检查 Crush 数据库中的新会话
     */
    private void checkForNewSessions() {
        if (crushDbPath == null || crushDbPath.isEmpty()) {
            return;
        }

        Path dbPath = Paths.get(crushDbPath);
        if (!dbPath.toFile().exists()) {
            log.debug("Crush 数据库不存在: {}", crushDbPath);
            return;
        }

        Connection localConn = null;
        try {
            // 连接 Crush 的 SQLite 数据库
            localConn = DriverManager.getConnection("jdbc:sqlite:" + crushDbPath);

            // 检查新会话
            checkNewSessions(localConn);

        } catch (SQLException e) {
            log.warn("连接 Crush 数据库失败: {}", e.getMessage());
        } finally {
            if (localConn != null) {
                try {
                    localConn.close();
                } catch (SQLException ignored) {}
            }
        }
    }

    /**
     * 检查新会话
     */
    private void checkNewSessions(Connection conn) throws SQLException {
        // 查询新会话（基于 updated_at）
        String sql = """
            SELECT id, parent_session_id, title, message_count,
                   updated_at, created_at
            FROM sessions
            WHERE updated_at > ?
            ORDER BY created_at ASC
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, lastCheckTime);

            try (ResultSet rs = stmt.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    String sessionId = rs.getString("id");
                    if (sessionExists(sessionId)) {
                        // 会话已存在，跳过
                        continue;
                    }

                    // 导入新会话
                    if (importSession(conn, sessionId)) {
                        count++;
                    }
                }

                if (count > 0) {
                    log.info("从 Crush 数据库导入了 {} 个新会话", count);
                }
            }
        }

        // 更新检查时间
        lastCheckTime = System.currentTimeMillis() / 1000;
    }

    /**
     * 检查会话是否已存在于主数据库
     */
    private boolean sessionExists(String sessionId) {
        try (Connection conn = databaseService.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM sessions WHERE id = ?")) {
            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 导入单个会话
     */
    private boolean importSession(Connection crushConn, String sessionId) throws SQLException {
        // 查询会话信息
        String sessionSql = """
            SELECT id, title, message_count, updated_at, created_at, cwd
            FROM sessions WHERE id = ?
            """;

        String messagesSql = """
            SELECT id, role, parts, created_at
            FROM messages
            WHERE session_id = ?
            ORDER BY created_at ASC
            """;

        try (PreparedStatement stmt = crushConn.prepareStatement(sessionSql)) {
            stmt.setString(1, sessionId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }

                String title = rs.getString("title");
                long updatedAt = rs.getLong("updated_at");
                long createdAt = rs.getLong("created_at");
                String cwd = rs.getString("cwd");

                // 保存会话
                databaseService.saveSessionIfNotExists(sessionId, "crush", cwd, title);

                // 导入消息
                return importMessages(crushConn, sessionId, messagesSql);
            }
        }
    }

    /**
     * 导入会话消息
     */
    private boolean importMessages(Connection crushConn, String sessionId, String sql) throws SQLException {
        try (PreparedStatement stmt = crushConn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);

            try (ResultSet rs = stmt.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    String msgId = rs.getString("id");
                    String role = rs.getString("role");
                    String parts = rs.getString("parts");
                    long createdAt = rs.getLong("created_at");

                    String content = parseParts(parts);
                    if (content.isEmpty()) {
                        continue;
                    }

                    // 创建消息对象
                    Message message = new Message();
                    message.setId(msgId);
                    message.setSessionId(sessionId);
                    message.setRole(role);
                    message.setContent(content);
                    message.setAgentType("crush");
                    message.setTimestamp(timestampToString(createdAt));

                    // 保存到数据库
                    databaseService.saveMessage(message);
                    count++;
                }

                return count > 0;
            }
        }
    }

    /**
     * 解析 Crush 消息的 parts 字段
     */
    private String parseParts(String partsStr) {
        if (partsStr == null || partsStr.isEmpty()) {
            return "";
        }

        try {
            // Crush 的 parts 是 JSON 数组
            com.fasterxml.jackson.databind.JsonNode parts =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(partsStr);

            if (!parts.isArray()) {
                return partsStr;
            }

            StringBuilder content = new StringBuilder();
            for (com.fasterxml.jackson.databind.JsonNode part : parts) {
                String partType = part.has("type") ? part.get("type").asText() : "";

                if ("text".equals(partType) && part.has("data")) {
                    com.fasterxml.jackson.databind.JsonNode data = part.get("data");
                    if (data.has("text")) {
                        String text = data.get("text").asText();
                        if (content.length() > 0) {
                            content.append("\n\n");
                        }
                        content.append(text);
                    }
                } else if ("tool_use".equals(partType) && part.has("data")) {
                    // 工具调用
                    com.fasterxml.jackson.databind.JsonNode data = part.get("data");
                    if (content.length() > 0) {
                        content.append("\n\n");
                    }
                    content.append("[工具调用]");

                    if (data.has("name")) {
                        content.append(" ").append(data.get("name").asText());
                    }
                }
                // 跳过 finish 等其他类型
            }

            return content.toString().trim();
        } catch (Exception e) {
            // 解析失败，返回原始内容
            return partsStr;
        }
    }

    /**
     * 将 Unix 时间戳转换为 ISO 格式字符串
     */
    private String timestampToString(long timestamp) {
        if (timestamp > 0) {
            return Instant.ofEpochSecond(timestamp).toString();
        }
        return "";
    }

    @Override
    public void stop() {
        super.stop();
        log.info("CrushDatabaseWatcher 已停止");
    }
}