package com.agentmemory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import com.agentmemory.config.ApplicationConfig;
import com.agentmemory.service.DatabaseService;
import com.agentmemory.service.EmbeddingClient;
import com.agentmemory.service.HybridMemoryClassifier;
import com.agentmemory.service.LLMClient;

public class HybridClassifierTest {

    public static void main(String[] args) {
        System.out.println("===========================================");
        System.out.println("   混合分类器测试 - 规则 + 向量分类");
        System.out.println("===========================================\n");

        Connection conn = null;
        try {
            System.out.println("初始化组件...");
            ApplicationConfig config = ApplicationConfig.load();
            DatabaseService dbService = new DatabaseService(config);
            dbService.init();
            EmbeddingClient embeddingClient = new EmbeddingClient();
            
            LLMClient llmClient = new LLMClient();
            // llama.cpp llama-server (OpenAI 兼容端点，端口 8080)，替代 Ollama
            llmClient.setProvider("openai", "http://localhost:8080/v1", null, "Qwen3.5-2B-Q8_0");
            
            HybridMemoryClassifier classifier = new HybridMemoryClassifier(dbService, embeddingClient, llmClient);

            System.out.println("✓ 混合分类器初始化完成");
            System.out.println("\n分类器配置:");
            System.out.println("  - 规则分类: 启用");
            System.out.println("  - 向量分类: 启用");
            System.out.println("  - LLM分类: 启用 (Ollama qwen3.5:2b)");
            System.out.println("\n-------------------------------------------");
            System.out.println("开始测试...\n");

            conn = DriverManager.getConnection("jdbc:postgresql://localhost:5500/agentmemory", "agentmemory", "agentmemory");
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT role, content FROM messages WHERE content IS NOT NULL AND LENGTH(content) > 80 ORDER BY RANDOM() LIMIT 8");

            int count = 0;
            int[] methodCounts = new int[5];
            int[] typeCounts = new int[6];

            while (rs.next()) {
                count++;
                String role = rs.getString("role");
                String content = rs.getString("content");

                if (content != null && content.length() > 80) {
                    String truncated = content.length() > 100 ? content.substring(0, 100) + "..." : content;
                    System.out.println("【测试 " + count + "】角色: " + role);
                    System.out.println("  内容: " + truncated.replace("\n", " "));

                    try {
                        HybridMemoryClassifier.ClassificationResult result = classifier.classify(content);
                        System.out.println("  → 分类结果: " + result.getType());
                        System.out.println("  → 分类方法: " + result.getMethod());
                        System.out.println("  → 置信度: " + String.format("%.2f", result.getConfidence()));

                        String method = result.getMethod();
                        switch (method) {
                            case "rule" -> methodCounts[0]++;
                            case "rule-correction" -> methodCounts[1]++;
                            case "rule+vector" -> methodCounts[2]++;
                            case "vector" -> methodCounts[3]++;
                            default -> methodCounts[4]++;
                        }

                        switch (result.getType()) {
                            case ERROR_CORRECTION -> typeCounts[0]++;
                            case USER_PROFILE -> typeCounts[1]++;
                            case BEST_PRACTICE -> typeCounts[2]++;
                            case PROJECT_CONTEXT -> typeCounts[3]++;
                            case SKILL -> typeCounts[4]++;
                            case UNKNOWN -> typeCounts[5]++;
                        }
                    } catch (Exception e) {
                        System.out.println("  → 分类出错: " + e.getMessage());
                        methodCounts[4]++;
                    }
                    System.out.println();
                }
            }
            rs.close();
            stmt.close();

            System.out.println("\n-------------------------------------------");
            System.out.println("\n📊 分类方法统计:");
            System.out.println("  ├─ rule (规则直接命中): " + methodCounts[0]);
            System.out.println("  ├─ rule-correction (错误纠正): " + methodCounts[1]);
            System.out.println("  ├─ rule+vector (规则+向量一致): " + methodCounts[2]);
            System.out.println("  ├─ vector (向量分类): " + methodCounts[3]);
            System.out.println("  └─ 其他/失败: " + methodCounts[4]);

            System.out.println("\n📈 分类类型统计:");
            System.out.println("  ├─ ERROR_CORRECTION (错误纠正): " + typeCounts[0]);
            System.out.println("  ├─ USER_PROFILE (用户画像): " + typeCounts[1]);
            System.out.println("  ├─ BEST_PRACTICE (最佳实践): " + typeCounts[2]);
            System.out.println("  ├─ PROJECT_CONTEXT (项目上下文): " + typeCounts[3]);
            System.out.println("  ├─ SKILL (技能方法): " + typeCounts[4]);
            System.out.println("  └─ UNKNOWN (未知): " + typeCounts[5]);

            System.out.println("\n📋 分类器统计信息:");
            System.out.println(classifier.getStats());

            System.out.println("\n===========================================");
            System.out.println("   测试完成!");
            System.out.println("===========================================");

        } catch (Exception e) {
            System.err.println("\n❌ 测试失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try { 
                    conn.close(); 
                } catch (java.sql.SQLException e) {
                    // 忽略连接关闭异常
                }
            }
        }
    }
}