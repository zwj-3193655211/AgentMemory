package com.agentmemory;

/**
 * Agent 信息实体
 */
public class AgentInfo {
    private String name;
    private String type;
    private String logPath;
    private String cliPath;
    private String version;
    private boolean enabled;
    private String parserType;  // 解析器类型：iflow, claude, openclaw, qwen
    
    public AgentInfo() {}
    
    public AgentInfo(String name, String type, String logPath) {
        this.name = name;
        this.type = type;
        this.logPath = logPath;
        this.enabled = true;
    }
    
    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public String getLogPath() { return logPath; }
    public void setLogPath(String logPath) { this.logPath = logPath; }
    
    public String getCliPath() { return cliPath; }
    public void setCliPath(String cliPath) { this.cliPath = cliPath; }
    
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public String getParserType() { return parserType; }
    public void setParserType(String parserType) { this.parserType = parserType; }
    
    @Override
    public String toString() {
        return "AgentInfo{" +
                "name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", logPath='" + logPath + '\'' +
                ", cliPath='" + cliPath + '\'' +
                ", parserType='" + parserType + '\'' +
                '}';
    }
}
