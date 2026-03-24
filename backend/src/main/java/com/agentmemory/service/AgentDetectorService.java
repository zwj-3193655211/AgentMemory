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
        addIfNotNull(agents, detectAgentWithVersion("Claude Code", "claude", ".claude", "projects"));
        addIfNotNull(agents, detectAgent("OpenClaw", "openclaw", ".openclaw", "agents", "main", "sessions"));
        addIfNotNull(agents, detectAgent("Nanobot", "nanobot", ".nanobot"));
        addIfNotNull(agents, detectAgent("Qwen CLI", "qwen", ".qwen", "projects"));
        addIfNotNull(agents, detectAgent("Qoder CLI", "qoder", ".qoder", "projects"));
        
        return agents;
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
            agent.setEnabled(cliPath != null);
            
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
