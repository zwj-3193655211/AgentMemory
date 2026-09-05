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
import java.util.Set;
import java.util.stream.Stream;

/**
 * 豆包 Work（DoubaoWork）产出物监控服务
 *
 * 背景：豆包 Work 的对话正文存放在其私有二进制 IndexedDB 中
 *   （%LOCALAPPDATA%\DoubaoWork\User Data\Default\IndexedDB\chrome_doubaowork-chat_0.indexeddb.leveldb），
 *   格式为「长度前缀字符串 + 类型标记」的私有序列化，正文负载无法稳定解析，故不解析对话。
 * 但它在本地留下了 Agent 的产出文件（普通可读文件）：
 *   %USERPROFILE%\DoubaoWork\chats\<日期>\<会话名>\...
 * 本服务定期扫描这些产出文件并纳入 AgentMemory。
 *
 * 存储模型（复用既有 sessions / messages 表，前端可直接看到）：
 *   会话 = chats 下的一个会话目录（如 2026-09-04/new-chat）
 *   消息 = 该会话目录下的一个产出文件（role = "artifact"）
 *
 * 幂等保证：
 *   - 消息 id = 会话ID + 相对文件路径（稳定），数据库按 id UPSERT，重复扫描只更新不堆积；
 *   - 另用「文件大小 + 修改时间」指纹跳过未变更文件，避免每轮重复写库。
 *
 * 注意：只读，绝不回写豆包 Work 的目录。
 */
public class DoubaoWorkWatcher extends ScheduledServiceBase {

    private static final Logger log = LoggerFactory.getLogger(DoubaoWorkWatcher.class);

    private static final String AGENT_TYPE = "doubaowork";
    private static final String ARTIFACT_ROLE = "artifact";

    /** 视为文本文件、需要提取正文的扩展名 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "txt", "py", "js", "ts", "tsx", "jsx", "json", "jsonl", "csv",
            "html", "htm", "css", "java", "go", "rs", "c", "h", "cpp", "sh",
            "bash", "bat", "ps1", "yaml", "yml", "toml", "ini", "conf",
            "properties", "sql", "xml", "log", "vue", "svg", "tex",
            "r", "rb", "php", "swift", "kt", "scala", "pl", "lua"
    );

    /** 单文件正文最大读取字符数（数据库层另有 100k 兜底截断） */
    private static final int MAX_CONTENT_CHARS = 30000;
    /** 超过此大小的文本文件不读正文，仅记录元信息 */
    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

    private final DatabaseService databaseService;
    private final Path chatsDir;

    /** 文件指纹缓存：绝对路径 -> "size:mtime"，用于跳过未变更文件 */
    private final Map<String, String> fileFingerprints = new HashMap<>();

    public DoubaoWorkWatcher(DatabaseService databaseService, String chatsDir) {
        this.databaseService = databaseService;
        this.chatsDir = chatsDir == null ? null : Paths.get(chatsDir);
    }

    @Override
    protected String getServiceName() {
        return "DoubaoWorkWatcher";
    }

    @Override
    protected long getInitialDelaySeconds() {
        return 20;   // 启动 20 秒后首次检查
    }

    @Override
    protected long getPeriodSeconds() {
        return 120;  // 产出文件变化频率低于对话，每 2 分钟检查一次
    }

    @Override
    protected void executeTask() {
        try {
            scanArtifacts();
        } catch (Exception e) {
            log.warn("扫描豆包 Work 产出物失败: {}", e.getMessage());
        }
    }

    /**
     * 扫描产出目录，索引新增或变更的文件
     */
    private void scanArtifacts() throws IOException {
        if (chatsDir == null || !Files.isDirectory(chatsDir)) {
            log.debug("豆包 Work 产出目录不存在: {}", chatsDir);
            return;
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(chatsDir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
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
                indexFile(file, chatsDir.relativize(file));
                fileFingerprints.put(abs, fp);
                indexed++;
            } catch (Exception e) {
                failed++;
                log.debug("索引豆包 Work 文件失败 {}: {}", file.getFileName(), e.getMessage());
            }
        }

        if (indexed > 0) {
            log.info("从豆包 Work 产出目录索引了 {} 个文件（未变更 {} 个，失败 {} 个）", indexed, unchanged, failed);
        }
    }

    /**
     * 将单个产出文件写入 AgentMemory
     */
    private void indexFile(Path file, Path relFromChats) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        String relFile = relFromChats.toString().replace('\\', '/');
        String relDir = chatsDir.relativize(parent).toString().replace('\\', '/');
        if (relDir.isEmpty()) {
            relDir = "chats";
        }

        // 会话 = chats 下的会话目录；id 稳定，便于重复扫描时复用
        String sessionId = "doubaowork-" + sanitize(relDir);
        String title = "DoubaoWork " + relDir.replace('/', ' ');
        databaseService.saveSessionIfNotExists(sessionId, AGENT_TYPE, parent.toString(), title);

        String fileName = file.getFileName().toString();
        long size = Files.size(file);
        String ext = extensionOf(fileName);

        StringBuilder content = new StringBuilder();
        content.append("文件: ").append(fileName).append('\n');
        content.append("路径: ").append(file.toAbsolutePath()).append('\n');
        content.append("大小: ").append(size).append(" 字节\n");

        if (TEXT_EXTENSIONS.contains(ext) && size <= MAX_TEXT_BYTES) {
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
        } else if (TEXT_EXTENSIONS.contains(ext)) {
            content.append("类型: 文本（体积超过 ")
                   .append(MAX_TEXT_BYTES / 1024 / 1024)
                   .append(" MB，未提取正文）\n");
        } else {
            content.append("类型: 二进制/非文本文件（仅记录元信息）\n");
        }

        Message message = new Message();
        message.setId(sessionId + "-f-" + sanitize(relFile));
        message.setSessionId(sessionId);
        message.setRole(ARTIFACT_ROLE);
        message.setContent(content.toString());
        message.setAgentType(AGENT_TYPE);
        message.setProjectName(parent.toString());
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

    private static String extensionOf(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 将路径片段规整为 id 安全片段（只保留字母数字与 . _ -）
     * 中文目录名会被替换为 '-'，保证 id 稳定且不引入特殊字符
     */
    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    @Override
    public void stop() {
        super.stop();
        log.info("DoubaoWorkWatcher 已停止");
    }
}
