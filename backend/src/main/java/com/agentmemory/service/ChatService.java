package com.agentmemory.service;

import com.agentmemory.AgentInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 对话服务
 * 支持两种模式：
 * 1. LLM API 模式 — 调用 OpenAI 兼容 API（Ollama/DeepSeek/OpenAI 等）
 * 2. CLI 模式 — 调用本地 CLI（claude/qwen/crush 等）
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final DatabaseService databaseService;
    private final AgentDetectorService agentDetectorService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService chatExecutor;

    // 内存中的活跃对话（key: sessionId, value: 消息历史）
    private final ConcurrentHashMap<String, List<Map<String, String>>> activeSessions = new ConcurrentHashMap<>();

    public ChatService(DatabaseService databaseService, AgentDetectorService agentDetectorService) {
        this.databaseService = databaseService;
        this.agentDetectorService = agentDetectorService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.chatExecutor = Executors.newCachedThreadPool();
    }

    // ===== 可用 Agent 列表 =====

    /**
     * 获取所有可用的对话 Agent
     * 合并 LLM providers + CLI agents
     */
    public List<Map<String, Object>> getAvailableAgents() {
        List<Map<String, Object>> agents = new ArrayList<>();

        // 1. 从数据库加载 LLM providers
        try (Connection conn = databaseService.getConnection()) {
            String sql = "SELECT id, provider_name, display_name, base_url, model, enabled " +
                    "FROM llm_providers WHERE enabled = true ORDER BY is_default DESC";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> agent = new HashMap<>();
                    agent.put("id", "llm-" + rs.getString("id"));
                    agent.put("type", "llm");
                    agent.put("name", rs.getString("display_name"));
                    agent.put("providerName", rs.getString("provider_name"));
                    agent.put("model", rs.getString("model"));
                    agent.put("baseUrl", rs.getString("base_url"));
                    agent.put("apiKey", rs.getString("api_key"));
                    agents.add(agent);
                }
            }
        } catch (SQLException e) {
            log.error("加载 LLM providers 失败", e);
        }

        // 2. 检测本地 CLI agents
        if (agentDetectorService != null) {
            List<AgentInfo> cliAgents = agentDetectorService.detectAgents();
            for (AgentInfo info : cliAgents) {
                Map<String, Object> agent = new HashMap<>();
                agent.put("id", "cli-" + info.getType());
                agent.put("type", "cli");
                agent.put("name", info.getName());
                agent.put("command", info.getType());  // CLI 命令名使用 type 字段
                agents.add(agent);
            }
        }

        return agents;
    }

    // ===== 会话管理 =====

    /**
     * 创建新会话
     */
    public Map<String, Object> createSession(String agentId, String agentName, String agentType) {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = databaseService.getConnection()) {
            String sql = "INSERT INTO chat_sessions (id, agent_type, agent_name) VALUES (?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, sessionId);
                stmt.setString(2, agentType);
                stmt.setString(3, agentName);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("创建会话失败", e);
            throw new RuntimeException("创建会话失败: " + e.getMessage());
        }

        // 初始化内存中的消息历史
        activeSessions.put(sessionId, new ArrayList<>());

        Map<String, Object> session = new HashMap<>();
        session.put("id", sessionId);
        session.put("agentId", agentId);
        session.put("agentName", agentName);
        session.put("agentType", agentType);
        session.put("title", "新对话");
        session.put("messages", List.of());
        return session;
    }

    /**
     * 获取会话列表
     */
    public List<Map<String, Object>> getSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();

        try (Connection conn = databaseService.getConnection()) {
            String sql = "SELECT id, agent_type, agent_name, title, created_at, updated_at " +
                    "FROM chat_sessions WHERE (deleted = false OR deleted IS NULL) " +
                    "ORDER BY updated_at DESC LIMIT 50";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> session = new HashMap<>();
                    session.put("id", rs.getString("id"));
                    session.put("agentType", rs.getString("agent_type"));
                    session.put("agentName", rs.getString("agent_name"));
                    session.put("title", rs.getString("title"));
                    session.put("createdAt", rs.getTimestamp("created_at").toString());
                    session.put("updatedAt", rs.getTimestamp("updated_at").toString());
                    sessions.add(session);
                }
            }
        } catch (SQLException e) {
            log.error("获取会话列表失败", e);
        }

        return sessions;
    }

    /**
     * 获取会话消息
     */
    public List<Map<String, Object>> getSessionMessages(String sessionId) {
        List<Map<String, Object>> messages = new ArrayList<>();

        try (Connection conn = databaseService.getConnection()) {
            String sql = "SELECT id, role, content, created_at FROM chat_messages " +
                    "WHERE session_id = ? ORDER BY created_at ASC";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, sessionId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> msg = new HashMap<>();
                        msg.put("id", rs.getString("id"));
                        msg.put("role", rs.getString("role"));
                        msg.put("content", rs.getString("content"));
                        msg.put("createdAt", rs.getTimestamp("created_at").toString());
                        messages.add(msg);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("获取会话消息失败", e);
        }

        return messages;
    }

    /**
     * 删除会话（软删除）
     */
    public void deleteSession(String sessionId) {
        try (Connection conn = databaseService.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE chat_sessions SET deleted = true WHERE id = ?")) {
                stmt.setString(1, sessionId);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("删除会话失败", e);
        }
        activeSessions.remove(sessionId);
    }

    // ===== 发送消息 =====

    /**
     * 发送消息（LLM API 模式）
     * 返回一个 Future，在线程中执行流式请求
     */
    public Future<Void> sendMessageLLM(String sessionId, String userMessage,
                                        String baseUrl, String apiKey, String model,
                                        OutputStream outputStream) {
        return chatExecutor.submit(() -> {
            try {
                // 保存用户消息
                saveMessage(sessionId, "user", userMessage);

                // 构建消息历史
                List<Map<String, String>> history = activeSessions.computeIfAbsent(
                        sessionId, k -> loadHistoryFromDB(sessionId));
                history.add(Map.of("role", "user", "content", userMessage));

                // 调用 LLM API（流式）
                String fullResponse = streamLLMChat(history, baseUrl, apiKey, model, outputStream);

                // 保存 AI 回复
                history.add(Map.of("role", "assistant", "content", fullResponse));
                saveMessage(sessionId, "assistant", fullResponse);

                // 更新会话标题（取首条用户消息前 30 字符）
                updateSessionTitle(sessionId, userMessage);

                return null;
            } catch (Exception e) {
                log.error("LLM 对话失败", e);
                try {
                    String error = "对话出错: " + e.getMessage();
                    outputStream.write(("event: error\ndata: " + objectMapper.writeValueAsString(
                            Map.of("content", error)) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException ignored) {}
                return null;
            }
        });
    }

    /**
     * 发送消息（CLI 模式）
     */
    public Future<Void> sendMessageCLI(String sessionId, String userMessage,
                                       String command,
                                       OutputStream outputStream) {
        return chatExecutor.submit(() -> {
            try {
                // 保存用户消息
                saveMessage(sessionId, "user", userMessage);

                // 调用 CLI
                String fullResponse = executeCLI(command, userMessage, outputStream);

                // 保存 AI 回复
                saveMessage(sessionId, "assistant", fullResponse);
                updateSessionTitle(sessionId, userMessage);

                return null;
            } catch (Exception e) {
                log.error("CLI 对话失败", e);
                try {
                    String error = "CLI 调用出错: " + e.getMessage();
                    outputStream.write(("event: error\ndata: " + objectMapper.writeValueAsString(
                            Map.of("content", error)) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException ignored) {}
                return null;
            }
        });
    }

    // ===== LLM API 流式调用 =====

    /**
     * 流式调用 OpenAI 兼容 API
     */
    private String streamLLMChat(List<Map<String, String>> messages,
                                  String baseUrl, String apiKey, String model,
                                  OutputStream outputStream) throws IOException, InterruptedException {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model != null ? model : "default");
        requestBody.put("stream", true);
        requestBody.put("messages", messages);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // 构建 URL
        String url;
        if (baseUrl.contains("/v1")) {
            url = baseUrl + "/chat/completions";
        } else if (baseUrl.contains("/api")) {
            // Ollama: baseUrl/api/chat
            url = baseUrl + "/chat";
        } else {
            url = baseUrl + "/v1/chat/completions";
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5));

        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        // 流式接收
        StringBuilder fullResponse = new StringBuilder();
        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("API 请求失败 (" + response.statusCode() + "): " + body);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;

                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;

                    try {
                        JsonNode node = objectMapper.readTree(data);

                        // OpenAI 格式
                        JsonNode choices = node.path("choices");
                        if (choices.isArray() && !choices.isEmpty()) {
                            String content = choices.get(0).path("delta").path("content").asText("");
                            if (!content.isEmpty()) {
                                fullResponse.append(content);
                                // SSE 推送到前端
                                String sseData = objectMapper.writeValueAsString(Map.of("content", content));
                                outputStream.write(("data: " + sseData + "\n\n").getBytes(StandardCharsets.UTF_8));
                                outputStream.flush();
                            }
                        }

                        // Ollama 格式
                        if (fullResponse.length() == 0) {
                            String ollamaContent = node.path("message").path("content").asText("");
                            if (!ollamaContent.isEmpty()) {
                                fullResponse.append(ollamaContent);
                                String sseData = objectMapper.writeValueAsString(Map.of("content", ollamaContent));
                                outputStream.write(("data: " + sseData + "\n\n").getBytes(StandardCharsets.UTF_8));
                                outputStream.flush();
                            }
                        }
                    } catch (Exception e) {
                        // 忽略解析错误（可能是不完整的 JSON）
                    }
                }
            }
        }

        return fullResponse.toString();
    }

    // ===== CLI 调用 =====

    /**
     * 执行 CLI 命令并流式返回输出
     */
    private String executeCLI(String command, String userMessage,
                              OutputStream outputStream) throws IOException, InterruptedException {
        List<String> cmd;
        String os = System.getProperty("os.name").toLowerCase();

        if ("claude".equalsIgnoreCase(command)) {
            // Claude Code: 使用 --print 模式（非交互式）
            if (os.contains("windows")) {
                cmd = List.of("cmd", "/c", "claude", "-p", "--output-format", "text", userMessage);
            } else {
                cmd = List.of("claude", "-p", "--output-format", "text", userMessage);
            }
        } else if ("crush".equalsIgnoreCase(command)) {
            // Crush: 使用 run 子命令进行非交互式调用
            if (os.contains("windows")) {
                cmd = List.of("cmd", "/c", "crush", "run", userMessage);
            } else {
                cmd = List.of("crush", "run", userMessage);
            }
        } else if ("aider".equalsIgnoreCase(command) || "continue".equalsIgnoreCase(command)
                || "goose".equalsIgnoreCase(command)) {
            // 其他支持 stdin 输入的 CLI，通过管道传递消息
            if (os.contains("windows")) {
                cmd = List.of("cmd", "/c", "echo", userMessage, "|", command);
            } else {
                cmd = List.of("sh", "-c", "echo " + escapeShell(userMessage) + " | " + command);
            }
        } else {
            // 通用 CLI: 传 --message 参数，兼容多数 AI CLI 工具
            if (os.contains("windows")) {
                cmd = List.of("cmd", "/c", command, "--message", userMessage);
            } else {
                cmd = List.of(command, "--message", userMessage);
            }
        }

        log.info("执行 CLI: {}", cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder fullOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fullOutput.append(line).append("\n");
                // SSE 推送
                String sseData = objectMapper.writeValueAsString(Map.of("content", line + "\n"));
                outputStream.write(("data: " + sseData + "\n\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("CLI 命令执行失败，退出码: " + exitCode);
        }

        return fullOutput.toString().trim();
    }

    // ===== 导入历史对话 =====

    /**
     * 从记忆库导入历史对话消息到当前聊天会话
     * @param chatSessionId 目标聊天会话 ID
     * @param sourceSessionIds 要导入的源会话 ID 列表
     * @return 导入的消息列表（包含元信息）
     */
    public List<Map<String, Object>> importHistoryMessages(String chatSessionId, List<String> sourceSessionIds) {
        List<Map<String, Object>> importedMessages = new ArrayList<>();

        try (Connection conn = databaseService.getConnection()) {
            for (String sourceSessionId : sourceSessionIds) {
                // 1. 获取源会话信息
                String sessionInfoSql = "SELECT agent_type, project_path, title FROM sessions WHERE id = ? AND (deleted = false OR deleted IS NULL)";
                String agentType = null;
                String projectPath = null;
                String sessionTitle = null;
                try (PreparedStatement stmt = conn.prepareStatement(sessionInfoSql)) {
                    stmt.setString(1, sourceSessionId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            agentType = rs.getString("agent_type");
                            projectPath = rs.getString("project_path");
                            sessionTitle = rs.getString("title");
                        }
                    }
                }

                if (agentType == null) continue;

                // 2. 获取源会话的消息
                String messagesSql = "SELECT role, content, timestamp FROM messages " +
                        "WHERE session_id = ? AND (deleted = false OR deleted IS NULL) " +
                        "ORDER BY timestamp ASC";
                List<Map<String, String>> sourceMessages = new ArrayList<>();
                try (PreparedStatement stmt = conn.prepareStatement(messagesSql)) {
                    stmt.setString(1, sourceSessionId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            sourceMessages.add(Map.of(
                                    "role", rs.getString("role"),
                                    "content", rs.getString("content")
                            ));
                        }
                    }
                }

                if (sourceMessages.isEmpty()) continue;

                // 3. 构建导入摘要（作为一条系统消息）
                StringBuilder summary = new StringBuilder();
                summary.append("## 导入的历史对话\n");
                summary.append("- **来源**: ").append(sessionTitle != null ? sessionTitle : sourceSessionId);
                if (projectPath != null && !projectPath.isEmpty()) {
                    summary.append("\n- **项目**: ").append(projectPath);
                }
                summary.append("\n- **Agent**: ").append(agentType);
                summary.append("\n- **消息数**: ").append(sourceMessages.size());
                summary.append("\n\n---\n\n");

                // 将消息格式化后插入
                for (Map<String, String> msg : sourceMessages) {
                    String role = msg.get("role");
                    String content = msg.get("content");
                    if (content == null || content.trim().isEmpty()) continue;

                    // 截断过长内容
                    if (content.length() > 2000) {
                        content = content.substring(0, 2000) + "\n...(已截断)";
                    }

                    String roleLabel = switch (role) {
                        case "user" -> "👤 用户";
                        case "assistant" -> "🤖 助手";
                        default -> "📌 " + role;
                    };
                    summary.append("**").append(roleLabel).append("**: ").append(content).append("\n\n");
                }

                // 4. 插入到 chat_messages
                String insertSql = "INSERT INTO chat_messages (id, session_id, role, content) VALUES (?, ?, ?, ?)";
                String msgId = UUID.randomUUID().toString().substring(0, 12);
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, msgId);
                    stmt.setString(2, chatSessionId);
                    stmt.setString(3, "system");
                    stmt.setString(4, summary.toString());
                    stmt.executeUpdate();
                }

                // 5. 记录导入结果
                Map<String, Object> result = new HashMap<>();
                result.put("id", msgId);
                result.put("sourceSessionId", sourceSessionId);
                result.put("agentType", agentType);
                result.put("title", sessionTitle);
                result.put("projectPath", projectPath);
                result.put("messageCount", sourceMessages.size());
                result.put("content", summary.toString());
                importedMessages.add(result);
            }

            // 6. 更新会话时间
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                stmt.setString(1, chatSessionId);
                stmt.executeUpdate();
            }

        } catch (SQLException e) {
            log.error("导入历史对话失败", e);
            throw new RuntimeException("导入失败: " + e.getMessage());
        }

        return importedMessages;
    }

    // ===== 辅助方法 =====

    /** 对 shell 参数中的单引号进行转义 */
    private static String escapeShell(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void saveMessage(String sessionId, String role, String content) {
        try (Connection conn = databaseService.getConnection()) {
            String sql = "INSERT INTO chat_messages (id, session_id, role, content) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, UUID.randomUUID().toString().substring(0, 12));
                stmt.setString(2, sessionId);
                stmt.setString(3, role);
                stmt.setString(4, content);
                stmt.executeUpdate();
            }
            // 更新会话时间
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                stmt.setString(1, sessionId);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("保存消息失败", e);
        }
    }

    private void updateSessionTitle(String sessionId, String firstMessage) {
        String title = firstMessage.length() > 30 ?
                firstMessage.substring(0, 30) + "..." : firstMessage;

        try (Connection conn = databaseService.getConnection()) {
            String sql = "UPDATE chat_sessions SET title = ? WHERE id = ? AND (title = '新对话' OR title IS NULL)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, title);
                stmt.setString(2, sessionId);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("更新会话标题失败", e);
        }
    }

    private List<Map<String, String>> loadHistoryFromDB(String sessionId) {
        List<Map<String, String>> history = new ArrayList<>();
        List<Map<String, Object>> messages = getSessionMessages(sessionId);
        for (Map<String, Object> msg : messages) {
            history.add(Map.of(
                    "role", (String) msg.get("role"),
                    "content", (String) msg.get("content")
            ));
        }
        return history;
    }

    public void shutdown() {
        chatExecutor.shutdown();
    }
}
