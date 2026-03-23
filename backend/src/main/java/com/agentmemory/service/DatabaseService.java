package com.agentmemory.service;

import com.agentmemory.AgentInfo;
import com.agentmemory.config.ApplicationConfig;
import com.agentmemory.model.Message;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.List;

/**
 * 数据库服务
 * 负责消息持久化和检索
 * 支持 SQLite 和 PostgreSQL
 */
public class DatabaseService {
    
    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);
    
    private final ApplicationConfig config;
    private HikariDataSource dataSource;
    private boolean useSqlite;
    
    public DatabaseService(ApplicationConfig config) {
        this.config = config;
    }
    
    /**
     * 初始化数据库连接池
     */
    public void init() {
        useSqlite = config.useSqlite();
        
        HikariConfig hikariConfig = new HikariConfig();
        
        if (useSqlite) {
            initSqlite(hikariConfig);
        } else {
            initPostgresql(hikariConfig);
        }
        
        hikariConfig.setMaximumPoolSize(config.getMaxPoolSize());
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(30000);
        
        dataSource = new HikariDataSource(hikariConfig);
        
        // 测试连接
        try (Connection conn = dataSource.getConnection()) {
            log.info("数据库连接成功 ({})", useSqlite ? "SQLite" : "PostgreSQL");
            
            // 初始化表结构
            initTables(conn);
        } catch (SQLException e) {
            throw new RuntimeException("数据库连接失败", e);
        }
    }
    
    private void initSqlite(HikariConfig hikariConfig) {
        String dbPath = config.getSqlitePath();
        Path path = Paths.get(dbPath);
        
        // 确保目录存在
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new RuntimeException("无法创建数据库目录: " + path.getParent(), e);
        }
        
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbPath);
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        
        log.info("使用 SQLite 数据库: {}", dbPath);
    }
    
    private void initPostgresql(HikariConfig hikariConfig) {
        hikariConfig.setJdbcUrl(config.getJdbcUrl());
        hikariConfig.setUsername(config.getJdbcUser());
        hikariConfig.setPassword(config.getJdbcPassword());
        
        log.info("使用 PostgreSQL 数据库: {}", config.getJdbcUrl());
    }
    
    /**
     * 初始化表结构
     */
    private void initTables(Connection conn) throws SQLException {
        if (useSqlite) {
            initSqliteTables(conn);
        }
        // 初始化压缩相关表（PostgreSQL 和 SQLite 都需要）
        initCompressionTables(conn);
    }
    
    /**
     * 初始化压缩相关表
     */
    private void initCompressionTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 会话摘要表
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS session_summaries (
                    id SERIAL PRIMARY KEY,
                    session_id VARCHAR(100),
                    summary TEXT NOT NULL,
                    compression_type VARCHAR(20) NOT NULL,
                    original_message_count INTEGER DEFAULT 0,
                    compressed_message_count INTEGER DEFAULT 0,
                    window_size INTEGER DEFAULT 50,
                    first_message_timestamp TIMESTAMP,
                    last_message_timestamp TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    version INTEGER DEFAULT 1,
                    deleted BOOLEAN DEFAULT false
                )
                """);
            
            // 压缩配置表
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS compression_config (
                    id SERIAL PRIMARY KEY,
                    config_key VARCHAR(50) UNIQUE NOT NULL,
                    enabled BOOLEAN DEFAULT true,
                    window_size INTEGER DEFAULT 50,
                    summary_threshold INTEGER DEFAULT 100,
                    auto_compress BOOLEAN DEFAULT true,
                    schedule_cron VARCHAR(50) DEFAULT '0 2 * * *',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
            
            // LLM Provider 配置表
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS llm_providers (
                    id SERIAL PRIMARY KEY,
                    provider_name VARCHAR(50) NOT NULL,
                    display_name VARCHAR(100),
                    base_url TEXT,
                    api_key VARCHAR(500),
                    model VARCHAR(100),
                    enabled BOOLEAN DEFAULT true,
                    is_default BOOLEAN DEFAULT false,
                    config JSONB DEFAULT '{}',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(provider_name)
                )
                """);
            
            // 压缩历史记录表
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS compression_history (
                    id SERIAL PRIMARY KEY,
                    session_id VARCHAR(100) NOT NULL,
                    operation VARCHAR(20) NOT NULL,
                    compression_type VARCHAR(20),
                    message_count_before INTEGER,
                    message_count_after INTEGER,
                    summary TEXT,
                    llm_provider VARCHAR(50),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
            
            // 插入默认压缩配置
            stmt.executeUpdate("""
                INSERT INTO compression_config (config_key, enabled, window_size, summary_threshold, auto_compress, schedule_cron) 
                VALUES ('session_compression', true, 50, 100, true, '0 2 * * *')
                ON CONFLICT (config_key) DO NOTHING
                """);
            
            // 添加 compression_type 字段（如果不存在）
            try {
                stmt.executeUpdate("ALTER TABLE compression_config ADD COLUMN IF NOT EXISTS compression_type VARCHAR(20) DEFAULT 'SLIDING_WINDOW'");
            } catch (SQLException e) {
                // 字段可能已存在，忽略错误
            }
            
            // 添加 llm_provider 字段（如果不存在）
            try {
                stmt.executeUpdate("ALTER TABLE compression_config ADD COLUMN IF NOT EXISTS llm_provider VARCHAR(100) DEFAULT '__builtin__'");
            } catch (SQLException e) {
                // 字段可能已存在，忽略错误
            }
            
            // 添加 sessions 表压缩相关字段（如果不存在）
            try {
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN IF NOT EXISTS is_compressed BOOLEAN DEFAULT false");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN IF NOT EXISTS compression_type VARCHAR(20)");
                stmt.executeUpdate("ALTER TABLE sessions ADD COLUMN IF NOT EXISTS compressed_at TIMESTAMP");
            } catch (SQLException e) {
                // 字段可能已存在，忽略错误
            }
            
            // 插入默认 LLM Provider
            stmt.executeUpdate("""
                INSERT INTO llm_providers (provider_name, display_name, model, enabled, is_default) 
                VALUES ('ollama', '本地 Qwen3', 'qwen3:0.6b', true, true)
                ON CONFLICT (provider_name) DO NOTHING
                """);
            
            log.info("压缩相关表初始化完成");
        }
    }
    
    private void initSqliteTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Agent 表
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS agents (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT UNIQUE NOT NULL,
                    type TEXT NOT NULL,
                    log_base_path TEXT,
                    cli_path TEXT,
                    version TEXT,
                    enabled INTEGER DEFAULT 1,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
            
            // 会话表
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    agent_id INTEGER,
                    project_path TEXT,
                    workspace_path TEXT,
                    started_at TIMESTAMP,
                    ended_at TIMESTAMP,
                    message_count INTEGER DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
            
            // 消息表
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    session_id TEXT,
                    parent_id TEXT,
                    role TEXT NOT NULL,
                    content TEXT,
                    raw_json TEXT,
                    timestamp TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
            
            // 索引
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_project ON sessions(project_path)");
            
            log.info("SQLite 表结构初始化完成");
        }
    }
    
    /**
     * 注册检测到的 Agent
     */
    public void registerAgents(List<AgentInfo> agents) {
        String sql;
        if (useSqlite) {
            sql = """
                INSERT OR REPLACE INTO agents (name, type, log_base_path, cli_path, version, enabled)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        } else {
            sql = """
                INSERT INTO agents (name, type, log_base_path, cli_path, version, enabled)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET
                    log_base_path = EXCLUDED.log_base_path,
                    cli_path = EXCLUDED.cli_path,
                    version = EXCLUDED.version,
                    updated_at = CURRENT_TIMESTAMP
                """;
        }
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            for (AgentInfo agent : agents) {
                stmt.setString(1, agent.getName());
                stmt.setString(2, agent.getType());
                stmt.setString(3, agent.getLogPath());
                stmt.setString(4, agent.getCliPath());
                stmt.setString(5, agent.getVersion());
                stmt.setBoolean(6, agent.isEnabled());
                stmt.executeUpdate();
            }
            
            log.info("已注册 {} 个 Agent", agents.size());
        } catch (SQLException e) {
            log.error("注册 Agent 失败", e);
        }
    }
    
    /**
     * 保存消息（优化版：减少数据库操作次数）
     */
    public void saveMessage(Message message) {
        try (Connection conn = dataSource.getConnection()) {
            // 使用事务一次性处理
            conn.setAutoCommit(false);
            try {
                // 1. 确保/创建会话（使用 UPSERT）
                ensureSessionExistsOptimized(conn, message);
                
                // 2. 插入消息
                insertMessage(conn, message);
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("保存消息失败: {}", message.getId(), e);
        }
    }
    
    /**
     * 优化的会话确保方法（单个连接，使用 UPSERT）
     */
    private void ensureSessionExistsOptimized(Connection conn, Message message) throws SQLException {
        String sql;
        if (useSqlite) {
            sql = """
                INSERT INTO sessions (id, project_path, agent_type, message_count, expires_at)
                VALUES (?, ?, ?, 0, datetime('now', '+14 days'))
                ON CONFLICT (id) DO UPDATE SET
                    project_path = COALESCE(sessions.project_path, excluded.project_path),
                    agent_type = excluded.agent_type
                """;
        } else {
            sql = """
                INSERT INTO sessions (id, project_path, agent_type, message_count, expires_at)
                VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP + INTERVAL '14 days')
                ON CONFLICT (id) DO UPDATE SET
                    project_path = COALESCE(sessions.project_path, EXCLUDED.project_path),
                    agent_type = EXCLUDED.agent_type
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, message.getSessionId());
            stmt.setString(2, message.getProjectName());
            stmt.setString(3, message.getAgentType());
            stmt.executeUpdate();
        }
    }
    
    /**
     * 插入消息（优化版：使用触发器自动更新计数）
     *
     * 注意: PostgreSQL 使用触发器自动更新会话消息计数
     *       SQLite 使用手动更新（兼容模式）
     */
    private void insertMessage(Connection conn, Message message) throws SQLException {
        String sql;
        if (useSqlite) {
            sql = """
                INSERT OR REPLACE INTO messages (id, session_id, parent_id, role, content, raw_json, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        } else {
            sql = """
                INSERT INTO messages (id, session_id, parent_id, role, content, raw_json, timestamp)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (id) DO UPDATE SET
                    content = EXCLUDED.content,
                    raw_json = EXCLUDED.raw_json
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, message.getId());
            stmt.setString(2, message.getSessionId());
            stmt.setString(3, message.getParentId());
            stmt.setString(4, message.getRole());
            stmt.setString(5, truncateContent(message.getContent(), 100000));
            stmt.setString(6, message.getRawJson());

            java.sql.Timestamp timestamp = parseTimestamp(message.getTimestamp());
            if (timestamp != null) {
                stmt.setTimestamp(7, timestamp);
            } else {
                stmt.setNull(7, java.sql.Types.TIMESTAMP);
            }

            stmt.executeUpdate();
        }

        // PostgreSQL: 触发器自动更新计数，无需手动操作
        // SQLite: 需要手动更新计数（兼容模式）
        if (useSqlite) {
            updateSessionMessageCountOptimized(conn, message.getSessionId());
        }

        log.debug("已保存消息: {} - {}", message.getId(), message.getRole());
    }
    
    /**
     * 优化的计数更新（使用同一个连接）
     */
    private void updateSessionMessageCountOptimized(Connection conn, String sessionId) throws SQLException {
        String sql = """
            UPDATE sessions SET
                message_count = (SELECT COUNT(*) FROM messages WHERE session_id = ?),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.executeUpdate();
        }
    }
    
    private String truncateContent(String content, int maxLength) {
        if (content == null) return null;
        if (content.length() <= maxLength) return content;
        return content.substring(0, maxLength) + "...[truncated]";
    }

    /**
     * 安全解析时间戳
     * 支持多种格式：ISO 8601、毫秒时间戳、秒时间戳
     */
    private java.sql.Timestamp parseTimestamp(String ts) {
        if (ts == null || ts.isEmpty() || "0".equals(ts) || "null".equalsIgnoreCase(ts)) {
            return null;
        }

        try {
            // 尝试解析 ISO 8601 格式（如 "2026-03-22T10:15:30Z"）
            return java.sql.Timestamp.from(java.time.Instant.parse(ts));
        } catch (java.time.format.DateTimeParseException e1) {
            try {
                // 尝试解析毫秒时间戳（13位数字）
                long millis = Long.parseLong(ts);
                // 如果是秒级时间戳（10位数字），转换为毫秒
                if (ts.length() <= 10) {
                    millis = millis * 1000;
                }
                return new java.sql.Timestamp(millis);
            } catch (NumberFormatException e2) {
                log.warn("无法解析时间戳: {}", ts);
                return null;
            }
        }
    }
    
    /**
     * 获取统计信息
     */
    public void printStats() {
        try (Connection conn = dataSource.getConnection()) {
            Statement stmt = conn.createStatement();
            
            ResultSet rs1 = stmt.executeQuery("SELECT COUNT(*) FROM agents");
            rs1.next();
            int agentCount = rs1.getInt(1);
            
            ResultSet rs2 = stmt.executeQuery("SELECT COUNT(*) FROM sessions");
            rs2.next();
            int sessionCount = rs2.getInt(1);
            
            ResultSet rs3 = stmt.executeQuery("SELECT COUNT(*) FROM messages");
            rs3.next();
            int messageCount = rs3.getInt(1);
            
            log.info("数据库统计: {} 个 Agent, {} 个会话, {} 条消息", agentCount, sessionCount, messageCount);
            
        } catch (SQLException e) {
            log.error("获取统计信息失败", e);
        }
    }
    
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
    
    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}