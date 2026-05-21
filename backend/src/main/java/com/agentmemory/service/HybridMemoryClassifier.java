package com.agentmemory.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 混合记忆分类器
 * 结合规则分类、向量分类、LLM分类三种方式，根据置信度动态选择最优分类策略
 *
 * 分类策略：
 * 1. 规则优先：明确场景（如纠正标记）直接返回结果，性能最高
 * 2. 向量辅助：规则无法判断时，使用向量相似度快速分类
 * 3. LLM兜底：向量置信度不足时，使用LLM进行语义分类
 */
public class HybridMemoryClassifier {

    private static final Logger log = LoggerFactory.getLogger(HybridMemoryClassifier.class);

    // 分类器组件
    private final RuleClassifier ruleClassifier;
    private final VectorClassifier vectorClassifier;
    private final LLMClassifier llmClassifier;

    // 置信度阈值配置
    private static final double RULE_CONFIDENCE_THRESHOLD = 0.80;   // 规则高置信度阈值
    private static final double VECTOR_CONFIDENCE_THRESHOLD = 0.75; // 向量高置信度阈值
    private static final double LLM_FALLBACK_THRESHOLD = 0.60;      // 触发LLM的向量置信度阈值

    // 是否启用各分类器
    private volatile boolean ruleEnabled = true;
    private volatile boolean vectorEnabled = true;
    private volatile boolean llmEnabled = true;

    // 分类统计（用于监控和调优）
    private final Map<String, Counter> stats = new ConcurrentHashMap<>();

    public HybridMemoryClassifier(DatabaseService databaseService, EmbeddingClient embeddingClient, LLMClient llmClient) {
        this.ruleClassifier = new RuleClassifier();
        this.vectorClassifier = new VectorClassifier(databaseService, embeddingClient);
        this.llmClassifier = new LLMClassifier(llmClient);

        // 初始化统计计数器
        stats.put("rule", new Counter());
        stats.put("vector", new Counter());
        stats.put("llm", new Counter());
        stats.put("fallback", new Counter());

        log.info("混合记忆分类器初始化完成");
        log.info("  - 规则分类: {}", ruleEnabled ? "启用" : "禁用");
        log.info("  - 向量分类: {}", vectorEnabled ? "启用" : "禁用");
        log.info("  - LLM分类: {}", llmEnabled ? "启用" : "禁用");
    }

    /**
     * 执行混合分类
     * @param content 待分类内容
     * @return 最终分类结果
     */
    public ClassificationResult classify(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new ClassificationResult(MemoryClassifier.MemoryType.UNKNOWN, 0.0, "empty");
        }

        long startTime = System.currentTimeMillis();

        try {
            // 阶段1：规则快速判断
            if (ruleEnabled) {
                ClassificationResult ruleResult = ruleClassifier.classify(content);

                if (ruleResult.getConfidence() >= RULE_CONFIDENCE_THRESHOLD) {
                    recordStat("rule", true);
                    log.debug("规则分类命中: type={}, confidence={}", ruleResult.getType(), ruleResult.getConfidence());
                    return ruleResult.withMethod("rule");
                }

                // 特殊场景：纠正标记直接返回（不等待向量/LLM）
                if (ruleResult.getType() == MemoryClassifier.MemoryType.ERROR_CORRECTION) {
                    recordStat("rule", true);
                    return ruleResult.withMethod("rule-correction");
                }
            }

            // 阶段2：向量相似度分类
            if (vectorEnabled) {
                try {
                    VectorClassifier.ClassificationResult vectorResult = vectorClassifier.classify(content);

                    if (vectorResult.getConfidence() >= VECTOR_CONFIDENCE_THRESHOLD) {
                        recordStat("vector", true);

                        // 检查规则和向量是否一致
                        if (ruleEnabled) {
                            ClassificationResult ruleResult = ruleClassifier.classify(content);
                            if (ruleResult.getType() == vectorResult.getType()) {
                                // 一致性高，提升置信度
                                double boostedConfidence = Math.min(1.0, vectorResult.getConfidence() + 0.1);
                                log.debug("规则与向量一致，置信度提升: {} -> {}", vectorResult.getConfidence(), boostedConfidence);
                                return new ClassificationResult(
                                    vectorResult.getType(),
                                    boostedConfidence,
                                    "rule+vector"
                                );
                            }
                        }

                        log.debug("向量分类: type={}, confidence={}", vectorResult.getType(), vectorResult.getConfidence());
                        return new ClassificationResult(
                            vectorResult.getType(),
                            vectorResult.getConfidence(),
                            "vector"
                        );
                    }

                    // 向量置信度不足，触发LLM
                    if (vectorResult.getConfidence() < LLM_FALLBACK_THRESHOLD &&
                        vectorResult.getType() != MemoryClassifier.MemoryType.UNKNOWN) {
                        // 向量给出了低置信度结果，可以作为参考但不直接使用
                        log.debug("向量低置信度结果: type={}, confidence={}", vectorResult.getType(), vectorResult.getConfidence());
                    }

                } catch (Exception e) {
                    log.warn("向量分类失败: {}", e.getMessage());
                    recordStat("vector", false);
                }
            }

            // 阶段3：LLM语义分类（兜底）
            if (llmEnabled && llmClassifier.isAvailable()) {
                try {
                    LLMClassifier.ClassificationResult llmResult = llmClassifier.classify(content);

                    if (llmResult.getConfidence() >= LLM_FALLBACK_THRESHOLD) {
                        recordStat("llm", true);
                        log.debug("LLM分类: type={}, confidence={}", llmResult.getType(), llmResult.getConfidence());
                        return new ClassificationResult(
                            llmResult.getType(),
                            llmResult.getConfidence(),
                            "llm"
                        );
                    }

                } catch (Exception e) {
                    log.warn("LLM分类失败: {}", e.getMessage());
                    recordStat("llm", false);
                }
            }

            // 兜底：返回规则结果（即使置信度低）
            if (ruleEnabled) {
                ClassificationResult ruleResult = ruleClassifier.classify(content);
                recordStat("fallback", true);
                log.debug("使用规则兜底: type={}, confidence={}", ruleResult.getType(), ruleResult.getConfidence());
                return ruleResult.withMethod("rule-fallback");
            }

            return new ClassificationResult(MemoryClassifier.MemoryType.UNKNOWN, 0.0, "all-failed");

        } finally {
            long duration = System.currentTimeMillis() - startTime;
            if (duration > 100) {
                log.debug("分类耗时: {}ms", duration);
            }
        }
    }

    /**
     * 批量分类
     */
    public List<ClassificationResult> classifyBatch(List<String> contents) {
        List<ClassificationResult> results = new ArrayList<>();
        for (String content : contents) {
            results.add(classify(content));
        }
        return results;
    }

    /**
     * 记录分类统计
     */
    private void recordStat(String method, boolean success) {
        stats.get(method).increment(success);
    }

    /**
     * 获取分类统计信息
     */
    public Map<String, ClassificationStats> getStats() {
        Map<String, ClassificationStats> result = new LinkedHashMap<>();
        for (Map.Entry<String, Counter> entry : stats.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toStats());
        }
        return result;
    }

    /**
     * 重置统计
     */
    public void resetStats() {
        for (Counter counter : stats.values()) {
            counter.reset();
        }
    }

    /**
     * 启用/禁用各分类器
     */
    public void setRuleEnabled(boolean enabled) { this.ruleEnabled = enabled; }
    public void setVectorEnabled(boolean enabled) { this.vectorEnabled = enabled; }
    public void setLlmEnabled(boolean enabled) { this.llmEnabled = enabled; }

    /**
     * 清除向量分类器的缓存
     */
    public void clearVectorCache() {
        vectorClassifier.clearCache();
    }

    // ==================== 内部类 ====================

    /**
     * 规则分类器（原有逻辑的封装）
     */
    private static class RuleClassifier {
        private final MemoryClassifier classifier = new MemoryClassifier();

        public ClassificationResult classify(String content) {
            MemoryClassifier.MemoryType type = classifier.classify(content);
            double confidence = estimateConfidence(content, type);
            return new ClassificationResult(type, confidence, "rule");
        }

        private double estimateConfidence(String content, MemoryClassifier.MemoryType type) {
            if (type == MemoryClassifier.MemoryType.UNKNOWN) {
                return 0.0;
            }

            // 根据内容长度和关键词密度估算置信度
            int length = content.length();
            double baseConfidence = 0.6;

            // 内容越长，置信度略高
            if (length > 100) baseConfidence += 0.1;
            if (length > 500) baseConfidence += 0.1;

            // 检查关键标记词
            String lower = content.toLowerCase();
            if (lower.contains("不是") || lower.contains("错了") || lower.contains("不对")) {
                baseConfidence += 0.15;
            }
            if (lower.contains("建议") || lower.contains("推荐") || lower.contains("应该")) {
                baseConfidence += 0.1;
            }
            if (lower.contains("我喜欢") || lower.contains("我习惯")) {
                baseConfidence += 0.15;
            }

            return Math.min(0.95, baseConfidence);
        }
    }

    /**
     * 分类结果
     */
    public static class ClassificationResult {
        private final MemoryClassifier.MemoryType type;
        private final double confidence;
        private final String source; // 分类来源
        private final String method; // 使用的分类方法

        public ClassificationResult(MemoryClassifier.MemoryType type, double confidence, String source) {
            this.type = type;
            this.confidence = confidence;
            this.source = source;
            this.method = source;
        }

        public ClassificationResult withMethod(String method) {
            return new ClassificationResult(this.type, this.confidence, method);
        }

        public MemoryClassifier.MemoryType getType() { return type; }
        public double getConfidence() { return confidence; }
        public String getSource() { return source; }
        public String getMethod() { return method; }

        @Override
        public String toString() {
            return "Result{type=" + type + ", confidence=" + String.format("%.2f", confidence) +
                   ", method=" + method + "}";
        }
    }

    /**
     * 统计计数器
     */
    private static class Counter {
        private long success = 0;
        private long total = 0;

        synchronized void increment(boolean success) {
            total++;
            if (success) this.success++;
        }

        synchronized void reset() {
            success = 0;
            total = 0;
        }

        synchronized ClassificationStats toStats() {
            return new ClassificationStats(total, success, total > 0 ? (double) success / total : 0.0);
        }
    }

    /**
     * 分类统计信息
     */
    public static class ClassificationStats {
        private final long total;
        private final long success;
        private final double successRate;

        public ClassificationStats(long total, long success, double successRate) {
            this.total = total;
            this.success = success;
            this.successRate = successRate;
        }

        public long getTotal() { return total; }
        public long getSuccess() { return success; }
        public double getSuccessRate() { return successRate; }

        @Override
        public String toString() {
            return String.format("stats{total=%d, success=%d, rate=%.2f%%}", total, success, successRate * 100);
        }
    }
}