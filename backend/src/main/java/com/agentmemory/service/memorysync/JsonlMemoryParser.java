package com.agentmemory.service.memorysync;

import com.agentmemory.model.AgentMemoryEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * JSONL parser: pi session event stream (~/.pi/agent/sessions, files ending in .jsonl).
 * Extract user text messages and merge into profile entries (preferences/habits).
 */
public class JsonlMemoryParser implements MemoryParser {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<AgentMemoryEntry> parse(String sourcePath) throws Exception {
        List<AgentMemoryEntry> entries = new ArrayList<>();
        List<String> userMsgs = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(sourcePath))) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = mapper.readTree(line);
                if (!"message".equals(node.path("type").asText())) continue;
                if (!"user".equals(node.path("message").path("role").asText())) continue;
                JsonNode content = node.path("message").path("content");
                StringBuilder text = new StringBuilder();
                if (content.isArray()) {
                    for (JsonNode c : content) {
                        if ("text".equals(c.path("type").asText())) {
                            text.append(c.path("text").asText());
                        }
                    }
                } else if (content.isTextual()) {
                    text.append(content.asText());
                }
                if (text.length() > 20) {
                    userMsgs.add(text.toString());
                }
            } catch (Exception ignored) {
                // 单行解析失败跳过
            }
        }
        String joined = String.join("\n", userMsgs);
        if (joined.length() > 50) {
            AgentMemoryEntry e = new AgentMemoryEntry();
            e.setContent(joined.substring(0, Math.min(joined.length(), 5000)));
            e.setCategory("会话偏好");
            e.setSourcePath(sourcePath);
            entries.add(e);
        }
        return entries;
    }
}
