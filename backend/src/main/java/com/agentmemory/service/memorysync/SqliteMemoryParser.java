package com.agentmemory.service.memorysync;

import com.agentmemory.model.AgentMemoryEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 解析器：只读打开指定表，提取画像类内容。
 * 为避免文件锁冲突（源库可能正在被 agent 写入），先复制临时文件再打开。
 *
 * 用途：
 * - marvis: memory.db / user_profile 表（content 列）
 * - codex: memories_1.sqlite / stage1_outputs 表（raw_memory 列）
 */
public class SqliteMemoryParser implements MemoryParser {

    private final String table;
    private final String contentCol;
    private final String whereClause;

    public SqliteMemoryParser(String table, String contentCol) {
        this(table, contentCol, null);
    }

    public SqliteMemoryParser(String table, String contentCol, String whereClause) {
        this.table = table;
        this.contentCol = contentCol;
        this.whereClause = whereClause;
    }

    @Override
    public List<AgentMemoryEntry> parse(String sourcePath) throws Exception {
        // 复制临时文件避免锁冲突
        Path tmp = Files.createTempFile("agentmem_sync_", ".db");
        Files.copy(Paths.get(sourcePath), tmp, StandardCopyOption.REPLACE_EXISTING);
        List<AgentMemoryEntry> entries = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tmp)) {
            String sql = "SELECT " + contentCol + " FROM " + table;
            if (whereClause != null && !whereClause.isBlank()) {
                sql += " WHERE " + whereClause;
            }
            sql += " LIMIT 500";
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    String c = rs.getString(1);
                    if (c != null && c.trim().length() > 20) {
                        AgentMemoryEntry e = new AgentMemoryEntry();
                        e.setContent(c.trim());
                        e.setCategory("画像");
                        e.setSourcePath(sourcePath);
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
