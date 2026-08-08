package com.agentmemory.service;

import com.agentmemory.AgentInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 检测服务
 * 自动检测系统中已安装的 CLI Agent
 * 检测方式：1) ~/.xxx 目录存在 2) CLI 命令在 PATH 中
 */
public class AgentDetectorService {
    
    private static final Logger log = LoggerFactory.getLogger(AgentDetectorService.class);
    
    private final String userHome;
    private final boolean isWindows;
    
    public AgentDetectorService() {
        this.userHome = System.getProperty("user.home");
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
    }
    
    /**
     * 检测命令是否在系统 PATH 中
     * @param command 要检测的命令名
     * @return 如果找到返回完整路径，否则返回 null
     */
    private String findInPath(String command) {
        try {
            String[] cmd;
            if (isWindows) {
                cmd = new String[]{"cmd", "/c", "where", command};
            } else {
                cmd = new String[]{"which", command};
            }
            
            Process process = Runtime.getRuntime().exec(cmd);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String path = reader.readLine();
                if (path != null && !path.isEmpty()) {
                    return path.trim();
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            log.debug("检测 {} 在 PATH 中失败: {}", command, e.getMessage());
        }
        return null;
    }
    
    /**
     * 检测已安装的 Agent（使用公共方法重构）
     */
    public List<AgentInfo> detectAgents() {
        List<AgentInfo> agents = new ArrayList<>();
        
        // 使用统一方法检测各 Agent
        addIfNotNull(agents, detectAgent("iFlow CLI", "iflow", ".iflow", "projects"));
        addIfNotNull(agents, detectClaudeCode());
        addIfNotNull(agents, detectAgent("Codex CLI", "codex", ".codex", "sessions"));
        addIfNotNull(agents, detectAgent("OpenClaw", "openclaw", ".openclaw", "agents", "main", "sessions"));
        addIfNotNull(agents, detectAgent("Nanobot", "nanobot", ".nanobot", "workspace", "sessions"));
        addIfNotNull(agents, detectAgent("Qwen CLI", "qwen", ".qwen", "projects"));
        addIfNotNull(agents, detectAgent("Qoder CLI", "qoder", ".qoder", "projects"));
        addIfNotNull(agents, detectAgent("Crush CLI", "crush", ".crush"));
        addIfNotNull(agents, detectAgent("WorkBuddy", "workbuddy", ".workbuddy", "projects"));
        addIfNotNull(agents, detectAgent("Pi Agent", "pi", ".pi", "agent", "sessions"));

        // 记忆重构新增：SQLite/JSONL 型 agent（会话由 AgentMemorySyncService 导入）
        addIfNotNull(agents, detectAgent("Hermes", "hermes", ".hermes"));
        addIfNotNull(agents, detectAgent("Mavis", "mavis", ".mavis"));
        addIfNotNull(agents, detectAgent("Marvis", "marvis", ".marvis"));
        addIfNotNull(agents, detectAgent("MiniMax Code", "minimax", ".minimax"));

        log.info("检测到 {} 个 Agent", agents.size());
        return agents;
    }
    
    /**
     * 专门检测 Claude Code（兼容 Unix/Windows 路径）
     */
    private AgentInfo detectClaudeCode() {
        // 标准路径: ~/.claude/projects
        AgentInfo agent = detectAgentWithVersion("Claude Code", "claude", ".claude", "projects");
        if (agent != null) return agent;
        
        // Windows 路径: ~/.claude（根目录，无 projects 子目录时也能识别）
        agent = detectAgentWithVersion("Claude Code", "claude", ".claude");
        if (agent != null) return agent;
        
        // Windows AppData 路径
        if (isWindows) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                Path claudeAppData = Paths.get(appData, "Claude");
                if (Files.exists(claudeAppData)) {
                    AgentInfo info = new AgentInfo();
                    info.setName("Claude Code");
                    info.setType("claude");
                    info.setLogPath(claudeAppData.toString());
                    info.setParserType("claude");
                    String cliPath = findInPath("claude");
                    info.setCliPath(cliPath);
                    info.setEnabled(cliPath != null);
                    log.debug("检测到 Claude Code (AppData): {}", claudeAppData);
                    return info;
                }
            }
        }
        return null;
    }
    
    /**
     * 添加 Agent 到列表（非空时）
     */
    private void addIfNotNull(List<AgentInfo> agents, AgentInfo agent) {
        if (agent != null) {
            agents.add(agent);
        }
    }
    
    /**
     * 检测 Agent 的公共方法
     * @param name 显示名称
     * @param type Agent 类型（用于 PATH 检测和作为默认解析器类型）
     * @param pathParts 相对于用户主目录的路径部分
     */
    private AgentInfo detectAgent(String name, String type, String... pathParts) {
        Path agentDir = Paths.get(userHome, pathParts);
        if (Files.exists(agentDir)) {
            AgentInfo agent = new AgentInfo();
            agent.setName(name);
            agent.setType(type);
            agent.setLogPath(agentDir.toString());
            agent.setParserType(type);  // 默认解析器类型与 Agent 类型相同
            
            String cliPath = findInPath(type.toLowerCase());
            agent.setCliPath(cliPath);
            // 启用状态与 CLI 解耦：目录存在即可启用（CLI 仅是附加信息，
            // 桌面版 agent 如 codex/marvis 没有命令行但也应启用）
            agent.setEnabled(true);
            
            log.debug("检测到 {}: {}, PATH: {}", name, type, cliPath);
            return agent;
        }
        return null;
    }
    
    /**
     * 检测 Agent 并读取版本文件
     */
    private AgentInfo detectAgentWithVersion(String name, String type, String... pathParts) {
        AgentInfo agent = detectAgent(name, type, pathParts);
        if (agent != null) {
            Path agentDir = Paths.get(userHome, pathParts);
            try {
                Path versionFile = agentDir.resolve("version.txt");
                if (Files.exists(versionFile)) {
                    agent.setVersion(Files.readString(versionFile).trim());
                }
            } catch (IOException ignored) {}
        }
        return agent;
    }
}
