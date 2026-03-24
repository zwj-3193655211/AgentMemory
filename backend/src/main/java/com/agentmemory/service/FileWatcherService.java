package com.agentmemory.service;

import com.agentmemory.model.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文件监控服务
 * 使用 Java WatchService 监控 Agent 会话日志目录
 */
public class FileWatcherService {
    
    private static final Logger log = LoggerFactory.getLogger(FileWatcherService.class);
    
    private final DatabaseService databaseService;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Map<WatchKey, Path> watchKeys;
    private final Map<String, Long> filePositions;  // 文件读取位置
    private volatile boolean running;
    
    // 定时任务线程池（需要正确关闭）
    private ScheduledExecutorService persistenceExecutor;
    private ScheduledExecutorService cleanupExecutor;
    
    // 文件级锁：防止同一文件并发处理
    private final ConcurrentHashMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();
    
    public FileWatcherService(DatabaseService databaseService) {
        this.databaseService = databaseService;
        this.memoryService = new MemoryService(databaseService);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        // 使用有界线程池，防止资源耗尽
        // 核心线程数需要足够大，因为每个Agent目录监控都会阻塞一个线程
        this.executor = new ThreadPoolExecutor(
            8,                          // 核心线程数：6个Agent + 额外任务
            20,                         // 最大线程数
            60L,                        // 空闲线程存活时间
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),  // 有界队列
            new ThreadPoolExecutor.CallerRunsPolicy()  // 拒绝策略：调用者执行
        );

        this.watchKeys = new ConcurrentHashMap<>();
        this.filePositions = new ConcurrentHashMap<>();
        this.running = false;

        // 启动定期清理任务
        startCleanupTask();

        // 启动持久化任务
        startPersistenceTask();
    }
    
    /**
     * 开始监控指定目录
     * @param agentType Agent类型名称（用于标识和存储）
     * @param parserType 解析器类型（iflow/claude/openclaw/qwen）
     * @param directory 监控目录
     */
    public void watchDirectory(String agentType, String parserType, Path directory) {
        // 首次调用时加载文件位置
        loadFilePositionsFromDatabase();
        executor.submit(() -> {
            startWatcher(agentType, parserType, directory);
        });
    }
    
    private boolean filePositionsLoaded = false;
    
    private void startWatcher(String agentType, String parserType, Path directory) {
        // 使用 try-with-resources 确保 WatchService 正确关闭
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {

            // 递归注册所有子目录
            registerDirectoryTree(directory, watchService);

            running = true;
            log.info("开始监控 [{}] 目录: {} (解析器: {})", agentType, directory, parserType);

            // 首次扫描已有文件
            scanExistingFiles(agentType, parserType, directory);

            while (running) {
                WatchKey key;
                try {
                    key = watchService.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (key == null) continue;

                Path watchDir = watchKeys.get(key);
                if (watchDir == null) {
                    key.reset();
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    handleWatchEvent(agentType, parserType, watchDir, event);
                }

                key.reset();
            }

        } catch (Exception e) {
            log.error("监控目录异常: {}", directory, e);
        }
    }
    
    private void registerDirectoryTree(Path start, WatchService watchService) throws IOException {
        Files.walk(start)
            .filter(Files::isDirectory)
            .forEach(dir -> {
                try {
                    WatchKey key = dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                    watchKeys.put(key, dir);
                } catch (IOException e) {
                    log.warn("无法注册目录监控: {}", dir, e);
                }
            });
    }
    
    private void scanExistingFiles(String agentType, String parserType, Path directory) {
        try {
            Files.walk(directory)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".jsonl"))
                .forEach(file -> processJsonlFile(agentType, parserType, file));
        } catch (IOException e) {
            log.error("扫描已有文件失败", e);
        }
    }
    
    private void handleWatchEvent(String agentType, String parserType, Path watchDir, WatchEvent<?> event) {
        WatchEvent.Kind<?> kind = event.kind();
        
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            return;
        }
        
        Path filePath = watchDir.resolve((Path) event.context());
        
        if (filePath.toString().endsWith(".jsonl")) {
            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                log.debug("新文件: {}", filePath);
                processJsonlFile(agentType, parserType, filePath);
            } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                log.debug("文件修改: {}", filePath);
                processJsonlFileIncremental(agentType, parserType, filePath);
            }
        }
    }
    
    /**
     * 处理 JSONL 文件（完整读取）
     */
    private void processJsonlFile(String agentType, String parserType, Path file) {
        String fileName = file.toString();
        
        // 获取文件级锁，防止并发处理同一文件
        ReentrantLock fileLock = fileLocks.computeIfAbsent(fileName, k -> new ReentrantLock());
        fileLock.lock();
        try {
            long lastPosition = filePositions.getOrDefault(fileName, 0L);
            
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                long currentPos = 0;
                
                while ((line = reader.readLine()) != null) {
                    currentPos += line.length() + 1;  // +1 for newline
                    
                    if (currentPos <= lastPosition) {
                        continue;  // 跳过已处理的内容
                    }
                    
                    if (!line.trim().isEmpty()) {
                        processJsonlLine(agentType, parserType, file, line);
                    }
                    
                    filePositions.put(fileName, currentPos);
                }
            } catch (IOException e) {
                log.error("读取文件失败: {}", file, e);
            }
        } finally {
            fileLock.unlock();
        }
    }
    
    /**
     * 处理 JSONL 文件（增量读取）
     */
    private void processJsonlFileIncremental(String agentType, String parserType, Path file) {
        processJsonlFile(agentType, parserType, file);  // 使用相同的逻辑，通过位置追踪实现增量
    }
    
    /**
     * 解析 JSONL 行并存储
     * @param agentType Agent类型名称（用于标识和存储）
     * @param parserType 解析器类型（iflow/claude/openclaw/qwen）
     */
    private void processJsonlLine(String agentType, String parserType, Path file, String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            
            // 根据 parserType 选择解析器
            Message message = null;
            
            if ("iflow".equals(parserType)) {
                message = parseIFlowMessage(node, file);
            } else if ("claude".equals(parserType)) {
                message = parseClaudeMessage(node, file);
            } else if ("openclaw".equals(parserType)) {
                message = parseOpenClawMessage(node, file);
            } else if ("qwen".equals(parserType)) {
                message = parseQwenMessage(node, file);
            }
            
            if (message != null) {
                // 使用 agentType 而非 parserType 作为消息的 agentType
                message.setAgentType(agentType);
                databaseService.saveMessage(message);
                log.debug("已保存消息: {} - {}", message.getId(), message.getRole());
                
                // 异步处理记忆提取（仅处理有内容的用户消息）
                if ("user".equals(message.getRole()) && message.getContent() != null
                    && message.getContent().length() > 20) {
                    final Message finalMessage = message;
                    final String finalAgentType = agentType;
                    executor.submit(() -> {
                        try {
                            memoryService.processMessage(
                                finalMessage.getSessionId(),
                                finalMessage.getContent(),
                                finalAgentType
                            );
                        } catch (Exception e) {
                            log.error("记忆处理失败 [session={}, role={}]: {}",
                                finalMessage.getSessionId(),
                                finalMessage.getRole(),
                                e.getMessage(), e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("解析 JSONL 行失败: {}", line.substring(0, Math.min(100, line.length())), e);
        }
    }
    
    /**
     * 解析 iFlow CLI 消息格式
     */
    private Message parseIFlowMessage(JsonNode node, Path file) {
        String type = getTextOrEmpty(node, "type");
        
        // 跳过非对话消息类型
        if ("tool_result".equals(type) || "system".equals(type)) {
            return null;
        }
        
        // 只处理 user 和 assistant
        if (!"user".equals(type) && !"assistant".equals(type)) {
            return null;
        }
        
        Message message = new Message();
        
        message.setId(getTextOrEmpty(node, "uuid"));
        message.setSessionId(getTextOrEmpty(node, "sessionId"));
        message.setParentId(getTextOrEmpty(node, "parentUuid"));
        message.setRole(type);
        message.setTimestamp(getTextOrEmpty(node, "timestamp"));
        
        // 从文件路径提取 project_path
        String projectPath = extractProjectPath(file);
        message.setProjectName(projectPath);
        
        // 解析消息内容
        StringBuilder contentBuilder = new StringBuilder();
        JsonNode msgNode = node.get("message");
        if (msgNode != null) {
            JsonNode contentNode = msgNode.get("content");
            if (contentNode != null) {
                if (contentNode.isTextual()) {
                    contentBuilder.append(contentNode.asText());
                } else if (contentNode.isArray()) {
                    for (JsonNode part : contentNode) {
                        if (!part.isObject()) continue;
                        
                        String partType = part.has("type") ? part.get("type").asText() : "text";
                        
                        if ("text".equals(partType) && part.has("text")) {
                            if (contentBuilder.length() > 0) contentBuilder.append("\n\n");
                            contentBuilder.append(part.get("text").asText());
                        } else if ("tool_use".equals(partType)) {
                            String toolName = part.has("name") ? part.get("name").asText() : "unknown";
                            if (contentBuilder.length() > 0) contentBuilder.append("\n\n");
                            contentBuilder.append("[工具调用: ").append(toolName).append("]");
                            
                            // 提取关键参数
                            JsonNode input = part.get("input");
                            if (input != null && input.isObject()) {
                                String[] keyFields = {"command", "file_path", "path", "url", "query", "description"};
                                for (String field : keyFields) {
                                    if (input.has(field)) {
                                        String value = input.get(field).asText();
                                        if (value.length() > 200) value = value.substring(0, 200) + "...";
                                        contentBuilder.append("\n  ").append(field).append(": ").append(value);
                                        break;
                                    }
                                }
                            }
                        }
                        // 跳过 tool_result 类型
                    }
                }
            }
        }
        
        String content = contentBuilder.toString().trim();
        
        // 跳过空消息
        if (content.isEmpty()) {
            return null;
        }
        
        message.setContent(content);
        message.setRawJson(node.toString());
        message.setAgentType("iflow");
        
        return message;
    }
    
    /**
     * 从文件路径提取项目路径
     * 格式: ~/.iflow/projects/<project-path>/session-xxx.jsonl
     */
    private String extractProjectPath(Path file) {
        String path = file.toString();
        
        // 查找 "projects" 后的部分
        int projectsIdx = path.indexOf("projects");
        if (projectsIdx == -1) {
            return "";
        }
        
        // 提取 projects/ 后面的路径
        String afterProjects = path.substring(projectsIdx + 9); // "projects".length() = 8, +1 for /
        
        // 去掉最后的 session-xxx.jsonl
        int sessionIdx = afterProjects.indexOf("session-");
        if (sessionIdx > 0) {
            return afterProjects.substring(0, sessionIdx - 1); // -1 for /
        }
        
        return afterProjects;
    }
    
    /**
     * 解析 Claude Code 消息格式
     * Claude projects 目录下的 JSONL 格式包含完整对话
     */
    private Message parseClaudeMessage(JsonNode node, Path file) {
        // 跳过非消息类型的行（如 file-history-snapshot）
        String type = getTextOrEmpty(node, "type");
        if (!"user".equals(type) && !"assistant".equals(type)) {
            return null;
        }
        
        Message message = new Message();
        
        message.setId(getTextOrEmpty(node, "uuid"));
        message.setSessionId(getTextOrEmpty(node, "sessionId"));
        message.setParentId(getTextOrEmpty(node, "parentUuid"));
        message.setRole(type);  // user 或 assistant
        message.setTimestamp(getTextOrEmpty(node, "timestamp"));
        
        // 从文件路径提取 project_path
        String projectPath = extractClaudeProjectPath(file);
        message.setProjectName(projectPath);
        
        // 解析消息内容
        JsonNode msgNode = node.get("message");
        StringBuilder contentBuilder = new StringBuilder();
        
        if (msgNode != null) {
            JsonNode contentNode = msgNode.get("content");
            if (contentNode != null) {
                if (contentNode.isTextual()) {
                    contentBuilder.append(contentNode.asText());
                } else if (contentNode.isArray()) {
                    for (JsonNode part : contentNode) {
                        String partType = part.has("type") ? part.get("type").asText() : "text";
                        
                        if ("text".equals(partType) && part.has("text")) {
                            // 文本内容
                            if (contentBuilder.length() > 0) contentBuilder.append("\n\n");
                            contentBuilder.append(part.get("text").asText());
                        } else if ("tool_use".equals(partType)) {
                            // 工具调用
                            String toolName = part.has("name") ? part.get("name").asText() : "unknown";
                            JsonNode input = part.get("input");
                            
                            if (contentBuilder.length() > 0) contentBuilder.append("\n\n");
                            contentBuilder.append("[工具调用: ").append(toolName).append("]");
                            
                            // 格式化关键参数
                            if (input != null && input.isObject()) {
                                StringBuilder toolDetails = new StringBuilder();
                                
                                // 提取常用字段
                                String[] keyFields = {"command", "file_path", "path", "url", "query", "description"};
                                for (String field : keyFields) {
                                    if (input.has(field)) {
                                        String value = input.get(field).asText();
                                        if (value.length() > 200) value = value.substring(0, 200) + "...";
                                        toolDetails.append("\n  ").append(field).append(": ").append(value);
                                    }
                                }
                                
                                if (toolDetails.length() > 0) {
                                    contentBuilder.append(toolDetails);
                                }
                            }
                        }
                        // 跳过 thinking 类型（内部思考过程）
                    }
                }
            }
        }
        
        String content = contentBuilder.toString().trim();
        
        // 跳过空消息
        if (content.isEmpty()) {
            return null;
        }
        
        message.setContent(content);
        message.setRawJson(node.toString());
        message.setAgentType("claude");
        
        return message;
    }
    
    /**
     * 从 Claude 文件路径提取项目路径
     * 格式: ~/.claude/projects/<project-id>/<session-id>.jsonl
     */
    private String extractClaudeProjectPath(Path file) {
        String path = file.toString();
        
        int projectsIdx = path.indexOf("projects");
        if (projectsIdx == -1) {
            return "";
        }
        
        // 提取 projects/ 后面的路径
        String afterProjects = path.substring(projectsIdx + 9); // "projects".length() = 8, +1 for /
        
        // 去掉最后的 session-id.jsonl
        int lastSep = afterProjects.lastIndexOf(System.getProperty("file.separator").charAt(0));
        if (lastSep > 0) {
            return afterProjects.substring(0, lastSep);
        }
        
        return afterProjects;
    }
    
    /**
     * 解析 Qwen CLI 消息格式
     */
    private Message parseQwenMessage(JsonNode node, Path file) {
        String type = getTextOrEmpty(node, "type");
        
        // 跳过非对话消息类型
        if ("tool_result".equals(type) || "system".equals(type)) {
            return null;
        }
        
        // 只处理 user 和 assistant
        if (!"user".equals(type) && !"assistant".equals(type)) {
            return null;
        }
        
        Message message = new Message();
        
        message.setId(getTextOrEmpty(node, "uuid"));
        message.setSessionId(getTextOrEmpty(node, "sessionId"));
        message.setParentId(getTextOrEmpty(node, "parentUuid"));
        message.setRole(type);
        message.setTimestamp(getTextOrEmpty(node, "timestamp"));
        
        // 解析消息内容 - Qwen 使用 message.parts[].text
        StringBuilder contentBuilder = new StringBuilder();
        JsonNode msgNode = node.get("message");
        if (msgNode != null) {
            JsonNode partsNode = msgNode.get("parts");
            if (partsNode != null && partsNode.isArray()) {
                for (JsonNode part : partsNode) {
                    if (!part.isObject()) continue;
                    
                    // 跳过内部思考 (thought=true)
                    boolean isThought = part.has("thought") && part.get("thought").asBoolean();
                    if (isThought) continue;
                    
                    if (part.has("text")) {
                        String text = part.get("text").asText();
                        if (!text.isEmpty()) {
                            if (contentBuilder.length() > 0) contentBuilder.append("\n");
                            contentBuilder.append(text);
                        }
                    }
                }
            }
        }
        
        String content = contentBuilder.toString().trim();
        
        // 跳过空消息
        if (content.isEmpty()) {
            return null;
        }
        
        message.setContent(content);
        message.setRawJson(node.toString());
        message.setAgentType("qwen");
        
        return message;
    }
    
    /**
     * 解析 OpenClaw 消息格式
     * OpenClaw 格式: {"type":"message", "id":"xxx", "parentId":"xxx", "timestamp":"...", "message":{"role":"user/assistant","content":[...]}}
     */
    private Message parseOpenClawMessage(JsonNode node, Path file) {
        String type = getTextOrEmpty(node, "type");
        
        // 只处理 message 类型
        if (!"message".equals(type)) {
            return null;
        }
        
        JsonNode msgNode = node.get("message");
        if (msgNode == null) {
            return null;
        }
        
        String role = getTextOrEmpty(msgNode, "role");
        
        // 只处理 user 和 assistant
        if (!"user".equals(role) && !"assistant".equals(role)) {
            return null;
        }
        
        Message message = new Message();
        
        message.setId(getTextOrEmpty(node, "id"));
        message.setParentId(getTextOrEmpty(node, "parentId"));
        message.setRole(role);
        message.setTimestamp(getTextOrEmpty(node, "timestamp"));
        
        // 从文件路径提取 sessionId (文件名就是 sessionId)
        String fileName = file.getFileName().toString();
        String sessionId = fileName.replace(".jsonl", "");
        message.setSessionId(sessionId);
        
        // 解析消息内容 - OpenClaw 使用 message.content[].text
        StringBuilder contentBuilder = new StringBuilder();
        JsonNode contentNode = msgNode.get("content");
        if (contentNode != null && contentNode.isArray()) {
            for (JsonNode part : contentNode) {
                if (!part.isObject()) continue;
                
                String partType = part.has("type") ? part.get("type").asText() : "text";
                
                if ("text".equals(partType) && part.has("text")) {
                    if (contentBuilder.length() > 0) contentBuilder.append("\n");
                    contentBuilder.append(part.get("text").asText());
                }
            }
        }
        
        String content = contentBuilder.toString().trim();
        
        // 跳过空消息
        if (content.isEmpty()) {
            return null;
        }
        
        message.setContent(content);
        message.setRawJson(node.toString());
        message.setAgentType("openclaw");
        
        // 从第一条 user 消息提取 projectPath
        JsonNode cwdNode = node.get("cwd");
        if (cwdNode != null) {
            message.setProjectName(cwdNode.asText());
        }
        
        return message;
    }
    
    /**
     * 清理不存在的文件位置记录
     */
    private void cleanupOldPositions() {
        int beforeSize = filePositions.size();
        filePositions.entrySet().removeIf(entry -> {
            Path file = Paths.get(entry.getKey());
            boolean exists = Files.exists(file);
            if (!exists) {
                log.debug("清理不存在的文件位置记录: {}", entry.getKey());
            }
            return !exists;
        });
        int afterSize = filePositions.size();
        if (beforeSize > afterSize) {
            log.info("已清理 {} 条不存在的文件位置记录，剩余 {} 条",
                    beforeSize - afterSize, afterSize);
        }
    }

    /**
     * 启动持久化任务，定期保存文件位置到数据库
     */
    private void startPersistenceTask() {
        persistenceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "file-watcher-persistence");
            thread.setDaemon(true);
            return thread;
        });

        // 每10分钟保存一次
        persistenceExecutor.scheduleAtFixedRate(() -> {
            try {
                persistFilePositions();
            } catch (Exception e) {
                log.warn("保存文件位置失败: {}", e.getMessage());
            }
        }, 10, 10, TimeUnit.MINUTES);
    }

    /**
     * 保存文件位置到数据库
     */
    private void persistFilePositions() {
        if (filePositions.isEmpty()) {
            return;
        }

        try (Connection conn = databaseService.getConnection()) {
            // 创建文件位置表（如果不存在）
            String createTableSql = """
                CREATE TABLE IF NOT EXISTS file_positions (
                    file_path TEXT PRIMARY KEY,
                    file_position BIGINT,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createTableSql);
            }

            // 批量更新或插入
            String upsertSql = """
                INSERT INTO file_positions (file_path, file_position)
                VALUES (?, ?)
                ON CONFLICT (file_path) DO UPDATE SET
                    file_position = EXCLUDED.file_position,
                    updated_at = CURRENT_TIMESTAMP
                """;

            try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
                for (var entry : filePositions.entrySet()) {
                    stmt.setString(1, entry.getKey());
                    stmt.setLong(2, entry.getValue());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            log.debug("已保存 {} 条文件位置记录", filePositions.size());

        } catch (SQLException e) {
            log.error("保存文件位置到数据库失败", e);
        }
    }

    /**
     * 从数据库恢复文件位置
     */
    private void loadFilePositionsFromDatabase() {
        if (filePositionsLoaded) return;
        filePositionsLoaded = true;
        
        try (Connection conn = databaseService.getConnection()) {
            // 检查表是否存在
            String checkTableSql = """
                SELECT COUNT(*) as count FROM information_schema.tables
                WHERE table_name = 'file_positions'
                """;

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(checkTableSql)) {

                if (rs.next() && rs.getInt("count") == 0) {
                    log.info("文件位置表不存在，跳过恢复");
                    return;
                }
            }

            // 加载文件位置
            String selectSql = "SELECT file_path, file_position FROM file_positions";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(selectSql)) {

                int count = 0;
                while (rs.next()) {
                    String filePath = rs.getString("file_path");
                    long position = rs.getLong("file_position");

                    // 只恢复仍然存在的文件
                    if (Files.exists(Paths.get(filePath))) {
                        filePositions.put(filePath, position);
                        count++;
                    }
                }

                if (count > 0) {
                    log.info("从数据库恢复了 {} 条文件位置记录", count);
                }
            }

        } catch (SQLException e) {
            log.warn("从数据库恢复文件位置失败: {}", e.getMessage());
        }
    }

    private String getTextOrEmpty(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        return fieldNode != null ? fieldNode.asText("") : "";
    }

    /**
     * 启动定期清理任务，清理不存在的文件位置记录
     */
    private void startCleanupTask() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "file-watcher-cleanup");
            thread.setDaemon(true);
            return thread;
        });

        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupOldPositions();
            } catch (Exception e) {
                log.warn("清理文件位置记录失败: {}", e.getMessage());
            }
        }, 5, 5, TimeUnit.MINUTES);  // 每5分钟执行一次
    }
    
    public void stop() {
        running = false;
        
        // 关闭主线程池
        executor.shutdown();
        
        // 关闭定时任务线程池
        if (persistenceExecutor != null) {
            persistenceExecutor.shutdown();
        }
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
        }
        
        // 等待线程池终止
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (persistenceExecutor != null && !persistenceExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                persistenceExecutor.shutdownNow();
            }
            if (cleanupExecutor != null && !cleanupExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            if (persistenceExecutor != null) persistenceExecutor.shutdownNow();
            if (cleanupExecutor != null) cleanupExecutor.shutdownNow();
        }
        
        log.info("FileWatcherService 已停止");
    }
}
