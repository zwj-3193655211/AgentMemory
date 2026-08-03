package com.agentmemory.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 命令行启动器
 * 用户通过此工具启动 Agent，实现实时捕获
 */
public class CommandLineLauncher {
    
    public static void main(String[] args) {
        AgentLauncher launcher = null;
        
        try {
            if (args.length == 0) {
                // 交互模式：选择 Agent
                launcher = runInteractive();
            } else if ("--list".equals(args[0]) || "-l".equals(args[0])) {
                // 列出可用 Agent
                listAgents();
            } else {
                // 直接启动指定 Agent
                String agentType = args[0];
                String[] agentArgs = args.length > 1 ? 
                    java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];
                
                launcher = new AgentLauncher(agentType);
                launchAgent(launcher, agentType, agentArgs);
            }
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (launcher != null) {
                launcher.shutdown();
            }
        }
    }
    
    private static AgentLauncher runInteractive() throws Exception {
        System.out.println("╔═══════════════════════════════════════════╗");
        System.out.println("║       AgentMemory - Agent 启动器          ║");
        System.out.println("╚═══════════════════════════════════════════╝");
        System.out.println();
        
        List<AgentConfig> agents = AgentLauncher.listAvailableAgents();
        
        System.out.println("可用的 Agent:");
        for (int i = 0; i < agents.size(); i++) {
            AgentConfig agent = agents.get(i);
            System.out.printf("  %d. %s %s%n", i + 1, agent.getDisplayName(), 
                agent.isAvailable() ? "[已安装]" : "[未安装]");
        }
        System.out.println();
        
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
        
        System.out.print("选择要启动的 Agent (输入序号): ");
        String choice = reader.readLine().trim();
        
        try {
            int index = Integer.parseInt(choice) - 1;
            if (index >= 0 && index < agents.size()) {
                AgentConfig selected = agents.get(index);
                if (selected.isAvailable()) {
                    AgentLauncher launcher = new AgentLauncher(selected.getType());
                    launchAgent(launcher, selected.getType(), new String[0]);
                    return launcher;
                } else {
                    System.out.println("该 Agent 未安装，请先安装 " + selected.getCommand());
                }
            } else {
                System.out.println("无效的选择");
            }
        } catch (NumberFormatException e) {
            System.out.println("请输入有效的数字");
        }
        return null;
    }
    
    private static void listAgents() {
        System.out.println("检测已安装的 Agent...\n");
        
        List<AgentConfig> agents = AgentLauncher.listAvailableAgents();
        for (AgentConfig agent : agents) {
            System.out.println(agent);
        }
    }
    
    private static void launchAgent(AgentLauncher launcher, String agentType, String[] args) throws Exception {
        System.out.println("\n启动 " + agentType + "...");
        System.out.println("会话将被实时记录到 AgentMemory 数据库");
        System.out.println("输入 'exit' 退出\n");
        System.out.println("─".repeat(50));
        
        launcher.launch(agentType, args);
        launcher.interactiveMode();
    }
}