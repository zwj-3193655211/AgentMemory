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
    private final ConcurrentHashMap<String, CodexSessionMeta> codexSessionMetaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CodexSessionMeta> piSessionMetaCache = new ConcurrentHashMap<>();
    
    // ========== 语义切分相关 ==========
    // 话题转换关键词（检测到这些词时，触发一次处理）
    private static final Set<String> TOPIC_SWITCH_KEYWORDS = new HashSet<>(Arrays.asList(
        "另外", "还有", "顺便", "对了", "新话题", "换个话题", "重新开始",
        "我有个问题", "另一个问题", "问一下", "顺便问一下",
        "先这样", "暂时这样", "先不管",
        "我重新", "重新开始"
    ));
    // 时间阈值：超过此时间（毫秒）未收到消息，视为新话题
    private static final long SEMANTIC_GAP_MS = 7200000;  // 2小时
    // 最小批量：至少积累这么多条才触发一次处理
    private static final int MIN_BATCH_SIZE = 3;
    // 最大批量：超过这么多条强制触发一次处理
    private static final int MAX_BATCH_SIZE = 15;
    // 强制刷新超时（兜底，防止消息永远卡在缓冲里）
    private static final long MAX_BUFFER_AGE_MS = 60000;  // 1分钟
    
    // ========== 缓冲批量分类相关 ==========
    // 消息缓冲：sessionId -> 缓冲的消息列表
    private final ConcurrentHashMap<String, List<BufferedMessage>> messageBuffer = new ConcurrentHashMap<>();
    // 缓冲刷新定时器
    private ScheduledExecutorService bufferFlushExecutor;
    // 上下文窗口大小（用于分类时包含的历史消息）
    private static final int CONTEXT_WINDOW_SIZE = 10;
    
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
        
        // 启动缓冲刷新任务
        startBufferFlushTask();
    }
    
    /**
     * 开始监控指定目录
     * @param agentType Agent类型名称（用于标识和存储）
     * @param parserType 解析器类型（iflow/claude/openclaw/qwen/nanobot/codex/pi）
     * @param directory 监控目录
     */
    public void watchDirectory(String agentType, String parserType, Path directory) {
        // 首次调用时加载文件位置
        loadFilePositionsFromDatabase();
        executor.submit(() -> {
            startWatcher(agentType, parserType, directory);
        });
    }
    
    private volatile boolean filePositionsLoaded = false;
    
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
     * @param parserType 解析器类型（iflow/claude/openclaw/qwen/nanobot/codex/pi）
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
            } else if ("nanobot".equals(parserType)) {
                message = parseNanobotMessage(node, file);
            } else if ("codex".equals(parserType)) {
                message = parseCodexMessage(node, file);
            } else if ("pi".equals(parserType)) {
                message = parsePiMessage(node, file);
            }
            
            if (message != null) {
                // 使用 agentType 而非 parserType 作为消息的 agentType
                message.setAgentType(agentType);
                databaseService.saveMessage(message);
                log.debug("已保存消息: {} - {}", message.getId(), message.getRole());

                // 通知 SSE 客户端刷新统计
                StatsEventBroadcaster.getInstance().notifyNewMessage();
                
                // 改为缓冲消息，批量处理以获得更好的上下文
                if ("user".equals(message.getRole()) && message.getContent() != null
                    && message.getContent().length() > 20) {
                    bufferMessage(message, agentType);
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
     * 解析 Nanobot 消息格式
     * Nanobot 格式: {"role":"user/assistant","content":"文本","timestamp":"ISO时间"}
     * 元数据行: {"_type":"metadata","key":"session-id",...}
     */
    private Message parseNanobotMessage(JsonNode node, Path file) {
        // 跳过元数据行
        String type = getTextOrEmpty(node, "_type");
        if ("metadata".equals(type)) {
            return null;
        }

        String role = getTextOrEmpty(node, "role");

        // 只处理 user 和 assistant
        if (!"user".equals(role) && !"assistant".equals(role)) {
            return null;
        }

        Message message = new Message();
        message.setParentId(getTextOrEmpty(node, "parentId"));
        message.setRole(role);
        message.setTimestamp(getTextOrEmpty(node, "timestamp"));

        // 从文件名提取 sessionId
        String fileName = file.getFileName().toString();
        String sessionId = fileName.replace(".jsonl", "");
        message.setSessionId(sessionId);

        // nanobot 消息没有唯一 ID，需要生成
        String msgId = getTextOrEmpty(node, "id");
        if (msgId.isEmpty()) {
            msgId = getTextOrEmpty(node, "key");
        }
        if (msgId.isEmpty()) {
            // 使用 sessionId + timestamp + role 生成唯一 ID
            String ts = getTextOrEmpty(node, "timestamp");
            msgId = sessionId + "-" + role + "-" + (ts != null ? ts.replaceAll("[^0-9]", "") : String.valueOf(System.nanoTime()));
        }
        message.setId(msgId);

        // 解析消息内容 - Nanobot 使用直接的 content 字符串
        String content = getTextOrEmpty(node, "content");

        // 跳过空消息
        if (content.isEmpty()) {
            return null;
        }

        message.setContent(content);
        message.setRawJson(node.toString());
        message.setAgentType("nanobot");

        return message;
    }

    private Message parseCodexMessage(JsonNode node, Path file) {
        String filePath = file.toString();
        String eventType = getTextOrEmpty(node, "type");
        JsonNode payload = node.get("payload");

        if ("session_meta".equals(eventType)) {
            cacheCodexSessionMeta(filePath, payload);
            return null;
        }

        String sessionId = extractCodexSessionId(node, filePath, file);
        if (sessionId.isEmpty()) {
            return null;
        }

        if (payload == null || !payload.isObject()) {
            return null;
        }

        String role;
        String content;

        if ("event_msg".equals(eventType)) {
            if (!"user_message".equals(getTextOrEmpty(payload, "type"))) {
                return null;
            }
            role = "user";
            content = getTextOrEmpty(payload, "message");
        } else if ("response_item".equals(eventType)) {
            if (!"message".equals(getTextOrEmpty(payload, "type"))) {
                return null;
            }
            role = getTextOrEmpty(payload, "role");
            if (!"assistant".equals(role)) {
                return null;
            }
            content = extractCodexMessageText(payload.get("content"), "output_text");
        } else {
            return null;
        }

        content = content != null ? content.trim() : "";
        if (content.isEmpty()) {
            return null;
        }

        Message message = new Message();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setTimestamp(getTextOrEmpty(node, "timestamp"));
        message.setProjectName(extractCodexProjectPath(filePath, payload));
        message.setContent(content);
        message.setRawJson(node.toString());
        message.setId(buildCodexMessageId(sessionId, node, role, content, payload));
        message.setAgentType("codex");
        return message;
    }

    private void cacheCodexSessionMeta(String filePath, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return;
        }
        String id = getTextOrEmpty(payload, "id");
        String cwd = getTextOrEmpty(payload, "cwd");
        if (id.isEmpty() && cwd.isEmpty()) {
            return;
        }
        codexSessionMetaCache.put(filePath, new CodexSessionMeta(id, cwd));
    }

    private String extractCodexSessionId(JsonNode node, String filePath, Path file) {
        JsonNode payload = node.get("payload");
        if (payload != null && payload.isObject()) {
            String payloadId = getTextOrEmpty(payload, "id");
            if (!payloadId.isEmpty()) {
                return payloadId;
            }
        }

        CodexSessionMeta meta = codexSessionMetaCache.get(filePath);
        if (meta != null && !meta.sessionId().isEmpty()) {
            return meta.sessionId();
        }

        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".jsonl")) {
            return "";
        }

        String base = fileName.substring(0, fileName.length() - ".jsonl".length());
        int idx = base.lastIndexOf("-019");
        if (idx > 0 && idx + 1 < base.length()) {
            return base.substring(idx + 1);
        }
        return base;
    }

    private String extractCodexProjectPath(String filePath, JsonNode payload) {
        if (payload != null && payload.isObject()) {
            String cwd = getTextOrEmpty(payload, "cwd");
            if (!cwd.isEmpty()) {
                return cwd;
            }
        }

        CodexSessionMeta meta = codexSessionMetaCache.get(filePath);
        if (meta != null && !meta.cwd().isEmpty()) {
            return meta.cwd();
        }
        return "";
    }

    private String extractCodexMessageText(JsonNode contentNode, String expectedPartType) {
        if (contentNode == null) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (!contentNode.isArray()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode part : contentNode) {
            if (!part.isObject()) {
                continue;
            }
            if (expectedPartType.equals(getTextOrEmpty(part, "type"))) {
                String text = getTextOrEmpty(part, "text");
                if (!text.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private String buildCodexMessageId(String sessionId, JsonNode node, String role, String content, JsonNode payload) {
        String timestamp = getTextOrEmpty(node, "timestamp");
        String itemId = payload != null ? getTextOrEmpty(payload, "id") : "";
        String callId = payload != null ? getTextOrEmpty(payload, "call_id") : "";
        String turnId = payload != null ? getTextOrEmpty(payload, "turn_id") : "";
        String seed = sessionId + "|" + timestamp + "|" + role + "|" + itemId + "|" + callId + "|" + turnId + "|" + content;
        return java.util.UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
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
                codexSessionMetaCache.remove(entry.getKey());
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
    
    /**
     * 手动触发重新扫描指定目录（用于 Setup 向导）
     * @param directory 要扫描的目录路径
     * @param agentType Agent 类型（用于标记消息归属）
     * @param parserType 解析器类型（iflow/claude/openclaw/qwen/nanobot/codex/pi）
     */
    public void rescanDirectory(String directory, String agentType, String parserType) {
        Path path = Paths.get(directory);
        if (Files.exists(path) && Files.isDirectory(path)) {
            executor.submit(() -> {
                log.info("手动触发重新扫描: {} (agent={}, parser={})", directory, agentType, parserType);
                // 清除该目录下的文件位置记录，确保从头重新读取
                clearFilePositionsForDirectory(path);
                scanExistingFiles(agentType, parserType, path);
                log.info("重新扫描完成: {}", directory);
            });
        }
    }

    /**
     * 清除指定目录下所有文件的位置追踪记录，使后续扫描从头开始
     * 同时处理正斜杠和反斜杠路径（Windows 兼容）
     */
    private void clearFilePositionsForDirectory(Path directory) {
        String dirNormalized = directory.toString().replace("\\", "/");
        int cleared = 0;
        for (String filePath : filePositions.keySet()) {
            if (filePath.replace("\\", "/").startsWith(dirNormalized)) {
                filePositions.remove(filePath);
                codexSessionMetaCache.remove(filePath);
                cleared++;
            }
        }
        // 同时清除数据库中的记录
        // 使用 REPLACE + LIKE 避免 Windows 反斜杠被 LIKE 当作转义字符的问题
        try (Connection conn = databaseService.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                 "DELETE FROM file_positions WHERE REPLACE(file_path, '\\', '/') LIKE ?")) {
                stmt.setString(1, dirNormalized + "%");
                int dbCleared = stmt.executeUpdate();
                log.info("已从数据库清除 {} 条文件位置记录 (目录: {})", dbCleared, directory);
            }
        } catch (Exception e) {
            log.warn("清除数据库文件位置记录失败: {}", e.getMessage());
        }
        if (cleared > 0) {
            log.info("已从内存清除 {} 条文件位置记录 (目录: {})", cleared, directory);
        }
    }

    /**
     * 手动触发重新扫描指定目录（兼容旧调用）
     * @param directory 要扫描的目录路径
     * @deprecated 使用 {@link #rescanDirectory(String, String, String)} 代替
     */
    @Deprecated
    public void rescanDirectory(String directory) {
        rescanDirectory(directory, "manual", "unknown");
    }


    /**
     * 解析 Pi Agent 消息格式
     * 日志格式: ~/.pi/agent/sessions/--<encoded-cwd>--/*.jsonl
     * 行类型:
     *   - {"type": "session", "id": "...", "cwd": "...", "timestamp": "..."}
     *   - {"type": "message", "id": "...", "message": {"role": "user|assistant|toolResult", "content": [...]}}
     */
    private Message parsePiMessage(JsonNode node, Path file) {
        String filePath = file.toString();
        String eventType = getTextOrEmpty(node, "type");

        // 会话元数据行：缓存 sessionId 和 cwd
        if ("session".equals(eventType)) {
            String sessionId = getTextOrEmpty(node, "id");
            String cwd = getTextOrEmpty(node, "cwd");
            if (!sessionId.isEmpty() || !cwd.isEmpty()) {
                piSessionMetaCache.put(filePath, new CodexSessionMeta(sessionId, cwd));
            }
            return null;
        }

        // 只处理消息行
        if (!"message".equals(eventType)) {
            return null;
        }

        JsonNode msgNode = node.get("message");
        if (msgNode == null || !msgNode.isObject()) {
            return null;
        }

        String role = getTextOrEmpty(msgNode, "role");
        // 只保留 user 和 assistant，跳过 toolResult 等
        if (!"user".equals(role) && !"assistant".equals(role)) {
            return null;
        }

        // 提取文本内容（content 为数组，元素类型: text/thinking/toolCall）
        String content = extractPiMessageText(msgNode.get("content"));
        if (content.isEmpty()) {
            return null;
        }

        // 从缓存获取 sessionId 和项目路径
        CodexSessionMeta meta = piSessionMetaCache.get(filePath);
        String sessionId = meta != null ? meta.sessionId() : "";
        String projectPath = meta != null ? meta.cwd() : "";
        if (sessionId.isEmpty()) {
            // 兜底：从文件路径提取（--D--Desktop_Archive-AgentMemory-- 目录名）
            sessionId = file.getParent() != null ? file.getParent().getFileName().toString() : "unknown";
        }

        Message message = new Message();
        message.setId("pi-" + sessionId + "-" + getTextOrEmpty(node, "id"));
        message.setSessionId(sessionId);
        message.setParentId(getTextOrEmpty(node, "parentId"));
        message.setRole(role);
        message.setTimestamp(getTextOrEmpty(node, "timestamp"));
        message.setProjectName(projectPath);
        message.setContent(content);
        message.setRawJson(node.toString());
        message.setAgentType("pi");
        return message;
    }

    /**
     * 提取 Pi 消息文本（content 数组中 type=text 的部分）
     */
    private String extractPiMessageText(JsonNode contentNode) {
        if (contentNode == null) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText().trim();
        }
        if (!contentNode.isArray()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode part : contentNode) {
            if (!part.isObject()) {
                continue;
            }
            if ("text".equals(getTextOrEmpty(part, "type"))) {
                String text = getTextOrEmpty(part, "text");
                if (!text.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(text);
                }
            }
        }
        return sb.toString().trim();
    }

    private record CodexSessionMeta(String sessionId, String cwd) {}
    
    /**
     * 内部类：缓冲消息
     */
    private static class BufferedMessage {
        final Message message;
        final long timestamp;
        
        BufferedMessage(Message message) {
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * 将消息添加到缓冲，支持语义边界检测
     */
    private void bufferMessage(Message message, String agentType) {
        String sessionId = message.getSessionId();
        if (sessionId == null) return;
        
        List<BufferedMessage> buffer = messageBuffer.computeIfAbsent(sessionId, k -> new ArrayList<>());
        synchronized (buffer) {
            boolean shouldFlush = false;
            String flushReason = null;
            
            if (!buffer.isEmpty()) {
                long timeSinceLast = System.currentTimeMillis() - buffer.get(buffer.size() - 1).timestamp;
                
                // 检测语义边界1：时间间隔超过阈值
                if (timeSinceLast > SEMANTIC_GAP_MS) {
                    shouldFlush = true;
                    flushReason = "语义间隔(" + (timeSinceLast / 1000) + "s)";
                }
                
                // 检测语义边界2：当前消息包含话题转换关键词
                else if (containsTopicSwitch(message.getContent())) {
                    // 如果缓冲里有多条消息，先刷新
                    if (buffer.size() >= MIN_BATCH_SIZE) {
                        shouldFlush = true;
                        flushReason = "话题切换";
                    }
                }
            }
            
            // 检查最大批量限制
            if (buffer.size() >= MAX_BATCH_SIZE) {
                shouldFlush = true;
                flushReason = "达到最大批量(" + MAX_BATCH_SIZE + ")";
            }
            
            buffer.add(new BufferedMessage(message));
            
            // 如果缓冲为空且当前消息是话题转换，也需要特殊处理
            if (buffer.size() == 1 && containsTopicSwitch(message.getContent())) {
                // 这种情况很少见，但仍需处理
                log.debug("检测到新话题起始: {}", truncate(message.getContent(), 50));
            }
            
            // 达到最小批量且检测到语义边界，刷新
            if (shouldFlush && buffer.size() >= MIN_BATCH_SIZE) {
                log.debug("语义边界触发刷新 [session={}]: {}", 
                    sessionId.substring(0, 8), flushReason);
                flushBuffer(sessionId, buffer, agentType);
            }
        }
    }
    
    /**
     * 检测消息内容是否包含话题转换关键词
     */
    private boolean containsTopicSwitch(String content) {
        if (content == null || content.isEmpty()) return false;
        
        String lower = content.toLowerCase();
        for (String keyword : TOPIC_SWITCH_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 启动缓冲刷新定时任务
     */
    private void startBufferFlushTask() {
        bufferFlushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "buffer-flush");
            thread.setDaemon(true);
            return thread;
        });
        
        // 每5秒检查一次超时缓冲
        bufferFlushExecutor.scheduleAtFixedRate(() -> {
            try {
                flushTimedOutBuffers();
            } catch (Exception e) {
                log.warn("刷新缓冲失败: {}", e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
        
        log.info("已启动缓冲刷新任务");
    }
    
    /**
     * 刷新超时的缓冲（兜底机制）
     */
    private void flushTimedOutBuffers() {
        long now = System.currentTimeMillis();
        List<String> sessionsToFlush = new ArrayList<>();
        
        // 找出需要刷新的会话
        for (Map.Entry<String, List<BufferedMessage>> entry : messageBuffer.entrySet()) {
            List<BufferedMessage> buffer = entry.getValue();
            synchronized (buffer) {
                if (!buffer.isEmpty()) {
                    long oldest = buffer.get(0).timestamp;
                    // 使用最大缓冲年龄作为超时阈值
                    if (now - oldest >= MAX_BUFFER_AGE_MS) {
                        sessionsToFlush.add(entry.getKey());
                    }
                }
            }
        }
        
        // 刷新这些会话
        for (String sessionId : sessionsToFlush) {
            List<BufferedMessage> buffer = messageBuffer.get(sessionId);
            if (buffer != null) {
                synchronized (buffer) {
                    if (!buffer.isEmpty()) {
                        String agentType = buffer.get(buffer.size() - 1).message.getAgentType();
                        log.debug("缓冲超时刷新 [session={}]: {}条消息", 
                            sessionId.substring(0, 8), buffer.size());
                        flushBuffer(sessionId, buffer, agentType);
                    }
                }
            }
        }
    }
    
    /**
     * 截断长文本
     */
    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
    
    /**
     * 刷新指定会话的缓冲
     */
    private void flushBuffer(String sessionId, List<BufferedMessage> buffer, String agentType) {
        if (buffer.isEmpty()) return;
        
        try {
            // 提取消息内容（带上下文）
            List<String> batchContents = new ArrayList<>();
            for (BufferedMessage bm : buffer) {
                batchContents.add(bm.message.getContent());
            }
            
            // 异步处理批量
            final List<String> finalContents = batchContents;
            final String finalAgentType = agentType;
            executor.submit(() -> {
                try {
                    processMessageBatch(sessionId, finalContents, finalAgentType);
                    log.debug("已处理会话 {} 的 {} 条缓冲消息", sessionId.substring(0, 8), buffer.size());
                } catch (Exception e) {
                    log.error("批量处理失败 [session={}]: {}", 
                        sessionId.substring(0, 8), e.getMessage(), e);
                }
            });
            
        } finally {
            buffer.clear();
            messageBuffer.remove(sessionId);
        }
    }
    
    /**
     * 批量处理消息（带上下文）
     * 这是改进后的核心方法：多消息一起处理，获得更好的上下文
     */
    private void processMessageBatch(String sessionId, List<String> batchContents, String agentType) {
        if (batchContents == null || batchContents.isEmpty()) return;
        
        // 对于每条消息，尝试用 LLM 提取（带上下文）
        // 改进：不再单独处理每条消息，而是将多条消息合并分析
        for (int i = 0; i < batchContents.size(); i++) {
            String content = batchContents.get(i);
            
            // 构建上下文：将前后的消息作为上下文
            StringBuilder contextBuilder = new StringBuilder();
            
            // 添加前面的一些消息作为上下文（最多5条）
            int contextStart = Math.max(0, i - 5);
            for (int j = contextStart; j < i; j++) {
                contextBuilder.append("[上文] ").append(batchContents.get(j)).append("\n\n");
            }
            
            // 当前消息
            contextBuilder.append("[当前] ").append(content);
            
            String fullContext = contextBuilder.toString();
            
            // 调用 memoryService 处理（带上下文）
            try {
                memoryService.processMessageWithContext(sessionId, fullContext, agentType);
            } catch (Exception e) {
                log.error("单条处理失败: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 手动刷新所有缓冲（用于关闭时）
     */
    private void flushAllBuffers() {
        for (Map.Entry<String, List<BufferedMessage>> entry : messageBuffer.entrySet()) {
            List<BufferedMessage> buffer = entry.getValue();
            synchronized (buffer) {
                if (!buffer.isEmpty()) {
                    String agentType = buffer.get(0).message.getAgentType();
                    flushBuffer(entry.getKey(), buffer, agentType);
                }
            }
        }
    }
    
    public void stop() {
        running = false;
        
        // 先刷新所有缓冲
        flushAllBuffers();
        codexSessionMetaCache.clear();
        
        // 关闭主线程池
        executor.shutdown();
        
        // 关闭定时任务线程池
        if (persistenceExecutor != null) {
            persistenceExecutor.shutdown();
        }
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
        }
        if (bufferFlushExecutor != null) {
            bufferFlushExecutor.shutdown();
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
            if (bufferFlushExecutor != null && !bufferFlushExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                bufferFlushExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            if (persistenceExecutor != null) persistenceExecutor.shutdownNow();
            if (cleanupExecutor != null) cleanupExecutor.shutdownNow();
            if (bufferFlushExecutor != null) bufferFlushExecutor.shutdownNow();
        }
        
        log.info("FileWatcherService 已停止");
    }
}
