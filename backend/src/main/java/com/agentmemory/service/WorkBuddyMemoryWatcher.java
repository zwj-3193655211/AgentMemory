package com.agentmemory.service;

import com.agentmemory.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * WorkBuddy 记忆文件监控服务
 *
 * 背景：WorkBuddy (Claw) 的对话会话已由 {@link WorkBuddyWatcher} 从
 *   ~/.workbuddy/projects/**\/*.jsonl 导入；本服务补充接入它的「记忆资产」——
 *   ~/.workbuddy 下的 Markdown 记忆文件：
 *     顶层  : IDENTITY.md / MEMORY.md / SOUL.md / USER.md（身份与长期记忆）
 *     memory/: &lt;uid&gt;_memory.md（用户记忆画像：工作背景/个人背景/当前关注/近期动态）
 *     memery/: &lt;uid&gt;_memery.md（旧版用户记忆画像）
 *
 * 存储模型（与 {@link DoubaoWorkWatcher} 一致，前端可直接看到）：
 *   会话 = workbuddy-memory（固定会话，聚合 WorkBuddy 全部记忆文件）
 *   消息 = 一个记忆 .md 文件（role = "artifact"）
 *
 * 幂等保证：
 *   - 消息 id = 会话ID + 相对文件路径（稳定），数据库按 id UPSERT，重复扫描只更新不堆积；
 *   - 文件指纹「大小 + 修改时间」跳过未变更文件，避免每轮重复写库。
 *
 * 注意：只读，绝不回写 WorkBuddy 目录。
 */
public class WorkBuddyMemoryWatcher extends ScheduledServiceBase {

    private static final Logger log = LoggerFactory.getLogger(WorkBuddyMemoryWatcher.class);

    private static final String AGENT_TYPE = "workbuddy";
    private static final String ARTIFACT_ROLE = "artifact";
    private static final String SESSION_ID = "workbuddy-memory";
    private static final String SESSION_TITLE = "WorkBuddy 记忆";

    /** 单文件正文最大读取字符数（数据库层另有兜底截断） */
    private static final int MAX_CONTENT_CHARS = 30000;

    private final DatabaseService databaseService;
    private final Path workbuddyDir;

    /** 文件指纹缓存：绝对路径 -> "size:mtime"，用于跳过未变更文件 */
    private final Map<String, String> fileFingerprints = new HashMap<>();

    public WorkBuddyMemoryWatcher(DatabaseService databaseService, String workbuddyDir) {
        this.databaseService = databaseService;
        this.workbuddyDir = workbuddyDir == null ? null : Paths.get(workbuddyDir);
    }

    @Override
    protected String getServiceName() {
        return "WorkBuddyMemoryWatcher";
    }

    @Override
    protected long getInitialDelaySeconds() {
        return 25;   // 启动 25 秒后首次检查
    }

    @Override
    protected long getPeriodSeconds() {
        return 120;  // 记忆文件变化频率低，每 2 分钟检查一次
    }

    @Override
    protected void executeTask() {
        try {
            scanMemoryFiles();
        } catch (Exception e) {
            log.warn("扫描 WorkBuddy 记忆文件失败: {}", e.getMessage());
        }
    }

    /**
     * 扫描 WorkBuddy 记忆目录，索引新增或变更的 .md 文件
     */
    private void scanMemoryFiles() throws IOException {
        if (workbuddyDir == null || !Files.isDirectory(workbuddyDir)) {
            log.debug("WorkBuddy 目录不存在: {}", workbuddyDir);
            return;
        }

        List<Path> files = new ArrayList<>();
        collectMdFiles(workbuddyDir, files);
        Path memoryDir = workbuddyDir.resolve("memory");
        if (Files.isDirectory(memoryDir)) {
            collectMdFiles(memoryDir, files);
        }
        Path memeryDir = workbuddyDir.resolve("memery");
        if (Files.isDirectory(memeryDir)) {
            collectMdFiles(memeryDir, files);
        }

        int indexed = 0;
        int unchanged = 0;
        int failed = 0;

        for (Path file : files) {
            String abs = file.toAbsolutePath().toString();
            try {
                String fp = fingerprint(file);
                if (fp == null) {
                    continue;
                }
                if (fp.equals(fileFingerprints.get(abs))) {
                    unchanged++;
                    continue;
                }
                indexFile(file);
                fileFingerprints.put(abs, fp);
                indexed++;
            } catch (Exception e) {
                failed++;
                log.debug("索引 WorkBuddy 记忆文件失败 {}: {}", file.getFileName(), e.getMessage());
            }
        }

        if (indexed > 0) {
            log.info("从 WorkBuddy 记忆目录索引了 {} 个文件（未变更 {} 个，失败 {} 个）", indexed, unchanged, failed);
        }
    }

    /**
     * 收集目录下的 .md 文件（排除 .bak 等非记忆后缀）
     */
    private void collectMdFiles(Path dir, List<Path> out) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                  .forEach(out::add);
        }
    }

    /**
     * 将单个记忆文件写入 AgentMemory
     */
    private void indexFile(Path file) throws IOException {
        String relStr = workbuddyDir.relativize(file).toString().replace('\\', '/');
        String fileName = file.getFileName().toString();
        long size = Files.size(file);

        databaseService.saveSessionIfNotExists(SESSION_ID, AGENT_TYPE, file.getParent().toString(), SESSION_TITLE);

        StringBuilder content = new StringBuilder();
        content.append("文件: ").append(fileName).append('\n');
        content.append("路径: ").append(file.toAbsolutePath()).append('\n');
        content.append("大小: ").append(size).append(" 字节\n");

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            text = "（文件读取失败或非 UTF-8 编码: " + e.getMessage() + "）";
        }
        if (text.length() > MAX_CONTENT_CHARS) {
            text = text.substring(0, MAX_CONTENT_CHARS) + "\n\n... (内容过长已截断)";
        }
        content.append("类型: 文本\n\n--- 文件内容 ---\n").append(text);

        Message message = new Message();
        message.setId(SESSION_ID + "-f-" + sanitize(relStr));
        message.setSessionId(SESSION_ID);
        message.setRole(ARTIFACT_ROLE);
        message.setContent(content.toString());
        message.setAgentType(AGENT_TYPE);
        message.setProjectName(file.getParent().toString());
        message.setTimestamp(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()).toString());

        databaseService.saveMessage(message);
    }

    /**
     * 文件指纹：大小 + 修改时间
     */
    private String fingerprint(Path file) {
        try {
            return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 将路径片段规整为 id 安全片段（只保留字母数字与 . _ -）
     */
    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    @Override
    public void stop() {
        super.stop();
        log.info("WorkBuddyMemoryWatcher 已停止");
    }
}
