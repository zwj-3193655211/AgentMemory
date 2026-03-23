package com.agentmemory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键节点提取器 v2
 * 从长对话中提取有价值的节点
 * 
 * 改进：
 * 1. 更精确的语境匹配（区分"你还记得"和"记得要"）
 * 2. 内容长度过滤
 * 3. 上下文窗口（保存前后消息）
 * 4. 配对错误-解决形成完整记录
 */
public class KeyNodeExtractor {
    
    private static final Logger log = LoggerFactory.getLogger(KeyNodeExtractor.class);
    
    // 最小内容长度
    private static final int MIN_CONTENT_LENGTH = 20;
    
    // 上下文窗口大小（前后各取几条）
    private static final int CONTEXT_WINDOW = 2;
    
    // ========== 精确匹配模式（与Python测试保持一致）==========
    
    // 排除模式 - 这些情况下不应该匹配
    private static final List<Pattern> EXCLUDE_PATTERNS = List.of(
        Pattern.compile("你还记得")  // "你还记得吗" 不是实践
    );
    
    // 解决模式
    private static final List<Pattern> RESOLVED_PATTERNS = List.of(
        Pattern.compile("(成功|完成了?|搞定).{0,10}(启动|安装|配置|运行|修复|部署|导入)"),
        Pattern.compile("(原来|后来发现|原因是).{3,50}"),
        Pattern.compile("(好了|可以了|修好了|改好了).{0,20}$"),
        Pattern.compile("(问题|错误|bug).{0,10}?(已|经).{0,5}(解决|修复|找到)"),
        Pattern.compile("解决(使用?|方案)")
    );
    
    // 放弃模式
    private static final List<Pattern> GIVE_UP_PATTERNS = List.of(
        Pattern.compile("放弃(使用|了|这个)?"),
        Pattern.compile("(算了|不搞了|先这样)"),
        Pattern.compile("换成?.{0,10}(方案|方法|试试)")
    );
    
    // 偏好模式
    private static final List<Pattern> PREFERENCE_PATTERNS = List.of(
        Pattern.compile("我(喜欢|习惯|偏好|更(喜欢|倾向)).{3,50}"),
        Pattern.compile("我(一般|通常|平时).{0,5}(用|使).{3,30}")
    );
    
    // 实践模式
    private static final List<Pattern> PRACTICE_PATTERNS = List.of(
        Pattern.compile("(有个坑|踩坑)"),
        Pattern.compile("(经验|建议|推荐).{3,50}"),
        Pattern.compile("注意.{0,5}(要|别|不).{3,40}")
    );
    
    // "后来发现"模式 - 典型的错误纠正
    private static final Pattern LATER_FOUND_PATTERN = Pattern.compile(
        "(?<problem>.{10,60}?)[，,]?(后来发现|原来|原来是)(?<cause>.{5,40}?)[，,，]?(?<solution>.{5,60}?)(就好了|就行|这样|搞定)",
        Pattern.DOTALL
    );
    
    /**
     * 提取关键节点（带上下文）
     */
    public List<KeyNode> extractWithContext(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<KeyNode> nodes = new ArrayList<>();
        
        for (int i = 0; i < messages.size(); i++) {
            String msg = messages.get(i);
            if (msg == null || msg.length() < MIN_CONTENT_LENGTH) continue;
            
            // 检测各类型节点
            KeyNode node = detectNodePrecise(msg, i);
            if (node != null) {
                // 添加上下文
                addContext(node, messages, i);
                nodes.add(node);
            }
        }
        
        // 合并相邻的节点
        nodes = mergeAdjacentNodes(nodes);
        
        log.debug("从 {} 条消息中提取了 {} 个关键节点", messages.size(), nodes.size());
        return nodes;
    }
    
    /**
     * 精确检测节点类型
     */
    private KeyNode detectNodePrecise(String content, int index) {
        // 先检查排除模式
        for (Pattern exclude : EXCLUDE_PATTERNS) {
            if (exclude.matcher(content).find()) {
                return null;
            }
        }
        
        // 优先检测解决（最有价值）
        for (Pattern pattern : RESOLVED_PATTERNS) {
            if (pattern.matcher(content).find()) {
                Map<String, String> extracted = extractStructuredInfo(content);
                return new KeyNode(index, NodeType.RESOLVED, "resolved", content, extracted);
            }
        }
        
        // 检测放弃
        for (Pattern pattern : GIVE_UP_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return new KeyNode(index, NodeType.GIVE_UP, "give_up", content, null);
            }
        }
        
        // 检测偏好
        for (Pattern pattern : PREFERENCE_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return new KeyNode(index, NodeType.PREFERENCE, "preference", content, null);
            }
        }
        
        // 检测实践经验
        for (Pattern pattern : PRACTICE_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return new KeyNode(index, NodeType.PRACTICE, "practice", content, null);
            }
        }
        
        return null;
    }
    
    /**
     * 添加上下文窗口
     */
    private void addContext(KeyNode node, List<String> messages, int index) {
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        
        // 前面的消息
        for (int i = Math.max(0, index - CONTEXT_WINDOW); i < index; i++) {
            String msg = messages.get(i);
            if (msg != null && msg.length() >= 10) {
                before.add(msg);
            }
        }
        
        // 后面的消息
        for (int i = index + 1; i < Math.min(messages.size(), index + CONTEXT_WINDOW + 1); i++) {
            String msg = messages.get(i);
            if (msg != null && msg.length() >= 10) {
                after.add(msg);
            }
        }
        
        node.setContext(before, after);
    }
    
    /**
     * 提取结构化信息
     */
    private Map<String, String> extractStructuredInfo(String content) {
        Map<String, String> result = new HashMap<>();
        
        Matcher m = LATER_FOUND_PATTERN.matcher(content);
        if (m.find()) {
            result.put("problem", m.group("problem").trim());
            result.put("cause", m.group("cause").trim());
            result.put("solution", m.group("solution").trim());
            return result;
        }
        
        return null;
    }
    
    /**
     * 合并相邻的同类节点
     */
    private List<KeyNode> mergeAdjacentNodes(List<KeyNode> nodes) {
        if (nodes.size() <= 1) return nodes;
        
        List<KeyNode> merged = new ArrayList<>();
        KeyNode prev = null;
        
        for (KeyNode node : nodes) {
            if (prev != null && 
                prev.getType() == node.getType() &&
                node.getIndex() - prev.getIndex() <= 2) {
                prev.appendContent("\n" + node.getContent());
            } else {
                merged.add(node);
                prev = node;
            }
        }
        
        return merged;
    }
    
    /**
     * 过滤出值得保存的节点
     */
    public List<KeyNode> filterWorthSaving(List<KeyNode> nodes) {
        List<KeyNode> result = new ArrayList<>();
        
        for (KeyNode node : nodes) {
            String content = node.getContent();
            
            // 内容长度检查
            if (content == null || content.length() < MIN_CONTENT_LENGTH) {
                continue;
            }
            
            // 所有通过检测的节点都值得保存
            result.add(node);
        }
        
        return result;
    }
    
    // 兼容旧接口
    public List<KeyNode> extract(List<String> messages) {
        return extractWithContext(messages);
    }
    
    // ========== 内部类 ==========
    
    /**
     * 关键节点
     */
    public static class KeyNode {
        private final int index;
        private final NodeType type;
        private final String marker;
        private String content;
        private Map<String, String> extractedInfo;
        private List<String> contextBefore = new ArrayList<>();
        private List<String> contextAfter = new ArrayList<>();
        
        public KeyNode(int index, NodeType type, String marker, String content, Map<String, String> extractedInfo) {
            this.index = index;
            this.type = type;
            this.marker = marker;
            this.content = content;
            this.extractedInfo = extractedInfo;
        }
        
        public int getIndex() { return index; }
        public NodeType getType() { return type; }
        public String getMarker() { return marker; }
        public String getContent() { return content; }
        public Map<String, String> getExtractedInfo() { return extractedInfo; }
        public List<String> getContextBefore() { return contextBefore; }
        public List<String> getContextAfter() { return contextAfter; }
        
        public void appendContent(String more) { this.content += more; }
        public void setContext(List<String> before, List<String> after) {
            this.contextBefore = before;
            this.contextAfter = after;
        }
        
        /**
         * 获取完整内容（含上下文）
         */
        public String getFullContent() {
            StringBuilder sb = new StringBuilder();
            if (!contextBefore.isEmpty()) {
                sb.append("【前情】\n");
                for (String ctx : contextBefore) {
                    sb.append(ctx.substring(0, Math.min(100, ctx.length()))).append("...\n");
                }
            }
            sb.append("【关键内容】\n").append(content).append("\n");
            if (!contextAfter.isEmpty()) {
                sb.append("【后续】\n");
                for (String ctx : contextAfter) {
                    sb.append(ctx.substring(0, Math.min(100, ctx.length()))).append("...\n");
                }
            }
            return sb.toString();
        }
        
        @Override
        public String toString() {
            return String.format("KeyNode[%d, %s, '%s']", index, type, marker);
        }
    }
    
    /**
     * 节点类型
     */
    public enum NodeType {
        RESOLVED("已解决"),
        GIVE_UP("放弃"),
        PREFERENCE("偏好"),
        PRACTICE("实践");
        
        private final String displayName;
        NodeType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }
}