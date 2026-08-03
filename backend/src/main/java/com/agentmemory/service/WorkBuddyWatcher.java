package com.agentmemory.service;

import com.agentmemory.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WorkBuddy 对话监控服务
 * 定期扫描 ~/.workbuddy/projects/ 目录下的 JSONL 文件，发现新消息后自动导入
 */
public class WorkBuddyWatcher extends ScheduledServiceBase {

    private static final Logger log = LoggerFactory.getLogger(WorkBuddyWatcher.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 匹配 <user_query>...</user_query> 标签
    private static final Pattern USER_QUERY_PATTERN = Pattern.compile(
            "<user_query>(.*?)</user_query>", Pattern.DOTALL);

    // 匹配需要清理的 system-reminder 等系统标签
    private static final Pattern SYSTEM_BLOCK_PATTERN = Pattern.compile(
            "<(?:system-reminder|user_info|project_context|additional_data|memory_and_skills_reminder|connector-status|working_memory_content)[^>]*>.*?</(?:system-reminder|user_info|project_context|additional_data|memory_and_skills_reminder|connector-status|working_memory_content)>",
            Pattern.DOTALL);

    private final DatabaseService databaseService;

    private final Path projectsDir;

    // 记录每个文件已读到的字节位置（增量读取）
    private final Map<String, Long> filePositions = new HashMap<>();

    // 记录已存在的 session（避免重复创建）
    private final Map<String, Boolean> knownSessions = new HashMap<>();

    public WorkBuddyWatcher(DatabaseService databaseService, String projectsDir) {
        this.databaseService = databaseService;
        this.projectsDir = Paths.get(projectsDir);
        // 启动时将所有已知 session 标记为已存在，避免重复导入
        loadKnownSessions();
    }

    /**
     * 从数据库加载已有的 WorkBuddy sessions，标记为已知
     */
    private void loadKnownSessions() {
        try (java.sql.Connection conn = databaseService.getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id FROM sessions WHERE agent_type = 'workbuddy'")) {
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    knownSessions.put(rs.getString("id"), true);
                }
            }
            log.debug("已加载 {} 个已知 WorkBuddy sessions", knownSessions.size());
        } catch (java.sql.SQLException e) {
            log.warn("加载已知 sessions 失败: {}", e.getMessage());
        }

        // 初始化所有已有文件的读取位置为文件末尾（跳过历史数据）
        if (projectsDir.toFile().exists()) {
            File[] projectDirs = projectsDir.toFile().listFiles(File::isDirectory);
            if (projectDirs != null) {
                for (File projectDir : projectDirs) {
                    File[] jsonlFiles = projectDir.listFiles((dir, name) ->
                            name.endsWith(".jsonl") && !dir.toPath().resolve(name).toString().contains("subagents"));
                    if (jsonlFiles != null) {
                        for (File f : jsonlFiles) {
                            filePositions.put(f.getAbsolutePath(), f.length());
                        }
                    }
                }
            }
        }
    }

    @Override
    protected String getServiceName() {
        return "WorkBuddyWatcher";
    }

    @Override
    protected long getInitialDelaySeconds() {
        return 15;  // 启动 15 秒后开始第一次检查
    }

    @Override
    protected long getPeriodSeconds() {
        return 60;  // 每 60 秒检查一次
    }

    @Override
    protected void executeTask() {
        try {
            scanForNewMessages();
        } catch (Exception e) {
            log.warn("检查 WorkBuddy 对话失败: {}", e.getMessage());
        }
    }

    /**
     * 扫描所有项目目录下的 JSONL 文件
     */
    private void scanForNewMessages() {
        if (!projectsDir.toFile().exists() || !projectsDir.toFile().isDirectory()) {
            log.debug("WorkBuddy 项目目录不存在: {}", projectsDir);
            return;
        }

        File[] projectDirs = projectsDir.toFile().listFiles(File::isDirectory);
        if (projectDirs == null || projectDirs.length == 0) {
            return;
        }

        int totalNewMessages = 0;

        for (File projectDir : projectDirs) {
            String projectDirName = projectDir.getName();
            File[] jsonlFiles = projectDir.listFiles((dir, name) ->
                    name.endsWith(".jsonl") && !dir.toPath().resolve(name).toString().contains("subagents"));

            if (jsonlFiles == null) {
                continue;
            }

            for (File jsonlFile : jsonlFiles) {
                try {
                    int count = processJsonlFile(jsonlFile, projectDirName);
                    totalNewMessages += count;
                } catch (Exception e) {
                    log.warn("处理 WorkBuddy 文件失败 {}: {}", jsonlFile.getName(), e.getMessage());
                }
            }
        }

        if (totalNewMessages > 0) {
            log.info("从 WorkBuddy 导入了 {} 条新消息", totalNewMessages);
        }
    }

    /**
     * 增量处理单个 JSONL 文件（只读新增部分）
     */
    private int processJsonlFile(File file, String projectDirName) throws IOException {
        String fileKey = file.getAbsolutePath();

        // 获取上次读取位置
        long position = filePositions.getOrDefault(fileKey, 0L);
        long fileSize = file.length();

        if (fileSize <= position) {
            return 0;  // 文件没有新增内容
        }

        String sessionId = file.getName().replace(".jsonl", "");
        boolean sessionCreated = knownSessions.getOrDefault(sessionId, false);

        int newMessages = 0;
        String firstUserText = null;

        try (BufferedReader reader = new BufferedReader(
                java.nio.file.Files.newBufferedReader(file.toPath()))) {

            // 跳到上次读取的位置
            reader.skip(position);

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                try {
                    JsonNode node = objectMapper.readTree(line);
                    String type = node.has("type") ? node.get("type").asText() : "";

                    // 只处理 message 类型
                    if (!"message".equals(type)) {
                        continue;
                    }

                    String role = node.has("role") ? node.get("role").asText() : "";
                    if (!"user".equals(role) && !"assistant".equals(role)) {
                        continue;
                    }

                    // 提取内容
                    String content = extractContent(node, role);
                    if (content == null || content.isEmpty()) {
                        continue;
                    }

                    // 保存第一条用户文本作为标题
                    if ("user".equals(role) && firstUserText == null) {
                        firstUserText = content.length() > 50
                                ? content.substring(0, 47) + "..."
                                : content.replace("\n", " ");
                    }

                    // 首次出现消息时创建 session（用第一条有效消息的用户文本作为标题）
                    if (!sessionCreated) {
                        String title = (firstUserText != null) ? firstUserText : "Untitled Session";
                        databaseService.saveSessionIfNotExists(
                                sessionId, "workbuddy", projectDirName, title);
                        knownSessions.put(sessionId, true);
                        sessionCreated = true;
                    }

                    // 创建消息
                    String msgId = sessionId + "-msg-" + position + "-" + newMessages;
                    String timestamp = extractTimestamp(node);

                    Message message = new Message();
                    message.setId(msgId);
                    message.setSessionId(sessionId);
                    message.setRole(role);
                    message.setContent(content);
                    message.setAgentType("workbuddy");
                    message.setTimestamp(timestamp);

                    databaseService.saveMessage(message);
                    newMessages++;

                } catch (Exception e) {
                    log.debug("解析 WorkBuddy JSONL 行失败: {}", e.getMessage());
                }
            }
        }

        // 更新文件读取位置
        filePositions.put(fileKey, fileSize);

        return newMessages;
    }

    /**
     * 从 JSON 节点提取消息内容
     */
    private String extractContent(JsonNode node, String role) {
        JsonNode contentNode = node.get("content");
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }

        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentNode) {
                String blockType = block.has("type") ? block.get("type").asText() : "";
                if ("input_text".equals(blockType) && "user".equals(role)) {
                    String text = block.has("text") ? block.get("text").asText() : "";
                    String cleaned = extractUserQuery(text);
                    if (!cleaned.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(cleaned);
                    }
                } else if (("output_text".equals(blockType) || "text".equals(blockType)) && "assistant".equals(role)) {
                    String text = block.has("text") ? block.get("text").asText() : "";
                    if (!text.trim().isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(text.trim());
                    }
                }
            }
            return sb.toString();
        }

        if (contentNode.isTextual()) {
            if ("user".equals(role)) {
                return extractUserQuery(contentNode.asText());
            }
            return contentNode.asText().trim();
        }

        return "";
    }

    /**
     * 从用户消息中提取 <user_query> 部分
     */
    private String extractUserQuery(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = USER_QUERY_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        // 如果没有 user_query 标签，清理系统块后返回
        String cleaned = SYSTEM_BLOCK_PATTERN.matcher(text).replaceAll("").trim();
        return cleaned.isEmpty() ? text.trim() : cleaned;
    }

    /**
     * 从节点提取时间戳
     */
    private String extractTimestamp(JsonNode node) {
        if (!node.has("timestamp")) {
            return "";
        }
        long ms = node.get("timestamp").asLong(0);
        if (ms > 0) {
            return Instant.ofEpochMilli(ms).toString();
        }
        return "";
    }

    @Override
    public void stop() {
        super.stop();
        log.info("WorkBuddyWatcher 已停止");
    }
}
