package com.agentmemory.api;

import com.agentmemory.service.DatabaseService;
import com.agentmemory.service.AgentDetectorService;
import com.agentmemory.service.FileWatcherService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * 初始化设置处理器
 */
public class SetupHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(SetupHandler.class);

    private final DatabaseService databaseService;
    private final AgentDetectorService agentDetectorService;
    private final FileWatcherService fileWatcherService;
    private final ObjectMapper objectMapper;

    public SetupHandler(DatabaseService databaseService, AgentDetectorService agentDetectorService, FileWatcherService fileWatcherService) {
        this.databaseService = databaseService;
        this.agentDetectorService = agentDetectorService;
        this.fileWatcherService = fileWatcherService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            if (path.endsWith("/setup/status")) {
                handleStatus(exchange);
            } else if (path.endsWith("/setup/agents")) {
                handleAgents(exchange);
            } else if (path.endsWith("/setup/import") && "POST".equalsIgnoreCase(method)) {
                handleImportFromAgents(exchange);
            } else if (path.endsWith("/setup/complete") && "POST".equalsIgnoreCase(method)) {
                handleComplete(exchange);
            } else if (path.endsWith("/import") && "POST".equalsIgnoreCase(method)) {
                handleImportFile(exchange);
            } else {
                sendError(exchange, 404, "Not Found");
            }
        } catch (Exception e) {
            log.error("Setup API error: {}", e.getMessage(), e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        Map<String, Object> result = new HashMap<>();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            conn = databaseService.getConnection();
            stmt = conn.createStatement();
            rs = stmt.executeQuery("SELECT COUNT(*) FROM sessions");
            boolean hasData = rs.next() && rs.getInt(1) > 0;
            result.put("hasData", hasData);

            if (hasData) {
                rs.close();
                rs = stmt.executeQuery("SELECT COUNT(*) FROM messages");
                result.put("messageCount", rs.next() ? rs.getInt(1) : 0);
            } else {
                result.put("messageCount", 0);
            }

            result.put("initialized", hasData);
            sendJson(exchange, result);
        } catch (Exception e) {
            result.put("hasData", false);
            result.put("messageCount", 0);
            result.put("initialized", false);
            result.put("error", e.getMessage());
            sendJson(exchange, result);
        } finally {
            closeQuietly(rs);
            closeQuietly(stmt);
            closeQuietly(conn);
        }
    }

    private void handleAgents(HttpExchange exchange) throws IOException {
        // 检测本地安装的 Agents
        var agents = agentDetectorService.detectAgents();

        List<Map<String, Object>> result = new ArrayList<>();
        for (var agent : agents) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", agent.getName());
            item.put("type", agent.getType());
            item.put("logPath", agent.getLogPath());
            result.add(item);
        }

        sendJson(exchange, result);
    }

    private void handleImportFromAgents(HttpExchange exchange) throws IOException {
        InputStream body = exchange.getRequestBody();
        Map<String, Object> input = objectMapper.readValue(body, new TypeReference<>() {});

        @SuppressWarnings("unchecked")
        List<String> agentTypes = (List<String>) input.get("agentTypes");
        String since = (String) input.get("since");  // 日期格式: 2024-01-01

        if (agentTypes == null || agentTypes.isEmpty()) {
            sendError(exchange, 400, "请选择要导入的 Agent");
            return;
        }

        int imported = 0;
        Connection conn = null;

        try {
            conn = databaseService.getConnection();
            conn.setAutoCommit(false);

            // 获取检测到的 Agents
            var agents = agentDetectorService.detectAgents();

            for (var agent : agents) {
                if (!agentTypes.contains(agent.getType())) continue;

                // 从 Agent 的日志目录导入会话
                imported += importFromAgent(conn, agent.getLogPath(), agent.getType(), since);
            }

            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (Exception ex) {}
            }
            log.error("导入失败: {}", e.getMessage());
            sendError(exchange, 500, "导入失败: " + e.getMessage());
            return;
        } finally {
            closeQuietly(conn);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("imported", imported);
        sendJson(exchange, result);
    }

    private int importFromAgent(Connection conn, String logPath, String agentType, String since) throws SQLException {
        int count = 0;
        
        // 根据 agentType 确定解析器类型
        String parserType = agentType;
        if ("iflow".equals(agentType)) {
            parserType = "iflow";
        } else if ("claude".equals(agentType)) {
            parserType = "claude";
        } else if ("openclaw".equals(agentType)) {
            parserType = "openclaw";
        } else if ("qwen".equals(agentType)) {
            parserType = "qwen";
        }
        
        // 查询已有的会话数量（用于对比）
        String countSql = "SELECT COUNT(*) FROM sessions WHERE agent_type = ?";
        if (since != null && !since.isEmpty()) {
            countSql += " AND created_at >= ?";
        }
        
        try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
            stmt.setString(1, agentType);
            if (since != null && !since.isEmpty()) {
                stmt.setString(2, since);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                count = rs.getInt(1);
            }
        }
        
        // 触发 FileWatcherService 重新扫描该目录
        if (logPath != null && !logPath.isEmpty()) {
            fileWatcherService.rescanDirectory(logPath);
            log.info("已触发重新扫描 Agent [{}] 目录: {}", agentType, logPath);
        }
        
        return count;
    }

    private void handleComplete(HttpExchange exchange) throws IOException {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("message", "初始化完成");
        sendJson(exchange, result);
    }

    private void handleImportFile(HttpExchange exchange) throws IOException {
        InputStream body = exchange.getRequestBody();
        Map<String, Object> input = objectMapper.readValue(body, new TypeReference<>() {});

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) input.get("sessions");

        if (sessions == null || sessions.isEmpty()) {
            sendError(exchange, 400, "缺少 sessions 数据");
            return;
        }

        int imported = 0;
        Connection conn = null;

        try {
            conn = databaseService.getConnection();
            conn.setAutoCommit(false);

            for (Map<String, Object> sessionData : sessions) {
                try {
                    importSession(conn, sessionData);
                    imported++;
                } catch (Exception e) {
                    log.warn("导入失败: {}", e.getMessage());
                }
            }

            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                try { conn.rollback(); } catch (Exception ex) {}
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("imported", imported);
        sendJson(exchange, result);

        closeQuietly(conn);
    }

    private void importSession(Connection conn, Map<String, Object> sessionData) throws SQLException {
        String sessionId = (String) sessionData.get("id");
        String agentType = (String) sessionData.get("agentType");
        String projectPath = (String) sessionData.get("projectPath");

        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO sessions (id, agent_type, project_path, message_count, created_at, updated_at) " +
            "VALUES (?, ?, ?, 0, NOW(), NOW()) " +
            "ON CONFLICT(id) DO UPDATE SET agent_type = EXCLUDED.agent_type, project_path = EXCLUDED.project_path"
        );
        stmt.setString(1, sessionId);
        stmt.setString(2, agentType != null ? agentType : "unknown");
        stmt.setString(3, projectPath != null ? projectPath : "");
        stmt.executeUpdate();
        stmt.close();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) sessionData.get("messages");
        if (messages != null) {
            importMessages(conn, sessionId, messages);
        }
    }

    private void importMessages(Connection conn, String sessionId, List<Map<String, Object>> messages) throws SQLException {
        int count = 0;
        for (Map<String, Object> msgData : messages) {
            String role = (String) msgData.get("role");
            String content = (String) msgData.get("content");
            String parentId = (String) msgData.get("parentId");

            if (role == null || content == null) continue;

            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO messages (id, session_id, parent_id, role, content, created_at) " +
                "VALUES (?, ?, ?, ?, ?, NOW()) " +
                "ON CONFLICT(id) DO NOTHING"
            );
            stmt.setString(1, UUID.randomUUID().toString());
            stmt.setString(2, sessionId);
            stmt.setString(3, parentId != null ? parentId : "");
            stmt.setString(4, role);
            stmt.setString(5, content);
            stmt.executeUpdate();
            stmt.close();
            count++;
        }

        if (count > 0) {
            PreparedStatement updateStmt = conn.prepareStatement(
                "UPDATE sessions SET message_count = message_count + ? WHERE id = ?"
            );
            updateStmt.setInt(1, count);
            updateStmt.setString(2, sessionId);
            updateStmt.executeUpdate();
            updateStmt.close();
        }
    }

    private void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try { c.close(); } catch (Exception e) {}
        }
    }

    private void sendJson(HttpExchange exchange, Object data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        String json = objectMapper.writeValueAsString(error);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}