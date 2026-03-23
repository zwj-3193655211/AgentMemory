package com.agentmemory;

import com.agentmemory.api.ApiServer;
import com.agentmemory.config.ApplicationConfig;
import com.agentmemory.service.DatabaseService;
import com.agentmemory.service.FileWatcherService;
import com.agentmemory.service.AgentDetectorService;
import com.agentmemory.service.CleanupService;
import com.agentmemory.service.SessionCompressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final ApiServer apiServer;
    
    public AgentMemoryApplication(ApplicationConfig config) {
        this.config = config;
        this.databaseService = new DatabaseService(config);
        this.fileWatcherService = new FileWatcherService(databaseService);
        this.agentDetectorService = new AgentDetectorService();
        this.cleanupService = new CleanupService(databaseService, config.getRetentionDays());
        this.compressionService = new SessionCompressionService(databaseService);
        this.apiServer = new ApiServer(databaseService, fileWatcherService, config.getApiPort());
    }
    
    public void start() throws Exception {
        log.info("========================================");
        log.info("AgentMemory 启动中...");
        log.info("========================================");
        
        // 1. 初始化数据库连接
        log.info("[1/6] 初始化数据库连接...");
        databaseService.init();
        log.info("      数据库连接成功: {}", config.getJdbcUrl());
        
        // 2. 检测已安装的 Agent
        log.info("[2/6] 检测已安装的 Agent...");
        List<AgentInfo> agents = agentDetectorService.detectAgents();
        for (AgentInfo agent : agents) {
            log.info("      发现: {} (路径: {})", agent.getName(), agent.getLogPath());
        }
        databaseService.registerAgents(agents);
        
        // 3. 启动文件监控
        log.info("[3/6] 启动文件监控服务...");
        for (AgentInfo agent : agents) {
            Path watchPath = expandHomePath(agent.getLogPath());
            if (watchPath.toFile().exists()) {
                fileWatcherService.watchDirectory(agent.getType(), watchPath);
                log.info("      监控: {}", watchPath);
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
        
        // 6. 启动 API 服务
        log.info("[6/7] 启动 API 服务...");
        apiServer.start();
        log.info("      API 地址: http://localhost:{}", config.getApiPort());
        
        // 7. 启动完成
        log.info("[7/7] AgentMemory 已就绪");
        log.info("========================================");
        log.info("正在监控 {} 个 Agent 的会话日志...", agents.size());
        log.info("前端界面: http://localhost:{}", config.getApiPort());
        log.info("按 Ctrl+C 退出");
        log.info("========================================");
        
        // 保持运行
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在关闭...");
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
