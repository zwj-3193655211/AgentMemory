package com.agentmemory.launcher;

import com.agentmemory.config.ApplicationConfig;
import com.agentmemory.model.Message;
import com.agentmemory.service.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Agent 启动器 - 代理终端模式
 * 通过 ProcessBuilder 启动 Agent 子进程，劫持 stdin/stdout 实现实时捕获
 */
public class AgentLauncher {
    
    private static final Logger log = LoggerFactory.getLogger(AgentLauncher.class);
    
    private final DatabaseService databaseService;
    private final ExecutorService executor;
    private final String sessionId;
    private final String agentType;
    
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private volatile boolean running;
    
    // 支持的 Agent 配置
    private static final Map<String, AgentConfig> AGENT_CONFIGS = new LinkedHashMap<>();
    
    static {
        AGENT_CONFIGS.put("iflow", new AgentConfig("iflow", "iflow", "iFlow CLI"));
        AGENT_CONFIGS.put("claude", new AgentConfig("claude", "claude", "Claude Code"));
        AGENT_CONFIGS.put("openclaw", new AgentConfig("openclaw", "openclaw", "OpenClaw"));
        AGENT_CONFIGS.put("nanobot", new AgentConfig("nanobot", "nanobot", "Nanobot"));
    }
    
    public AgentLauncher() {
        ApplicationConfig config = ApplicationConfig.load();
        this.databaseService = new DatabaseService(config);
        this.databaseService.init();
        this.executor = Executors.newFixedThreadPool(2);
        this.sessionId = "launcher-" + UUID.randomUUID().toString();
        this.agentType = "unknown";
    }
    
    public AgentLauncher(String agentType) {
        ApplicationConfig config = ApplicationConfig.load();
        this.databaseService = new DatabaseService(config);
        this.databaseService.init();
        this.executor = Executors.newFixedThreadPool(2);
        this.sessionId = "launcher-" + UUID.randomUUID().toString();
        this.agentType = agentType;
    }
    
    /**
     * 列出可用的 Agent
     */
    public static List<AgentConfig> listAvailableAgents() {
        List<AgentConfig> available = new ArrayList<>();
        
        for (AgentConfig config : AGENT_CONFIGS.values()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(config.getCommand(), "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean exited = p.waitFor(2, TimeUnit.SECONDS);
                if (exited && p.exitValue() == 0) {
                    config.setAvailable(true);
                }
            } catch (Exception e) {
                // Agent 不可用
            }
            
            // 检查 PATH 中是否存在
            String command = config.getCommand();
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                command += ".exe";
            }
            
            try {
                ProcessBuilder pb = new ProcessBuilder("where", command);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    config.setAvailable(true);
                }
            } catch (Exception e) {
                // 忽略
            }
            
            available.add(config);
        }
        
        return available;
    }
    
    /**
     * 启动 Agent
     */
    public void launch(String agentType, String[] args) throws IOException {
        AgentConfig config = AGENT_CONFIGS.get(agentType.toLowerCase());
        if (config == null) {
            throw new IllegalArgumentException("未知的 Agent 类型: " + agentType);
        }
        
        // 构建命令
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        if (args != null) {
            command.addAll(Arrays.asList(args));
        }
        
        log.info("启动 Agent: {}", command);
        
        // 启动进程
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.environment().putAll(System.getenv());
        
        process = pb.start();
        running = true;
        
        // 获取 I/O 流
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        
        // 启动输出捕获线程
        executor.submit(this::captureOutput);
        
        // 启动错误输出捕获线程
        executor.submit(this::captureError);
        
        log.info("Agent 已启动，Session ID: {}", sessionId);
    }
    
    /**
     * 捕获标准输出
     */
    private void captureOutput() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                // 输出到控制台
                System.out.println(line);
                System.out.flush();
                
                // 存储为 AI 回复
                saveMessage("assistant", line);
            }
        } catch (IOException e) {
            if (running) {
                log.error("读取输出失败", e);
            }
        }
    }
    
    /**
     * 捕获错误输出
     */
    private void captureError() {
        BufferedReader errorReader = new BufferedReader(
            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while (running && (line = errorReader.readLine()) != null) {
                System.err.println(line);
                System.err.flush();
            }
        } catch (IOException e) {
            if (running) {
                log.error("读取错误输出失败", e);
            }
        }
    }
    
    /**
     * 发送输入到 Agent
     */
    public void sendInput(String input) throws IOException {
        if (writer != null && running) {
            writer.write(input);
            writer.newLine();
            writer.flush();
            
            // 存储为用户输入
            saveMessage("user", input);
        }
    }
    
    /**
     * 保存消息到数据库
     */
    private void saveMessage(String role, String content) {
        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setTimestamp(Instant.now().toString());
        message.setAgentType("launcher");
        
        try {
            databaseService.saveMessage(message);
        } catch (Exception e) {
            log.error("保存消息失败", e);
        }
    }
    
    /**
     * 交互模式 - 用户直接与 Agent 交互
     */
    public void interactiveMode() throws IOException {
        BufferedReader consoleReader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
        
        // 启动一个线程来处理进程结束
        executor.submit(() -> {
            try {
                int exitCode = process.waitFor();
                running = false;
                log.info("Agent 退出，退出码: {}", exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        // 主循环：读取用户输入并转发
        while (running) {
            String line = consoleReader.readLine();
            if (line == null || "exit".equalsIgnoreCase(line.trim())) {
                shutdown();
                break;
            }
            
            if (!line.trim().isEmpty()) {
                sendInput(line);
            }
        }
    }
    
    /**
     * 关闭
     */
    public void shutdown() {
        running = false;
        
        try {
            if (writer != null) writer.close();
            if (reader != null) reader.close();
        } catch (IOException e) {
            // 忽略
        }
        
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
            }
        }
        
        executor.shutdown();
        log.info("AgentLauncher 已关闭");
    }
    
    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }
    
    public String getSessionId() {
        return sessionId;
    }
}
