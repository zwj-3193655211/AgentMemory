package com.agentmemory.service;

import com.agentmemory.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WorkBuddy 对话监控服务
 * 定期扫描 ~/.workbuddy/projects/ 目录下的 JSONL 文件并导入。
 *
 * 导入策略（指纹 + 全量重读 + 内容哈希 id）：
 *   - 每个文件记录「大小 + 修改时间」指纹，未变化的文件直接跳过；
 *   - 指纹变化（新建或追加）的文件从头完整重读，逐条 UPSERT；
 *   - 消息 id = sessionId + "-m-" + sha256(role|content|timestamp) 前 16 位，
 *     同一条消息无论何时重读都生成相同 id，天然幂等，不会重复入库。
 *
 * 这样后端停机期间产生的会话和消息（包括会话文件的增量追加）
 * 都会在下次扫描时自动补齐，不再依赖"启动时已读位置"。
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

    // 文件指纹缓存：绝对路径 -> "size:mtime"，未变化的文件跳过解析
    private final Map<String, String> fileFingerprints = new HashMap<>();

    public WorkBuddyWatcher(DatabaseService databaseService, String projectsDir) {
        this.databaseService = databaseService;
        this.projectsDir = Paths.get(projectsDir);
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
        int rescannedFiles = 0;

        for (File projectDir : projectDirs) {
            String projectDirName = projectDir.getName();
            File[] jsonlFiles = projectDir.listFiles((dir, name) ->
                    name.endsWith(".jsonl") && !dir.toPath().resolve(name).toString().contains("subagents"));

            if (jsonlFiles == null) {
                continue;
            }

            for (File jsonlFile : jsonlFiles) {
                String absPath = jsonlFile.getAbsolutePath();
                String fingerprint = fingerprintOf(jsonlFile);
                if (fingerprint == null || fingerprint.equals(fileFingerprints.get(absPath))) {
                    continue;  // 文件未变化
                }
                try {
                    int count = importJsonlFile(jsonlFile, projectDirName);
                    fileFingerprints.put(absPath, fingerprint);
                    totalNewMessages += count;
                    rescannedFiles++;
                } catch (Exception e) {
                    log.warn("处理 WorkBuddy 文件失败 {}: {}", jsonlFile.getName(), e.getMessage());
                }
            }
        }

        if (totalNewMessages > 0) {
            log.info("从 WorkBuddy 导入了 {} 条新消息（重扫 {} 个文件）", totalNewMessages, rescannedFiles);
        } else if (rescannedFiles > 0) {
            log.debug("重扫了 {} 个 WorkBuddy 文件，无新增消息", rescannedFiles);
        }
    }

    /**
     * 完整导入单个 JSONL 文件（内容哈希 id 保证重复导入幂等）
     */
    private int importJsonlFile(File file, String projectDirName) throws IOException {
        String sessionId = file.getName().replace(".jsonl", "");
        boolean sessionCreated = false;
        int newMessages = 0;
        String firstUserText = null;

        try (BufferedReader reader = java.nio.file.Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
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
                        sessionCreated = true;
                    }

                    String timestamp = extractTimestamp(node);

                    Message message = new Message();
                    message.setId(sessionId + "-m-" + contentHash(role, content, timestamp));
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

        return newMessages;
    }

    /**
     * 消息内容哈希（同一条消息始终生成相同 id，重复导入只更新不堆积）
     */
    private static String contentHash(String role, String content, String timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((role + '\n' + content + '\n' + timestamp)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {  // 取前 8 字节 = 16 个十六进制字符
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 必然存在，这里仅兜底
            return String.valueOf((role + content + timestamp).hashCode());
        }
    }

    /**
     * 文件指纹：大小 + 修改时间
     */
    private static String fingerprintOf(File file) {
        try {
            Path path = file.toPath();
            return Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return null;
        }
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
