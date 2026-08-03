package com.agentmemory.api;

import com.agentmemory.service.AgentDetectorService;
import com.agentmemory.service.ChatService;
import com.agentmemory.service.DatabaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * 对话 API Handler
 * 路径: /api/chat/*
 *
 * 端点:
 *   GET    /api/chat/agents       — 获取可用 agent 列表
 *   POST   /api/chat/sessions     — 创建新会话
 *   GET    /api/chat/sessions     — 获取会话列表
 *   GET    /api/chat/sessions/:id — 获取会话消息
 *   DELETE /api/chat/sessions/:id — 删除会话
 *   POST   /api/chat/send         — 发送消息（SSE 流式响应）
 *   POST   /api/chat/import-messages — 导入历史对话
 */
public class ChatHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatService chatService;

    public ChatHandler(DatabaseService databaseService, AgentDetectorService agentDetectorService) {
        this.chatService = new ChatService(databaseService, agentDetectorService);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS 预检
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            // 路由分发
            if ("/api/chat/agents".equals(path) && "GET".equalsIgnoreCase(method)) {
                handleGetAgents(exchange);
            } else if ("/api/chat/sessions".equals(path) && "POST".equalsIgnoreCase(method)) {
                handleCreateSession(exchange);
            } else if ("/api/chat/sessions".equals(path) && "GET".equalsIgnoreCase(method)) {
                handleListSessions(exchange);
            } else if (path.startsWith("/api/chat/sessions/") && "GET".equalsIgnoreCase(method)) {
                String sessionId = path.substring("/api/chat/sessions/".length());
                handleGetSessionMessages(exchange, sessionId);
            } else if (path.startsWith("/api/chat/sessions/") && "DELETE".equalsIgnoreCase(method)) {
                String sessionId = path.substring("/api/chat/sessions/".length());
                handleDeleteSession(exchange, sessionId);
            } else if ("/api/chat/send".equals(path) && "POST".equalsIgnoreCase(method)) {
                handleSendMessage(exchange);
            } else if ("/api/chat/import-messages".equals(path) && "POST".equalsIgnoreCase(method)) {
                handleImportMessages(exchange);
            } else {
                sendError(exchange, 404, "未知的端点: " + path);
            }
        } catch (Exception e) {
            log.error("ChatHandler 异常", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ===== 端点处理 =====

    /**
     * GET /api/chat/agents — 获取可用 agent 列表
     */
    private void handleGetAgents(HttpExchange exchange) throws IOException {
        sendCorsHeaders(exchange);
        sendJson(exchange, 200, chatService.getAvailableAgents());
    }

    /**
     * POST /api/chat/sessions — 创建新会话
     * Body: { "agentId": "llm-1", "agentName": "DeepSeek", "agentType": "llm" }
     */
    private void handleCreateSession(HttpExchange exchange) throws IOException {
        sendCorsHeaders(exchange);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);

        String agentId = json.path("agentId").asText();
        String agentName = json.path("agentName").asText("Unknown");
        String agentType = json.path("agentType").asText("llm");

        if (agentId.isEmpty()) {
            sendError(exchange, 400, "agentId 不能为空");
            return;
        }

        Map<String, Object> session = chatService.createSession(agentId, agentName, agentType);
        sendJson(exchange, 201, session);
    }

    /**
     * GET /api/chat/sessions — 获取会话列表
     */
    private void handleListSessions(HttpExchange exchange) throws IOException {
        sendCorsHeaders(exchange);
        sendJson(exchange, 200, chatService.getSessions());
    }

    /**
     * GET /api/chat/sessions/:id — 获取会话消息
     */
    private void handleGetSessionMessages(HttpExchange exchange, String sessionId) throws IOException {
        sendCorsHeaders(exchange);
        sendJson(exchange, 200, chatService.getSessionMessages(sessionId));
    }

    /**
     * DELETE /api/chat/sessions/:id — 删除会话
     */
    private void handleDeleteSession(HttpExchange exchange, String sessionId) throws IOException {
        sendCorsHeaders(exchange);
        chatService.deleteSession(sessionId);
        sendJson(exchange, 200, Map.of("status", "ok"));
    }

    /**
     * POST /api/chat/import-messages — 导入历史对话
     * Body: { "sessionId": "chat-abc", "sourceSessionIds": ["sess1", "sess2"] }
     */
    private void handleImportMessages(HttpExchange exchange) throws IOException {
        sendCorsHeaders(exchange);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);

        String sessionId = json.path("sessionId").asText();
        JsonNode idsNode = json.path("sourceSessionIds");

        if (sessionId.isEmpty()) {
            sendError(exchange, 400, "sessionId 不能为空");
            return;
        }
        if (!idsNode.isArray() || idsNode.isEmpty()) {
            sendError(exchange, 400, "sourceSessionIds 不能为空");
            return;
        }

        List<String> sourceIds = new java.util.ArrayList<>();
        for (JsonNode node : idsNode) {
            String id = node.asText();
            if (!id.isEmpty()) sourceIds.add(id);
        }

        if (sourceIds.isEmpty()) {
            sendError(exchange, 400, "sourceSessionIds 不能为空");
            return;
        }

        List<Map<String, Object>> result = chatService.importHistoryMessages(sessionId, sourceIds);
        sendJson(exchange, 200, Map.of(
                "status", "ok",
                "importedCount", result.size(),
                "messages", result
        ));
    }

    /**
     * POST /api/chat/send — 发送消息（SSE 流式响应）
     * Body: {
     *   "sessionId": "abc123",
     *   "message": "你好",
     *   "agentType": "llm" | "cli",
     *   "agentConfig": { "baseUrl": "...", "apiKey": "...", "model": "...", "command": "..." }
     * }
     */
    private void handleSendMessage(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);

        String sessionId = json.path("sessionId").asText();
        String message = json.path("message").asText();
        String agentType = json.path("agentType").asText("llm");
        JsonNode config = json.path("agentConfig");

        if (sessionId.isEmpty() || message.isEmpty()) {
            sendError(exchange, 400, "sessionId 和 message 不能为空");
            return;
        }

        // 设置 SSE 响应头
        sendCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();

        // 发送会话开始事件
        os.write("event: start\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
        os.flush();

        // 根据类型分发
        Future<Void> future;
        if ("cli".equalsIgnoreCase(agentType)) {
            String command = config.path("command").asText();
            future = chatService.sendMessageCLI(sessionId, message, command, os);
        } else {
            // LLM API 模式
            String baseUrl = config.path("baseUrl").asText();
            String apiKey = config.path("apiKey").asText(null);
            String model = config.path("model").asText();
            future = chatService.sendMessageLLM(sessionId, message, baseUrl, apiKey, model, os);
        }

        // 等待完成
        try {
            future.get();
            os.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("对话执行异常", e);
            os.write(("event: error\ndata: " + objectMapper.writeValueAsString(
                    Map.of("content", "执行出错: " + e.getMessage())) + "\n\n").getBytes(StandardCharsets.UTF_8));
        }

        os.flush();
        os.close();
    }

    // ===== 辅助方法 =====

    private void sendCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin",
                origin != null ? origin : "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendJson(HttpExchange exchange, int code, Object data) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        sendCorsHeaders(exchange);
        sendJson(exchange, code, Map.of("error", message));
    }
}
