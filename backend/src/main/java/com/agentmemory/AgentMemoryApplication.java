package com.agentmemory;

import com.agentmemory.api.ApiServer;
import com.agentmemory.config.ApplicationConfig;
import com.agentmemory.service.DatabaseService;
import com.agentmemory.service.FileWatcherService;
import com.agentmemory.service.AgentDetectorService;
import com.agentmemory.service.CleanupService;
import com.agentmemory.service.CrushDatabaseWatcher;
import com.agentmemory.service.WorkBuddyWatcher;
import com.agentmemory.service.DoubaoWorkWatcher;
import com.agentmemory.service.WorkBuddyMemoryWatcher;
import com.agentmemory.service.SessionCompressionService;
import com.agentmemory.service.AgentMemorySyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * AgentMemory - 本地 Agent 语义化记忆引擎
 * 
 * 核心功能：
 * 1. 自动监控多个 CLI Agent 的会话日志文件
 * 2. 实时解析并存入 PostgreSQL
 * 3. 支持语义化检索
 * 4. 自动过期清理（默认14天）
 * 5. 提供 HTTP API 和前端界面
 */
public class AgentMemoryApplication {
    
    private static final Logger log = LoggerFactory.getLogger(AgentMemoryApplication.class);
    
    private final ApplicationConfig config;
    private final DatabaseService databaseService;
    private final FileWatcherService fileWatcherService;
    private final AgentDetectorService agentDetectorService;
    private final CleanupService cleanupService;
    private final SessionCompressionService compressionService;
    private final AgentMemorySyncService memorySyncService;
    private final ApiServer apiServer;
    private List<AgentInfo> detectedAgents;
    private CrushDatabaseWatcher crushDatabaseWatcher;
    private WorkBuddyWatcher workBuddyWatcher;
    private DoubaoWorkWatcher doubaoWorkWatcher;
    private WorkBuddyMemoryWatcher workBuddyMemoryWatcher;

    public AgentMemoryApplication(ApplicationConfig config) {
        this.config = config;
        this.databaseService = new DatabaseService(config);
        this.fileWatcherService = new FileWatcherService(databaseService);
        this.agentDetectorService = new AgentDetectorService();
        this.cleanupService = new CleanupService(databaseService, config.getRetentionDays());
        this.compressionService = new SessionCompressionService(databaseService);
        this.memorySyncService = new AgentMemorySyncService(databaseService);
        this.apiServer = new ApiServer(databaseService, fileWatcherService, agentDetectorService, config.getApiPort());
    }

    public void start() throws Exception {
        log.info("========================================");
        log.info("AgentMemory 启动中...");
        log.info("========================================");
        
        // 1. 初始化数据库连接
        log.info("[1/6] 初始化数据库连接...");
        databaseService.init();
        log.info("      数据库连接成功: {}", config.getJdbcUrl());
        
        // 1.5 数据库就绪后加载 LLM Provider 配置（MemoryService 构造时数据库尚未初始化）
        fileWatcherService.configureMemoryLLM();
        
        // 2. 检测已安装的 Agent
        log.info("[2/6] 检测已安装的 Agent...");
        detectedAgents = agentDetectorService.detectAgents();
        for (AgentInfo agent : detectedAgents) {
            log.info("      发现: {} (路径: {})", agent.getName(), agent.getLogPath());
        }
        databaseService.registerAgents(detectedAgents);

        // 3. 启动文件监控（WorkBuddy 使用独立 Watcher，跳过；SQLite 型 agent 由 AgentMemorySyncService 轮询导入）
        log.info("[3/6] 启动文件监控服务...");
        for (AgentInfo agent : detectedAgents) {
            if ("workbuddy".equals(agent.getType())) {
                continue;  // WorkBuddy 由 WorkBuddyWatcher 独立监控
            }
            if ("hermes".equals(agent.getType()) || "mavis".equals(agent.getType())
                    || "marvis".equals(agent.getType()) || "minimax".equals(agent.getType())) {
                continue;  // SQLite 型由 AgentMemorySyncService 轮询；minimax 会话目录暂空，待格式确认后接入
            }
            Path watchPath = expandHomePath(agent.getLogPath());
            if (watchPath.toFile().exists()) {
                String parserType = agent.getParserType() != null ? agent.getParserType() : agent.getType();
                fileWatcherService.watchDirectory(agent.getType(), parserType, watchPath);
                log.info("      监控: {} (解析器: {})", watchPath, parserType);
            } else {
                log.warn("      路径不存在，跳过: {}", watchPath);
            }
        }
        
        // 4. 启动清理服务
        log.info("[4/7] 启动数据清理服务...");
        cleanupService.start();
        log.info("      数据保留: {} 天", config.getRetentionDays());
        
        // 5. 启动会话压缩服务
        log.info("[5/7] 启动会话压缩服务...");
        compressionService.start();

        // 5.5 启动 Agent 记忆同步服务（画像 + SQLite 会话导入）
        log.info("[5.5/8] 启动 Agent 记忆同步服务...");
        memorySyncService.start();

        // 6. 启动 Crush 数据库监控服务
        log.info("[6/8] 启动 Crush 数据库监控服务...");
        startCrushDatabaseWatcher();

        // 7. 启动 WorkBuddy 对话监控服务
        log.info("[7/8] 启动 WorkBuddy 对话监控服务...");
        startWorkBuddyWatcher();

        // 7.5 启动豆包 Work 产出物监控服务（对话正文为私有二进制，只索引产出文件）
        log.info("[7.5/9] 启动豆包 Work 产出监控服务...");
        startDoubaoWorkWatcher();

        // 7.6 启动 WorkBuddy 记忆文件监控服务（补充接入 .workbuddy 下的 .md 记忆资产）
        log.info("[7.6/9] 启动 WorkBuddy 记忆监控服务...");
        startWorkBuddyMemoryWatcher();

        // 8. 启动 API 服务
        log.info("[8/8] 启动 API 服务...");
        apiServer.setCompressionService(compressionService);
        apiServer.setMemorySyncService(memorySyncService);
        apiServer.start();
        log.info("      API 地址: http://localhost:{}", config.getApiPort());

        // 9. 启动完成
        log.info("AgentMemory 已就绪");
        log.info("========================================");
        log.info("正在监控 {} 个 Agent 的会话日志...", detectedAgents.size());
        log.info("前端界面: http://localhost:{}", config.getApiPort());
        log.info("按 Ctrl+C 退出");
        log.info("========================================");
        
        // 保持运行
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在关闭...");
            if (crushDatabaseWatcher != null) {
                crushDatabaseWatcher.stop();
            }
            if (workBuddyWatcher != null) {
                workBuddyWatcher.stop();
            }
            if (workBuddyMemoryWatcher != null) {
                workBuddyMemoryWatcher.stop();
            }
            apiServer.stop();
            compressionService.stop();
            cleanupService.stop();
            fileWatcherService.stop();
            databaseService.close();
            latch.countDown();
        }));
        
        latch.await();
    }
    
    private Path expandHomePath(String path) {
        if (path.startsWith("~")) {
            String home = System.getProperty("user.home");
            return Paths.get(path.replace("~", home));
        }
        return Paths.get(path);
    }

    /**
     * 启动 Crush 数据库监控服务
     */
    private void startCrushDatabaseWatcher() {
        // 查找 Crush agent 的配置
        for (AgentInfo agent : detectedAgents) {
            if ("crush".equals(agent.getType())) {
                String crushDbPath = agent.getLogPath();
                if (crushDbPath != null && !crushDbPath.isEmpty()) {
                    crushDbPath = expandHomePath(crushDbPath).toString();
                    crushDatabaseWatcher = new CrushDatabaseWatcher(databaseService, crushDbPath);
                    crushDatabaseWatcher.start();
                    log.info("      Crush 数据库监控: {} (每 30 秒检查)", crushDbPath);
                    return;
                }
            }
        }
        log.info("      Crush 数据库未配置，跳过");
    }

    /**
     * 启动 WorkBuddy 对话监控服务
     */
    private void startWorkBuddyWatcher() {
        for (AgentInfo agent : detectedAgents) {
            if ("workbuddy".equals(agent.getType())) {
                String projectsDir = agent.getLogPath();
                if (projectsDir != null && !projectsDir.isEmpty()) {
                    projectsDir = expandHomePath(projectsDir).toString();
                    workBuddyWatcher = new WorkBuddyWatcher(databaseService, projectsDir);
                    workBuddyWatcher.start();
                    log.info("      WorkBuddy 对话监控: {} (每 60 秒检查)", projectsDir);
                    return;
                }
            }
        }
        log.info("      WorkBuddy 未配置，跳过");
    }

    /**
     * 启动豆包 Work 产出物监控服务
     *
     * 豆包 Work 的对话正文存放在私有二进制 IndexedDB 中，无法稳定解析；
     * 这里只监控它本地的 Agent 产出目录（%USERPROFILE%\DoubaoWork\chats），
     * 把产出文件纳入 AgentMemory，供检索与统计使用（只读，不回写）。
     */
    private void startDoubaoWorkWatcher() {
        Path chatsDir = Paths.get(System.getProperty("user.home"), "DoubaoWork", "chats");
        if (!Files.isDirectory(chatsDir)) {
            log.info("      豆包 Work 产出目录不存在，跳过: {}", chatsDir);
            return;
        }
        doubaoWorkWatcher = new DoubaoWorkWatcher(databaseService, chatsDir.toString());
        doubaoWorkWatcher.start();
        log.info("      豆包 Work 产出监控: {} (每 120 秒检查)", chatsDir);
    }

    /**
     * 启动 WorkBuddy 记忆文件监控服务
     *
     * WorkBuddy 的对话会话由 WorkBuddyWatcher 从 projects 目录下的 jsonl 导入，
     * 这里补充接入它的 Markdown 记忆资产（顶层 *.md + memory/*.md + memery/*.md），
     * 作为消息（role=artifact）纳入 AgentMemory，供检索与统计使用（只读，不回写）。
     */
    private void startWorkBuddyMemoryWatcher() {
        Path wbDir = Paths.get(System.getProperty("user.home"), ".workbuddy");
        if (!Files.isDirectory(wbDir)) {
            log.info("      WorkBuddy 记忆目录不存在，跳过: {}", wbDir);
            return;
        }
        workBuddyMemoryWatcher = new WorkBuddyMemoryWatcher(databaseService, wbDir.toString());
        workBuddyMemoryWatcher.start();
        log.info("      WorkBuddy 记忆监控: {} (每 120 秒检查)", wbDir);
    }
    
    public static void main(String[] args) {
        try {
            ApplicationConfig config = ApplicationConfig.load();
            AgentMemoryApplication app = new AgentMemoryApplication(config);
            app.start();
        } catch (Exception e) {
            log.error("启动失败", e);
            System.exit(1);
        }
    }
}
