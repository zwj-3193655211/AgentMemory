package com.agentmemory.service.memorysync;

import com.agentmemory.model.AgentMemoryEntry;

import java.util.List;

/**
 * 统一解析接口：每种记忆格式一个实现
 */
public interface MemoryParser {
    List<AgentMemoryEntry> parse(String sourcePath) throws Exception;
}
