package com.agentmemory.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentmemory.service.MemoryClassifier.MemoryType;
import com.agentmemory.service.MemoryExtractor.ExtractedMemory;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 记忆管理服务
 * 整合分类、提取、存储流程
 */
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    
    // 允许的表名白名单（防止 SQL 注入）
    private static final Set<String> ALLOWED_TABLES = Set.of(
        "experiences",
        "user_profiles",
        "skills"
    );

    private final DatabaseService databaseService;
    private final EmbeddingClient embeddingClient;
    private final MemoryClassifier classifier;
    private final MemoryExtractor extractor;
    private final ObjectMapper objectMapper;
    private final HybridMemoryClassifier hybridClassifier;
    private final LLMClient llmClient;
    private volatile boolean useHybridClassifier = true;

    // 错误计数器（用于监控）
    private volatile int embeddingFailureCount = 0;
    private volatile int databaseFailureCount = 0;
    private static final int FAILURE_THRESHOLD = 10;
    
    // 正在处理的标题集合（用于并发去重）
    private final Set<String> processingTitles = ConcurrentHashMap.newKeySet();
    
    /**
     * 验证表名是否在白名单中（防止 SQL 注入）
     */
    private boolean isValidTableName(String tableName) {
        return tableName != null && ALLOWED_TABLES.contains(tableName);
    }
    
    /**
     * 更新会话摘要
     */
    public void updateSessionSummary(String sessionId, String summary) {
        databaseService.updateSessionSummary(sessionId, summary);
    }

    public MemoryService(DatabaseService databaseService) {
        this.databaseService = databaseService;
        this.embeddingClient = new EmbeddingClient();
        this.classifier = new MemoryClassifier();
        this.extractor = new MemoryExtractor();
        this.objectMapper = new ObjectMapper();
        
        LLMClient llmClient = new LLMClient();
        this.llmClient = llmClient;
        this.hybridClassifier = new HybridMemoryClassifier(databaseService, embeddingClient, llmClient);
    }

    /**
     * 从数据库加载 LLM Provider 配置（ollama 等），需在数据库初始化完成后调用。
     * 不在此处配置时，默认指向本地 embedding_service，其 /v1 端点会返回 503 "LLM 未启用"。
     */
    public void configureLLM() {
        LLMConfigLoader.applyFromDatabase(llmClient, databaseService);
    }

    /**
     * 获取错误统计（用于监控）
     */
    public Map<String, Object> getErrorStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("embeddingFailures", embeddingFailureCount);
        stats.put("databaseFailures", databaseFailureCount);
        return stats;
    }
    
    /**
     * 启用/禁用混合分类器
     */
    public void setUseHybridClassifier(boolean enabled) {
        this.useHybridClassifier = enabled;
        log.info("混合分类器状态: {}", enabled ? "启用" : "禁用");
    }
    
    /**
     * 获取混合分类器状态
     */
    public boolean isUseHybridClassifier() {
        return useHybridClassifier;
    }
    
    /**
     * 获取混合分类器统计信息
     */
    public Map<String, Object> getHybridClassifierStats() {
        if (hybridClassifier != null) {
            Map<String, HybridMemoryClassifier.ClassificationStats> rawStats = hybridClassifier.getStats();
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<String, HybridMemoryClassifier.ClassificationStats> entry : rawStats.entrySet()) {
                result.put(entry.getKey(), entry.getValue().toMap());
            }
            return result;
        }
        return Map.of("error", "混合分类器未初始化");
    }
    
    /**
     * 处理消息，判断是否需要提取记忆（使用混合智能分类系统）
     */
    public void processMessage(String sessionId, String content, String agentType) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        
        // 使用混合智能分类系统
        MemoryType type = null;
        String classifyMethod = "unknown";
        
        if (useHybridClassifier && hybridClassifier != null) {
            HybridMemoryClassifier.ClassificationResult result = hybridClassifier.classify(content);
            type = result.getType();
            classifyMethod = result.getMethod();
            log.debug("混合分类结果: type={}, method={}, confidence={}", type, classifyMethod, result.getConfidence());
            
            if (type == MemoryType.UNKNOWN) {
                log.debug("混合分类返回 UNKNOWN，跳过");
                return;
            }
        } else {
            // 回退到传统分类方式
            type = classifier.classify(content);
            classifyMethod = "rule-fallback";
            
            if (type == MemoryType.UNKNOWN) {
                log.debug("消息未匹配任何记忆类型，跳过");
                return;
            }
            if (!classifier.isWorthRemembering(content, type)) {
                log.debug("消息不值得保存为记忆，跳过");
                return;
            }
        }
        
        // 尝试使用 LLM 提取结构化内容
        EmbeddingClient.ExtractResult llmResult = null;
        if (embeddingClient.isHealthy()) {
            llmResult = embeddingClient.extract(content);
        }
        
        ExtractedMemory memory;
        
        if (llmResult != null && !"SKIP".equals(llmResult.type) && llmResult.extracted != null) {
            try {
                type = MemoryType.valueOf(llmResult.type);
                memory = convertFromLLMResult(llmResult);
            } catch (IllegalArgumentException e) {
                memory = extractWithKeywords(content, type);
            }
        } else {
            memory = extractWithKeywords(content, type);
        }
        
        if (memory == null) {
            return;
        }
        
        // 语义去重
        if (isDuplicate(memory.title, type)) {
            log.debug("检测到相似记忆，跳过: {}", memory.title);
            return;
        }
        
        // 生成向量
        float[] embedding = null;
        if (embeddingClient.isHealthy()) {
            embedding = embeddingClient.embed(memory.title + " " + content);
        }
        
        // 存储
        saveMemory(memory, type, sessionId, agentType, embedding);
        
        log.info("已保存记忆 [{}]: {}", type.getDisplayName(), memory.title);
    }
    
    /**
     * 处理消息（带上下文）- 用于缓冲批量处理
     * 改进：使用完整上下文进行分类和提取
     */
    public void processMessageWithContext(String sessionId, String fullContext, String agentType) {
        if (fullContext == null || fullContext.trim().isEmpty()) {
            return;
        }
        
        // 提取当前消息（[当前] 标记之后的内容）
        String currentContent = "";
        int currentIndex = fullContext.indexOf("[当前] ");
        if (currentIndex >= 0) {
            currentContent = fullContext.substring(currentIndex + 7).trim();
        } else {
            // 没有上下文标记，直接使用全部内容
            currentContent = fullContext;
        }
        
        if (currentContent.isEmpty()) {
            return;
        }
        
        // 提取上下文（[上文] 部分）
        String contextContent = "";
        if (currentIndex > 0) {
            contextContent = fullContext.substring(0, currentIndex).trim();
        }
        
        // 使用 LLM 提取（优先）- 带完整上下文
        EmbeddingClient.ExtractResult llmResult = null;
        if (embeddingClient.isHealthy()) {
            llmResult = embeddingClient.extractWithContext(fullContext);
        }
        
        MemoryType type;
        ExtractedMemory memory;
        
        if (llmResult != null && !"SKIP".equals(llmResult.type) && llmResult.extracted != null) {
            try {
                type = MemoryType.valueOf(llmResult.type);
                memory = convertFromLLMResult(llmResult);
            } catch (IllegalArgumentException e) {
                log.warn("LLM 返回未知类型: {}, 使用混合分类器", llmResult.type);
                HybridMemoryClassifier.ClassificationResult result = hybridClassifier.classify(currentContent);
                type = result.getType();
                memory = extractWithKeywords(currentContent, type);
            }
        } else {
            if (useHybridClassifier && hybridClassifier != null) {
                HybridMemoryClassifier.ClassificationResult result = hybridClassifier.classify(currentContent);
                type = result.getType();
                if (type == MemoryType.UNKNOWN) {
                    log.debug("消息未匹配任何记忆类型，跳过");
                    return;
                }
                memory = extractWithKeywords(currentContent, type);
            } else {
                type = classifier.classify(currentContent);
                if (type == MemoryType.UNKNOWN) {
                    log.debug("消息未匹配任何记忆类型，跳过");
                    return;
                }
                if (!classifier.isWorthRemembering(currentContent, type)) {
                    log.debug("消息不值得保存为记忆，跳过");
                    return;
                }
                memory = extractWithKeywords(currentContent, type);
            }
        }
        
        if (memory == null) {
            return;
        }
        
        // 语义去重
        if (isDuplicate(memory.title, type)) {
            log.debug("检测到相似记忆，跳过: {}", memory.title);
            return;
        }
        
        // 生成向量
        float[] embedding = null;
        if (embeddingClient.isHealthy()) {
            embedding = embeddingClient.embed(memory.title + " " + currentContent);
        }
        
        // 存储
        saveMemory(memory, type, sessionId, agentType, embedding);
        
        log.info("已保存记忆 [带上下文 - {}]: {}", type.getDisplayName(), memory.title);
    }
    
    /**
     * 将 LLM 提取结果转换为 ExtractedMemory
     */
    private ExtractedMemory convertFromLLMResult(EmbeddingClient.ExtractResult result) {
        ExtractedMemory memory = new ExtractedMemory();
        memory.title = result.title;
        memory.tags = result.tags;
        
        var extracted = result.extracted;
        if (extracted == null) return memory;
        
        // 根据类型提取字段
        switch (result.type) {
            case "ERROR_CORRECTION" -> {
                // 用户纠正 Agent 的错误
                // mistake=Agent说错了什么, correction=用户的纠正, conclusion=正确结论
                memory.problem = extracted.has("mistake") ? extracted.get("mistake").asText() : "";
                memory.cause = ""; // 不再适用"根因"概念
                memory.solution = extracted.has("correction") ? extracted.get("correction").asText() : "";
                memory.description = extracted.has("conclusion") ? extracted.get("conclusion").asText() : "";
            }
            case "USER_PROFILE" -> {
                memory.description = extracted.has("preference") ? extracted.get("preference").asText() : "";
            }
            case "BEST_PRACTICE" -> {
                memory.scenario = extracted.has("scenario") ? extracted.get("scenario").asText() : "";
                memory.practice = extracted.has("practice") ? extracted.get("practice").asText() : "";
            }
            case "PROJECT_CONTEXT" -> {
                if (extracted.has("tech_stack") && extracted.get("tech_stack").isArray()) {
                    List<String> techStack = new ArrayList<>();
                    for (var t : extracted.get("tech_stack")) {
                        techStack.add(t.asText());
                    }
                    memory.extra.put("techStack", techStack);
                }
                if (extracted.has("project_name")) {
                    memory.extra.put("projectName", extracted.get("project_name").asText());
                }
                if (extracted.has("summary")) {
                    memory.extra.put("summary", extracted.get("summary").asText());
                    memory.description = extracted.get("summary").asText();
                }
                if (extracted.has("key_decisions")) {
                    memory.extra.put("keyDecisions", extracted.get("key_decisions").asText());
                }
                if (extracted.has("next_steps")) {
                    memory.extra.put("nextSteps", extracted.get("next_steps").asText());
                }
            }
            case "SKILL" -> {
                memory.description = extracted.has("skill_name") ? extracted.get("skill_name").asText() : "";
                if (extracted.has("steps") && extracted.get("steps").isArray()) {
                    List<String> steps = new ArrayList<>();
                    for (var s : extracted.get("steps")) {
                        steps.add(s.asText());
                    }
                    memory.steps = steps;
                }
            }
        }
        
        return memory;
    }
    
    /**
     * 关键词提取方法（回退）
     */
    private ExtractedMemory extractWithKeywords(String content, MemoryType type) {
        List<String> tags = classifier.extractTags(content);
        return extractMemory(content, type, tags);
    }

    /**
     * 根据类型提取记忆
     */
    private ExtractedMemory extractMemory(String content, MemoryType type, List<String> tags) {
        return switch (type) {
            case ERROR_CORRECTION -> extractor.extractErrorCorrection(content, tags);
            case USER_PROFILE -> extractor.extractUserProfile(content, tags);
            case BEST_PRACTICE -> extractor.extractBestPractice(content, tags);
            case PROJECT_CONTEXT -> extractor.extractProjectContext(content, tags);
            case SKILL -> extractor.extractSkill(content, tags);
            default -> null;
        };
    }
    
    /**
     * 检查是否重复（使用数据库向量相似度，批量处理）
     * 添加并发安全：使用 processingTitles 集合防止并发重复处理
     */
    private boolean isDuplicate(String title, MemoryType type) {
        // 快速路径：检查是否正在处理相同标题
        String titleKey = title.hashCode() + "_" + type.name();
        if (!processingTitles.add(titleKey)) {
            log.debug("已有其他线程在处理相同标题，跳过: {}", title);
            return true;  // 已有其他线程在处理
        }
        
        try {
            if (!embeddingClient.isHealthy()) {
                return false;
            }

            // 先生成查询向量
            float[] queryEmbedding = embeddingClient.embed(title);
            if (queryEmbedding == null) {
                return false;
            }
            
            // 获取表名并验证（防止 SQL 注入）；PROJECT_CONTEXT 不单独存储，无对应表，跳过去重
            String tableName = type.getTableName();
            if (tableName == null) {
                return false;
            }
            if (!isValidTableName(tableName)) {
                log.error("非法表名: {}", tableName);
                return false;
            }

            try (Connection conn = databaseService.getConnection()) {
                String vecStr = toArrayString(queryEmbedding);

                // 使用数据库的向量相似度计算（比API调用更快）
                // 表名已通过白名单验证，可以安全使用
                String sql = String.format("""
                    SELECT title, 1 - (embedding <=> ?::vector) as similarity
                    FROM %s
                    WHERE embedding IS NOT NULL AND (deleted = false OR deleted IS NULL)
                    ORDER BY similarity DESC
                    LIMIT 10
                    """, tableName);

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, vecStr);
                    try (ResultSet rs = stmt.executeQuery()) {
                        // 检查最相似的一个是否超过阈值
                        if (rs.next()) {
                            float similarity = rs.getFloat("similarity");
                            if (similarity > 0.9f) {
                                String existingTitle = rs.getString("title");
                                log.debug("发现相似记忆: {} (相似度: {:.2f})", existingTitle, similarity);
                                return true;
                            }
                        }
                    }
                }

            } catch (SQLException e) {
                log.error("检查重复失败", e);
            }

            return false;
        } finally {
            // 处理完成后移除标记
            processingTitles.remove(titleKey);
        }
    }
    
    /**
     * 保存记忆到数据库
     */
    private void saveMemory(ExtractedMemory memory, MemoryType type, 
                           String sessionId, String agentType, float[] embedding) {
        String tableName = type.getTableName();
        
        // PROJECT_CONTEXT 并入会话管理，不单独存储，直接返回（不记错误日志）
        if (tableName == null) {
            return;
        }
        
        // 验证表名（防止 SQL 注入）
        if (!isValidTableName(tableName)) {
            log.error("非法表名: {}", tableName);
            return;
        }
        
        String id = UUID.randomUUID().toString();
        
        try (Connection conn = databaseService.getConnection()) {
            switch (type) {
                case ERROR_CORRECTION -> saveExperience(conn, id, memory, "error_correction", sessionId, embedding);
                case USER_PROFILE -> saveUserProfile(conn, id, memory);
                case BEST_PRACTICE -> saveExperience(conn, id, memory, "best_practice", sessionId, embedding);
                case PROJECT_CONTEXT -> { /* 项目上下文并入会话管理，不再单独存储 */ }
                case SKILL -> saveSkill(conn, id, memory, embedding);
                default -> {}
            }
        } catch (SQLException e) {
            log.error("保存记忆失败: {}", memory.title, e);
        }
    }
    
    /**
     * 公共方法：设置基本字段（id, title, tags）
     */
    private void setBasicFields(PreparedStatement stmt, Connection conn, 
                                String id, String title, List<String> tags, int startIndex) throws SQLException {
        stmt.setString(startIndex, id);
        stmt.setString(startIndex + 1, title);
        stmt.setArray(startIndex + 2, conn.createArrayOf("text", tags != null ? tags.toArray() : new String[]{}));
    }
    
    /**
     * 公共方法：设置向量字段
     */
    private void setEmbeddingField(PreparedStatement stmt, float[] embedding, int index) throws SQLException {
        if (embedding != null) {
            stmt.setString(index, toArrayString(embedding));
        }
    }
    
    private void saveUserProfile(Connection conn, String id, ExtractedMemory memory) throws SQLException {
        String sql = "INSERT INTO user_profiles (id, title, category, items) VALUES (?, ?, ?, ?::jsonb)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, memory.title);
            stmt.setString(3, "preference");
            String escapedValue = escapeJsonString(memory.description);
            String jsonValue = String.format("[{\"key\":\"preference\",\"value\":%s}]", escapedValue);
            stmt.setString(4, jsonValue);
            stmt.executeUpdate();
        }
    }
    
    private void saveExperience(Connection conn, String id, ExtractedMemory memory,
                                 String type, String sessionId, float[] embedding) throws SQLException {
        String sql = embedding != null
            ? "INSERT INTO experiences (id, title, type, scenario, practice, rationale, tags, source_session, embedding) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector)"
            : "INSERT INTO experiences (id, title, type, scenario, practice, rationale, tags, source_session) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, memory.title);
            stmt.setString(3, type);
            // scenario: 错误纠正用 problem，最佳实践用 scenario
            String scenario = "error_correction".equals(type)
                ? (memory.problem != null ? memory.problem : "")
                : (memory.scenario != null ? memory.scenario : "");
            stmt.setString(4, scenario);
            // practice: 错误纠正用 solution，最佳实践用 practice
            String practice = "error_correction".equals(type)
                ? (memory.solution != null ? memory.solution : "")
                : (memory.practice != null ? memory.practice : "");
            stmt.setString(5, practice);
            // rationale: 错误纠正用 cause，最佳实践无独立字段用 description 兜底
            String rationale = "error_correction".equals(type) ? memory.cause : memory.description;
            stmt.setString(6, rationale);
            stmt.setArray(7, conn.createArrayOf("text", memory.tags.toArray()));
            stmt.setString(8, sessionId);
            if (embedding != null) {
                stmt.setString(9, toArrayString(embedding));
            }
            stmt.executeUpdate();
        }
    }

    private void saveSkill(Connection conn, String id, ExtractedMemory memory, float[] embedding) throws SQLException {
        String sql = embedding != null
            ? "INSERT INTO skills (id, title, skill_type, description, tags, embedding, status, extracted_by) VALUES (?, ?, ?, ?, ?, ?::vector, 'pending', 'llm')"
            : "INSERT INTO skills (id, title, skill_type, description, tags, status, extracted_by) VALUES (?, ?, ?, ?, ?, 'pending', 'llm')";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, memory.title);
            stmt.setString(3, "general");
            stmt.setString(4, memory.description);
            stmt.setArray(5, conn.createArrayOf("text", memory.tags.toArray()));
            if (embedding != null) {
                stmt.setString(6, toArrayString(embedding));
            }
            stmt.executeUpdate();
        }
    }
    
    /**
     * 将 float[] 转换为 PostgreSQL 数组字符串
     */
    private String toArrayString(float[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 安全转义 JSON 字符串
     * 使用 ObjectMapper 确保正确转义所有特殊字符
     */
    private String escapeJsonString(String text) {
        if (text == null) {
            return "";
        }
        try {
            // 使用 ObjectMapper 自动转义所有特殊字符
            return objectMapper.writeValueAsString(text);
        } catch (Exception e) {
            log.warn("JSON 转义失败，使用基础转义: {}", e.getMessage());
            // 降级到手动转义
            return text.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t")
                       .replace("\b", "\\b")
                       .replace("\f", "\\f");
        }
    }
    
    /**
     * 通用的保存记忆方法（供SessionProcessor使用）
     */
    public void saveMemory(String tableName, String title, String description,
                          List<String> tags, String sessionId, String projectName) {
        if (tableName == null || title == null) {
            return;
        }

        // 构建临时 ExtractedMemory 对象并复用保存逻辑
        ExtractedMemory memory = new ExtractedMemory();
        memory.title = title;
        memory.description = description;
        memory.tags = tags != null ? tags : new ArrayList<>();

        // 映射 tableName 到 MemoryType
        MemoryType type = switch (tableName) {
            case "error_corrections" -> MemoryType.ERROR_CORRECTION;
            case "user_profiles" -> MemoryType.USER_PROFILE;
            case "best_practices" -> MemoryType.BEST_PRACTICE;
            case "project_contexts" -> MemoryType.PROJECT_CONTEXT;
            case "skills" -> MemoryType.SKILL;
            default -> null;
        };

        if (type == null) {
            log.warn("未知的表名: {}", tableName);
            return;
        }

        // 处理特殊字段
        if (type == MemoryType.PROJECT_CONTEXT && projectName != null) {
            memory.extra = new HashMap<>();
            memory.extra.put("paths", List.of(projectName));
        }

        // 委托给私有方法处理
        saveMemory(memory, type, sessionId, null, null);
    }
    
    /**
     * 搜索相似记忆
     */
    public List<String> searchSimilar(String query, MemoryType type, int limit) {
        List<String> results = new ArrayList<>();
        
        // 验证表名（防止 SQL 注入）；PROJECT_CONTEXT 无对应记忆表，返回空结果
        String tableName = type.getTableName();
        if (tableName == null) {
            return results;
        }
        if (!isValidTableName(tableName)) {
            log.error("非法表名: {}", tableName);
            return results;
        }
        
        if (!embeddingClient.isHealthy()) {
            return results;
        }
        
        // 生成查询向量
        float[] queryEmbedding = embeddingClient.embed(query);
        if (queryEmbedding == null) {
            return results;
        }
        
        try (Connection conn = databaseService.getConnection()) {
            // 表名已通过白名单验证，可以安全使用
            String sql = """
                SELECT title, 1 - (embedding <=> ?::vector) as similarity
                FROM %s
                WHERE deleted = false
                ORDER BY similarity DESC
                LIMIT ?
                """.formatted(tableName);
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, toArrayString(queryEmbedding));
                stmt.setInt(2, limit);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String title = rs.getString("title");
                        float similarity = rs.getFloat("similarity");
                        results.add(String.format("[%.2f] %s", similarity, title));
                    }
                }
            }
            
        } catch (SQLException e) {
            log.error("搜索相似记忆失败", e);
        }
        
        return results;
    }
}
