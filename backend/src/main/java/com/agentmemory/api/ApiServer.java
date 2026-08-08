package com.agentmemory.api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentmemory.service.AgentDetectorService;
import com.agentmemory.service.AgentMemorySyncService;
import com.agentmemory.service.DatabaseService;
import com.agentmemory.service.FileWatcherService;
import com.agentmemory.service.SessionCompressionService;
import com.agentmemory.service.StatsEventBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * HTTP API 服务
 * 提供前端调用的 RESTful API
 */
public class ApiServer {
    
    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);
    
    private final DatabaseService databaseService;
    private final FileWatcherService fileWatcherService;
    private final AgentDetectorService agentDetectorService;
    private final ObjectMapper objectMapper;
    private HttpServer server;
    private final int port;
    private SessionCompressionService compressionService;
    private AgentMemorySyncService memorySyncService;
    
    // CORS 配置：允许的源列表（可通过系统属性配置）
    private static final String ALLOWED_ORIGINS = System.getProperty("api.cors.origins", 
        "http://localhost:8082,http://localhost:5173,http://localhost:5175,http://127.0.0.1:8082,http://127.0.0.1:5173,http://127.0.0.1:5175");
    
    /**
     * 获取允许的 CORS 源
     * @param requestOrigin 请求中的 Origin 头
     * @return 如果请求源在允许列表中则返回该源，否则返回默认的第一个允许源
     */
    private String getAllowedOrigin(String requestOrigin) {
        if (requestOrigin != null && ALLOWED_ORIGINS.contains(requestOrigin)) {
            return requestOrigin;
        }
        return ALLOWED_ORIGINS.split(",")[0]; // 默认返回第一个允许的源
    }

    public ApiServer(DatabaseService databaseService, FileWatcherService fileWatcherService, AgentDetectorService agentDetectorService, int port) {
        this.databaseService = databaseService;
        this.fileWatcherService = fileWatcherService;
        this.agentDetectorService = agentDetectorService;
        this.port = port;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    
    public void setCompressionService(SessionCompressionService compressionService) {
        this.compressionService = compressionService;
    }

    public void setMemorySyncService(AgentMemorySyncService memorySyncService) {
        this.memorySyncService = memorySyncService;
    }
    
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // 注册路由
        server.createContext("/api/agents", new AgentsHandler());
        server.createContext("/api/sessions", new SessionsHandler());
        server.createContext("/api/messages", new MessagesHandler());
        server.createContext("/api/experiences", new ExperiencesHandler());
        server.createContext("/api/errors", new ExperiencesHandler("error_correction"));  // 兼容旧端点
        server.createContext("/api/practices", new ExperiencesHandler("best_practice"));  // 兼容旧端点
        server.createContext("/api/profiles", new UserProfilesHandler());
        server.createContext("/api/skills", new SkillsHandler());
        server.createContext("/api/stats", new StatsHandler());
        server.createContext("/api/search", new SearchHandler());
        server.createContext("/api/compression", new CompressionHandler());
        server.createContext("/api/llm-providers", new LLMProviderHandler());
        server.createContext("/api/cleanup", new CleanupHandler());
        server.createContext("/api/setup", new SetupHandler(databaseService, agentDetectorService, fileWatcherService));
        server.createContext("/api/import", new SetupHandler(databaseService, agentDetectorService, fileWatcherService));
        server.createContext("/api/chat", new ChatHandler(databaseService, agentDetectorService));
        server.createContext("/api/events", new SseHandler());
        server.createContext("/api/sync", new MemorySyncHandler());
        server.createContext("/api/agents/sync", new AgentSyncHandler());

        // Embedding 服务代理（解决前端 CORS 问题）
        String embedBase = System.getenv().getOrDefault("EMBEDDING_BASE_URL", "http://localhost:8100");
        HttpClient embedClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        server.createContext("/api/embedding", exchange -> {
            try {
                // 处理 CORS 预检请求（OPTIONS）
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }

                String path = exchange.getRequestURI().getPath();
                String subPath = path.substring("/api/embedding".length()); // e.g., "/models"
                String targetUrl = embedBase + subPath;
                if (exchange.getRequestURI().getQuery() != null) {
                    targetUrl += "?" + exchange.getRequestURI().getQuery();
                }

                log.debug("代理 Embedding 请求: {} -> {}", path, targetUrl);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(30));

                String method = exchange.getRequestMethod();
                if ("POST".equals(method)) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    reqBuilder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                } else {
                    reqBuilder.GET();
                }

                HttpResponse<String> resp = embedClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                byte[] respBody = resp.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(resp.statusCode(), respBody.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBody);
                }
            } catch (Exception e) {
                log.error("Embedding 代理错误: {}", e.getMessage());
                String errorJson = "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                byte[] errorBytes = errorJson.getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(502, errorBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(errorBytes);
                    }
                } catch (IOException ignored) {}
            }
        });
        
        // 静态文件服务（前端）
        server.createContext("/", new StaticFileHandler());
        
        // DEBUG: 测试数据库连接
        server.createContext("/api/debug/db", exchange -> {
            try (Connection conn = databaseService.getConnection()) {
                sendJson(exchange, Map.of("connected", conn != null));
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        });

        // DEBUG: 重新计算消息数
        server.createContext("/api/debug/recalc-counts", exchange -> {
            try {
                try (Connection conn = databaseService.getConnection();
                     Statement stmt = conn.createStatement()) {

                    // 重新计算所有会话的消息数
                    String sql = """
                        UPDATE sessions SET message_count = (
                            SELECT COUNT(*) FROM messages WHERE messages.session_id = sessions.id
                        ) WHERE deleted = false
                        """;
                    int updated = stmt.executeUpdate(sql);
                    sendJson(exchange, Map.of("status", "ok", "updated", updated));
                }
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        });
        
        // DEBUG: 测试 sessions SQL
        server.createContext("/api/debug/sessions", exchange -> {
            try {
                List<String> results = new ArrayList<>();
                try (Connection conn = databaseService.getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT id, agent_type, message_count, deleted FROM sessions")) {
                    while (rs.next()) {
                        String row = String.format("id=%s, agent=%s, msg=%d, deleted=%d",
                            rs.getString("id"),
                            rs.getString("agent_type"),
                            rs.getInt("message_count"),
                            rs.getInt("deleted"));
                        results.add(row);
                    }
                }
                sendJson(exchange, results);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        });
        
        // DEBUG: 测试 stats
        server.createContext("/api/debug/stats", exchange -> {
            try {
                Map<String, Object> stats = new HashMap<>();
                try (Connection conn = databaseService.getConnection();
                     Statement stmt = conn.createStatement()) {

                    // 测试每个查询
                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM agents")) {
                        rs.next();
                        stats.put("agents", rs.getInt(1));
                    } catch (Exception e) { stats.put("agents_error", e.getMessage()); }

                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions WHERE deleted = false")) {
                        rs.next();
                        stats.put("sessions", rs.getInt(1));
                    } catch (Exception e) { stats.put("sessions_error", e.getMessage()); }

                    try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM messages WHERE deleted = false")) {
                        rs.next();
                        stats.put("messages", rs.getInt(1));
                    } catch (Exception e) { stats.put("messages_error", e.getMessage()); }
                }
                sendJson(exchange, stats);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        });
        
        // DEBUG: 测试 messages
        server.createContext("/api/debug/messages", exchange -> {
            try {
                List<String> results = new ArrayList<>();
                try (Connection conn = databaseService.getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT id, role, session_id FROM messages LIMIT 5")) {
                    while (rs.next()) {
                        String row = String.format("id=%s, role=%s, session=%s",
                            rs.getString("id"), rs.getString("role"), rs.getString("session_id"));
                        results.add(row);
                    }
                }
                sendJson(exchange, results);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        });
        
        // DEBUG: 检查 sessions 详细情况
        server.createContext("/api/debug/sessionsql", exchange -> {
            try {
                Map<String, Object> result = new HashMap<>();
                try (Connection conn = databaseService.getConnection();
                     Statement stmt = conn.createStatement()) {

                    // 所有 sessions
                    try (ResultSet rs1 = stmt.executeQuery("SELECT COUNT(*) FROM sessions")) {
                        rs1.next();
                        result.put("total_sessions", rs1.getInt(1));
                    }

                    // 未删除的 sessions
                    try (ResultSet rs2 = stmt.executeQuery("SELECT COUNT(*) FROM sessions WHERE deleted = false")) {
                        rs2.next();
                        result.put("active_sessions", rs2.getInt(1));
                    }

                    // 按 agent_type 分组
                    try (ResultSet rs3 = stmt.executeQuery("SELECT agent_type, COUNT(*) FROM sessions GROUP BY agent_type")) {
                        List<Map<String, Object>> byAgent = new ArrayList<>();
                        while (rs3.next()) {
                            Map<String, Object> row = new HashMap<>();
                            row.put("agent", rs3.getString(1));
                            row.put("count", rs3.getInt(2));
                            byAgent.add(row);
                        }
                        result.put("by_agent", byAgent);
                    }

                    // 最新和最老的 session
                    try (ResultSet rs4 = stmt.executeQuery("SELECT MIN(created_at), MAX(created_at) FROM sessions")) {
                        rs4.next();
                        result.put("oldest", rs4.getString(1));
                        result.put("newest", rs4.getString(2));
                    }
                }
                sendJson(exchange, result);
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        });
        
        server.setExecutor(Executors.newCachedThreadPool());  // 缓存线程池，支持 SSE 长连接
        server.start();
        
        log.info("API 服务已启动: http://localhost:{}", port);
    }
    
    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("API 服务已停止");
        }
    }
    
    // ===== 通用方法 =====

    /**
     * RowMapper 接口，用于映射 ResultSet 到对象
     */
    @FunctionalInterface
    private interface RowMapper<T> {
        T mapRow(ResultSet rs) throws SQLException;
    }

    /**
     * 通用查询方法，执行 SQL 并映射结果
     */
    private <T> List<T> queryList(String sql, RowMapper<T> mapper) throws SQLException {
        try (Connection conn = databaseService.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            List<T> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapper.mapRow(rs));
            }
            return list;
        }
    }

    /**
     * 通用查询方法（带PreparedStatement），执行 SQL 并映射结果
     */
    private <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (Connection conn = databaseService.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // 设置参数
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                List<T> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapper.mapRow(rs));
                }
                return list;
            }
        }
    }

    /**
     * 执行更新操作（INSERT/UPDATE/DELETE）
     */
    @FunctionalInterface
    private interface StatementSetter {
        void setStatement(PreparedStatement stmt) throws SQLException;
    }

    private void executeUpdate(String sql, StatementSetter setter) throws SQLException {
        try (Connection conn = databaseService.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            setter.setStatement(stmt);
            stmt.executeUpdate();
        }
    }

    /**
     * 包装处理器执行，自动处理异常和响应
     */
    private interface HttpHandlerFunc {
        void handle() throws SQLException, IOException;
    }

    private void wrapHandler(HttpExchange exchange, HttpHandlerFunc handler) {
        // CORS 处理 - 动态验证请求来源
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        String allowedOrigin = getAllowedOrigin(requestOrigin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", allowedOrigin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            try {
                exchange.sendResponseHeaders(200, -1);
            } catch (IOException e) {
                log.error("发送 OPTIONS 响应失败", e);
            }
            return;
        }
        
        try {
            handler.handle();
        } catch (SQLException e) {
            try {
                sendError(exchange, 500, "数据库错误: " + e.getMessage());
            } catch (IOException ioException) {
                log.error("发送错误响应失败", ioException);
            }
        } catch (IOException e) {
            log.error("IO 错误", e);
        } catch (Exception e) {
            try {
                sendError(exchange, 500, "服务器错误: " + e.getMessage());
            } catch (IOException ioException) {
                log.error("发送错误响应失败", ioException);
            }
        }
    }

    /**
     * 将 SQL Array 转换为 List
     */
    private List<String> sqlArrayToList(java.sql.Array sqlArray) throws SQLException {
        if (sqlArray == null) return new ArrayList<>();
        Object[] arr = (Object[]) sqlArray.getArray();
        List<String> list = new ArrayList<>();
        for (Object obj : arr) {
            list.add(obj != null ? obj.toString() : null);
        }
        return list;
    }

    private void sendJson(HttpExchange exchange, Object data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        // CORS：动态设置允许的源
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", getAllowedOrigin(requestOrigin));
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 获取 URL 查询参数
     */
    private String getQueryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) {
                try {
                    return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("error", true);
        error.put("message", message);

        String json = objectMapper.writeValueAsString(error);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        // CORS：动态设置允许的源
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", getAllowedOrigin(requestOrigin));
        exchange.sendResponseHeaders(code, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ===== CRUD 通用工具方法 =====

    /**
     * 从路径提取ID
     */
    private String parseIdFromPath(String path, String prefix) {
        String id = path.substring(prefix.length());
        if (id.endsWith("/")) id = id.substring(0, id.length() - 1);
        return id;
    }

    /**
     * 读取请求体
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readRequestBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.trim().isEmpty()) return new HashMap<>();
        return objectMapper.readValue(body, Map.class);
    }

    /**
     * 验证必填字段
     */
    private void validateRequiredFields(Map<String, Object> body, String... fields) throws IOException {
        List<String> missing = new ArrayList<>();
        for (String field : fields) {
            if (!body.containsKey(field) || body.get(field) == null ||
                (body.get(field) instanceof String && ((String) body.get(field)).trim().isEmpty())) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required fields: " + String.join(", ", missing));
        }
    }

    /**
     * 生成UUID
     */
    private String generateId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 导出JSON
     */
    private void exportAsJson(HttpExchange exchange, List<Map<String, Object>> items, String filename) throws IOException {
        String timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fullFilename = filename + "_" + timestamp + ".json";
        String json = objectMapper.writeValueAsString(items);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fullFilename + "\"");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    // ===== Handlers =====
    
    class AgentsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                String method = exchange.getRequestMethod();
                
                // POST: 添加自定义 Agent
                if ("POST".equalsIgnoreCase(method)) {
                    Map<String, Object> input = readRequestBody(exchange);
                    
                    String name = (String) input.get("name");
                    String displayName = (String) input.getOrDefault("displayName", name);
                    String logBasePath = (String) input.get("logBasePath");
                    String cliPath = (String) input.getOrDefault("cliPath", "");
                    String parserType = (String) input.getOrDefault("parserType", "openclaw");
                    boolean enabled = input.containsKey("enabled") ? Boolean.TRUE.equals(input.get("enabled")) : true;
                    
                    if (name == null || name.isEmpty()) {
                        sendError(exchange, 400, "Agent name is required");
                        return;
                    }
                    
                    // 插入或更新 Agent
                    executeUpdate(
                        "INSERT INTO agents (name, display_name, log_base_path, cli_path, parser_type, enabled) VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(name) DO UPDATE SET display_name = ?, log_base_path = ?, cli_path = ?, parser_type = ?, enabled = ?",
                        stmt -> {
                            stmt.setString(1, name);
                            stmt.setString(2, displayName);
                            stmt.setString(3, logBasePath);
                            stmt.setString(4, cliPath);
                            stmt.setString(5, parserType);
                            stmt.setBoolean(6, enabled);
                            stmt.setString(7, displayName);
                            stmt.setString(8, logBasePath);
                            stmt.setString(9, cliPath);
                            stmt.setString(10, parserType);
                            stmt.setBoolean(11, enabled);
                        }
                    );
                    
                    // 启动文件监控（如果启用）
                    if (enabled && logBasePath != null && !logBasePath.isEmpty()) {
                        try {
                            Path dir = Paths.get(logBasePath.replace("~", System.getProperty("user.home")));
                            if (Files.exists(dir)) {
                                fileWatcherService.watchDirectory(name, parserType, dir);
                            }
                        } catch (Exception e) {
                            log.warn("启动监控失败: " + name, e);
                        }
                    }
                    
                    sendJson(exchange, Map.of("status", "ok", "name", name));
                    return;
                }
                
                // GET: 列出所有 Agents（去重，优先选择有完整信息的）
                List<Map<String, Object>> agents = queryList(
                    """
                    SELECT * FROM agents a1 WHERE id = (
                        SELECT id FROM agents a2 
                        WHERE COALESCE(a2.parser_type, a2.name) = COALESCE(a1.parser_type, a1.name)
                        ORDER BY 
                            CASE WHEN cli_path IS NOT NULL THEN 0 ELSE 1 END,
                            id
                        LIMIT 1
                    ) ORDER BY name
                    """,
                    rs -> {
                        Map<String, Object> agent = new HashMap<>();
                        agent.put("id", rs.getInt("id"));
                        agent.put("name", rs.getString("name"));
                        agent.put("displayName", rs.getString("display_name"));
                        agent.put("logBasePath", rs.getString("log_base_path"));
                        agent.put("cliPath", rs.getString("cli_path"));
                        agent.put("parserType", rs.getString("parser_type"));
                        agent.put("version", rs.getString("version"));
                        agent.put("enabled", rs.getBoolean("enabled"));
                        return agent;
                    }
                );
                sendJson(exchange, agents);
            });
        }
    }
    
    class SessionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            // 检查是否为导出请求
            if (path.endsWith("/export")) {
                handleExport(exchange);
                return;
            }

            // 会话标题懒生成：GET /api/sessions/{id}/title
            if (path.matches("/api/sessions/[^/]+/title") && "GET".equals(exchange.getRequestMethod())) {
                String id = parseIdFromPath(path, "/api/sessions/");
                if (id.endsWith("/title")) id = id.substring(0, id.length() - "/title".length());
                handleTitle(exchange, id);
                return;
            }

            // 删除原消息：DELETE /api/sessions/{id}/messages
            if (path.matches("/api/sessions/[^/]+/messages") && "DELETE".equals(exchange.getRequestMethod())) {
                String id = parseIdFromPath(path, "/api/sessions/");
                if (id.endsWith("/messages")) id = id.substring(0, id.length() - "/messages".length());
                handleDeleteMessages(exchange, id);
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            int limit = 200;
            int offset = 0;
            String agentType = null;

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2) {
                        switch (pair[0]) {
                            case "limit" -> limit = Integer.parseInt(pair[1]);
                            case "offset" -> offset = Integer.parseInt(pair[1]);
                            case "agent" -> agentType = pair[1];
                        }
                    }
                }
            }

            // 查询会话列表，实时统计消息数
            String baseSql = "SELECT s.id, s.agent_type, s.project_path, s.created_at, " +
                "(SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id AND m.deleted = false) as msg_count " +
                "FROM sessions s WHERE s.deleted = false ";
            String filterSql = (agentType != null && !agentType.isEmpty())
                ? "AND s.agent_type = ? " : "";
            String orderSql = "ORDER BY COALESCE(s.created_at, '1970-01-01') DESC NULLS LAST LIMIT ? OFFSET ?";
            String sql = baseSql + filterSql + orderSql;

            try (Connection conn = databaseService.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                int paramIndex = 1;
                if (agentType != null && !agentType.isEmpty()) {
                    stmt.setString(paramIndex++, agentType);
                }
                stmt.setInt(paramIndex++, limit);
                stmt.setInt(paramIndex++, offset);

                try (ResultSet rs = stmt.executeQuery()) {
                    List<Map<String, Object>> sessions = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> session = new HashMap<>();
                        session.put("id", rs.getString("id"));
                        session.put("agentType", rs.getString("agent_type"));
                        session.put("projectPath", rs.getString("project_path"));
                        session.put("messageCount", rs.getInt("msg_count"));
                        session.put("createdAt", rs.getString("created_at"));
                        sessions.add(session);
                    }

                    log.info("SessionsHandler 返回 {} 条", sessions.size());
                    sendJson(exchange, sessions);
                }
            } catch (SQLException e) {
                log.error("SessionsHandler SQL错误: {}", e.getMessage());
                sendError(exchange, 500, e.getMessage());
            } catch (Exception e) {
                log.error("SessionsHandler 未知错误: {}", e.getMessage(), e);
                sendError(exchange, 500, e.getMessage());
            }
        }

        private void handleExport(HttpExchange exchange) throws IOException {
            try {
                List<Map<String, Object>> sessions = queryList(
                    "SELECT * FROM sessions WHERE deleted = false ORDER BY created_at DESC",
                    rs -> {
                        Map<String, Object> session = new HashMap<>();
                        session.put("id", rs.getString("id"));
                        session.put("agentType", rs.getString("agent_type"));
                        session.put("projectPath", rs.getString("project_path"));
                        session.put("messageCount", rs.getInt("message_count"));
                        session.put("createdAt", rs.getTimestamp("created_at"));
                        session.put("expiresAt", rs.getTimestamp("expires_at"));
                        return session;
                    }
                );
                exportAsJson(exchange, sessions, "sessions");
            } catch (SQLException e) {
                sendError(exchange, 500, e.getMessage());
            }
        }

        /** 会话标题懒生成：查缓存，无则用首条 user 消息截断并写回 */
        private void handleTitle(HttpExchange exchange, String sessionId) throws IOException {
            try {
                // 1. 查缓存
                String cached = null;
                try (Connection conn = databaseService.getConnection();
                     PreparedStatement st = conn.prepareStatement("SELECT title FROM sessions WHERE id = ?")) {
                    st.setString(1, sessionId);
                    try (ResultSet rs = st.executeQuery()) {
                        if (rs.next()) cached = rs.getString("title");
                    }
                }
                if (cached != null && !cached.isBlank()) {
                    sendJson(exchange, Map.of("title", cached));
                    return;
                }

                // 2. 取前 10 条 user 消息，跳过元数据/工具指令，生成标题
                String title = null;
                try (Connection conn = databaseService.getConnection();
                     PreparedStatement st = conn.prepareStatement(
                         "SELECT content FROM messages WHERE session_id = ? AND role = 'user' AND deleted = false " +
                         "AND content IS NOT NULL AND LENGTH(TRIM(content)) > 5 ORDER BY timestamp LIMIT 10")) {
                    st.setString(1, sessionId);
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            String content = rs.getString("content").trim();
                            // 跳过 Claude 元数据 / 工具指令 / 纯标签
                            if (content.startsWith("Conversation info") || content.startsWith("<")
                                    || content.startsWith("```") || content.contains("untrusted metadata")) {
                                continue;
                            }
                            String first = content.replaceAll("\\s+", " ");
                            title = first.length() > 40 ? first.substring(0, 40) : first;
                            break;
                        }
                    }
                }
                if (title == null || title.isBlank()) {
                    title = "未命名会话";
                }

                // 3. 写回缓存
                try (Connection conn = databaseService.getConnection();
                     PreparedStatement st = conn.prepareStatement("UPDATE sessions SET title = ? WHERE id = ?")) {
                    st.setString(1, title);
                    st.setString(2, sessionId);
                    st.executeUpdate();
                }
                sendJson(exchange, Map.of("title", title));
            } catch (SQLException e) {
                sendError(exchange, 500, e.getMessage());
            }
        }

        /** 软删除会话原消息（保留压缩摘要） */
        private void handleDeleteMessages(HttpExchange exchange, String sessionId) throws IOException {
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement st = conn.prepareStatement(
                     "UPDATE messages SET deleted = true, expires_at = NOW() WHERE session_id = ? AND deleted = false")) {
                st.setString(1, sessionId);
                int n = st.executeUpdate();
                sendJson(exchange, Map.of("deleted", n));
            } catch (SQLException e) {
                sendError(exchange, 500, e.getMessage());
            }
        }
    }
    
    class MessagesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String sessionId = path.substring("/api/messages/".length());
            
            if (sessionId.isEmpty() || sessionId.equals("/api/messages")) {
                sendError(exchange, 400, "需要提供 sessionId");
                return;
            }
            
            String sql = "SELECT id, role, content, timestamp FROM messages " +
                        "WHERE session_id = ? AND deleted = false " +
                        "ORDER BY timestamp";
            
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, sessionId);
                ResultSet rs = stmt.executeQuery();
                
                List<Map<String, Object>> messages = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("id", rs.getString("id"));
                    msg.put("role", rs.getString("role"));
                    msg.put("content", rs.getString("content"));
                    msg.put("timestamp", rs.getString("timestamp"));
                    messages.add(msg);
                }
                
                log.info("MessagesHandler sessionId={} 返回 {} 条", sessionId, messages.size());
                sendJson(exchange, messages);
            } catch (SQLException e) {
                log.error("MessagesHandler 错误: {}", e.getMessage());
                sendError(exchange, 500, e.getMessage());
            } catch (Exception e) {
                log.error("MessagesHandler 未知错误: {}", e.getMessage());
                sendError(exchange, 500, e.getMessage());
            }
        }
    }
    
    class ExperiencesHandler implements HttpHandler {
        private final String defaultType;

        public ExperiencesHandler() { this.defaultType = null; }
        public ExperiencesHandler(String type) { this.defaultType = type; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                String type = getQueryParam(exchange, "type");
                if (type == null || type.isBlank()) type = defaultType;

                if ("GET".equals(method)) {
                    if (path.endsWith("/export")) {
                        handleExport(exchange, type);
                    } else if (path.matches("/api/experiences/[^/]+")) {
                        handleGetSingle(exchange, parseIdFromPath(path, "/api/experiences/"));
                    } else {
                        handleList(exchange, type);
                    }
                } else if ("POST".equals(method)) {
                    handleCreate(exchange, type);
                } else if ("PUT".equals(method)) {
                    handleUpdate(exchange, parseIdFromPath(path, "/api/experiences/"));
                } else if ("DELETE".equals(method)) {
                    handleDelete(exchange, parseIdFromPath(path, "/api/experiences/"));
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            });
        }

        private void handleList(HttpExchange exchange, String type) throws SQLException, IOException {
            String sql = "SELECT * FROM experiences WHERE deleted = false";
            List<Map<String, Object>> items;
            if (type != null && !type.isBlank()) {
                sql += " AND type = ? ORDER BY created_at DESC LIMIT 200";
                items = queryList(sql, rs -> mapExperience(rs), type);
            } else {
                sql += " ORDER BY created_at DESC LIMIT 200";
                items = queryList(sql, rs -> mapExperience(rs));
            }
            sendJson(exchange, items);
        }

        private void handleGetSingle(HttpExchange exchange, String id) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM experiences WHERE id = ? AND (deleted = false OR deleted IS NULL)",
                rs -> mapExperience(rs), id);
            if (items.isEmpty()) {
                sendError(exchange, 404, "Not found");
            } else {
                sendJson(exchange, items.get(0));
            }
        }

        private void handleCreate(HttpExchange exchange, String type) throws SQLException, IOException {
            Map<String, Object> body = readRequestBody(exchange);
            validateRequiredFields(body, "title", "scenario", "practice");
            String resolvedType = type != null ? type : (String) body.getOrDefault("type", "best_practice");

            String id = generateId();
            try (Connection conn = databaseService.getConnection()) {
                String sql = "INSERT INTO experiences (id, title, type, scenario, practice, rationale, example, tags, created_at, deleted) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, false)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, id);
                    stmt.setString(2, (String) body.get("title"));
                    stmt.setString(3, resolvedType);
                    stmt.setString(4, (String) body.get("scenario"));
                    stmt.setString(5, (String) body.get("practice"));
                    stmt.setString(6, (String) body.get("rationale"));
                    stmt.setString(7, (String) body.get("example"));
                    @SuppressWarnings("unchecked")
                    List<String> tagsList = (List<String>) body.getOrDefault("tags", new ArrayList<String>());
                    stmt.setArray(8, conn.createArrayOf("TEXT", tagsList.toArray()));
                    stmt.executeUpdate();
                }
            }
            handleGetSingle(exchange, id);
        }

        private void handleUpdate(HttpExchange exchange, String id) throws SQLException, IOException {
            Map<String, Object> body = readRequestBody(exchange);
            validateRequiredFields(body, "title", "scenario", "practice");
            try (Connection conn = databaseService.getConnection()) {
                String sql = "UPDATE experiences SET title=?, scenario=?, practice=?, rationale=?, example=?, tags=? WHERE id=?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, (String) body.get("title"));
                    stmt.setString(2, (String) body.get("scenario"));
                    stmt.setString(3, (String) body.get("practice"));
                    stmt.setString(4, (String) body.get("rationale"));
                    stmt.setString(5, (String) body.get("example"));
                    @SuppressWarnings("unchecked")
                    List<String> tagsList = (List<String>) body.getOrDefault("tags", new ArrayList<String>());
                    stmt.setArray(6, conn.createArrayOf("TEXT", tagsList.toArray()));
                    stmt.setString(7, id);
                    if (stmt.executeUpdate() == 0) {
                        sendError(exchange, 404, "Not found");
                        return;
                    }
                }
            }
            handleGetSingle(exchange, id);
        }

        private void handleDelete(HttpExchange exchange, String id) throws SQLException, IOException {
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("UPDATE experiences SET deleted=true WHERE id=?")) {
                stmt.setString(1, id);
                if (stmt.executeUpdate() == 0) {
                    sendError(exchange, 404, "Not found");
                    return;
                }
            }
            sendJson(exchange, Map.of("deleted", true));
        }

        private void handleExport(HttpExchange exchange, String type) throws SQLException, IOException {
            String sql = "SELECT * FROM experiences WHERE deleted = false";
            List<Map<String, Object>> items;
            if (type != null && !type.isBlank()) {
                sql += " AND type = ?";
                items = queryList(sql, rs -> mapExperience(rs), type);
            } else {
                items = queryList(sql, rs -> mapExperience(rs));
            }
            exportAsJson(exchange, items, "experiences");
        }

        private Map<String, Object> mapExperience(ResultSet rs) throws SQLException {
            Map<String, Object> item = new HashMap<>();
            item.put("id", rs.getString("id"));
            item.put("title", rs.getString("title"));
            item.put("type", rs.getString("type"));
            item.put("scenario", rs.getString("scenario"));
            item.put("practice", rs.getString("practice"));
            item.put("rationale", rs.getString("rationale"));
            item.put("example", rs.getString("example"));
            item.put("tags", sqlArrayToList(rs.getArray("tags")));
            item.put("sourceSession", rs.getString("source_session"));
            item.put("createdAt", rs.getTimestamp("created_at"));
            return item;
        }
    }

    
    class UserProfilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();

                if ("GET".equals(method)) {
                    if (path.endsWith("/export")) {
                        handleExport(exchange);
                    } else if (path.matches("/api/profiles/[^/]+")) {
                        handleGetSingle(exchange, parseIdFromPath(path, "/api/profiles/"));
                    } else {
                        handleList(exchange);
                    }
                } else if ("POST".equals(method)) {
                    handleCreate(exchange);
                } else if ("PUT".equals(method)) {
                    handleUpdate(exchange, parseIdFromPath(path, "/api/profiles/"));
                } else if ("DELETE".equals(method)) {
                    handleDelete(exchange, parseIdFromPath(path, "/api/profiles/"));
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            });
        }

        private void handleList(HttpExchange exchange) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM user_profiles WHERE (deleted = false OR deleted IS NULL) ORDER BY updated_at DESC",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("category", rs.getString("category"));
                    item.put("items", rs.getString("items"));
                    item.put("sourceAgent", rs.getString("source_agent"));
                    item.put("updatedAt", rs.getTimestamp("updated_at"));
                    return item;
                }
            );
            sendJson(exchange, items);
        }

        private void handleGetSingle(HttpExchange exchange, String id) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM user_profiles WHERE id = ? AND (deleted = false OR deleted IS NULL)",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("category", rs.getString("category"));
                    item.put("items", rs.getString("items"));
                    item.put("sourceAgent", rs.getString("source_agent"));
                    item.put("updatedAt", rs.getTimestamp("updated_at"));
                    return item;
                },
                id
            );
            if (items.isEmpty()) {
                sendError(exchange, 404, "Not found");
            } else {
                sendJson(exchange, items.get(0));
            }
        }

        private void handleCreate(HttpExchange exchange) throws SQLException, IOException {
            Map<String, Object> body = readRequestBody(exchange);
            validateRequiredFields(body, "title", "category", "items");

            String id = generateId();
            try (Connection conn = databaseService.getConnection()) {
                String sql = "INSERT INTO user_profiles (id, title, category, items, updated_at, deleted) " +
                            "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, false)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, id);
                    stmt.setString(2, (String) body.get("title"));
                    stmt.setString(3, (String) body.get("category"));
                    stmt.setString(4, (String) body.get("items"));
                    stmt.executeUpdate();
                }
            }
            handleGetSingle(exchange, id);
        }

        private void handleUpdate(HttpExchange exchange, String id) throws SQLException, IOException {
            Map<String, Object> body = readRequestBody(exchange);
            validateRequiredFields(body, "title", "category", "items");

            try (Connection conn = databaseService.getConnection()) {
                String sql = "UPDATE user_profiles SET title=?, category=?, items=?, updated_at=CURRENT_TIMESTAMP WHERE id=?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, (String) body.get("title"));
                    stmt.setString(2, (String) body.get("category"));
                    stmt.setString(3, (String) body.get("items"));
                    stmt.setString(4, id);
                    if (stmt.executeUpdate() == 0) {
                        sendError(exchange, 404, "Not found");
                        return;
                    }
                }
            }
            handleGetSingle(exchange, id);
        }

        private void handleDelete(HttpExchange exchange, String id) throws SQLException, IOException {
            try (Connection conn = databaseService.getConnection()) {
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE user_profiles SET deleted=true WHERE id=?")) {
                    stmt.setString(1, id);
                    if (stmt.executeUpdate() == 0) {
                        sendError(exchange, 404, "Not found");
                        return;
                    }
                }
            }
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            sendJson(exchange, response);
        }

        private void handleExport(HttpExchange exchange) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM user_profiles WHERE (deleted = false OR deleted IS NULL) ORDER BY updated_at DESC",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("category", rs.getString("category"));
                    item.put("items", rs.getString("items"));
                    item.put("updatedAt", rs.getTimestamp("updated_at"));
                    return item;
                }
            );
            exportAsJson(exchange, items, "user_profiles");
        }
    }
    
    
    
    class SkillsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();

                if ("GET".equals(method)) {
                    if (path.endsWith("/export")) {
                        handleExport(exchange);
                    } else if (path.matches("/api/skills/pending-count")) {
                        sendJson(exchange, Map.of("count", getPendingSkillCount()));
                    } else if (path.matches("/api/skills/[^/]+")) {
                        handleGetSingle(exchange, parseIdFromPath(path, "/api/skills/"));
                    } else {
                        handleList(exchange);
                    }
                } else if ("POST".equals(method)) {
                    if (path.matches("/api/skills/[^/]+/approve")) {
                        String id = parseIdFromPath(path, "/api/skills/");
                        if (id.endsWith("/approve")) id = id.substring(0, id.length() - "/approve".length());
                        handleStatusChange(exchange, id, "approved");
                    } else if (path.matches("/api/skills/[^/]+/reject")) {
                        String id = parseIdFromPath(path, "/api/skills/");
                        if (id.endsWith("/reject")) id = id.substring(0, id.length() - "/reject".length());
                        handleStatusChange(exchange, id, "rejected");
                    } else {
                        handleCreate(exchange);
                    }
                } else if ("PUT".equals(method)) {
                    handleUpdate(exchange, parseIdFromPath(path, "/api/skills/"));
                } else if ("DELETE".equals(method)) {
                    handleDelete(exchange, parseIdFromPath(path, "/api/skills/"));
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            });
        }

        private void handleList(HttpExchange exchange) throws SQLException, IOException {
            String status = getQueryParam(exchange, "status");
            String sql = "SELECT * FROM skills WHERE (deleted = false OR deleted IS NULL)";
            List<Map<String, Object>> items;
            if (status != null && !status.isBlank()) {
                sql += " AND status = ? ORDER BY created_at DESC";
                items = queryList(sql, rs -> mapSkill(rs), status);
            } else {
                sql += " ORDER BY created_at DESC";
                items = queryList(sql, rs -> mapSkill(rs));
            }
            sendJson(exchange, items);
        }

        private Map<String, Object> mapSkill(ResultSet rs) throws SQLException {
            Map<String, Object> item = new HashMap<>();
            item.put("id", rs.getString("id"));
            item.put("title", rs.getString("title"));
            item.put("skillType", rs.getString("skill_type"));
            item.put("description", rs.getString("description"));
            item.put("steps", rs.getString("steps"));
            item.put("tags", sqlArrayToList(rs.getArray("tags")));
            item.put("status", rs.getString("status"));
            item.put("extractedBy", rs.getString("extracted_by"));
            item.put("createdAt", rs.getTimestamp("created_at"));
            return item;
        }

        /** 技能确认/忽略：更新状态 */
        private void handleStatusChange(HttpExchange exchange, String id, String status) throws SQLException, IOException {
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement st = conn.prepareStatement("UPDATE skills SET status = ? WHERE id = ?")) {
                st.setString(1, status);
                st.setString(2, id);
                int n = st.executeUpdate();
                if (n == 0) {
                    sendError(exchange, 404, "Not found");
                    return;
                }
                sendJson(exchange, Map.of("id", id, "status", status));
            }
        }

        /** 待确认技能候选数（前端红点） */
        private int getPendingSkillCount() throws SQLException {
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement st = conn.prepareStatement(
                     "SELECT COUNT(*) FROM skills WHERE status = 'pending' AND (deleted = false OR deleted IS NULL)");
                 ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }

        private void handleGetSingle(HttpExchange exchange, String id) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM skills WHERE id = ? AND (deleted = false OR deleted IS NULL)",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("skillType", rs.getString("skill_type"));
                    item.put("description", rs.getString("description"));
                    item.put("steps", rs.getString("steps"));
                    item.put("tags", sqlArrayToList(rs.getArray("tags")));
                    item.put("createdAt", rs.getTimestamp("created_at"));
                    return item;
                },
                id
            );
            if (items.isEmpty()) {
                sendError(exchange, 404, "Not found");
            } else {
                sendJson(exchange, items.get(0));
            }
        }

        private void handleCreate(HttpExchange exchange) throws SQLException, IOException {
            Map<String, Object> body = readRequestBody(exchange);
            validateRequiredFields(body, "title", "skillType", "description");

            String id = generateId();
            try (Connection conn = databaseService.getConnection()) {
                String sql = "INSERT INTO skills (id, title, skill_type, description, steps, tags, created_at, deleted) " +
                            "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, false)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, id);
                    stmt.setString(2, (String) body.get("title"));
                    stmt.setString(3, (String) body.get("skillType"));
                    stmt.setString(4, (String) body.get("description"));
                    stmt.setString(5, (String) body.get("steps"));

                    @SuppressWarnings("unchecked")
                    List<String> tagsList = (List<String>) body.getOrDefault("tags", new ArrayList<String>());
                    stmt.setArray(6, conn.createArrayOf("TEXT", tagsList.toArray()));
                    stmt.executeUpdate();
                }
            }
            handleGetSingle(exchange, id);
        }

        private void handleUpdate(HttpExchange exchange, String id) throws SQLException, IOException {
            Map<String, Object> body = readRequestBody(exchange);
            validateRequiredFields(body, "title", "skillType", "description");

            try (Connection conn = databaseService.getConnection()) {
                String sql = "UPDATE skills SET title=?, skill_type=?, description=?, steps=?, tags=? WHERE id=?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, (String) body.get("title"));
                    stmt.setString(2, (String) body.get("skillType"));
                    stmt.setString(3, (String) body.get("description"));
                    stmt.setString(4, (String) body.get("steps"));

                    @SuppressWarnings("unchecked")
                    List<String> tagsList = (List<String>) body.getOrDefault("tags", new ArrayList<String>());
                    stmt.setArray(5, conn.createArrayOf("TEXT", tagsList.toArray()));
                    stmt.setString(6, id);
                    if (stmt.executeUpdate() == 0) {
                        sendError(exchange, 404, "Not found");
                        return;
                    }
                }
            }
            handleGetSingle(exchange, id);
        }

        private void handleDelete(HttpExchange exchange, String id) throws SQLException, IOException {
            try (Connection conn = databaseService.getConnection()) {
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE skills SET deleted=true WHERE id=?")) {
                    stmt.setString(1, id);
                    if (stmt.executeUpdate() == 0) {
                        sendError(exchange, 404, "Not found");
                        return;
                    }
                }
            }
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            sendJson(exchange, response);
        }

        private void handleExport(HttpExchange exchange) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM skills WHERE (deleted = false OR deleted IS NULL) ORDER BY created_at DESC",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("skillType", rs.getString("skill_type"));
                    item.put("description", rs.getString("description"));
                    item.put("steps", rs.getString("steps"));
                    item.put("tags", sqlArrayToList(rs.getArray("tags")));
                    item.put("createdAt", rs.getTimestamp("created_at"));
                    return item;
                }
            );
            exportAsJson(exchange, items, "skills");
        }
    }
    
    /**
     * SSE (Server-Sent Events) 实时事件推送
     * GET /api/events - 建立长连接，接收 stats_update 等事件
     */
    class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // SSE 响应头
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0);  // 0 =  chunked/不定长度

            // 注册客户端（连接保持，由 broadcaster 心跳维持）
            StatsEventBroadcaster.SseClient client =
                    new StatsEventBroadcaster.SseClient(exchange.getResponseBody());
            StatsEventBroadcaster.getInstance().register(client);

            // 发送连接成功事件
            StatsEventBroadcaster.getInstance().broadcast("connected", "{}");

            // 保持连接直到客户端断开（心跳/事件发送失败时标记 inactive）
            try {
                while (client.isActive()) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                client.close();
            }
        }
    }

    class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> stats = new HashMap<>();
            
            try (Connection conn = databaseService.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                // 查询各表的数量（排除 deleted 的记录）
                ResultSet rsAgents = stmt.executeQuery("SELECT COUNT(*) FROM agents WHERE enabled = true");
                rsAgents.next();
                stats.put("agents", rsAgents.getInt(1));
                
                ResultSet rsSessions = stmt.executeQuery("SELECT COUNT(*) FROM sessions WHERE deleted = false");
                rsSessions.next();
                stats.put("sessions", rsSessions.getInt(1));
                
                ResultSet rsMessages = stmt.executeQuery("SELECT COUNT(*) FROM messages WHERE deleted = false");
                rsMessages.next();
                stats.put("messages", rsMessages.getInt(1));
                
                ResultSet rsErrors = stmt.executeQuery("SELECT COUNT(*) FROM experiences WHERE type = 'error_correction' AND deleted = false");
                rsErrors.next();
                stats.put("errors", rsErrors.getInt(1));
                
                ResultSet rsProfiles = stmt.executeQuery("SELECT COUNT(*) FROM user_profiles");
                rsProfiles.next();
                stats.put("profiles", rsProfiles.getInt(1));
                
                ResultSet rsPractices = stmt.executeQuery("SELECT COUNT(*) FROM experiences WHERE type = 'best_practice' AND deleted = false");
                rsPractices.next();
                stats.put("practices", rsPractices.getInt(1));
                
                ResultSet rsContexts = stmt.executeQuery("SELECT COUNT(*) FROM experiences WHERE deleted = false");
                rsContexts.next();
                stats.put("contexts", rsContexts.getInt(1));
                
                ResultSet rsSkills = stmt.executeQuery("SELECT COUNT(*) FROM skills");
                rsSkills.next();
                stats.put("skills", rsSkills.getInt(1));
                
                // 添加总计数据（不区分 deleted 状态）
                ResultSet rsTotalSessions = stmt.executeQuery("SELECT COUNT(*) FROM sessions");
                rsTotalSessions.next();
                stats.put("totalSessions", rsTotalSessions.getInt(1));
                
                ResultSet rsTotalMessages = stmt.executeQuery("SELECT COUNT(*) FROM messages");
                rsTotalMessages.next();
                stats.put("totalMessages", rsTotalMessages.getInt(1));
                
                // 近30天每日会话数
                List<Map<String, Object>> dailySessions = new ArrayList<>();
                try (ResultSet rsDailySessions = stmt.executeQuery(
                    "SELECT DATE(created_at) as date, COUNT(*) as count FROM sessions " +
                    "WHERE created_at >= CURRENT_DATE - INTERVAL '30 days' AND deleted = false " +
                    "GROUP BY DATE(created_at) ORDER BY date")) {
                    while (rsDailySessions.next()) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("date", rsDailySessions.getString("date"));
                        row.put("count", rsDailySessions.getInt("count"));
                        dailySessions.add(row);
                    }
                } catch (Exception e) { log.warn("dailySessions query failed: {}", e.getMessage()); }
                stats.put("dailySessions", dailySessions);
                
                // 近30天每日消息数
                List<Map<String, Object>> dailyMessages = new ArrayList<>();
                try (ResultSet rsDailyMessages = stmt.executeQuery(
                    "SELECT DATE(timestamp) as date, COUNT(*) as count FROM messages " +
                    "WHERE timestamp >= CURRENT_DATE - INTERVAL '30 days' AND deleted = false " +
                    "GROUP BY DATE(timestamp) ORDER BY date")) {
                    while (rsDailyMessages.next()) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("date", rsDailyMessages.getString("date"));
                        row.put("count", rsDailyMessages.getInt("count"));
                        dailyMessages.add(row);
                    }
                } catch (Exception e) { log.warn("dailyMessages query failed: {}", e.getMessage()); }
                stats.put("dailyMessages", dailyMessages);
                
                // 按 agent_type 分组的会话分布
                List<Map<String, Object>> agentDistribution = new ArrayList<>();
                try (ResultSet rsAgentDist = stmt.executeQuery(
                    "SELECT COALESCE(agent_type, '未知') as agent_type, COUNT(*) as count " +
                    "FROM sessions WHERE deleted = false GROUP BY agent_type ORDER BY count DESC")) {
                    while (rsAgentDist.next()) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("agentType", rsAgentDist.getString("agent_type"));
                        row.put("count", rsAgentDist.getInt("count"));
                        agentDistribution.add(row);
                    }
                } catch (Exception e) { log.warn("agentDistribution query failed: {}", e.getMessage()); }
                stats.put("agentDistribution", agentDistribution);
                
                // 各记忆模块数量分布
                List<Map<String, Object>> memoryDistribution = new ArrayList<>();
                String[][] memoryTypes = {
                    {"错误纠正", "SELECT COUNT(*) FROM experiences WHERE type = 'error_correction' AND deleted = false"},
                    {"实践经验", "SELECT COUNT(*) FROM experiences WHERE type = 'best_practice' AND deleted = false"},
                    {"用户画像", "SELECT COUNT(*) FROM user_profiles"},
                    {"技能沉淀", "SELECT COUNT(*) FROM skills"}
                };
                for (String[] typeInfo : memoryTypes) {
                    try (ResultSet rsType = stmt.executeQuery(typeInfo[1])) {
                        if (rsType.next()) {
                            Map<String, Object> row = new HashMap<>();
                            row.put("type", typeInfo[0]);
                            row.put("count", rsType.getInt(1));
                            memoryDistribution.add(row);
                        }
                    } catch (Exception e) { log.warn("memoryDistribution {} failed: {}", typeInfo[0], e.getMessage()); }
                }
                stats.put("memoryDistribution", memoryDistribution);
                
            } catch (SQLException e) {
                log.error("查询统计失败", e);
                stats.put("agents", 0);
                stats.put("sessions", 0);
                stats.put("messages", 0);
                stats.put("errors", 0);
                stats.put("profiles", 0);
                stats.put("practices", 0);
                stats.put("contexts", 0);
                stats.put("skills", 0);
                stats.put("dailySessions", new ArrayList<>());
                stats.put("dailyMessages", new ArrayList<>());
                stats.put("agentDistribution", new ArrayList<>());
                stats.put("memoryDistribution", new ArrayList<>());
            }
            
            sendJson(exchange, stats);
        }
    }
    
    class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "仅支持 POST 方法");
                return;
            }

            try {
                // 读取请求体
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                @SuppressWarnings("unchecked")
                Map<String, Object> request = objectMapper.readValue(body, Map.class);

                String query = (String) request.get("query");
                int limit = request.containsKey("limit") ? ((Number) request.get("limit")).intValue() : 20;

                if (query == null || query.trim().isEmpty()) {
                    sendError(exchange, 400, "query 不能为空");
                    return;
                }

                List<Map<String, Object>> results = new ArrayList<>();

                // 尝试语义搜索
                try {
                    java.net.URL embedUrl = URI.create("http://localhost:8100/embed").toURL();
                    java.net.HttpURLConnection embedConn = (java.net.HttpURLConnection) embedUrl.openConnection();
                    embedConn.setRequestMethod("POST");
                    embedConn.setRequestProperty("Content-Type", "application/json");
                    embedConn.setDoOutput(true);
                    embedConn.setConnectTimeout(2000); // 2秒超时
                    embedConn.setReadTimeout(2000);

                    String embedRequest = objectMapper.writeValueAsString(Map.of("texts", List.of(query)));
                    try (OutputStream os = embedConn.getOutputStream()) {
                        os.write(embedRequest.getBytes(StandardCharsets.UTF_8));
                    }

                    if (embedConn.getResponseCode() == 200) {
                        // Embedding 服务可用，使用语义搜索
                        String embedResponse = new String(embedConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        Map<String, Object> embedResult = objectMapper.readValue(embedResponse, Map.class);
                        @SuppressWarnings("unchecked")
                        List<List<Double>> embeddings = (List<List<Double>>) embedResult.get("embeddings");

                        if (embeddings != null && !embeddings.isEmpty()) {
                            // 构建向量字符串
                            StringBuilder vecStr = new StringBuilder("[");
                            List<Double> vec = embeddings.get(0);
                            for (int i = 0; i < vec.size(); i++) {
                                if (i > 0) vecStr.append(",");
                                vecStr.append(vec.get(i));
                            }
                            vecStr.append("]");

                            try (Connection conn = databaseService.getConnection()) {
                                semanticSearchTable(conn, "experiences", "BEST_PRACTICE", vecStr.toString(), limit, results, "type = 'best_practice'");
                                semanticSearchTable(conn, "experiences", "ERROR_CORRECTION", vecStr.toString(), limit, results, "type = 'error_correction'");
                                semanticSearchTable(conn, "skills", "SKILL", vecStr.toString(), limit, results, null);
                            }

                            // 按相似度排序
                            results.sort((a, b) -> Double.compare((Double) b.get("similarity"), (Double) a.get("similarity")));

                            // 限制结果数量
                            if (results.size() > limit) {
                                results = results.subList(0, limit);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Embedding 服务不可用，回退到文本搜索: {}", e.getMessage());
                }

                // 如果语义搜索没有结果，使用文本搜索
                if (results.isEmpty()) {
                    try (Connection conn = databaseService.getConnection()) {
                        textSearchTable(conn, "experiences", "BEST_PRACTICE", query, limit, results);
                        textSearchTable(conn, "experiences", "ERROR_CORRECTION", query, limit, results);
                        textSearchTable(conn, "skills", "SKILL", query, limit, results);
                    }

                    // 去重（按 ID）
                    Map<String, Map<String, Object>> uniqueResults = new HashMap<>();
                    for (Map<String, Object> item : results) {
                        uniqueResults.put((String) item.get("id"), item);
                    }
                    results = new ArrayList<>(uniqueResults.values());

                    // 限制结果数量
                    if (results.size() > limit) {
                        results = results.subList(0, limit);
                    }
                }

                sendJson(exchange, results);

            } catch (Exception e) {
                log.error("搜索失败", e);
                sendError(exchange, 500, "搜索失败: " + e.getMessage());
            }
        }

        private void semanticSearchTable(Connection conn, String table, String type,
                                         String vecStr, int limit, List<Map<String, Object>> results,
                                         String extraWhere) throws SQLException {
            String sql = buildSemanticSearchSql(table, vecStr, extraWhere);

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limit / 2);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("type", type);
                    item.put("title", rs.getString("title"));
                    item.put("content", rs.getString("content"));
                    item.put("similarity", rs.getDouble("similarity"));
                    results.add(item);
                }
            }
        }

        private String buildSemanticSearchSql(String table, String vecStr, String extraWhere) {
            String contentField = getContentField(table);
            String where = "WHERE embedding IS NOT NULL AND (deleted = false OR deleted IS NULL)";
            if (extraWhere != null && !extraWhere.isBlank()) {
                where += " AND " + extraWhere;
            }
            return String.format(
                "SELECT id, title, %s as content, " +
                "1 - (embedding <=> '%s'::vector) as similarity FROM %s " +
                "%s ORDER BY similarity DESC LIMIT ?",
                contentField, vecStr, table, where
            );
        }

        private void textSearchTable(Connection conn, String table, String type,
                                      String query, int limit, List<Map<String, Object>> results) throws SQLException {
            String sql = buildTextSearchSql(table);

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                String searchPattern = "%" + query + "%";
                String[] fields = getSearchFields(table);
                for (int i = 0; i < fields.length; i++) {
                    stmt.setString(i + 1, searchPattern);
                }
                stmt.setInt(fields.length + 1, limit);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("type", type);
                    item.put("title", rs.getString("title"));
                    item.put("content", rs.getString("content"));
                    item.put("similarity", 0.0);
                    results.add(item);
                }
            }
        }

        private String buildTextSearchSql(String table) {
            String contentField = getContentField(table);
            String[] fields = getSearchFields(table);
            StringBuilder whereClause = new StringBuilder("(");
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) whereClause.append(" OR ");
                whereClause.append(fields[i]).append(" ILIKE ?");
            }
            whereClause.append(")");
            return String.format(
                "SELECT id, title, %s as content FROM %s " +
                "WHERE (deleted = false OR deleted IS NULL) AND %s LIMIT ?",
                contentField, table, whereClause.toString()
            );
        }

        private String getContentField(String table) {
            switch (table) {
                case "experiences": return "COALESCE(practice, scenario, rationale, '')";
                case "user_profiles": return "COALESCE(items::text, category, '')";
                case "skills": return "COALESCE(description, steps::text, '')";
                default: return "COALESCE(description, '')";
            }
        }

        private String[] getSearchFields(String table) {
            switch (table) {
                case "experiences": return new String[]{"title", "scenario", "practice", "rationale"};
                case "user_profiles": return new String[]{"title", "category"};
                case "skills": return new String[]{"title", "description"};
                default: return new String[]{"title"};
            }
        }
    }
    
    class StaticFileHandler implements HttpHandler {
        // 项目根目录（backend 的上级目录）
        private final String projectRoot = System.getProperty("user.dir").contains("backend") 
            ? new java.io.File(System.getProperty("user.dir")).getParent() 
            : System.getProperty("user.dir");
        
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }

            // 使用绝对路径
            java.io.File file = new java.io.File(projectRoot + "/frontend/dist" + path);
            if (!file.exists()) {
                file = new java.io.File(projectRoot + "/frontend" + path);
            }

            // 路径安全验证：防止路径遍历攻击
            try {
                String canonicalPath = file.getCanonicalPath();
                String allowedRoot = new java.io.File(projectRoot + "/frontend").getCanonicalPath();
                if (!canonicalPath.startsWith(allowedRoot)) {
                    sendError(exchange, 403, "Access denied");
                    return;
                }
            } catch (Exception e) {
                sendError(exchange, 403, "Invalid path");
                return;
            }

            if (file.exists() && file.isFile()) {
                String contentType = getContentType(path);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, file.length());
                
                try (OutputStream os = exchange.getResponseBody();
                     java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    fis.transferTo(os);
                }
            } else {
                // 返回 index.html（SPA 路由支持）
                java.io.File indexFile = new java.io.File(projectRoot + "/frontend/dist/index.html");
                if (!indexFile.exists()) {
                    indexFile = new java.io.File(projectRoot + "/frontend/index.html");
                }
                
                if (indexFile.exists()) {
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, indexFile.length());
                    
                    try (OutputStream os = exchange.getResponseBody();
                         java.io.FileInputStream fis = new java.io.FileInputStream(indexFile)) {
                        fis.transferTo(os);
                    }
                } else {
                    String html = "<html><body><h1>AgentMemory API</h1><p>前端未构建，请先运行前端构建命令</p></body></html>";
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, html.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(html.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }
        
        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (path.endsWith(".css")) return "text/css; charset=UTF-8";
            if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (path.endsWith(".json")) return "application/json; charset=UTF-8";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
            if (path.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }
    }
    
    // ===== 压缩 API Handler =====
    class CompressionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                
                // POST /api/compression/test-llm: 测试 LLM 连接
                if ("POST".equalsIgnoreCase(method) && path.endsWith("/test-llm")) {
                    Map<String, Object> input = readRequestBody(exchange);
                    String providerName = (String) input.get("providerName");
                    String baseUrl = (String) input.get("baseUrl");

                    // 兜底：Ollama/本地模型未填 baseUrl 时默认使用 localhost:11434
                    if ((baseUrl == null || baseUrl.isBlank()) &&
                        ("ollama".equalsIgnoreCase(providerName) || "local".equalsIgnoreCase(providerName))) {
                        baseUrl = "http://localhost:11434";
                    }

                    if (baseUrl == null || baseUrl.isBlank()) {
                        sendJson(exchange, Map.of("success", false, "error", "Base URL 不能为空"));
                        return;
                    }

                    try {
                        // 标准化 baseUrl（移除末尾的 /api/* 路径）
                        String cleanBaseUrl = baseUrl.replaceAll("/(api/[^/]*|v1)/?$", "");
                        
                        // 简单测试：发送一个简单的请求到 LLM 服务
                        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofSeconds(10))
                                .build();
                        
                        String testUrl;
                        if (providerName.equalsIgnoreCase("ollama") || providerName.equalsIgnoreCase("local")) {
                            testUrl = cleanBaseUrl + "/api/tags";
                            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                                    .uri(java.net.URI.create(testUrl))
                                    .timeout(java.time.Duration.ofSeconds(10))
                                    .GET()
                                    .build();
                            java.net.http.HttpResponse<String> response = httpClient.send(request, 
                                    java.net.http.HttpResponse.BodyHandlers.ofString());
                            if (response.statusCode() == 200) {
                                sendJson(exchange, Map.of("success", true, "message", "Ollama 服务可达"));
                            } else {
                                sendJson(exchange, Map.of("success", false, "error", "Ollama 响应: " + response.statusCode()));
                            }
                        } else {
                            // OpenAI 兼容 API：检查 models 端点
                            testUrl = cleanBaseUrl + "/v1/models";
                            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                                    .uri(java.net.URI.create(testUrl))
                                    .timeout(java.time.Duration.ofSeconds(10))
                                    .GET()
                                    .build();
                            java.net.http.HttpResponse<String> response = httpClient.send(request, 
                                    java.net.http.HttpResponse.BodyHandlers.ofString());
                            if (response.statusCode() == 200 || response.statusCode() == 401 || response.statusCode() == 404) {
                                // 200 = 有 key 可访问，401 = 端点存在但需要 key，404 = 端点可能不同但服务可达
                                sendJson(exchange, Map.of("success", true, "message", "API 端点可达"));
                            } else {
                                sendJson(exchange, Map.of("success", false, "error", "API 响应: " + response.statusCode()));
                            }
                        }
                    } catch (java.net.ConnectException e) {
                        sendJson(exchange, Map.of("success", false, "error", "无法连接到服务，请检查地址和端口"));
                    } catch (Exception e) {
                        sendJson(exchange, Map.of("success", false, "error", e.getMessage()));
                    }
                    return;
                }
                
                // GET: 获取压缩统计和配置
                if ("GET".equalsIgnoreCase(method)) {
                    // 获取统计信息
                    Map<String, Object> stats = new HashMap<>();
                    
                    // 总会话数
                    try (Connection conn = databaseService.getConnection();
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions")) {
                        if (rs.next()) stats.put("totalSessions", rs.getInt(1));
                    }
                    
                    // 已压缩会话数
                    try (Connection conn = databaseService.getConnection();
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM session_summaries")) {
                        if (rs.next()) stats.put("compressedSessions", rs.getInt(1));
                    }
                    
                    // 待压缩会话数（实际消息数超过阈值且未压缩的）
                    try (Connection conn = databaseService.getConnection();
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(
                             "SELECT COUNT(*) FROM sessions s WHERE " +
                             "(SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id AND (m.deleted = false OR m.deleted IS NULL)) > COALESCE(" +
                             "(SELECT summary_threshold FROM compression_config WHERE config_key = 'session_compression'), 100) " +
                             "AND (s.is_compressed = false OR s.is_compressed IS NULL)")) {
                        if (rs.next()) stats.put("pendingSessions", rs.getInt(1));
                    }
                    
                    // 压缩消息总数
                    try (Connection conn = databaseService.getConnection();
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT COALESCE(SUM(original_message_count), 0) FROM session_summaries")) {
                        if (rs.next()) stats.put("totalMessages", rs.getInt(1));
                    }
                    
                    // 获取配置
                    Map<String, Object> config = new HashMap<>();
                    try (Connection conn = databaseService.getConnection();
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(
                             "SELECT window_size, summary_threshold, auto_compress, compression_type, llm_provider " +
                             "FROM compression_config WHERE config_key = 'session_compression'")) {
                        if (rs.next()) {
                            config.put("windowSize", rs.getInt("window_size"));
                            config.put("summaryThreshold", rs.getInt("summary_threshold"));
                            config.put("autoCompress", rs.getBoolean("auto_compress"));
                            config.put("compressionType", rs.getString("compression_type"));
                            config.put("llmProvider", rs.getString("llm_provider"));
                        } else {
                            config.put("windowSize", 50);
                            config.put("summaryThreshold", 100);
                            config.put("autoCompress", true);
                            config.put("compressionType", "SLIDING_WINDOW");
                            config.put("llmProvider", "__builtin__");
                        }
                    }
                    
                    // 获取摘要列表
                    List<Map<String, Object>> summaries = new ArrayList<>();
                    try (Connection conn = databaseService.getConnection();
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(
                             "SELECT session_id, summary, compression_type, original_message_count, created_at " +
                             "FROM session_summaries ORDER BY created_at DESC LIMIT 50")) {
                        while (rs.next()) {
                            Map<String, Object> row = new HashMap<>();
                            row.put("sessionId", rs.getString("session_id"));
                            row.put("summary", rs.getString("summary"));
                            row.put("compressionType", rs.getString("compression_type"));
                            row.put("messageCount", rs.getInt("original_message_count"));
                            row.put("compressedAt", rs.getTimestamp("created_at").toString());
                            summaries.add(row);
                        }
                    }
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("stats", stats);
                    result.put("config", config);
                    result.put("summaries", summaries);
                    sendJson(exchange, result);
                    return;
                }
                
                // POST: 保存配置（排除 /compress 子路径）
                if ("POST".equalsIgnoreCase(method) && !path.endsWith("/compress")) {
                    Map<String, Object> input = readRequestBody(exchange);
                    
                    String sql = "INSERT INTO compression_config (config_key, window_size, summary_threshold, auto_compress, compression_type, llm_provider) " +
                            "VALUES ('session_compression', ?, ?, ?, ?, ?) " +
                            "ON CONFLICT(config_key) DO UPDATE SET window_size = ?, summary_threshold = ?, auto_compress = ?, compression_type = ?, llm_provider = ?";
                    
                    executeUpdate(sql, stmt -> {
                        stmt.setInt(1, (Integer) input.getOrDefault("windowSize", 50));
                        stmt.setInt(2, (Integer) input.getOrDefault("summaryThreshold", 100));
                        stmt.setBoolean(3, (Boolean) input.getOrDefault("autoCompress", true));
                        stmt.setString(4, (String) input.getOrDefault("compressionType", "SLIDING_WINDOW"));
                        stmt.setString(5, (String) input.getOrDefault("llmProvider", "__builtin__"));
                        stmt.setInt(6, (Integer) input.getOrDefault("windowSize", 50));
                        stmt.setInt(7, (Integer) input.getOrDefault("summaryThreshold", 100));
                        stmt.setBoolean(8, (Boolean) input.getOrDefault("autoCompress", true));
                        stmt.setString(9, (String) input.getOrDefault("compressionType", "SLIDING_WINDOW"));
                        stmt.setString(10, (String) input.getOrDefault("llmProvider", "__builtin__"));
                    });
                    
                    sendJson(exchange, Map.of("status", "ok"));
                    return;
                }
                
                // PUT: 手动触发压缩
                if ("PUT".equalsIgnoreCase(method)) {
                    if (compressionService == null) {
                        sendJson(exchange, Map.of("status", "error", "message", "压缩服务未初始化"));
                        return;
                    }
                    
                    // 查找需要压缩的会话（消息数超过阈值）
                    List<String> sessionsToCompress = new ArrayList<>();
                    int threshold = 100;
                    try (Connection conn = databaseService.getConnection();
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(
                             "SELECT summary_threshold FROM compression_config WHERE config_key = 'session_compression'")) {
                        if (rs.next()) threshold = rs.getInt(1);
                    }
                    
                    try (Connection conn = databaseService.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                             "SELECT s.id FROM sessions s WHERE " +
                             "(SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id AND (m.deleted = false OR m.deleted IS NULL)) > ? " +
                             "AND (s.is_compressed = false OR s.is_compressed IS NULL) LIMIT 10")) {
                        stmt.setInt(1, threshold);
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                sessionsToCompress.add(rs.getString("id"));
                            }
                        }
                    }
                    
                    if (sessionsToCompress.isEmpty()) {
                        sendJson(exchange, Map.of("status", "ok", "message", "没有需要压缩的会话", "compressedCount", 0));
                        return;
                    }
                    
                    // 执行压缩
                    int successCount = 0;
                    for (String sessionId : sessionsToCompress) {
                        if (compressionService.compressSessionManual(sessionId)) {
                            successCount++;
                        }
                    }
                    
                    sendJson(exchange, Map.of(
                        "status", "ok", 
                        "message", "压缩完成", 
                        "compressedCount", successCount,
                        "totalSessions", sessionsToCompress.size()
                    ));
                    return;
                }
                
                // POST /api/compression/compress: 压缩指定会话（可指定压缩类型）
                if ("POST".equalsIgnoreCase(method) && path.endsWith("/compress")) {
                    Map<String, Object> input = readRequestBody(exchange);
                    String sessionId = (String) input.get("sessionId");
                    String compressionType = (String) input.get("compressionType");  // 可选
                    
                    if (sessionId == null || sessionId.isEmpty()) {
                        sendJson(exchange, Map.of("status", "error", "message", "缺少 sessionId"));
                        return;
                    }
                    
                    if (compressionService == null) {
                        sendJson(exchange, Map.of("status", "error", "message", "压缩服务未初始化"));
                        return;
                    }
                    
                    boolean success;
                    log.info("[compress-debug] 收到压缩请求 sessionId={} type={}, service={}",
                            sessionId, compressionType, compressionService != null ? compressionService.getClass().getSimpleName() : "null");
                    if (compressionType != null && !compressionType.isEmpty()) {
                        success = compressionService.compressSessionWithType(sessionId, compressionType);
                    } else {
                        success = compressionService.compressSessionManual(sessionId);
                    }
                    log.info("[compress-debug] 压缩结果: {}", success);
                    
                    if (success) {
                        sendJson(exchange, Map.of("status", "ok", "message", "压缩成功", "sessionId", sessionId, "compressionType", compressionType));
                    } else {
                        sendJson(exchange, Map.of("status", "error", "message", "压缩失败"));
                    }
                    return;
                }
                
                sendJson(exchange, Map.of("error", "Unsupported method"));
            });
        }
    }
    
    // ===== LLM Provider API Handler =====
    class LLMProviderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                String method = exchange.getRequestMethod();
                
                // GET: 获取所有 Provider
                if ("GET".equalsIgnoreCase(method)) {
                    List<Map<String, Object>> providers = queryList(
                        "SELECT id, provider_name, display_name, base_url, model, enabled, is_default, config " +
                        "FROM llm_providers ORDER BY is_default DESC, id ASC",
                        rs -> {
                            Map<String, Object> p = new HashMap<>();
                            p.put("id", rs.getInt("id"));
                            p.put("providerName", rs.getString("provider_name"));
                            p.put("displayName", rs.getString("display_name"));
                            p.put("baseUrl", rs.getString("base_url"));
                            p.put("model", rs.getString("model"));
                            p.put("enabled", rs.getBoolean("enabled"));
                            p.put("isDefault", rs.getBoolean("is_default"));
                            // 解析 config JSON 中的 thinkMode
                            String configJson = rs.getString("config");
                            if (configJson != null && !configJson.isEmpty()) {
                                try {
                                    com.fasterxml.jackson.databind.JsonNode configNode = 
                                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(configJson);
                                    p.put("thinkMode", configNode.path("thinkMode").asBoolean(false));
                                } catch (Exception e) {
                                    p.put("thinkMode", false);
                                }
                            } else {
                                p.put("thinkMode", false);
                            }
                            // 不返回 apiKey
                            p.put("apiKey", "[REDACTED]");
                            return p;
                        }
                    );
                    sendJson(exchange, providers);
                    return;
                }
                
                // POST: 添加或更新 Provider
                if ("POST".equalsIgnoreCase(method)) {
                    Map<String, Object> input = readRequestBody(exchange);
                    
                    String providerName = (String) input.get("providerName");
                    String displayName = (String) input.getOrDefault("displayName", providerName);
                    String baseUrl = (String) input.get("baseUrl");
                    String apiKey = (String) input.get("apiKey");
                    String model = (String) input.get("model");
                    Boolean enabled = (Boolean) input.getOrDefault("enabled", true);
                    Boolean isDefault = (Boolean) input.getOrDefault("isDefault", false);
                    Boolean thinkMode = (Boolean) input.getOrDefault("thinkMode", false);
                    
                    // 构建 config JSON
                    String configJson = "{\"thinkMode\":" + (thinkMode != null && thinkMode) + "}";
                    
                    // 如果设为默认，先清除其他默认
                    if (isDefault != null && isDefault) {
                        executeUpdate("UPDATE llm_providers SET is_default = false", stmt -> {});
                    }
                    
                    String sql = "INSERT INTO llm_providers (provider_name, display_name, base_url, api_key, model, enabled, is_default, config) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) " +
                            "ON CONFLICT(provider_name) DO UPDATE SET display_name = ?, base_url = ?, api_key = ?, model = ?, enabled = ?, is_default = ?, config = ?::jsonb";
                    
                    executeUpdate(sql, stmt -> {
                        stmt.setString(1, providerName);
                        stmt.setString(2, displayName);
                        stmt.setString(3, baseUrl);
                        stmt.setString(4, apiKey);
                        stmt.setString(5, model);
                        stmt.setBoolean(6, enabled);
                        stmt.setBoolean(7, isDefault != null && isDefault);
                        stmt.setString(8, configJson);
                        stmt.setString(9, displayName);
                        stmt.setString(10, baseUrl);
                        stmt.setString(11, apiKey);
                        stmt.setString(12, model);
                        stmt.setBoolean(13, enabled);
                        stmt.setBoolean(14, isDefault != null && isDefault);
                        stmt.setString(15, configJson);
                    });
                    
                    sendJson(exchange, Map.of("status", "ok"));
                    return;
                }
                
                // DELETE: 删除 Provider
                if ("DELETE".equalsIgnoreCase(method)) {
                    String path = exchange.getRequestURI().getPath();
                    String id = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : null;
                    
                    if (id != null && !id.isEmpty()) {
                        executeUpdate("DELETE FROM llm_providers WHERE id = ?", stmt -> stmt.setInt(1, Integer.parseInt(id)));
                        sendJson(exchange, Map.of("status", "ok"));
                    } else {
                        sendError(exchange, 400, "Invalid provider ID");
                    }
                    return;
                }
                
                sendJson(exchange, Map.of("error", "Unsupported method"));
            });
        }
    }
    
    // ===== Cleanup API Handler =====
    class CleanupHandler implements HttpHandler {
        
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            
            String method = exchange.getRequestMethod();
            
            try {
                if ("POST".equalsIgnoreCase(method)) {
                    // 立即执行清理
                    Map<String, Object> body = readRequestBody(exchange);
                    int days = (Integer) body.getOrDefault("days", 30);
                    
                    // 调用 CleanupService 的清理方法
                    int deleted = executeCleanup(days);
                    
                    sendJson(exchange, Map.of(
                        "status", "ok",
                        "deleted", deleted,
                        "message", "清理完成，共删除 " + deleted + " 条记录"
                    ));
                    return;
                }
                
                if ("GET".equalsIgnoreCase(method)) {
                    // 获取清理配置
                    Map<String, Object> config = getCleanupConfig();
                    sendJson(exchange, config);
                    return;
                }
                
                sendError(exchange, 405, "Method not allowed");
            } catch (Exception e) {
                log.error("清理失败", e);
                sendError(exchange, 500, "清理失败: " + e.getMessage());
            }
        }
        
        private int executeCleanup(int days) throws SQLException {
            int total = 0;
            
            // 硬删除超过指定天数的会话及其关联数据
            try (Connection conn = databaseService.getConnection()) {
                // 先删除关联的消息
                String sql = "DELETE FROM messages WHERE session_id IN (SELECT id FROM sessions WHERE created_at < NOW() - INTERVAL '" + days + " days')";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    total += stmt.executeUpdate();
                }
                
                // 删除会话
                sql = "DELETE FROM sessions WHERE created_at < NOW() - INTERVAL '" + days + " days'";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    total += stmt.executeUpdate();
                }
                
                // 删除过期的压缩历史
                sql = "DELETE FROM compression_history WHERE created_at < NOW() - INTERVAL '" + days + " days'";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    total += stmt.executeUpdate();
                }
            }
            
            return total;
        }
        
        private Map<String, Object> getCleanupConfig() throws SQLException {
            Map<String, Object> config = new HashMap<>();
            try (Connection conn = databaseService.getConnection()) {
                String sql = "SELECT config_value FROM app_config WHERE config_key = 'cleanup'";
                try (PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String value = rs.getString("config_value");
                        // 解析 JSON 格式的配置
                        config.put("enabled", value.contains("\"enabled\":true") || value.contains("\"enabled\": true"));
                        // 提取天数
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"days\":(\\d+)");
                        java.util.regex.Matcher matcher = pattern.matcher(value);
                        if (matcher.find()) {
                            config.put("days", Integer.parseInt(matcher.group(1)));
                        } else {
                            config.put("days", 30);
                        }
                    } else {
                        config.put("enabled", false);
                        config.put("days", 30);
                    }
                }
            }
            if (config.isEmpty()) {
                config.put("enabled", false);
                config.put("days", 30);
            }
            return config;
        }
    }

    /**
     * 记忆同步 handler：POST /api/sync 手动触发画像同步 + SQLite 会话导入
     */
    class MemorySyncHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method Not Allowed");
                    return;
                }
                if (memorySyncService == null) {
                    sendError(exchange, 500, "AgentMemorySyncService 未初始化");
                    return;
                }
                Map<String, Object> result = new HashMap<>();
                result.put("profiles", memorySyncService.syncAllProfiles());
                result.put("sessions", memorySyncService.importAllSqliteSessions());
                sendJson(exchange, result);
            });
        }
    }

    /**
     * 单 agent 同步 handler：POST /api/agents/sync 同步指定 agent（body: {"agent":"hermes"}）
     */
    class AgentSyncHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method Not Allowed");
                    return;
                }
                if (memorySyncService == null) {
                    sendError(exchange, 500, "AgentMemorySyncService 未初始化");
                    return;
                }
                Map<String, Object> body = readRequestBody(exchange);
                String agent = body != null ? (String) body.get("agent") : null;
                if (agent == null || agent.isBlank()) {
                    sendError(exchange, 400, "缺少 agent 参数");
                    return;
                }
                Map<String, Object> result = new HashMap<>();
                // 单个 agent 同步（画像 + 会话导入）
                result.put("agent", agent);
                result.put("synced", syncSingleAgent(agent));
                sendJson(exchange, result);
            });
        }
    }

    /** 同步单个 agent（支持 hermes/mavis/marvis 会话导入 + 所有画像源） */
    private boolean syncSingleAgent(String agent) {
        try {
            switch (agent) {
                case "hermes" -> memorySyncService.importSessionsFromDb("hermes",
                        "C:/Users/31936/.hermes/state.db");
                case "mavis" -> memorySyncService.importSessionsFromDb("mavis",
                        "C:/Users/31936/.mavis/sqlite.db");
                case "marvis" -> memorySyncService.importSessionsFromDb("marvis",
                        "C:/Users/31936/.marvis/database/memory.db");
                default -> { return true; } // 画像类 agent 由全量同步覆盖
            }
            return true;
        } catch (Exception e) {
            log.error("同步 agent {} 失败: {}", agent, e.getMessage());
            return false;
        }
    }
}

// UNIQUE_MARKER_20260808
