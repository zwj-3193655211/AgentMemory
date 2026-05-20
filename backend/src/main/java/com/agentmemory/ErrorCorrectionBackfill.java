package com.agentmemory;

import com.agentmemory.service.MemoryExtractor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.List;
import java.util.UUID;

/**
 * 回填工具：从 messages 表扫描用户消息，提取错误纠正内容写入 error_corrections 表
 */
public class ErrorCorrectionBackfill {

    private final HikariDataSource ds;
    private final MemoryExtractor extractor = new MemoryExtractor();

    public ErrorCorrectionBackfill(String jdbcUrl, String user, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(1);
        this.ds = new HikariDataSource(cfg);
    }

    public void run() {
        String query = """
            SELECT m.id, m.content, m.session_id, s.agent_type
            FROM messages m
            LEFT JOIN sessions s ON m.session_id = s.id
            WHERE m.role = 'user'
              AND m.deleted = false
              AND m.content IS NOT NULL
              AND LENGTH(m.content) BETWEEN 10 AND 2000
            ORDER BY m.created_at DESC
            LIMIT 500
            """;

        int total = 0, inserted = 0, skipped = 0;

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                total++;
                String msgId = rs.getString("id");
                String content = rs.getString("content");
                String sessionId = rs.getString("session_id");
                String agentType = rs.getString("agent_type");

                // 检查是否已存在（按 session_id + content 片段去重）
                if (isAlreadyExtracted(conn, sessionId, content)) {
                    skipped++;
                    continue;
                }

                MemoryExtractor.ExtractedMemory memory = extractor.extractErrorCorrection(content, List.of());
                if (memory != null && memory.solution != null && !memory.solution.isEmpty()) {
                    saveErrorCorrection(conn, memory, sessionId, agentType);
                    inserted++;
                    System.out.println("  [INSERT] " + memory.title);
                }
            }

            System.out.println("\n=== 回填完成 ===");
            System.out.println("扫描: " + total + " 条用户消息");
            System.out.println("新增: " + inserted + " 条错误纠正");
            System.out.println("跳过（已存在）: " + skipped + " 条");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean isAlreadyExtracted(Connection conn, String sessionId, String content) throws SQLException {
        // 简单去重：检查同一个 session 中是否已有相似 content 的记录
        String sql = "SELECT COUNT(*) FROM error_corrections WHERE session_id = ? AND deleted = false";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    private void saveErrorCorrection(Connection conn, MemoryExtractor.ExtractedMemory memory,
                                     String sessionId, String agentType) throws SQLException {
        String sql = """
            INSERT INTO error_corrections (id, title, problem, solution, agent_type, session_id, created_at, updated_at, deleted, visit_count)
            VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW(), false, 0)
            """;

        // problem 字段 NOT NULL，所以要确保不为空
        String problem = (memory.problem != null && !memory.problem.isEmpty()) ? memory.problem : memory.title;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, memory.title);
            ps.setString(3, problem);
            ps.setString(4, memory.solution);
            ps.setString(5, agentType);
            ps.setString(6, sessionId);
            ps.executeUpdate();
        }
    }

    public void close() {
        ds.close();
    }

    public static void main(String[] args) {
        String jdbcUrl = System.getenv("JDBC_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");

        if (jdbcUrl == null) jdbcUrl = "jdbc:postgresql://localhost:5500/agentmemory";
        if (user == null) user = "agentmemory";
        if (password == null) password = "agentmemory";

        System.out.println("=== 错误纠正回填工具 ===");
        System.out.println("JDBC: " + jdbcUrl);

        ErrorCorrectionBackfill tool = new ErrorCorrectionBackfill(jdbcUrl, user, password);
        tool.run();
        tool.close();
    }
}
