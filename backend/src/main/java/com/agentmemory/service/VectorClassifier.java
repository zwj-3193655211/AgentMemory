package com.agentmemory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量相似度分类器
 * 利用已分类的记忆样本，通过向量相似度判断新内容的类型
 */
public class VectorClassifier {

    private static final Logger log = LoggerFactory.getLogger(VectorClassifier.class);

    private final DatabaseService databaseService;
    private final EmbeddingClient embeddingClient;

    // 五大记忆库的典型样本向量（缓存）
    private final Map<MemoryClassifier.MemoryType, List<SampleVector>> sampleCache = new ConcurrentHashMap<>();

    // 向量缓存TTL（毫秒），5分钟刷新一次
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    private long lastCacheTime = 0;

    // 相似度阈值
    private static final double HIGH_SIMILARITY_THRESHOLD = 0.85;
    private static final double LOW_SIMILARITY_THRESHOLD = 0.60;

    public VectorClassifier(DatabaseService databaseService, EmbeddingClient embeddingClient) {
        this.databaseService = databaseService;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 根据向量相似度分类
     * @param content 待分类内容
     * @return 分类结果（包含类型和置信度）
     */
    public ClassificationResult classify(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new ClassificationResult(MemoryClassifier.MemoryType.UNKNOWN, 0.0);
        }

        try {
            // 1. 生成内容向量
            float[] contentEmbedding = embeddingClient.embed(content);
            if (contentEmbedding == null) {
                log.warn("无法生成内容向量，使用规则分类");
                return new ClassificationResult(MemoryClassifier.MemoryType.UNKNOWN, 0.0);
            }

            // 2. 在每个记忆库中搜索最相似的样本
            Map<MemoryClassifier.MemoryType, Double> typeSimilarities = new EnumMap<>(MemoryClassifier.MemoryType.class);

            for (MemoryClassifier.MemoryType type : MemoryClassifier.MemoryType.values()) {
                if (type == MemoryClassifier.MemoryType.UNKNOWN) continue;

                double maxSimilarity = findMaxSimilarity(contentEmbedding, type);
                typeSimilarities.put(type, maxSimilarity);
            }

            // 3. 找出相似度最高的类型
            Map.Entry<MemoryClassifier.MemoryType, Double> best = typeSimilarities.entrySet().stream()
                    .filter(e -> e.getValue() > LOW_SIMILARITY_THRESHOLD)
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);

            if (best != null) {
                MemoryClassifier.MemoryType type = best.getKey();
                double similarity = best.getValue();

                // 计算置信度（基于相似度）
                double confidence = calculateConfidence(similarity);

                log.debug("向量分类结果: type={}, similarity={}, confidence={}", type, similarity, confidence);

                return new ClassificationResult(type, confidence, similarity);
            }

            return new ClassificationResult(MemoryClassifier.MemoryType.UNKNOWN, 0.0);

        } catch (Exception e) {
            log.error("向量分类失败", e);
            return new ClassificationResult(MemoryClassifier.MemoryType.UNKNOWN, 0.0);
        }
    }

    /**
     * 在指定记忆库中查找最相似的记录
     */
    private double findMaxSimilarity(float[] contentEmbedding, MemoryClassifier.MemoryType type) {
        String tableName = type.getTableName();
        if (tableName == null) return 0.0;

        String sql = """
            SELECT embedding <=> ?::vector AS similarity
            FROM %s
            WHERE embedding IS NOT NULL AND deleted = false
            ORDER BY embedding <=> ?::vector
            LIMIT 5
            """.formatted(tableName);

        try (Connection conn = databaseService.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // 设置查询向量（用于与存储的向量计算余弦距离）
            // pgvector的 <=> 操作符计算余弦距离，距离越小越相似
            stmt.setObject(1, toPgVector(contentEmbedding));
            stmt.setObject(2, toPgVector(contentEmbedding));

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    // 余弦距离转换为相似度（距离越小，相似度越高）
                    double distance = rs.getDouble("similarity");
                    return 1.0 - distance; // 距离转相似度
                }
            }

        } catch (SQLException e) {
            log.warn("查询{}的向量相似度失败: {}", tableName, e.getMessage());
        }

        return 0.0;
    }

    /**
     * 将float数组转换为PostgreSQL vector格式字符串
     */
    private String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 计算置信度
     * 相似度越高，置信度越高
     */
    private double calculateConfidence(double similarity) {
        if (similarity >= HIGH_SIMILARITY_THRESHOLD) {
            return 0.9 + (similarity - HIGH_SIMILARITY_THRESHOLD) * 0.5;
        } else if (similarity >= LOW_SIMILARITY_THRESHOLD) {
            return 0.6 + (similarity - LOW_SIMILARITY_THRESHOLD) * 2.0;
        }
        return similarity;
    }

    /**
     * 清除样本缓存
     */
    public void clearCache() {
        sampleCache.clear();
        lastCacheTime = 0;
    }

    /**
     * 分类结果
     */
    public static class ClassificationResult {
        private final MemoryClassifier.MemoryType type;
        private final double confidence;
        private final double similarity;

        public ClassificationResult(MemoryClassifier.MemoryType type, double confidence) {
            this(type, confidence, 0.0);
        }

        public ClassificationResult(MemoryClassifier.MemoryType type, double confidence, double similarity) {
            this.type = type;
            this.confidence = confidence;
            this.similarity = similarity;
        }

        public MemoryClassifier.MemoryType getType() { return type; }
        public double getConfidence() { return confidence; }
        public double getSimilarity() { return similarity; }

        @Override
        public String toString() {
            return "ClassificationResult{type=" + type + ", confidence=" + confidence + ", similarity=" + similarity + "}";
        }
    }

    /**
     * 样本向量（用于缓存）
     */
    private static class SampleVector {
        final String content;
        final float[] embedding;
        final MemoryClassifier.MemoryType type;

        SampleVector(String content, float[] embedding, MemoryClassifier.MemoryType type) {
            this.content = content;
            this.embedding = embedding;
            this.type = type;
        }
    }
}