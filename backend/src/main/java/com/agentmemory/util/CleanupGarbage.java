package com.agentmemory.util;

import java.sql.*;
import java.util.*;

/**
 * 深度清理 error_corrections 表中的垃圾记录
 */
public class CleanupGarbage {
    public static void main(String[] args) throws Exception {
        String url = System.getenv().getOrDefault("DATABASE_URL",
            "jdbc:postgresql://localhost:5500/agentmemory");
        String user = System.getenv().getOrDefault("DATABASE_USER", "agentmemory");
        String password = System.getenv().getOrDefault("DATABASE_PASSWORD", "agentmemory");

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);

            // 1. 先查看所有记录
            System.out.println("=== 清理前的记录 ===");
            List<String> idsToDelete = new ArrayList<>();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                    "SELECT id, title, problem, solution FROM error_corrections WHERE deleted = false")) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String title = rs.getString("title");
                    String problem = rs.getString("problem");
                    String solution = rs.getString("solution");

                    boolean isGarbage = isGarbageRecord(title, problem, solution);
                    String status = isGarbage ? "[垃圾]" : "[保留]";
                    System.out.println(status + " id=" + id.substring(0, 8) + " title=" + (title != null ? title.replace("\n", "\\n").substring(0, Math.min(40, title.length())) : "null"));

                    if (isGarbage) {
                        idsToDelete.add(id);
                    }
                }
            }

            // 2. 删除垃圾记录
            if (!idsToDelete.isEmpty()) {
                StringBuilder sql = new StringBuilder("UPDATE error_corrections SET deleted = true WHERE id IN (");
                for (int i = 0; i < idsToDelete.size(); i++) {
                    if (i > 0) sql.append(",");
                    sql.append("'").append(idsToDelete.get(i)).append("'");
                }
                sql.append(")");

                try (Statement stmt = conn.createStatement()) {
                    int n = stmt.executeUpdate(sql.toString());
                    System.out.println("\n标记删除垃圾记录: " + n + " 条");
                }
            }

            // 3. 清空剩余记录的 problem 字段（用户只关心正确做法）
            try (Statement stmt = conn.createStatement()) {
                int n = stmt.executeUpdate(
                    "UPDATE error_corrections SET problem = '' " +
                    "WHERE deleted = false AND problem IS NOT NULL AND problem != ''");
                System.out.println("清空 problem 字段: " + n + " 条");
            }

            // 4. 截断过长的 solution
            try (Statement stmt = conn.createStatement()) {
                int n = stmt.executeUpdate(
                    "UPDATE error_corrections SET solution = LEFT(solution, 200) " +
                    "WHERE deleted = false AND LENGTH(solution) > 200");
                System.out.println("截断过长 solution: " + n + " 条");
            }

            conn.commit();
            System.out.println("\n清理完成！");

            // 5. 显示剩余记录
            System.out.println("\n=== 清理后的记录 ===");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                    "SELECT id, title, solution FROM error_corrections WHERE deleted = false")) {
                while (rs.next()) {
                    String title = rs.getString("title");
                    String solution = rs.getString("solution");
                    System.out.println("title=" + title + " | solution=" + (solution != null ? solution.substring(0, Math.min(50, solution.length())) : "null"));
                }
            }
        }
    }

    private static boolean isGarbageRecord(String title, String problem, String solution) {
        String combined = (title != null ? title : "") + " " + (problem != null ? problem : "") + " " + (solution != null ? solution : "");

        // 1. 包含 markdown/代码结构符号
        if (combined.contains("├──") || combined.contains("└──") || combined.contains("```")) return true;

        // 2. 包含大量 markdown 表格/格式符号
        int specialCount = 0;
        for (char c : combined.toCharArray()) {
            if (c == '|' || c == '*' || c == '`' || c == '#' || c == '<' || c == '>') specialCount++;
        }
        if (specialCount > combined.length() * 0.15) return true;

        // 3. 包含 Windows 命令行错误输出
        if (combined.contains("不是内部或外部命令") || combined.contains("可运行的程序")) return true;

        // 4. 包含 JSON/YAML 片段
        if (combined.contains("\":\"") || combined.contains("\"hasSeenTasksHint\"") || combined.contains("Pending Tasks:")) return true;

        // 5. title 太短或太奇怪
        if (title == null || title.trim().length() < 3) return true;
        if (title.startsWith("：") || title.startsWith(":") || title.startsWith("\n")) return true;

        // 6. 内容基本是代码/路径
        if (combined.contains("C:\\Users\\") && combined.contains(".md")) {
            // 但如果是很明确的约束（如"不要写入C盘"），则保留
            if (!combined.contains("不要") && !combined.contains("别") && !combined.contains("不能")) {
                return true;
            }
        }

        // 7. solution 和 problem 几乎相同
        if (problem != null && solution != null) {
            String p = problem.trim();
            String s = solution.trim();
            if (p.length() > 5 && s.length() > 5) {
                // 计算相似度（简单版本：包含关系）
                if (s.contains(p) || p.contains(s)) {
                    // 如果 solution 只是 problem 的扩展，且没有提供新的正确做法
                    if (Math.abs(s.length() - p.length()) < 10) return true;
                }
            }
        }

        return false;
    }
}
