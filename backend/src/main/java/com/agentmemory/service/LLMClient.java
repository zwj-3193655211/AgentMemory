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
import java.util.*;

/**
 * LLM 客户端
 * 支持多种 Provider：本地模型 (Ollama/Qwen)、OpenAI、DeepSeek 等
 */
public class LLMClient {

    private static final Logger log = LoggerFactory.getLogger(LLMClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 当前使用的 Provider
    private LLMProvider provider;

    // Provider 配置
    private String providerName;
    private String baseUrl;
    private String apiKey;
    private String model;
    private boolean thinkMode = false;  // 思考模式，默认关闭

    public LLMClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();

        // 默认使用本地 embedding_service（OpenAI 兼容端点）
        setProvider("openai", "http://localhost:8100/v1", null, "local");
    }

    /**
     * 设置 LLM Provider
     */
    public void setProvider(String providerName, String baseUrl, String apiKey, String model) {
        setProvider(providerName, baseUrl, apiKey, model, false);
    }
    
    /**
     * 设置 LLM Provider（带思考模式）
     */
    public void setProvider(String providerName, String baseUrl, String apiKey, String model, boolean thinkMode) {
        this.providerName = providerName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.thinkMode = thinkMode;

        this.provider = createProvider(providerName);
        log.info("LLM Provider 已切换到: {} (model: {}, thinkMode: {})", providerName, model, thinkMode);
    }

    /**
     * 根据 Provider 名称创建对应的 Provider 实例
     */
    private LLMProvider createProvider(String providerName) {
        return switch (providerName.toLowerCase()) {
            case "local", "ollama" -> new OllamaProvider(baseUrl, model, thinkMode);
            case "huggingface", "hf" -> new HuggingFaceProvider(baseUrl, apiKey, model);
            case "openai" -> new OpenAIProvider(baseUrl, apiKey, model);
            case "deepseek" -> new DeepSeekProvider(baseUrl, apiKey, model);
            default -> {
                log.warn("未知的 LLM Provider: {}，使用 HuggingFace", providerName);
                yield new HuggingFaceProvider("https://api-inference.huggingface.co", null, "Qwen/Qwen3-0.6B");
            }
        };
    }

    /**
     * 生成摘要
     * @param messages 会话消息列表
     * @return 生成的摘要
     */
    public String summarize(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        // 构建提示词
        String prompt = buildSummarizePrompt(messages);

        try {
            return provider.generate(prompt);
        } catch (Exception e) {
            log.error("生成摘要失败", e);
            return generateFallbackSummary(messages);
        }
    }

    /**
     * 生成摘要（带系统提示）
     */
    public String summarize(List<String> messages, String systemPrompt) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        String prompt = buildSummarizePrompt(messages);

        try {
            return provider.generateWithSystemPrompt(systemPrompt, prompt);
        } catch (Exception e) {
            log.error("生成摘要失败", e);
            return generateFallbackSummary(messages);
        }
    }

    /**
     * 构建摘要提示词
     */
    private String buildSummarizePrompt(List<String> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("请分析以下对话内容，生成简洁的摘要。摘要应包含：\n");
        sb.append("1. 对话的主要主题和目的\n");
        sb.append("2. 关键的技术决策或问题解决方案\n");
        sb.append("3. 重要的信息或偏好\n\n");
        sb.append("对话内容：\n\n");

        for (int i = 0; i < messages.size(); i++) {
            sb.append("--- 消息 ").append(i + 1).append(" ---\n");
            sb.append(messages.get(i)).append("\n\n");
        }

        sb.append("\n请生成不超过 200 字的摘要：");
        return sb.toString();
    }

    /**
     * 回退方案：使用规则生成简单摘要
     */
    private String generateFallbackSummary(List<String> messages) {
        if (messages.isEmpty()) {
            return "";
        }

        // 简单策略：取前3条和后3条消息的关键词
        StringBuilder summary = new StringBuilder();
        summary.append("对话包含 ").append(messages.size()).append(" 条消息。");

        // 提取关键信息
        Set<String> keyPhrases = new LinkedHashSet<>();
        for (String msg : messages) {
            // 提取技术关键词
            if (msg.contains("错误") || msg.contains("error")) {
                keyPhrases.add("包含错误处理");
            }
            if (msg.contains("优化") || msg.contains("优化")) {
                keyPhrases.add("涉及性能优化");
            }
            if (msg.contains("安装") || msg.contains("配置")) {
                keyPhrases.add("涉及环境配置");
            }
            if (msg.contains("推荐") || msg.contains("建议")) {
                keyPhrases.add("包含建议推荐");
            }
        }

        if (!keyPhrases.isEmpty()) {
            summary.append(" 关键主题：").append(String.join("、", keyPhrases));
        }

        return summary.toString();
    }

    /**
     * 检查 LLM 服务是否可用
     */
    public boolean isHealthy() {
        return provider != null && provider.isHealthy();
    }

    public String getProviderName() {
        return providerName;
    }

    public String getModel() {
        return model;
    }

    // ===== LLM Provider 接口 =====

    interface LLMProvider {
        String generate(String prompt) throws IOException, InterruptedException;
        String generateWithSystemPrompt(String systemPrompt, String userPrompt) throws IOException, InterruptedException;
        boolean isHealthy();
    }

    // ===== Ollama/本地模型 Provider =====

    static class OllamaProvider implements LLMProvider {
        private final String baseUrl;
        private final String model;
        private final boolean thinkMode;
        private final HttpClient httpClient;
        private final ObjectMapper objectMapper;

        OllamaProvider(String baseUrl, String model, boolean thinkMode) {
            this.baseUrl = baseUrl;
            this.model = model;
            this.thinkMode = thinkMode;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(60))
                    .build();
            this.objectMapper = new ObjectMapper();
        }

        @Override
        public String generate(String prompt) throws IOException, InterruptedException {
            return generateWithSystemPrompt(
                "你是一个专业的技术文档助手，擅长总结对话内容。",
                prompt
            );
        }

        @Override
        public String generateWithSystemPrompt(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("stream", false);
            
            // 添加思考模式参数（Qwen3.5 支持）
            // think: true 开启思考模式（显示推理过程）
            // think: false 关闭思考模式（直接输出答案）
            requestBody.put("think", thinkMode);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));
            requestBody.put("messages", messages);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                return json.path("message").path("content").asText("");
            } else {
                throw new IOException("Ollama 请求失败: " + response.statusCode() + " - " + response.body());
            }
        }

        @Override
        public boolean isHealthy() {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/tags"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ===== OpenAI Provider =====

    static class OpenAIProvider implements LLMProvider {
        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final HttpClient httpClient;
        private final ObjectMapper objectMapper;

        OpenAIProvider(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl != null ? baseUrl : "https://api.openai.com/v1";
            this.apiKey = apiKey;
            this.model = model != null ? model : "gpt-3.5-turbo";
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            this.objectMapper = new ObjectMapper();
        }

        @Override
        public String generate(String prompt) throws IOException, InterruptedException {
            return generateWithSystemPrompt(
                "你是一个专业的技术文档助手，擅长总结对话内容。",
                prompt
            );
        }

        @Override
        public String generateWithSystemPrompt(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("stream", false);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));
            requestBody.put("messages", messages);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(60));
            
            // 只在 apiKey 存在时添加 Authorization header
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }
            
            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                return json.path("choices").get(0).path("message").path("content").asText("");
            } else {
                throw new IOException("OpenAI 请求失败: " + response.statusCode() + " - " + response.body());
            }
        }

        @Override
        public boolean isHealthy() {
            // 本地服务不需要 apiKey
            return baseUrl != null && baseUrl.contains("localhost");
        }
    }

    // ===== DeepSeek Provider =====

    static class DeepSeekProvider implements LLMProvider {
        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final HttpClient httpClient;
        private final ObjectMapper objectMapper;

        DeepSeekProvider(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl != null ? baseUrl : "https://api.deepseek.com/v1";
            this.apiKey = apiKey;
            this.model = model != null ? model : "deepseek-chat";
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            this.objectMapper = new ObjectMapper();
        }

        @Override
        public String generate(String prompt) throws IOException, InterruptedException {
            return generateWithSystemPrompt(
                "你是一个专业的技术文档助手，擅长总结对话内容。",
                prompt
            );
        }

        @Override
        public String generateWithSystemPrompt(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("stream", false);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));
            requestBody.put("messages", messages);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                return json.path("choices").get(0).path("message").path("content").asText("");
            } else {
                throw new IOException("DeepSeek 请求失败: " + response.statusCode() + " - " + response.body());
            }
        }

        @Override
        public boolean isHealthy() {
            return apiKey != null && !apiKey.isEmpty();
        }
    }

    // ===== HuggingFace Provider =====
    static class HuggingFaceProvider implements LLMProvider {
        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final HttpClient httpClient;
        private final ObjectMapper objectMapper;

        HuggingFaceProvider(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(60))
                    .build();
            this.objectMapper = new ObjectMapper();
        }

        @Override
        public String generate(String prompt) throws IOException, InterruptedException {
            return generateWithSystemPrompt(
                "你是一个专业的技术文档助手，擅长总结对话内容。",
                prompt
            );
        }

        @Override
        public String generateWithSystemPrompt(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
            // HuggingFace Inference API 格式
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("inputs", "System: " + systemPrompt + "\n\nUser: " + userPrompt + "\n\nAssistant:");
            requestBody.put("parameters", Map.of(
                "max_new_tokens", 512,
                "temperature", 0.7f,
                "return_full_text", false
            ));

            String requestJson = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models/" + model))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                // 解析 HuggingFace 返回格式
                JsonNode generatedText = root.path(0).path("generated_text");
                if (generatedText.isMissingNode()) {
                    // 尝试其他格式
                    generatedText = root.path("generated_text");
                }
                String result = generatedText.asText("");
                // 提取实际回复内容（去掉输入部分）
                int assistantIdx = result.lastIndexOf("Assistant:");
                if (assistantIdx >= 0) {
                    result = result.substring(assistantIdx + 9).trim();
                }
                return result;
            } else {
                throw new IOException("HuggingFace 请求失败: " + response.statusCode() + " - " + response.body());
            }
        }

        @Override
        public boolean isHealthy() {
            // HuggingFace 可以不需要 API key（免费限额）
            return true;
        }
    }
}
