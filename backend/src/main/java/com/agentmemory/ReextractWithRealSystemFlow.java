package com.agentmemory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.agentmemory.config.ApplicationConfig;
import com.agentmemory.service.DatabaseService;
import com.agentmemory.service.MemoryService;

/**
 * 使用系统真实混合分类流程重新提取所有记忆
 * 流程：规则分类 → 向量分类 → 置信度判断 → 提取记忆
 */
public class ReextractWithRealSystemFlow {

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  使用真实混合分类流程重新提取记忆");
        System.out.println("========================================");
        System.out.println();

        Connection conn = null;
        try {
            System.out.println("[1/5] 初始化系统组件...");
            ApplicationConfig config = ApplicationConfig.load();
            DatabaseService dbService = new DatabaseService(config);
            dbService.init();
            MemoryService memoryService = new MemoryService(dbService);
            memoryService.setUseHybridClassifier(true);
            System.out.println("      ✓ 组件初始化完成");
            System.out.println();

            System.out.println("[2/5] 清空现有记忆表...");
            conn = DriverManager.getConnection("jdbc:postgresql://localhost:5500/agentmemory", "agentmemory", "agentmemory");
            String[] tables = {"user_profiles", "error_corrections", "skills", "best_practices", "project_contexts"};
            for (String table : tables) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("DELETE FROM " + table);
                    System.out.println("      ✓ 已清空: " + table);
                }
            }
            System.out.println();

            System.out.println("[3/5] 获取所有会话...");
            int totalSessions = 0;
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions WHERE deleted = false")) {
                    if (rs.next()) {
                        totalSessions = rs.getInt(1);
                    }
                }
            }
            System.out.println("      共 " + totalSessions + " 个会话");
            System.out.println();

            System.out.println("[4/5] 开始处理会话（使用真实混合分类流程）...");
            System.out.println();

            List<String> sessionIds = new ArrayList<>();
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT id, agent_type FROM sessions WHERE deleted = false ORDER BY created_at")) {
                    while (rs.next()) {
                        sessionIds.add(rs.getString("id") + "|" + rs.getString("agent_type"));
                    }
                }
            }

            int successCount = 0;
            int idx = 0;

            for (String sessionInfo : sessionIds) {
                idx++;
                String[] parts = sessionInfo.split("\\|", 2);
                String sessionId = parts[0];
                String agentType = parts.length > 1 ? parts[1] : "unknown";

                System.out.printf("[%d/%d] 会话 %s...%n", idx, totalSessions, sessionId.substring(0, Math.min(8, sessionId.length())));

                try {
                    List<String> messages = new ArrayList<>();
                    try (PreparedStatement pstmt = conn.prepareStatement(
                            "SELECT role, content FROM messages " +
                            "WHERE session_id = ? AND deleted = false " +
                            "ORDER BY created_at")) {
                        pstmt.setString(1, sessionId);
                        try (ResultSet rs = pstmt.executeQuery()) {
                            while (rs.next()) {
                                String role = rs.getString("role");
                                String content = rs.getString("content");
                                if (content != null && !content.trim().isEmpty()) {
                                    messages.add(role + ": " + content);
                                }
                            }
                        }
                    }

                    if (messages.isEmpty()) {
                        System.out.println("      ⚠ 无消息，跳过");
                        System.out.println();
                        continue;
                    }

                    System.out.println("      消息数: " + messages.size());

                    for (int i = 0; i < messages.size(); i++) {
                        String content = messages.get(i);

                        StringBuilder contextBuilder = new StringBuilder();
                        int contextStart = Math.max(0, i - 5);
                        for (int j = contextStart; j < i; j++) {
                            contextBuilder.append("[上文] ").append(messages.get(j)).append("\n\n");
                        }
                        contextBuilder.append("[当前] ").append(content);

                        String fullContext = contextBuilder.toString();

                        try {
                            memoryService.processMessageWithContext(sessionId, fullContext, agentType);
                        } catch (Exception e) {
                            System.err.println("      ⚠ 单条处理失败: " + e.getMessage());
                        }
                    }

                    successCount++;
                    System.out.println("      ✓ 会话处理完成");

                } catch (Exception e) {
                    System.err.println("      ✗ 会话处理失败: " + e.getMessage());
                    e.printStackTrace();
                }
                System.out.println();

                if (idx % 10 == 0) {
                    System.out.println("  --- 进度: " + idx + "/" + totalSessions + " ---");
                    System.out.println();
                }
            }

            System.out.println("[5/5] 显示处理结果...");
            System.out.println();
            System.out.println("处理完成！");
            System.out.println("成功处理: " + successCount + " 个会话");
            System.out.println();
            System.out.println("各记忆表统计:");

            for (String table : tables) {
                try (Statement stmt = conn.createStatement()) {
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        if (rs.next()) {
                            System.out.println("  - " + table + ": " + rs.getInt(1) + " 条");
                        }
                    }
                }
            }

            System.out.println();
            System.out.println("========================================");
            System.out.println("  ✓ 记忆重新提取完成！");
            System.out.println("  已使用真实混合分类流程：");
            System.out.println("    1. 规则快速分类");
            System.out.println("    2. 向量语义分类");
            System.out.println("    3. 置信度判断过滤");
            System.out.println("    4. 关键词提取记忆");
            System.out.println("========================================");

        } catch (Exception e) {
            System.err.println("发生错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (Exception e) {
            }
        }
    }
}
