package com.agentmemory.service;

import com.agentmemory.config.ApplicationConfig;
import com.agentmemory.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话处理器
 * 处理长对话，提取有价值的记忆
 * 使用 ConcurrentHashMap + 会话级锁实现高并发
 */
public class SessionProcessor {

    private static final Logger log = LoggerFactory.getLogger(SessionProcessor.class);

    private final CompletionDetector completionDetector;
    private final KeyNodeExtractor keyNodeExtractor;
    private final MemoryClassifier memoryClassifier;
    private final MemoryExtractor memoryExtractor;
    private final MemoryService memoryService;
    private final ApplicationConfig config;

    // 会话缓存：使用 ConcurrentHashMap 支持高并发
    private final int MAX_CACHE_SIZE;
    private final ConcurrentHashMap<String, SessionContext> sessionCache;
    
    // 会话级锁映射：每个会话独立的锁
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks;
    
    // LRU 队列：使用 LinkedHashSet 实现 O(1) 的查找和删除，同时保持插入顺序
    private final LinkedHashSet<String> lruQueue;
    private final Object lruLock = new Object();
    
    // 当前缓存大小
    private final AtomicInteger cacheSize = new AtomicInteger(0);
    
    // 定时清理任务
    private ScheduledExecutorService cleanupScheduler;
    private static final long DEFAULT_MAX_IDLE_HOURS = 1;  // 默认最大空闲时间1小时

    public SessionProcessor(MemoryService memoryService, ApplicationConfig config) {
        this.memoryService = memoryService;
        this.config = config;
        this.MAX_CACHE_SIZE = config != null ? config.getMaxCacheSize() : 100;

        // 使用 ConcurrentHashMap 支持高并发
        this.sessionCache = new ConcurrentHashMap<>(MAX_CACHE_SIZE);
        this.sessionLocks = new ConcurrentHashMap<>();
        this.lruQueue = new LinkedHashSet<>();

        this.completionDetector = new CompletionDetector();
        this.keyNodeExtractor = new KeyNodeExtractor();
        this.memoryClassifier = new MemoryClassifier();
        this.memoryExtractor = new MemoryExtractor();
    }
    
    /**
     * 启动定时清理任务
     * @param checkIntervalHours 检查间隔（小时）
     */
    public void startCleanupTask(int checkIntervalHours) {
        if (cleanupScheduler != null) {
            log.warn("清理任务已在运行");
            return;
        }
        
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SessionProcessor-Cleanup");
            t.setDaemon(true);
            return t;
        });
        
        long intervalMillis = TimeUnit.HOURS.toMillis(checkIntervalHours);
        long maxIdleMillis = TimeUnit.HOURS.toMillis(DEFAULT_MAX_IDLE_HOURS);
        
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupStaleSessions(maxIdleMillis);
            } catch (Exception e) {
                log.error("清理过期会话失败", e);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        
        log.info("已启动会话清理任务，间隔 {} 小时", checkIntervalHours);
    }
    
    /**
     * 启动定时清理任务（使用默认间隔）
     */
    public void startCleanupTask() {
        startCleanupTask(1);  // 默认每小时检查一次
    }
    
    /**
     * 停止定时清理任务
     */
    public void stopCleanupTask() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
            try {
                if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            cleanupScheduler = null;
            log.info("已停止会话清理任务");
        }
    }

    // 配置参数（从配置读取，提供默认值）
    private int getIncrementalThreshold() {
        return config != null ? config.getIncrementalThreshold() : 30;
    }
    
    /**
     * 处理新消息（线程安全，使用会话级锁）
     */
    public void processMessage(Message message) {
        String sessionId = message.getSessionId();
        if (sessionId == null) {
            log.warn("消息缺少sessionId: {}", message.getId());
            return;
        }

        // 获取会话级锁
        ReentrantLock sessionLock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        sessionLock.lock();
        try {
            // 获取或创建会话上下文
            SessionContext ctx = sessionCache.computeIfAbsent(sessionId, id -> {
                SessionContext newCtx = new SessionContext(id);
                // 更新 LRU
                synchronized (lruLock) {
                    lruQueue.add(id);
                    // 检查是否需要淘汰
                    while (lruQueue.size() > MAX_CACHE_SIZE) {
                        // LinkedHashSet: 获取并移除第一个元素（最旧）
                        String oldest = lruQueue.iterator().next();
                        lruQueue.remove(oldest);
                        SessionContext removed = sessionCache.remove(oldest);
                        sessionLocks.remove(oldest);
                        if (removed != null) {
                            log.debug("LRU 缓存已满，移除最旧的会话: {} (消息数: {})",
                                oldest.substring(0, 8), removed.getMessageCount());
                        }
                    }
                }
                return newCtx;
            });
            
            // 更新 LRU 访问顺序：先删除再添加，移动到末尾
            synchronized (lruLock) {
                lruQueue.remove(sessionId);  // O(1) 操作
                lruQueue.add(sessionId);
            }
            
            ctx.addMessage(message);

            // 检查是否需要处理
            CompletionDetector.CompletionStatus status = completionDetector.detect(
                ctx.getContents(),
                ctx.getLastActivityTime()
            );

            log.debug("会话 {} 状态: {}", sessionId.substring(0, 8), status.getDescription());

            // 增量处理：消息数达到阈值时
            if (completionDetector.shouldProcessIncrementally(ctx.getContents())) {
                log.info("会话 {} 达到增量处理阈值 ({}条)",
                    sessionId.substring(0, 8), ctx.getMessageCount());
                processSessionIncrementally(ctx);
            }

            // 完成处理：会话结束时
            if (status.canProcess()) {
                log.info("会话 {} 完成: {}", sessionId.substring(0, 8), status.getDescription());
                processSessionComplete(ctx);
                sessionCache.remove(sessionId);
                sessionLocks.remove(sessionId);
                synchronized (lruLock) {
                    lruQueue.remove(sessionId);
                }
            }
        } finally {
            sessionLock.unlock();
        }
    }
    
    /**
     * 增量处理（处理已积累的内容）
     */
    private void processSessionIncrementally(SessionContext ctx) {
        // 只处理已处理的索引之后的新消息
        int startIdx = ctx.getLastProcessedIndex();
        List<String> newContents = ctx.getContents().subList(startIdx, ctx.getContents().size());
        
        if (newContents.isEmpty()) return;
        
        // 提取关键节点
        List<KeyNodeExtractor.KeyNode> nodes = keyNodeExtractor.extract(newContents);
        nodes = keyNodeExtractor.filterWorthSaving(nodes);
        
        // 保存记忆
        for (KeyNodeExtractor.KeyNode node : nodes) {
            saveNodeAsMemory(node, ctx, startIdx + node.getIndex());
        }
        
        // 更新处理索引
        ctx.setLastProcessedIndex(ctx.getContents().size() - 5); // 保留最后5条未处理，避免截断
    }
    
    /**
     * 完整处理（会话结束时）
     */
    private void processSessionComplete(SessionContext ctx) {
        // 处理剩余未处理的消息
        int startIdx = ctx.getLastProcessedIndex();
        List<String> remaining = ctx.getContents().subList(
            Math.max(0, startIdx), 
            ctx.getContents().size()
        );
        
        if (!remaining.isEmpty()) {
            // 提取关键节点
            List<KeyNodeExtractor.KeyNode> nodes = keyNodeExtractor.extract(remaining);
            nodes = keyNodeExtractor.filterWorthSaving(nodes);
            
            // 保存记忆
            for (KeyNodeExtractor.KeyNode node : nodes) {
                saveNodeAsMemory(node, ctx, startIdx + node.getIndex());
            }
        }
        
        // 生成会话摘要（如果有足够内容）
        if (ctx.getMessageCount() >= 10) {
            generateSessionSummary(ctx);
        }
    }
    
    /**
     * 将节点保存为记忆
     */
    private void saveNodeAsMemory(KeyNodeExtractor.KeyNode node, SessionContext ctx, int msgIndex) {
        String content = node.getContent();
        if (content == null || content.length() < 20) return;
        
        // 确定记忆类型
        MemoryClassifier.MemoryType type = mapNodeTypeToMemoryType(node.getType());
        if (type == MemoryClassifier.MemoryType.UNKNOWN) return;
        
        // 提取结构化信息
        Map<String, String> info = node.getExtractedInfo();
        String title;
        String description;
        
        if (info != null && info.containsKey("problem") && info.containsKey("solution")) {
            title = info.get("problem");
            description = "原因: " + info.get("cause") + "\n解决: " + info.get("solution");
        } else {
            title = memoryClassifier.extractTitle(content, type);
            description = content.length() > 200 ? content.substring(0, 200) + "..." : content;
        }
        
        // 提取标签
        List<String> tags = memoryClassifier.extractTags(content);
        
        // 保存到对应表
        try {
            memoryService.saveMemory(
                type.getTableName(),
                title,
                description,
                tags,
                ctx.getSessionId(),
                ctx.getProjectName()
            );
            log.info("保存记忆: {} -> {} ({})", type.getDisplayName(), title.substring(0, Math.min(30, title.length())), type.getTableName());
        } catch (Exception e) {
            log.error("保存记忆失败: {}", e.getMessage());
        }
    }
    
    /**
     * 映射节点类型到记忆类型
     */
    private MemoryClassifier.MemoryType mapNodeTypeToMemoryType(KeyNodeExtractor.NodeType nodeType) {
        switch (nodeType) {
            case RESOLVED:
                return MemoryClassifier.MemoryType.ERROR_CORRECTION;
            case GIVE_UP:
                return MemoryClassifier.MemoryType.ERROR_CORRECTION;  // 放弃方案也是经验
            case PREFERENCE:
                return MemoryClassifier.MemoryType.USER_PROFILE;
            case PRACTICE:
                return MemoryClassifier.MemoryType.BEST_PRACTICE;
            default:
                return MemoryClassifier.MemoryType.UNKNOWN;
        }
    }
    
    /**
     * 生成会话摘要
     */
    private void generateSessionSummary(SessionContext ctx) {
        // TODO: 可以调用LLM生成摘要，或者简单地统计关键节点
        log.debug("会话 {} 包含 {} 条消息，可生成摘要",
            ctx.getSessionId().substring(0, 8), ctx.getMessageCount());
    }

    /**
     * 手动触发处理指定会话
     */
    public void forceProcess(String sessionId) {
        ReentrantLock sessionLock = sessionLocks.get(sessionId);
        if (sessionLock != null) {
            sessionLock.lock();
            try {
                SessionContext ctx = sessionCache.get(sessionId);
                if (ctx != null) {
                    processSessionComplete(ctx);
                    sessionCache.remove(sessionId);
                    sessionLocks.remove(sessionId);
                    synchronized (lruLock) {
                        lruQueue.remove(sessionId);
                    }
                }
            } finally {
                sessionLock.unlock();
            }
        }
    }
    
    /**
     * 获取缓存统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeSessions", sessionCache.size());
        stats.put("sessions", sessionCache.keySet().stream()
            .map(id -> id.substring(0, 8) + "...")
            .toList());
        return stats;
    }
    
    /**
     * 清理过期的会话锁和缓存
     * 应定期调用以防止内存泄漏
     * @param maxIdleMillis 最大空闲时间（毫秒），超过此时间的会话将被清理
     * @return 清理的会话数量
     */
    public int cleanupStaleSessions(long maxIdleMillis) {
        long now = System.currentTimeMillis();
        int cleaned = 0;
        
        // 遍历所有会话，检查最后活动时间
        for (Map.Entry<String, SessionContext> entry : sessionCache.entrySet()) {
            String sessionId = entry.getKey();
            SessionContext ctx = entry.getValue();
            
            if (ctx == null) {
                // 会话上下文为空，清理锁
                sessionLocks.remove(sessionId);
                synchronized (lruLock) {
                    lruQueue.remove(sessionId);
                }
                cleaned++;
                continue;
            }
            
            long idleTime = now - ctx.getLastActivityTime().toEpochMilli();
            if (idleTime > maxIdleMillis) {
                // 会话已过期，尝试清理
                ReentrantLock lock = sessionLocks.get(sessionId);
                if (lock != null && lock.tryLock()) {
                    try {
                        // 获取锁成功，可以安全清理
                        sessionCache.remove(sessionId);
                        sessionLocks.remove(sessionId);
                        synchronized (lruLock) {
                            lruQueue.remove(sessionId);
                        }
                        log.debug("清理过期会话: {} (空闲 {}分钟)", 
                            sessionId.substring(0, 8), idleTime / 60000);
                        cleaned++;
                    } finally {
                        lock.unlock();
                    }
                }
                // 如果无法获取锁，说明会话正在被处理，跳过
            }
        }
        
        if (cleaned > 0) {
            log.info("清理了 {} 个过期会话", cleaned);
        }
        return cleaned;
    }
    
    // ========== 内部类 ==========
    
    /**
     * 会话上下文
     */
    public static class SessionContext {
        private final String sessionId;
        private final List<Message> messages = new ArrayList<>();
        private Instant lastActivityTime = Instant.now();
        private int lastProcessedIndex = 0;
        private String projectName;
        
        public SessionContext(String sessionId) {
            this.sessionId = sessionId;
        }
        
        public void addMessage(Message msg) {
            messages.add(msg);
            lastActivityTime = Instant.now();
            if (msg.getProjectName() != null) {
                this.projectName = msg.getProjectName();
            }
        }
        
        public String getSessionId() { return sessionId; }
        public int getMessageCount() { return messages.size(); }
        public Instant getLastActivityTime() { return lastActivityTime; }
        public int getLastProcessedIndex() { return lastProcessedIndex; }
        public void setLastProcessedIndex(int idx) { this.lastProcessedIndex = idx; }
        public String getProjectName() { return projectName; }
        
        public List<String> getContents() {
            return messages.stream()
                .map(Message::getContent)
                .filter(Objects::nonNull)
                .toList();
        }
    }
}
