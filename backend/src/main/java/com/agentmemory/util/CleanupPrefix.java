package com.agentmemory.util;

import java.sql.*;

/**
 * 清理 error_corrections 表中旧版本添加的冗余前缀
 */
public class CleanupPrefix {
    public static void main(String[] args) throws Exception {
        String url = System.getenv().getOrDefault("DATABASE_URL",
            "jdbc:postgresql://localhost:5500/agentmemory");
        String user = System.getenv().getOrDefault("DATABASE_USER", "agentmemory");
        String password = System.getenv().getOrDefault("DATABASE_PASSWORD", "agentmemory");

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);

            // 1. 先查看有多少条记录受影响
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM error_corrections " +
                    "WHERE problem LIKE 'AI%' OR solution LIKE '正确%' OR title LIKE '纠正%'")) {
                rs.next();
                int count = rs.getInt(1);
                System.out.println("需要清理的记录数: " + count);
            }

            // 2. 清理 title
            try (Statement stmt = conn.createStatement()) {
                int n = stmt.executeUpdate(
                    "UPDATE error_corrections SET title = regexp_replace(title, '^(纠正[\uFF1A:]\\s*|纠正约束[\uFF1A:]\\s*)', '') " +
                    "WHERE title LIKE '纠正%'");
                System.out.println("清理 title 前缀: " + n + " 条");
            }

            // 3. 清理 problem
            try (Statement stmt = conn.createStatement()) {
                int n = stmt.executeUpdate(
                    "UPDATE error_corrections SET problem = regexp_replace(problem, " +
                    "'^(AI认为[\uFF1A:]\\s*|AI的错误方案[\uFF1A:]\\s*|AI错误做法[\uFF1A:]\\s*|" +
                    "AI的约束违规[\uFF1A:]\\s*|AI误解为[\uFF1A:]\\s*|用户纠正\\s*)', '') " +
                    "WHERE problem LIKE 'AI%' OR problem LIKE '用户纠正%'");
                System.out.println("清理 problem 前缀: " + n + " 条");
            }

            // 4. 清理 solution
            try (Statement stmt = conn.createStatement()) {
                int n = stmt.executeUpdate(
                    "UPDATE error_corrections SET solution = regexp_replace(solution, " +
                    "'^(正确答案[\uFF1A:]\\s*|正确做法[\uFF1A:]\\s*|正确方案[\uFF1A:]\\s*|" +
                    "约束要求[\uFF1A:]\\s*|用户实际意思[\uFF1A:]\\s*)', '') " +
                    "WHERE solution LIKE '正确%' OR solution LIKE '约束要求%' OR solution LIKE '用户实际%'");
                System.out.println("清理 solution 前缀: " + n + " 条");
            }

            // 5. 将 problem 为空的记录，把内容移到 solution
            try (Statement stmt = conn.createStatement()) {
                int n = stmt.executeUpdate(
                    "UPDATE error_corrections SET solution = COALESCE(NULLIF(solution,''), problem), " +
                    "problem = NULL WHERE problem IS NOT NULL AND (solution IS NULL OR solution = '')");
                System.out.println("合并空 solution: " + n + " 条");
            }

            // 6. 删除明显是垃圾的记录（包含大量 markdown/代码符号）
            try (Statement stmt = conn.createStatement()) {
                int n = stmt.executeUpdate(
                    "DELETE FROM error_corrections WHERE " +
                    "title LIKE '%|%>%' OR title LIKE '%**%' OR title LIKE '%```%' OR " +
                    "solution LIKE '%|%>%' OR solution LIKE '%**%' OR solution LIKE '%```%' OR " +
                    "LENGTH(title) < 3 OR LENGTH(solution) < 3");
                System.out.println("删除垃圾记录: " + n + " 条");
            }

            conn.commit();
            System.out.println("\n清理完成！");
        }
    }
}
