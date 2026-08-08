package com.agentmemory.service.memorysync;

import com.agentmemory.model.AgentMemoryEntry;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 解析器：处理 § 分隔（hermes）、frontmatter（workbuddy）、
 * 结构化章节（minimax）、规则文件（claude）等多种 Markdown 形态。
 * 统一输出：按 § / 空行 / 章节标题切分为画像条目。
 */
public class MarkdownMemoryParser implements MemoryParser {

    @Override
    public List<AgentMemoryEntry> parse(String sourcePath) throws Exception {
        String content = Files.readString(Paths.get(sourcePath));
        List<AgentMemoryEntry> entries = new ArrayList<>();

        // 1. 优先按 § 分隔（hermes USER.md/MEMORY.md 格式）
        if (content.contains("§")) {
            for (String sec : content.split("§")) {
                String cleaned = clean(sec);
                if (isValid(cleaned)) {
                    entries.add(entry(cleaned, sourcePath));
                }
            }
            return entries;
        }

        // 2. 按章节标题（## / # ）切分（minimax 结构化格式）
        List<String> sections = splitByHeadings(content);
        for (String sec : sections) {
            String cleaned = clean(sec);
            if (isValid(cleaned)) {
                entries.add(entry(cleaned, sourcePath));
            }
        }
        return entries;
    }

    /** 按标题切分，保留标题作为条目首行 */
    private List<String> splitByHeadings(String content) {
        List<String> sections = new ArrayList<>();
        String[] lines = content.split("\\R");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.matches("^#{1,3}\\s.*")) {
                if (current.length() > 0) {
                    sections.add(current.toString());
                }
                current = new StringBuilder(line + "\n");
            } else {
                current.append(line).append("\n");
            }
        }
        if (current.length() > 0) {
            sections.add(current.toString());
        }
        return sections;
    }

    /** 清理：去 frontmatter、标题符号、粗体、多余空行 */
    private String clean(String s) {
        String t = s.replaceAll("(?s)^---.*?---", "").trim();
        t = t.replaceAll("^#{1,6}\\s*", "").replaceAll("\\*\\*", "").trim();
        // 压缩连续空行为单个
        t = t.replaceAll("\\R{2,}", "\n").trim();
        return t;
    }

    private boolean isValid(String s) {
        return s != null && s.length() > 20;
    }

    private AgentMemoryEntry entry(String content, String sourcePath) {
        return new AgentMemoryEntry(null, inferCategory(content), content, sourcePath);
    }

    /** 根据内容关键词推断分类 */
    public static String inferCategory(String content) {
        String c = content.toLowerCase();
        if (c.contains("偏好") || c.contains("风格") || c.contains("prefer")) return "偏好";
        if (c.contains("工具") || c.contains("tool") || c.contains("命令")) return "工具";
        if (c.contains("项目") || c.contains("project") || c.contains("仓库")) return "项目";
        if (c.contains("沟通") || c.contains("回复") || c.contains("cli")) return "沟通";
        if (c.contains("错误") || c.contains("坑") || c.contains("修复")) return "教训";
        return "通用";
    }
}
