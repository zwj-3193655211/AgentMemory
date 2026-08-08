package com.agentmemory.model;

/**
 * Agent 记忆源解析出的画像条目
 */
public class AgentMemoryEntry {
    private String agent;        // hermes/pi/claude/workbuddy/minimax/mavis/marvis/codex
    private String category;     // 偏好/工具/项目/沟通 等
    private String content;      // 条目正文
    private String sourcePath;   // 原始文件路径

    public AgentMemoryEntry() {}

    public AgentMemoryEntry(String agent, String category, String content, String sourcePath) {
        this.agent = agent;
        this.category = category;
        this.content = content;
        this.sourcePath = sourcePath;
    }

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
}
