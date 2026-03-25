package com.agentmemory.api;

import com.agentmemory.service.DatabaseService;
import com.agentmemory.service.FileWatcherService;
import com.agentmemory.service.SessionCompressionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP API 服务
 * 提供前端调用的 RESTful API
 */
public class ApiServer {
    
    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);
    
    private final DatabaseService databaseService;
    private final FileWatcherService fileWatcherService;
    private final ObjectMapper objectMapper;
    private HttpServer server;
    private final int port;
    private SessionCompressionService compressionService;
    
    public ApiServer(DatabaseService databaseService, FileWatcherService fileWatcherService, int port) {
        this.databaseService = databaseService;
        this.fileWatcherService = fileWatcherService;
        this.port = port;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    
    public void setCompressionService(SessionCompressionService compressionService) {
        this.compressionService = compressionService;
    }
    
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // 注册路由
        server.createContext("/api/agents", new AgentsHandler());
        server.createContext("/api/sessions", new SessionsHandler());
        server.createContext("/api/messages", new MessagesHandler());
        server.createContext("/api/errors", new ErrorCorrectionsHandler());
        server.createContext("/api/profiles", new UserProfilesHandler());
        server.createContext("/api/practices", new BestPracticesHandler());
        server.createContext("/api/contexts", new ProjectContextsHandler());
        server.createContext("/api/skills", new SkillsHandler());
        server.createContext("/api/stats", new StatsHandler());
        server.createContext("/api/search", new SearchHandler());
        server.createContext("/api/compression", new CompressionHandler());
        server.createContext("/api/llm-providers", new LLMProviderHandler());
        server.createContext("/api/cleanup", new CleanupHandler());
        
        // 静态文件服务（前端）
        server.createContext("/", new StaticFileHandler());
        
        server.setExecutor(null);
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
        // CORS 处理
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("error", true);
        error.put("message", message);

        String json = objectMapper.writeValueAsString(error);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
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
                    Boolean enabled = input.containsKey("enabled") ? (Boolean) input.get("enabled") : true;
                    
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
                
                // GET: 列出所有 Agents
                List<Map<String, Object>> agents = queryList(
                    "SELECT * FROM agents ORDER BY name",
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

            String query = exchange.getRequestURI().getQuery();
            int limit = 50;
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

            // 使用 PreparedStatement 防止 SQL 注入
            // 支持 agent_types 数组查询：同一个 session 可以属于多个 agent
            String sql = "SELECT * FROM sessions WHERE deleted = false";
            if (agentType != null) {
                sql += " AND (? = ANY(agent_types) OR agent_type = ?)";
            }
            sql += " ORDER BY created_at DESC LIMIT ? OFFSET ?";

            try (Connection conn = databaseService.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                int paramIndex = 1;
                if (agentType != null) {
                    stmt.setString(paramIndex++, agentType);
                    stmt.setString(paramIndex++, agentType);
                }
                stmt.setInt(paramIndex++, limit);
                stmt.setInt(paramIndex, offset);

                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> sessions = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> session = new HashMap<>();
                    session.put("id", rs.getString("id"));
                    session.put("agentType", rs.getString("agent_type"));
                    // 将 java.sql.Array 转换为 String[] 以支持 JSON 序列化
                    java.sql.Array sqlArray = rs.getArray("agent_types");
                    session.put("agentTypes", sqlArray != null ? (String[]) sqlArray.getArray() : null);
                    session.put("projectPath", rs.getString("project_path"));
                    session.put("messageCount", rs.getInt("message_count"));
                    session.put("createdAt", rs.getTimestamp("created_at"));
                    session.put("expiresAt", rs.getTimestamp("expires_at"));
                    sessions.add(session);
                }

                sendJson(exchange, sessions);
            } catch (SQLException e) {
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
                    msg.put("timestamp", rs.getTimestamp("timestamp"));
                    messages.add(msg);
                }
                
                sendJson(exchange, messages);
            } catch (SQLException e) {
                sendError(exchange, 500, e.getMessage());
            }
        }
    }
    
    class ErrorCorrectionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();

                if ("GET".equals(method)) {
                    if (path.endsWith("/export")) {
                        handleExport(exchange);
                    } else if (path.matches("/api/errors/[^/]+")) {
                        handleGetSingle(exchange, parseIdFromPath(path, "/api/errors/"));
                    } else {
                        handleList(exchange);
                    }
                } else if ("POST".equals(method)) {
                    handleCreate(exchange);
                } else if ("PUT".equals(method)) {
                    handleUpdate(exchange, parseIdFromPath(path, "/api/errors/"));
                } else if ("DELETE".equals(method)) {
                    handleDelete(exchange, parseIdFromPath(path, "/api/errors/"));
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            });
        }

        private void handleList(HttpExchange exchange) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM error_corrections WHERE deleted = false ORDER BY created_at DESC LIMIT 100",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("problem", rs.getString("problem"));
                    item.put("cause", rs.getString("cause"));
                    item.put("solution", rs.getString("solution"));
                    item.put("example", rs.getString("example"));
                    item.put("tags", sqlArrayToList(rs.getArray("tags")));
                    item.put("createdAt", rs.getTimestamp("created_at"));
                    return item;
                }
            );
            sendJson(exchange, items);
        }

        private void handleGetSingle(HttpExchange exchange, String id) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM error_corrections WHERE id = ? AND (deleted = false OR deleted IS NULL)",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("problem", rs.getString("problem"));
                    item.put("cause", rs.getString("cause"));
                    item.put("solution", rs.getString("solution"));
                    item.put("example", rs.getString("example"));
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
            validateRequiredFields(body, "title", "problem", "solution");

            String id = generateId();
            try (Connection conn = databaseService.getConnection()) {
                String sql = "INSERT INTO error_corrections (id, title, problem, cause, solution, example, tags, created_at, deleted) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, false)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, id);
                    stmt.setString(2, (String) body.get("title"));
                    stmt.setString(3, (String) body.get("problem"));
                    stmt.setString(4, (String) body.get("cause"));
                    stmt.setString(5, (String) body.get("solution"));
                    stmt.setString(6, (String) body.get("example"));

                    List<String> tagsList = (List<String>) body.getOrDefault("tags", new ArrayList<String>());
                    stmt.setArray(7, conn.createArrayOf("TEXT", tagsList.toArray()));
                    stmt.executeUpdate();
                }
            }
            handleGetSingle(exchange, id);
        }

        private void handleUpdate(HttpExchange exchange, String id) throws SQLException, IOException {
            Map<String, Object> body = readRequestBody(exchange);
            validateRequiredFields(body, "title", "problem", "solution");

            try (Connection conn = databaseService.getConnection()) {
                String sql = "UPDATE error_corrections SET title=?, problem=?, cause=?, solution=?, example=?, tags=? WHERE id=?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, (String) body.get("title"));
                    stmt.setString(2, (String) body.get("problem"));
                    stmt.setString(3, (String) body.get("cause"));
                    stmt.setString(4, (String) body.get("solution"));
                    stmt.setString(5, (String) body.get("example"));

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
            try (Connection conn = databaseService.getConnection()) {
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE error_corrections SET deleted=true WHERE id=?")) {
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
                "SELECT * FROM error_corrections WHERE deleted = false ORDER BY created_at DESC",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("problem", rs.getString("problem"));
                    item.put("cause", rs.getString("cause"));
                    item.put("solution", rs.getString("solution"));
                    item.put("example", rs.getString("example"));
                    item.put("tags", sqlArrayToList(rs.getArray("tags")));
                    item.put("createdAt", rs.getTimestamp("created_at"));
                    return item;
                }
            );
            exportAsJson(exchange, items, "error_corrections");
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
    
    class BestPracticesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();

                if ("GET".equals(method)) {
                    if (path.endsWith("/export")) {
                        handleExport(exchange);
                    } else if (path.matches("/api/practices/[^/]+")) {
                        handleGetSingle(exchange, parseIdFromPath(path, "/api/practices/"));
                    } else {
                        handleList(exchange);
                    }
                } else if ("POST".equals(method)) {
                    handleCreate(exchange);
                } else if ("PUT".equals(method)) {
                    handleUpdate(exchange, parseIdFromPath(path, "/api/practices/"));
                } else if ("DELETE".equals(method)) {
                    handleDelete(exchange, parseIdFromPath(path, "/api/practices/"));
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            });
        }

        private void handleList(HttpExchange exchange) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM best_practices WHERE deleted = false ORDER BY created_at DESC LIMIT 100",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("scenario", rs.getString("scenario"));
                    item.put("practice", rs.getString("practice"));
                    item.put("rationale", rs.getString("rationale"));
                    item.put("tags", sqlArrayToList(rs.getArray("tags")));
                    item.put("createdAt", rs.getTimestamp("created_at"));
                    return item;
                }
            );
            sendJson(exchange, items);
        }

        private void handleGetSingle(HttpExchange exchange, String id) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM best_practices WHERE id = ? AND (deleted = false OR deleted IS NULL)",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("scenario", rs.getString("scenario"));
                    item.put("practice", rs.getString("practice"));
                    item.put("rationale", rs.getString("rationale"));
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
            validateRequiredFields(body, "title", "scenario", "practice");

            String id = generateId();
            try (Connection conn = databaseService.getConnection()) {
                String sql = "INSERT INTO best_practices (id, title, scenario, practice, rationale, tags, created_at, deleted) " +
                            "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, false)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, id);
                    stmt.setString(2, (String) body.get("title"));
                    stmt.setString(3, (String) body.get("scenario"));
                    stmt.setString(4, (String) body.get("practice"));
                    stmt.setString(5, (String) body.get("rationale"));

                    List<String> tagsList = (List<String>) body.getOrDefault("tags", new ArrayList<String>());
                    stmt.setArray(6, conn.createArrayOf("TEXT", tagsList.toArray()));
                    stmt.executeUpdate();
                }
            }
            handleGetSingle(exchange, id);
        }

        private void handleUpdate(HttpExchange exchange, String id) throws SQLException, IOException {
            Map<String, Object> body = readRequestBody(exchange);
            validateRequiredFields(body, "title", "scenario", "practice");

            try (Connection conn = databaseService.getConnection()) {
                String sql = "UPDATE best_practices SET title=?, scenario=?, practice=?, rationale=?, tags=? WHERE id=?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, (String) body.get("title"));
                    stmt.setString(2, (String) body.get("scenario"));
                    stmt.setString(3, (String) body.get("practice"));
                    stmt.setString(4, (String) body.get("rationale"));

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
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE best_practices SET deleted=true WHERE id=?")) {
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
                "SELECT * FROM best_practices WHERE deleted = false ORDER BY created_at DESC",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("scenario", rs.getString("scenario"));
                    item.put("practice", rs.getString("practice"));
                    item.put("rationale", rs.getString("rationale"));
                    item.put("tags", sqlArrayToList(rs.getArray("tags")));
                    item.put("createdAt", rs.getTimestamp("created_at"));
                    return item;
                }
            );
            exportAsJson(exchange, items, "best_practices");
        }
    }
    
    class ProjectContextsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            wrapHandler(exchange, () -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();

                if ("GET".equals(method)) {
                    if (path.endsWith("/export")) {
                        handleExport(exchange);
                    } else if (path.matches("/api/contexts/[^/]+")) {
                        handleGetSingle(exchange, parseIdFromPath(path, "/api/contexts/"));
                    } else {
                        handleList(exchange);
                    }
                } else if ("POST".equals(method)) {
                    handleCreate(exchange);
                } else if ("PUT".equals(method)) {
                    handleUpdate(exchange, parseIdFromPath(path, "/api/contexts/"));
                } else if ("DELETE".equals(method)) {
                    handleDelete(exchange, parseIdFromPath(path, "/api/contexts/"));
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            });
        }

        private void handleList(HttpExchange exchange) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM project_contexts WHERE (deleted = false OR deleted IS NULL) ORDER BY updated_at DESC",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("projectPath", rs.getString("project_path"));
                    item.put("techStack", sqlArrayToList(rs.getArray("tech_stack")));
                    item.put("keyDecisions", rs.getString("key_decisions"));
                    item.put("structure", rs.getString("structure"));
                    item.put("updatedAt", rs.getTimestamp("updated_at"));
                    return item;
                }
            );
            sendJson(exchange, items);
        }

        private void handleGetSingle(HttpExchange exchange, String id) throws SQLException, IOException {
            List<Map<String, Object>> items = queryList(
                "SELECT * FROM project_contexts WHERE id = ? AND (deleted = false OR deleted IS NULL)",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("projectPath", rs.getString("project_path"));
                    item.put("techStack", sqlArrayToList(rs.getArray("tech_stack")));
                    item.put("keyDecisions", rs.getString("key_decisions"));
                    item.put("structure", rs.getString("structure"));
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
            validateRequiredFields(body, "title", "projectPath");

            String id = generateId();
            try (Connection conn = databaseService.getConnection()) {
                String sql = "INSERT INTO project_contexts (id, title, project_path, tech_stack, key_decisions, structure, updated_at, deleted) " +
                            "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, false)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, id);
                    stmt.setString(2, (String) body.get("title"));
                    stmt.setString(3, (String) body.get("projectPath"));

                    List<String> techStackList = (List<String>) body.getOrDefault("techStack", new ArrayList<String>());
                    stmt.setArray(4, conn.createArrayOf("TEXT", techStackList.toArray()));
                    stmt.setString(5, (String) body.get("keyDecisions"));
                    stmt.setString(6, (String) body.get("structure"));
                    stmt.executeUpdate();
                }
            }
            handleGetSingle(exchange, id);
        }

        private void handleUpdate(HttpExchange exchange, String id) throws SQLException, IOException {
            Map<String, Object> body = readRequestBody(exchange);
            validateRequiredFields(body, "title", "projectPath");

            try (Connection conn = databaseService.getConnection()) {
                String sql = "UPDATE project_contexts SET title=?, project_path=?, tech_stack=?, key_decisions=?, structure=?, updated_at=CURRENT_TIMESTAMP WHERE id=?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, (String) body.get("title"));
                    stmt.setString(2, (String) body.get("projectPath"));

                    List<String> techStackList = (List<String>) body.getOrDefault("techStack", new ArrayList<String>());
                    stmt.setArray(3, conn.createArrayOf("TEXT", techStackList.toArray()));
                    stmt.setString(4, (String) body.get("keyDecisions"));
                    stmt.setString(5, (String) body.get("structure"));
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
                try (PreparedStatement stmt = conn.prepareStatement("UPDATE project_contexts SET deleted=true WHERE id=?")) {
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
                "SELECT * FROM project_contexts WHERE (deleted = false OR deleted IS NULL) ORDER BY updated_at DESC",
                rs -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("title", rs.getString("title"));
                    item.put("projectPath", rs.getString("project_path"));
                    item.put("techStack", sqlArrayToList(rs.getArray("tech_stack")));
                    item.put("keyDecisions", rs.getString("key_decisions"));
                    item.put("structure", rs.getString("structure"));
                    item.put("updatedAt", rs.getTimestamp("updated_at"));
                    return item;
                }
            );
            exportAsJson(exchange, items, "project_contexts");
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
                    } else if (path.matches("/api/skills/[^/]+")) {
                        handleGetSingle(exchange, parseIdFromPath(path, "/api/skills/"));
                    } else {
                        handleList(exchange);
                    }
                } else if ("POST".equals(method)) {
                    handleCreate(exchange);
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
            sendJson(exchange, items);
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
    
    class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (Connection conn = databaseService.getConnection()) {
                Map<String, Object> stats = new HashMap<>();
                
                Statement stmt = conn.createStatement();
                
                ResultSet rs1 = stmt.executeQuery("SELECT COUNT(*) FROM agents");
                rs1.next();
                stats.put("agents", rs1.getInt(1));
                
                ResultSet rs2 = stmt.executeQuery("SELECT COUNT(*) FROM sessions WHERE deleted = false");
                rs2.next();
                stats.put("sessions", rs2.getInt(1));
                
                ResultSet rs3 = stmt.executeQuery("SELECT COUNT(*) FROM messages WHERE deleted = false");
                rs3.next();
                stats.put("messages", rs3.getInt(1));
                
                ResultSet rs4 = stmt.executeQuery("SELECT COUNT(*) FROM error_corrections WHERE deleted = false");
                rs4.next();
                stats.put("errors", rs4.getInt(1));
                
                ResultSet rs5 = stmt.executeQuery("SELECT COUNT(*) FROM user_profiles WHERE (deleted = false OR deleted IS NULL)");
                rs5.next();
                stats.put("profiles", rs5.getInt(1));

                ResultSet rs6 = stmt.executeQuery("SELECT COUNT(*) FROM best_practices WHERE deleted = false");
                rs6.next();
                stats.put("practices", rs6.getInt(1));

                ResultSet rs7 = stmt.executeQuery("SELECT COUNT(*) FROM project_contexts WHERE (deleted = false OR deleted IS NULL)");
                rs7.next();
                stats.put("contexts", rs7.getInt(1));

                ResultSet rs8 = stmt.executeQuery("SELECT COUNT(*) FROM skills WHERE (deleted = false OR deleted IS NULL)");
                rs8.next();
                stats.put("skills", rs8.getInt(1));
                
                sendJson(exchange, stats);
            } catch (SQLException e) {
                sendError(exchange, 500, e.getMessage());
            }
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
                    java.net.URL embedUrl = new java.net.URL("http://localhost:8100/embed");
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
                                semanticSearchTable(conn, "error_corrections", "ERROR_CORRECTION", vecStr.toString(), limit, results);
                                semanticSearchTable(conn, "best_practices", "BEST_PRACTICE", vecStr.toString(), limit, results);
                                semanticSearchTable(conn, "skills", "SKILL", vecStr.toString(), limit, results);
                                semanticSearchTable(conn, "project_contexts", "PROJECT_CONTEXT", vecStr.toString(), limit, results);
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
                        textSearchTable(conn, "error_corrections", "ERROR_CORRECTION", query, limit, results);
                        textSearchTable(conn, "best_practices", "BEST_PRACTICE", query, limit, results);
                        textSearchTable(conn, "skills", "SKILL", query, limit, results);
                        textSearchTable(conn, "project_contexts", "PROJECT_CONTEXT", query, limit, results);
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
                                         String vecStr, int limit, List<Map<String, Object>> results) throws SQLException {
            String sql = String.format(
                "SELECT id, title, COALESCE(solution, practice, description, '') as content, " +
                "1 - (embedding <=> '%s'::vector) as similarity FROM %s " +
                "WHERE embedding IS NOT NULL AND (deleted = false OR deleted IS NULL) " +
                "ORDER BY similarity DESC LIMIT ?",
                vecStr, table
            );

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

        private void textSearchTable(Connection conn, String table, String type,
                                      String query, int limit, List<Map<String, Object>> results) throws SQLException {
            String sql = String.format(
                "SELECT id, title, COALESCE(solution, practice, description, '') as content FROM %s " +
                "WHERE (deleted = false OR deleted IS NULL) AND " +
                "(title ILIKE ? OR solution ILIKE ? OR practice ILIKE ? OR description ILIKE ?) " +
                "LIMIT ?",
                table
            );

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                String searchPattern = "%" + query + "%";
                stmt.setString(1, searchPattern);
                stmt.setString(2, searchPattern);
                stmt.setString(3, searchPattern);
                stmt.setString(4, searchPattern);
                stmt.setInt(5, limit);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("type", type);
                    item.put("title", rs.getString("title"));
                    item.put("content", rs.getString("content"));
                    item.put("similarity", 0.0); // 文本搜索不提供相似度
                    results.add(item);
                }
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
                    String model = (String) input.get("model");
                    
                    try {
                        // 标准化 baseUrl（移除末尾的 /api/* 路径）
                        String cleanBaseUrl = baseUrl;
                        if (baseUrl != null) {
                            cleanBaseUrl = baseUrl.replaceAll("/(api/[^/]*|v1)/?$", "");
                        }
                        
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
                             "SELECT session_id, summary, compression_type, original_message_count, compressed_at " +
                             "FROM session_summaries ORDER BY compressed_at DESC LIMIT 50")) {
                        while (rs.next()) {
                            Map<String, Object> row = new HashMap<>();
                            row.put("sessionId", rs.getString("session_id"));
                            row.put("summary", rs.getString("summary"));
                            row.put("compressionType", rs.getString("compression_type"));
                            row.put("messageCount", rs.getInt("original_message_count"));
                            row.put("compressedAt", rs.getTimestamp("compressed_at").toString());
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
                
                // POST: 保存配置
                if ("POST".equalsIgnoreCase(method)) {
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
                    if (compressionType != null && !compressionType.isEmpty()) {
                        success = compressionService.compressSessionWithType(sessionId, compressionType);
                    } else {
                        success = compressionService.compressSessionManual(sessionId);
                    }
                    
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
            String path = exchange.getRequestURI().getPath();
            
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
}
