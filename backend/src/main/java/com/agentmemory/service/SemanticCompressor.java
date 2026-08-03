package com.agentmemory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义压缩器
 * 提供智能化的会话压缩能力：
 *
 * 1. 语义聚类压缩 - 使用 Embedding 将语义相近的消息分组，每组保留代表消息
 * 2. 多级摘要 (Map-Reduce) - 分块摘要再合并，支持超长会话
 * 3. 自适应窗口 - 根据消息重要性动态调整保留数量
 * 4. 重要性评分 - 基于长度、关键词、角色等因素评估消息价值
 */
public class SemanticCompressor {

    private static final Logger log = LoggerFactory.getLogger(SemanticCompressor.class);

    private final EmbeddingClient embeddingClient;
    private final LLMClient llmClient;

    /** 语义聚类相似度阈值（余弦相似度） */
    private static final double CLUSTER_THRESHOLD = 0.82;

    /** 多级摘要的分块大小（每条块的消息数） */
    private static final int SUMMARY_CHUNK_SIZE = 30;

    /** 重要性关键词（命中加分） */
    private static final String[] IMPORTANCE_KEYWORDS = {
            "错误", "error", "失败", "fail", "解决", "fix", "修复",
            "重要", "注意", "必须", "关键", "bug", "问题",
            "如何", "怎么", "为什么", "?"
    };

    public SemanticCompressor(EmbeddingClient embeddingClient, LLMClient llmClient) {
        this.embeddingClient = embeddingClient;
        this.llmClient = llmClient;
    }

    // ==================== 1. 语义聚类压缩 ====================

    /**
     * 语义聚类压缩
     * 将语义相近的消息聚成簇，每簇保留首条和末条作为代表，
     * 中间消息用簇摘要替代
     *
     * @param messages 原始消息列表（格式: "[role] content"）
     * @return 压缩后的消息列表
     */
    public List<String> compressBySemanticCluster(List<String> messages) {
        if (messages.size() <= 10) {
            return messages;  // 消息太少，无需聚类
        }

        // 检查 embedding 服务是否可用
        if (embeddingClient == null || !embeddingClient.isHealthy()) {
            log.warn("Embedding 服务不可用，跳过语义聚类");
            return messages;
        }

        try {
            // 1. 批量获取消息向量
            List<float[]> vectors = embeddingClient.embed(messages);
            if (vectors == null || vectors.size() != messages.size()) {
                log.warn("获取消息向量失败，跳过语义聚类");
                return messages;
            }

            // 2. 贪心聚类
            List<List<Integer>> clusters = greedyCluster(vectors, CLUSTER_THRESHOLD);
            log.info("语义聚类: {} 条消息 -> {} 个簇", messages.size(), clusters.size());

            // 3. 每簇保留代表消息 + 簇摘要
            List<String> result = new ArrayList<>();
            for (List<Integer> cluster : clusters) {
                if (cluster.size() <= 2) {
                    // 小簇：全部保留
                    for (int idx : cluster) {
                        result.add(messages.get(idx));
                    }
                } else {
                    // 大簇：保留首条 + 摘要 + 末条
                    result.add(messages.get(cluster.get(0)));

                    // 簇中间消息生成简要占位
                    int skipped = cluster.size() - 2;
                    result.add("[系统] （此处省略 " + skipped + " 条语义相近的消息）");

                    result.add(messages.get(cluster.get(cluster.size() - 1)));
                }
            }
            return result;

        } catch (Exception e) {
            log.error("语义聚类压缩失败", e);
            return messages;  // 失败时返回原始消息
        }
    }

    /**
     * 贪心语义聚类
     * 遍历消息向量，与当前簇质心相似度 > 阈值则入簇，否则新建簇
     */
    private List<List<Integer>> greedyCluster(List<float[]> vectors, double threshold) {
        List<List<Integer>> clusters = new ArrayList<>();
        List<float[]> centroids = new ArrayList<>();

        for (int i = 0; i < vectors.size(); i++) {
            float[] vec = vectors.get(i);
            if (vec == null) {
                // 无向量的消息自成一簇
                List<Integer> single = new ArrayList<>();
                single.add(i);
                clusters.add(single);
                centroids.add(null);
                continue;
            }

            // 找最相似的簇
            int bestCluster = -1;
            double bestSim = -1;
            for (int c = 0; c < centroids.size(); c++) {
                float[] centroid = centroids.get(c);
                if (centroid == null) continue;
                double sim = cosineSimilarity(vec, centroid);
                if (sim > bestSim) {
                    bestSim = sim;
                    bestCluster = c;
                }
            }

            if (bestCluster >= 0 && bestSim >= threshold) {
                // 加入簇并更新质心（增量平均）
                List<Integer> cluster = clusters.get(bestCluster);
                cluster.add(i);
                centroids.set(bestCluster, averageVectors(centroids.get(bestCluster), vec, cluster.size()));
            } else {
                // 新建簇
                List<Integer> newCluster = new ArrayList<>();
                newCluster.add(i);
                clusters.add(newCluster);
                centroids.add(vec);
            }
        }
        return clusters;
    }

    /**
     * 增量更新质心: newCentroid = (old * (n-1) + new) / n
     */
    private float[] averageVectors(float[] oldCentroid, float[] newVec, int n) {
        float[] result = new float[oldCentroid.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (oldCentroid[i] * (n - 1) + newVec[i]) / n;
        }
        return result;
    }

    /**
     * 余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ==================== 2. 多级摘要 (Map-Reduce) ====================

    /**
     * 多级摘要
     * Map 阶段: 将消息分块，每块生成子摘要
     * Reduce 阶段: 合并子摘要为总摘要（超长时递归）
     *
     * @param messages 原始消息列表
     * @return 总摘要
     */
    public String generateMultiLevelSummary(List<String> messages) {
        if (messages.isEmpty()) {
            return "";
        }

        // 消息量小，直接单级摘要
        if (messages.size() <= SUMMARY_CHUNK_SIZE) {
            return llmClient.summarize(messages);
        }

        // Map: 分块生成子摘要
        List<String> chunkSummaries = new ArrayList<>();
        for (int i = 0; i < messages.size(); i += SUMMARY_CHUNK_SIZE) {
            int end = Math.min(i + SUMMARY_CHUNK_SIZE, messages.size());
            List<String> chunk = messages.subList(i, end);
            String chunkSummary = llmClient.summarize(chunk);
            if (chunkSummary != null && !chunkSummary.isEmpty()) {
                chunkSummaries.add("【第" + (chunkSummaries.size() + 1) + "部分】" + chunkSummary);
            }
        }

        if (chunkSummaries.isEmpty()) {
            return "";
        }

        // Reduce: 合并子摘要
        log.info("多级摘要: {} 条消息 -> {} 个子摘要，合并中", messages.size(), chunkSummaries.size());
        if (chunkSummaries.size() == 1) {
            return chunkSummaries.get(0);
        }

        // 子摘要数量仍较多时递归合并
        if (chunkSummaries.size() > SUMMARY_CHUNK_SIZE) {
            return generateMultiLevelSummary(chunkSummaries);
        }

        String finalSummary = llmClient.summarize(chunkSummaries);
        return finalSummary != null ? finalSummary : String.join("\n", chunkSummaries);
    }

    // ==================== 3. 自适应窗口 ====================

    /**
     * 自适应窗口大小
     * 根据消息重要性分布动态调整：保留的消息应覆盖重要性总量的约 80%
     *
     * @param messages   消息列表
     * @param baseWindow 基础窗口大小（配置值）
     * @param minWindow  最小窗口（保证基本上下文）
     * @param maxWindow  最大窗口（防止过度保留）
     * @return 自适应窗口大小
     */
    public int calculateAdaptiveWindowSize(List<String> messages, int baseWindow, int minWindow, int maxWindow) {
        if (messages.size() <= minWindow) {
            return messages.size();
        }

        // 计算每条消息的重要性
        double[] scores = new double[messages.size()];
        double totalScore = 0;
        for (int i = 0; i < messages.size(); i++) {
            scores[i] = scoreImportance(messages.get(i));
            totalScore += scores[i];
        }

        if (totalScore == 0) {
            return baseWindow;
        }

        // 从最新消息往前累加，直到覆盖 80% 重要性
        double target = totalScore * 0.8;
        double accumulated = 0;
        int window = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            accumulated += scores[i];
            window++;
            if (accumulated >= target) {
                break;
            }
        }

        // 限制在 [minWindow, maxWindow] 范围内
        int adaptive = Math.max(minWindow, Math.min(maxWindow, window));
        log.debug("自适应窗口: 基础={}, 自适应={}, 消息总数={}", baseWindow, adaptive, messages.size());
        return adaptive;
    }

    /**
     * 消息重要性评分
     * 因素: 长度、关键词、角色、问题句
     */
    private double scoreImportance(String message) {
        if (message == null || message.isEmpty()) {
            return 0;
        }

        double score = 1.0;

        // 长度因子: log10 缩放，避免超长消息主导
        score += Math.log10(message.length() + 1);

        // 关键词加分
        String lower = message.toLowerCase();
        for (String keyword : IMPORTANCE_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) {
                score += 0.5;
            }
        }

        // user 角色权重（用户意图通常更重要）
        if (message.startsWith("[user]")) {
            score *= 1.2;
        }

        return score;
    }
}
