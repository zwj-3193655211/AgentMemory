package com.agentmemory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入服务客户端
 * 调用 Python 嵌入服务生成文本向量
 */
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final int maxRetries;
    private final long retryDelayMs;

    public EmbeddingClient() {
        this("http://127.0.0.1:8100");
    }

    public EmbeddingClient(String baseUrl) {
        this(baseUrl, 3, 1000);
    }

    public EmbeddingClient(String baseUrl, int maxRetries, long retryDelayMs) {
        this.baseUrl = baseUrl;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 通用重试方法
     */
    private <T> T executeWithRetry(String operation, RetryableCallback<T> callback) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                return callback.execute();
            } catch (Exception e) {
                lastException = e;
                attempt++;

                if (attempt > maxRetries) {
                    log.error("{} 失败，已达到最大重试次数 ({})", operation, maxRetries, e);
                    break;
                }

                long delay = retryDelayMs * attempt;  // 指数退避
                log.warn("{} 失败，{} ms 后重试 ({}/{}): {}",
                    operation, delay, attempt, maxRetries, e.getMessage());

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试被中断", ie);
                }
            }
        }

        throw new RuntimeException(operation + " 失败: " + lastException.getMessage(), lastException);
    }

    @FunctionalInterface
    private interface RetryableCallback<T> {
        T execute() throws Exception;
    }
    
    /**
     * 检查服务是否可用
     */
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("嵌入服务不可用: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 生成单个文本的向量
     */
    public float[] embed(String text) {
        List<float[]> results = embed(List.of(text));
        return results.isEmpty() ? null : results.get(0);
    }
    
    /**
     * 批量生成向量（带重试）
     */
    public List<float[]> embed(List<String> texts) {
        try {
            return executeWithRetry("生成向量", () -> {
                String requestBody = objectMapper.writeValueAsString(new EmbedRequest(texts));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/embed"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("嵌入服务返回错误: " + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode embeddingsNode = root.get("embeddings");

                List<float[]> results = new ArrayList<>();
                for (JsonNode embedding : embeddingsNode) {
                    float[] vector = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        vector[i] = (float) embedding.get(i).asDouble();
                    }
                    results.add(vector);
                }

                return results;
            });
        } catch (RuntimeException e) {
            log.error("生成向量失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 计算两个文本的相似度
     */
    public float similarity(String text1, String text2) {
        try {
            String requestBody = objectMapper.writeValueAsString(
                new SimilarityRequest(text1, text2));
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/similarity"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                return 0f;
            }
            
            JsonNode root = objectMapper.readTree(response.body());
            return (float) root.get("similarity").asDouble();
            
        } catch (Exception e) {
            log.error("计算相似度失败", e);
            return 0f;
        }
    }
    
    /**
     * 批量计算查询与候选的相似度
     */
    public List<Float> batchSimilarity(String query, List<String> candidates) {
        try {
            String requestBody = objectMapper.writeValueAsString(
                new BatchSimilarityRequest(query, candidates));
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/batch_similarity"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                return new ArrayList<>();
            }
            
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode similaritiesNode = root.get("similarities");
            
            List<Float> results = new ArrayList<>();
            for (JsonNode sim : similaritiesNode) {
                results.add((float) sim.asDouble());
            }
            
            return results;
            
        } catch (Exception e) {
            log.error("批量计算相似度失败", e);
            return new ArrayList<>();
        }
    }
    
    private static class EmbedRequest {
        public List<String> texts;
        public EmbedRequest(List<String> texts) { this.texts = texts; }
    }
    
    private static class SimilarityRequest {
        public String text1;
        public String text2;
        public SimilarityRequest(String text1, String text2) {
            this.text1 = text1;
            this.text2 = text2;
        }
    }
    
    private static class BatchSimilarityRequest {
        public String query;
        public List<String> candidates;
        public BatchSimilarityRequest(String query, List<String> candidates) {
            this.query = query;
            this.candidates = candidates;
        }
    }
    
    /**
     * LLM 提取结果
     */
    public static class ExtractResult {
        public String type;
        public String title;
        public List<String> tags;
        public JsonNode extracted;
        public String reason;
    }
    
    /**
     * 使用 LLM 提取结构化记忆（带重试）
     */
    public ExtractResult extract(String content) {
        try {
            return executeWithRetry("LLM 提取", () -> {
                String requestBody = objectMapper.writeValueAsString(
                    new ExtractRequest(content));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/extract"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("LLM 提取服务返回错误: " + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());

                ExtractResult result = new ExtractResult();
                result.type = root.has("type") ? root.get("type").asText() : "SKIP";
                result.title = root.has("title") ? root.get("title").asText() : "";
                result.reason = root.has("reason") ? root.get("reason").asText() : null;
                result.extracted = root.has("extracted") ? root.get("extracted") : null;

                result.tags = new ArrayList<>();
                if (root.has("tags") && root.get("tags").isArray()) {
                    for (JsonNode tag : root.get("tags")) {
                        result.tags.add(tag.asText());
                    }
                }

                return result;
            });
        } catch (RuntimeException e) {
            log.error("LLM 提取失败", e);
            return null;
        }
    }
    
    private static class ExtractRequest {
        public String content;
        public ExtractRequest(String content) { this.content = content; }
    }
    
    /**
     * 带上下文的 LLM 提取（用于缓冲批量处理）
     * 发送完整上下文，让 LLM 理解对话的完整情况
     */
    public ExtractResult extractWithContext(String fullContext) {
        try {
            return executeWithRetry("LLM 上下文提取", () -> {
                String requestBody = objectMapper.writeValueAsString(
                    new ExtractWithContextRequest(fullContext));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/extract_with_context"))
                        .timeout(Duration.ofSeconds(90))  // 上下文更长，需要更长的超时
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    throw new RuntimeException("LLM 上下文提取服务返回错误: " + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());

                ExtractResult result = new ExtractResult();
                result.type = root.has("type") ? root.get("type").asText() : "SKIP";
                result.title = root.has("title") ? root.get("title").asText() : "";
                result.reason = root.has("reason") ? root.get("reason").asText() : null;
                result.extracted = root.has("extracted") ? root.get("extracted") : null;

                result.tags = new ArrayList<>();
                if (root.has("tags") && root.get("tags").isArray()) {
                    for (JsonNode tag : root.get("tags")) {
                        result.tags.add(tag.asText());
                    }
                }

                return result;
            });
        } catch (RuntimeException e) {
            log.error("LLM 上下文提取失败", e);
            return null;
        }
    }
    
    private static class ExtractWithContextRequest {
        public String content;     // 当前消息
        public String context;     // 上下文内容（[上文] 和 [当前] 标记）
        
        public ExtractWithContextRequest(String context) {
            this.context = context;
            // 从 context 中提取当前消息
            int currentIndex = context.indexOf("[当前] ");
            if (currentIndex >= 0) {
                this.content = context.substring(currentIndex + 7).trim();
            } else {
                this.content = context;
            }
        }
    }
    
    /**
     * 使用 LLM 对消息进行分类
     * @param content 消息内容
     * @return 分类结果，如 ERROR_CORRECTION, USER_PROFILE 等
     */
    public String classifyWithLLM(String content) {
        try {
            return executeWithRetry("LLM分类", () -> {
                String requestBody = objectMapper.writeValueAsString(
                    new ClassifyRequest(content));
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/classify"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    throw new RuntimeException("LLM分类服务返回错误: " + response.body());
                }
                
                JsonNode root = objectMapper.readTree(response.body());
                return root.has("type") ? root.get("type").asText() : "UNKNOWN";
            });
        } catch (Exception e) {
            log.error("LLM分类失败: {}", e.getMessage());
            return null;  // 返回 null 表示分类失败，使用规则方法
        }
    }
    
    private static class ClassifyRequest {
        public String content;
        public ClassifyRequest(String content) { this.content = content; }
    }
}