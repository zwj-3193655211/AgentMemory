package com.agentmemory.launcher;

/**
 * Agent 配置
 */
public class AgentConfig {
    
    private final String type;
    private final String command;
    private final String displayName;
    private boolean available;
    
    public AgentConfig(String type, String command, String displayName) {
        this.type = type;
        this.command = command;
        this.displayName = displayName;
        this.available = false;
    }
    
    public String getType() {
        return type;
    }
    
    public String getCommand() {
        return command;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public boolean isAvailable() {
        return available;
    }
    
    public void setAvailable(boolean available) {
        this.available = available;
    }
    
    @Override
    public String toString() {
        return String.format("%s (%s) %s", displayName, command, available ? "[可用]" : "[未安装]");
    }
}
