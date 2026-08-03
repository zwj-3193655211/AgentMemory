package com.agentmemory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * LLM 语义分类器
 * 利用大语言模型的语义理解能力进行精确分类
 */
public class LLMClassifier {

    private static final Logger log = LoggerFactory.getLogger(LLMClassifier.class);

    private final LLMClient llmClient;

    // LLM分类的置信度阈值
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.85;
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.50;

    // 系统提示词 - 优化版
    private static final String SYSTEM_PROMPT = """
        你是一个专业的技术对话记忆分类助手。你的任务是对用户与AI助手的对话内容进行高质量分类。

        【记忆类型定义】每个类型都有明确的区分标准：

        1. error_corrections（错误纠正）：
           - 用户明确指出AI之前的回答是错误的
           - 包含"不对"、"错了"、"不是这样"、"你搞错了"等表述
           - 包含正确的解决方案或替代方案

        2. user_profiles（用户画像）：
           - 用户的个人偏好、使用习惯、环境配置
           - 包含"我喜欢"、"我习惯"、"我的环境是"、"我用的是"等表述
           - 不包含具体技术问题，只描述个人偏好

        3. best_practices（最佳实践）：
           - 经过验证的成功经验或有效方法
           - 包含"建议"、"推荐"、"最好用"、"经验证明"等表述
           - 是通用性的建议，不针对特定bug修复

        4. project_contexts（项目上下文）：
           - 项目的技术栈、架构决策、项目路径
           - 包含具体项目名称、技术名词组合
           - 描述项目是"做什么的"而不是"怎么做的"

        5. skills（技能沉淀）：
           - 编程技能、方法论、步骤流程
           - 包含"步骤"、"流程"、"方法"、"原理"等表述
           - 可以复用到其他项目的通用知识

        6. unknown（未知）：
           - 简单的问候、闲聊、纯提问
           - 没有明确知识点的对话
           - 一次性问题而非可积累的经验

        【分类原则】
        - 优先检查是否包含"不对/错了"等错误纠正信号
        - project_contexts要看是否描述"项目是什么"而非"项目出了什么问题"
        - 如果内容包含多种信号，选择信息量最大、最有长期价值的那一类
        - 纯抱怨或情绪表达属于unknown
        """;

    // 分类结果JSON模板
    private static final String CLASSIFICATION_PROMPT_TEMPLATE = """
        请分析以下对话内容，判断它属于哪种记忆类型。

        对话内容：
        %s

        请返回JSON格式的分类结果，格式如下：
        {
            "type": "记忆类型",
            "confidence": 置信度(0-1之间的浮点数),
            "reasoning": "分类理由（简短）"
        }

        注意事项：
        - type 必须是以下值之一：error_corrections, user_profiles, best_practices, project_contexts, skills, unknown
        - confidence 表示分类的确定程度，0.0-1.0之间
        - 如果对话是用户的提问或闲聊，返回 unknown 类型
        """;

    public LLMClassifier(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 使用LLM进行分类
     * @param content 待分类内容
     * @return 分类结果
     */
    public ClassificationResult classify(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new ClassificationResult(MemoryClassifier.MemoryType.UNKNOWN, 0.0, "空内容");
        }

        // 内容过长时截断（避免token过多）
        String truncatedContent = truncateContent(content, 2000);

        String prompt = String.format(CLASSIFICATION_PROMPT_TEMPLATE, truncatedContent);

        try {
            String response = llmClient.generateWithSystemPrompt(SYSTEM_PROMPT, prompt);

            // 解析JSON响应
            return parseClassificationResponse(response, truncatedContent);

        } catch (Exception e) {
            log.error("LLM分类失败: {}", e.getMessage());
            return new ClassificationResult(MemoryClassifier.MemoryType.UNKNOWN, 0.0, "LLM调用失败: " + e.getMessage());
        }
    }

    /**
     * 批量分类（用于提高效率）
     */
    public List<ClassificationResult> classifyBatch(List<String> contents) {
        List<ClassificationResult> results = new ArrayList<>();
        for (String content : contents) {
            results.add(classify(content));
        }
        return results;
    }

    /**
     * 解析LLM返回的分类响应
     */
    private ClassificationResult parseClassificationResponse(String response, String originalContent) {
        try {
            // 尝试提取JSON（可能包含在markdown代码块中）
            String jsonStr = extractJson(response);

            // 简单解析JSON
            Map<String, String> parsed = parseSimpleJson(jsonStr);

            String typeStr = parsed.getOrDefault("type", "unknown");
            String confidenceStr = parsed.getOrDefault("confidence", "0.0");
            String reasoning = parsed.getOrDefault("reasoning", "");

            double confidence = Double.parseDouble(confidenceStr);

            MemoryClassifier.MemoryType type = parseMemoryType(typeStr);

            return new ClassificationResult(type, confidence, reasoning);

        } catch (Exception e) {
            log.warn("解析LLM响应失败，使用备用方案: {}", e.getMessage());

            // 备用方案：尝试从响应中提取关键词判断
            return fallbackClassify(response);
        }
    }

    /**
     * 从响应中提取JSON
     */
    private String extractJson(String response) {
        // 处理 markdown 代码块
        if (response.contains("```json")) {
            int start = response.indexOf("```json") + 7;
            int end = response.lastIndexOf("```");
            if (end > start) {
                return response.substring(start, end).trim();
            }
        } else if (response.contains("```")) {
            int start = response.indexOf("```") + 3;
            int end = response.lastIndexOf("```");
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }

        // 尝试直接解析
        return response.trim();
    }

    /**
     * 简单JSON解析（避免引入JSON库）
     */
    private Map<String, String> parseSimpleJson(String json) {
        Map<String, String> result = new LinkedHashMap<>();

        // 提取 type 字段
        result.put("type", extractJsonValue(json, "type"));
        result.put("confidence", extractJsonValue(json, "confidence"));
        result.put("reasoning", extractJsonValue(json, "reasoning"));

        return result;
    }

    /**
     * 提取JSON字符串中的值
     */
    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }

        // 尝试提取数字
        String numPattern = "\"" + key + "\"\\s*:\\s*([0-9.]+)";
        java.util.regex.Pattern np = java.util.regex.Pattern.compile(numPattern);
        java.util.regex.Matcher nm = np.matcher(json);
        if (nm.find()) {
            return nm.group(1);
        }

        return "";
    }

    /**
     * 解析记忆类型字符串
     */
    private MemoryClassifier.MemoryType parseMemoryType(String typeStr) {
        if (typeStr == null || typeStr.isEmpty()) {
            return MemoryClassifier.MemoryType.UNKNOWN;
        }

        return switch (typeStr.toLowerCase().trim()) {
            case "error_corrections", "error_correction", "errors", "error" -> MemoryClassifier.MemoryType.ERROR_CORRECTION;
            case "user_profiles", "user_profile", "profiles", "profile" -> MemoryClassifier.MemoryType.USER_PROFILE;
            case "best_practices", "best_practice", "practices", "practice" -> MemoryClassifier.MemoryType.BEST_PRACTICE;
            case "project_contexts", "project_context", "contexts", "context" -> MemoryClassifier.MemoryType.PROJECT_CONTEXT;
            case "skills", "skill" -> MemoryClassifier.MemoryType.SKILL;
            default -> MemoryClassifier.MemoryType.UNKNOWN;
        };
    }

    /**
     * 备用分类方案（当JSON解析失败时）
     */
    private ClassificationResult fallbackClassify(String response) {
        String lower = response.toLowerCase();

        // 根据响应中的关键词判断
        if (lower.contains("error") || lower.contains("纠正")) {
            return new ClassificationResult(MemoryClassifier.MemoryType.ERROR_CORRECTION, 0.6, "关键词匹配");
        } else if (lower.contains("profile") || lower.contains("偏好")) {
            return new ClassificationResult(MemoryClassifier.MemoryType.USER_PROFILE, 0.6, "关键词匹配");
        } else if (lower.contains("practice") || lower.contains("最佳实践")) {
            return new ClassificationResult(MemoryClassifier.MemoryType.BEST_PRACTICE, 0.6, "关键词匹配");
        } else if (lower.contains("context") || lower.contains("项目")) {
            return new ClassificationResult(MemoryClassifier.MemoryType.PROJECT_CONTEXT, 0.6, "关键词匹配");
        } else if (lower.contains("skill") || lower.contains("技能")) {
            return new ClassificationResult(MemoryClassifier.MemoryType.SKILL, 0.6, "关键词匹配");
        }

        return new ClassificationResult(MemoryClassifier.MemoryType.UNKNOWN, 0.3, "无法解析");
    }

    /**
     * 截断过长的内容
     */
    private String truncateContent(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...(内容已截断)";
    }

    /**
     * 检查LLM服务是否可用
     */
    public boolean isAvailable() {
        return llmClient != null && llmClient.isHealthy();
    }

    /**
     * 分类结果
     */
    public static class ClassificationResult {
        private final MemoryClassifier.MemoryType type;
        private final double confidence;
        private final String reasoning;

        public ClassificationResult(MemoryClassifier.MemoryType type, double confidence, String reasoning) {
            this.type = type;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }

        public MemoryClassifier.MemoryType getType() { return type; }
        public double getConfidence() { return confidence; }
        public String getReasoning() { return reasoning; }

        @Override
        public String toString() {
            return "LLMResult{type=" + type + ", confidence=" + confidence + ", reasoning=" + reasoning + "}";
        }
    }
}