package com.agentmemory.service;

import com.agentmemory.config.ApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 会话压缩服务
 * 负责长会话的滑动窗口压缩和摘要生成
 * 使用 ScheduledServiceBase 简化定时任务管理
 */
public class SessionCompressionService extends ScheduledServiceBase {

    private final DatabaseService databaseService;
    private final LLMClient llmClient;
    private final Logger log = LoggerFactory.getLogger(SessionCompressionService.class);

    // 压缩配置（从配置读取）
    private final int windowSize;
    private final int summaryThreshold;
    private final boolean autoCompress;
    private final int checkIntervalHours;

    public SessionCompressionService(DatabaseService databaseService, ApplicationConfig config) {
        this.databaseService = databaseService;
        this.llmClient = new LLMClient();
        
        // 从配置读取参数
        this.windowSize = config != null ? config.getCompressionWindowSize() : 50;
        this.summaryThreshold = config != null ? config.getCompressionSummaryThreshold() : 100;
        this.autoCompress = config != null ? config.isCompressionAutoCompress() : true;
        this.checkIntervalHours = config != null ? config.getCompressionCheckIntervalHours() : 2;
    }
    
    // 兼容旧构造函数
    public SessionCompressionService(DatabaseService databaseService) {
        this(databaseService, null);
    }
    
    @Override
    protected String getServiceName() {
        return "SessionCompressionService";
    }
    
    @Override
    protected long getInitialDelaySeconds() {
        return TimeUnit.HOURS.toSeconds(1);  // 启动1小时后开始
    }
    
    @Override
    protected long getPeriodSeconds() {
        return TimeUnit.HOURS.toSeconds(checkIntervalHours);
    }
    
    @Override
    protected Logger getLogger() {
        return log;
    }
    
    @Override
    protected void executeTask() {
        checkAndCompressSessions();
    }

    /**
     * 加载压缩配置
     */
    private void loadConfig() {
        try (Connection conn = databaseService.getConnection()) {
            // 1. 加载压缩策略配置
            String sql = "SELECT window_size, summary_threshold, auto_compress " +
                    "FROM compression_config WHERE config_key = 'session_compression'";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    windowSize = rs.getInt("window_size");
                    summaryThreshold = rs.getInt("summary_threshold");
                    autoCompress = rs.getBoolean("auto_compress");
                    log.info("加载压缩配置: windowSize={}, threshold={}, auto={}",
                            windowSize, summaryThreshold, autoCompress);
                }
            }
            
            // 2. 加载 LLM Provider 配置
            loadLLMConfig(conn);
            
        } catch (SQLException e) {
            log.warn("加载压缩配置失败，使用默认配置", e);
        }
    }
    
    /**
     * 从数据库加载 LLM 配置
     */
    private void loadLLMConfig(Connection conn) {
        try {
            // 优先使用默认 Provider，如果没有则查找启用的第一个
            String sql = "SELECT provider_name, base_url, api_key, model, config FROM llm_providers " +
                    "WHERE enabled = true ORDER BY is_default DESC, id ASC LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String providerName = rs.getString("provider_name");
                    String baseUrl = rs.getString("base_url");
                    String apiKey = rs.getString("api_key");
                    String model = rs.getString("model");
                    String configJson = rs.getString("config");
                    
                    // 解析 config 中的 thinkMode
                    boolean thinkMode = false;
                    if (configJson != null && !configJson.isEmpty()) {
                        try {
                            com.fasterxml.jackson.databind.JsonNode configNode = 
                                new com.fasterxml.jackson.databind.ObjectMapper().readTree(configJson);
                            thinkMode = configNode.path("thinkMode").asBoolean(false);
                        } catch (Exception e) {
                            log.warn("解析 config JSON 失败", e);
                        }
                    }
                    
                    // 如果是本地模型，baseUrl 可能为 null，使用本地 Ollama
                    if (baseUrl == null || baseUrl.isEmpty()) {
                        baseUrl = "http://localhost:11434";
                        providerName = "ollama";
                    }
                    
                    llmClient.setProvider(providerName, baseUrl, apiKey, model, thinkMode);
                    log.info("加载 LLM 配置: provider={}, model={}, thinkMode={}", providerName, model, thinkMode);
                } else {
                    log.warn("未找到启用的 LLM Provider，使用默认配置");
                }
            }
        } catch (SQLException e) {
            log.warn("加载 LLM 配置失败，使用默认配置", e);
        }
    }

    /**
     * 启动压缩服务
     */
    public void start() {
        // 加载配置（此时数据库已初始化）
        loadConfig();
        
        if (!autoCompress) {
            log.info("自动压缩已禁用");
            return;
        }
        
        // 使用基类的 start() 方法
        super.start();

        log.info("会话压缩服务已启动 (检查间隔: {} 小时)", CHECK_INTERVAL_HOURS);
    }

    /**
     * 检查并压缩需要压缩的会话
     */
    private void checkAndCompressSessions() {
        log.debug("开始检查需要压缩的会话...");

        List<String> sessionsToCompress = getSessionsNeedingCompression();

        for (String sessionId : sessionsToCompress) {
            try {
                compressSession(sessionId);
            } catch (Exception e) {
                log.error("压缩会话失败: {}", sessionId, e);
            }
        }

        log.debug("会话压缩检查完成，共处理 {} 个会话", sessionsToCompress.size());
    }

    /**
     * 获取需要压缩的会话列表
     */
    private List<String> getSessionsNeedingCompression() {
        List<String> sessions = new ArrayList<>();

        try (Connection conn = databaseService.getConnection()) {
            // 查询消息数超过阈值且未压缩的会话
            String sql = """
                SELECT s.id FROM sessions s
                WHERE s.message_count > ?
                AND (s.is_compressed = false OR s.is_compressed IS NULL)
                AND (s.expires_at IS NULL OR s.expires_at > NOW())
                ORDER BY s.message_count DESC
                LIMIT 100
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, summaryThreshold);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        sessions.add(rs.getString("id"));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("查询需要压缩的会话失败", e);
        }

        return sessions;
    }

    /**
     * 压缩单个会话
     * @return 是否成功压缩
     */
    public boolean compressSession(String sessionId) {
        log.info("开始压缩会话: {}", sessionId);

        try (Connection conn = databaseService.getConnection()) {
            // 1. 获取会话消息
            List<String> messages = getSessionMessages(conn, sessionId);
            if (messages.isEmpty()) {
                log.debug("会话无消息，跳过: {}", sessionId);
                return false;
            }

            int originalCount = messages.size();

            // 2. 确定压缩策略
            String compressionType = determineCompressionType(messages.size());

            // 3. 执行压缩
            List<String> compressedMessages;
            String summary;

            switch (compressionType) {
                case "SLIDING_WINDOW" -> {
                    // 滑动窗口：只保留最近 N 条
                    compressedMessages = applySlidingWindow(messages);
                    summary = "滑动窗口压缩，保留最近 " + windowSize + " 条消息";
                }
                case "SUMMARIZE" -> {
                    // 摘要：调用 LLM 生成摘要
                    compressedMessages = messages; // 保留全部，但生成摘要
                    summary = llmClient.summarize(messages);
                    if (summary == null || summary.isEmpty()) {
                        summary = "LLM 摘要生成失败，使用简单摘要";
                    }
                }
                case "HYBRID" -> {
                    // 混合：滑动窗口 + 摘要
                    compressedMessages = applySlidingWindow(messages);
                    summary = llmClient.summarize(messages);
                    if (summary == null || summary.isEmpty()) {
                        summary = generateSimpleSummary(messages);
                    }
                }
                default -> {
                    log.warn("未知的压缩类型: {}", compressionType);
                    return false;
                }
            }

            // 4. 标记原消息为已删除（软删除）
            markMessagesAsDeleted(conn, sessionId, windowSize);

            // 5. 保存压缩摘要
            saveSessionSummary(conn, sessionId, summary, compressionType,
                    originalCount, compressedMessages.size(), messages);

            // 6. 更新会话状态
            updateSessionCompressionStatus(conn, sessionId, compressionType);

            log.info("会话压缩完成: {}, 原始消息: {}, 压缩后: {}, 类型: {}",
                    sessionId, originalCount, compressedMessages.size(), compressionType);

            // 7. 记录压缩历史
            recordCompressionHistory(conn, sessionId, compressionType,
                    originalCount, compressedMessages.size(), summary);

            return true;

        } catch (Exception e) {
            log.error("压缩会话失败: {}", sessionId, e);
            return false;
        }
    }

    /**
     * 确定压缩类型
     */
    private String determineCompressionType(int messageCount) {
        if (messageCount > summaryThreshold * 2) {
            return "HYBRID";  // 超过阈值2倍，使用混合压缩
        } else if (messageCount > summaryThreshold) {
            return "SUMMARIZE";  // 超过阈值，使用摘要
        } else {
            return "SLIDING_WINDOW";  // 未超过阈值，使用滑动窗口
        }
    }

    /**
     * 获取会话消息
     */
    private List<String> getSessionMessages(Connection conn, String sessionId) throws SQLException {
        List<String> messages = new ArrayList<>();

        String sql = """
            SELECT role, content FROM messages
            WHERE session_id = ? AND (deleted = false OR deleted IS NULL)
            ORDER BY timestamp ASC
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String role = rs.getString("role");
                    String content = rs.getString("content");
                    if (content != null && !content.isEmpty()) {
                        messages.add("[" + role + "] " + content);
                    }
                }
            }
        }

        return messages;
    }

    /**
     * 应用滑动窗口
     */
    private List<String> applySlidingWindow(List<String> messages) {
        if (messages.size() <= windowSize) {
            return messages;
        }
        // 返回最近 windowSize 条消息
        return messages.subList(messages.size() - windowSize, messages.size());
    }

    /**
     * 标记消息为已删除
     */
    private void markMessagesAsDeleted(Connection conn, String sessionId, int keepCount) throws SQLException {
        String sql = """
            UPDATE messages
            SET deleted = true, expires_at = NOW()
            WHERE session_id = ? AND id NOT IN (
                SELECT id FROM messages
                WHERE session_id = ?
                ORDER BY timestamp DESC
                LIMIT ?
            )
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.setInt(3, keepCount);
            stmt.executeUpdate();
        }
    }

    /**
     * 保存会话摘要
     */
    private void saveSessionSummary(Connection conn, String sessionId, String summary,
                                    String compressionType, int originalCount, int compressedCount,
                                    List<String> messages) throws SQLException {
        // 获取会话时间范围
        LocalDateTime firstTime = null;
        LocalDateTime lastTime = null;

        String timeSql = """
            SELECT MIN(timestamp) as first_time, MAX(timestamp) as last_time
            FROM messages WHERE session_id = ?
            """;

        try (PreparedStatement stmt = conn.prepareStatement(timeSql)) {
            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Timestamp ft = rs.getTimestamp("first_time");
                    Timestamp lt = rs.getTimestamp("last_time");
                    if (ft != null) firstTime = ft.toLocalDateTime();
                    if (lt != null) lastTime = lt.toLocalDateTime();
                }
            }
        }

        // 获取当前版本号
        int version = 1;
        String versionSql = "SELECT COALESCE(MAX(version), 0) + 1 as next_version FROM session_summaries WHERE session_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(versionSql)) {
            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    version = rs.getInt("next_version");
                }
            }
        }

        // 插入摘要
        String insertSql = """
            INSERT INTO session_summaries
            (session_id, summary, compression_type, original_message_count, compressed_message_count,
             window_size, first_message_timestamp, last_message_timestamp, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, summary);
            stmt.setString(3, compressionType);
            stmt.setInt(4, originalCount);
            stmt.setInt(5, compressedCount);
            stmt.setInt(6, windowSize);
            stmt.setTimestamp(7, firstTime != null ? Timestamp.valueOf(firstTime) : null);
            stmt.setTimestamp(8, lastTime != null ? Timestamp.valueOf(lastTime) : null);
            stmt.setInt(9, version);
            stmt.executeUpdate();
        }
    }

    /**
     * 更新会话压缩状态
     */
    private void updateSessionCompressionStatus(Connection conn, String sessionId, String compressionType) throws SQLException {
        String sql = """
            UPDATE sessions
            SET is_compressed = true,
                compression_type = ?,
                compressed_at = NOW()
            WHERE id = ?
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, compressionType);
            stmt.setString(2, sessionId);
            stmt.executeUpdate();
        }
    }

    /**
     * 记录压缩历史
     */
    private void recordCompressionHistory(Connection conn, String sessionId, String compressionType,
                                          int beforeCount, int afterCount, String summary) throws SQLException {
        String sql = """
            INSERT INTO compression_history
            (session_id, operation, compression_type, message_count_before, message_count_after, summary, llm_provider)
            VALUES (?, 'COMPRESS', ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, compressionType);
            stmt.setInt(3, beforeCount);
            stmt.setInt(4, afterCount);
            stmt.setString(5, summary);
            stmt.setString(6, llmClient.getProviderName());
            stmt.executeUpdate();
        }
    }

    /**
     * 生成简单的回退摘要（当 LLM 不可用时）
     */
    private String generateSimpleSummary(List<String> messages) {
        if (messages.isEmpty()) {
            return "";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("会话共包含 ").append(messages.size()).append(" 条消息。");

        // 统计消息类型
        int userCount = 0;
        int assistantCount = 0;

        for (String msg : messages) {
            if (msg.startsWith("[user]")) userCount++;
            else if (msg.startsWith("[assistant]")) assistantCount++;
        }

        summary.append(" 其中用户消息 ").append(userCount)
                .append(" 条，助手消息 ").append(assistantCount).append(" 条。");

        return summary.toString();
    }

    private StringBuilder append(int count) {
        return new StringBuilder().append(count);
    }

    /**
     * 手动触发会话压缩（API 调用）
     */
    public boolean compressSessionManual(String sessionId) {
        return compressSession(sessionId);
    }

    /**
     * 获取压缩统计信息
     */
    public Map<String, Object> getCompressionStats() {
        Map<String, Object> stats = new HashMap<>();

        try (Connection conn = databaseService.getConnection()) {
            // 会话压缩统计
            String sql = """
                SELECT
                    COUNT(*) as total_sessions,
                    SUM(CASE WHEN is_compressed = true THEN 1 ELSE 0 END) as compressed_sessions,
                    SUM(message_count) as total_messages
                FROM sessions
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.put("totalSessions", rs.getInt("total_sessions"));
                    stats.put("compressedSessions", rs.getInt("compressed_sessions"));
                    stats.put("totalMessages", rs.getInt("total_messages"));
                }
            }

            // 压缩历史统计
            String historySql = """
                SELECT COUNT(*) as compress_count, AVG(message_count_before - message_count_after) as avg_reduction
                FROM compression_history
                WHERE created_at > NOW() - INTERVAL '7 days'
                """;

            try (PreparedStatement stmt = conn.prepareStatement(historySql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.put("compressCount7d", rs.getInt("compress_count"));
                    stats.put("avgReduction7d", rs.getDouble("avg_reduction"));
                }
            }

            // LLM 状态
            stats.put("llmProvider", llmClient.getProviderName());
            stats.put("llmHealthy", llmClient.isHealthy());
            stats.put("config", Map.of(
                "windowSize", windowSize,
                "summaryThreshold", summaryThreshold,
                "autoCompress", autoCompress
            ));

        } catch (SQLException e) {
            log.error("获取压缩统计失败", e);
        }

        return stats;
    }
}
