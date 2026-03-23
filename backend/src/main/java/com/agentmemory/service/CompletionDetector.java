package com.agentmemory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 完成信号检测器
 * 检测对话是否完成/可以处理
 */
public class CompletionDetector {
    
    private static final Logger log = LoggerFactory.getLogger(CompletionDetector.class);
    
    // 配置参数
    private final Duration timeWindow;        // 时间窗口（默认5分钟）
    private final int minMessages;            // 最小消息数
    
    // 显式完成标记词（用户说"好了/解决了/搞定"等）
    private static final List<String> COMPLETION_MARKERS = List.of(
        "好了", "搞定", "解决", "成功", "可以了", "完成了", "没问题了",
        "修好了", "改好了", "这样就行", "行了", "完事", "告一段落"
    );
    
    // 显式中断标记词（用户说"算了/不搞了/先这样"等）
    private static final List<String> INTERRUPTION_MARKERS = List.of(
        "算了", "不搞了", "先这样", "暂时", "以后再说", "不做了",
        "放弃", "换", "跳过", "先停", "先关"
    );
    
    // Agent完成标记（AI回复中的成功信号）
    private static final List<String> AGENT_SUCCESS_MARKERS = List.of(
        "已完成", "已修复", "已解决", "成功启动", "安装成功",
        "配置完成", "部署成功", "测试通过"
    );
    
    public CompletionDetector() {
        this(Duration.ofMinutes(5), 3);
    }
    
    public CompletionDetector(Duration timeWindow, int minMessages) {
        this.timeWindow = timeWindow;
        this.minMessages = minMessages;
    }
    
    /**
     * 检测会话是否完成
     * @param messages 会话消息列表
     * @param lastActivity 最后活动时间
     * @return 完成状态
     */
    public CompletionStatus detect(List<String> messages, Instant lastActivity) {
        if (messages == null || messages.isEmpty()) {
            return CompletionStatus.EMPTY;
        }
        
        // 检查消息数量
        if (messages.size() < minMessages) {
            return CompletionStatus.TOO_SHORT;
        }
        
        String lastMessage = messages.get(messages.size() - 1);
        
        // 1. 检查显式完成标记
        if (hasCompletionMarker(lastMessage)) {
            log.debug("检测到显式完成标记");
            return CompletionStatus.COMPLETED;
        }
        
        // 2. 检查中断标记
        if (hasInterruptionMarker(lastMessage)) {
            log.debug("检测到中断标记");
            return CompletionStatus.INTERRUPTED;
        }
        
        // 3. 检查时间窗口
        if (lastActivity != null) {
            Duration elapsed = Duration.between(lastActivity, Instant.now());
            if (elapsed.compareTo(timeWindow) > 0) {
                log.debug("时间窗口已过: {}", elapsed);
                return CompletionStatus.TIMEOUT;
            }
        }
        
        // 4. 检查Agent成功标记（在最后几条消息中）
        for (int i = Math.max(0, messages.size() - 3); i < messages.size(); i++) {
            if (hasAgentSuccessMarker(messages.get(i))) {
                log.debug("检测到Agent成功标记");
                return CompletionStatus.COMPLETED;
            }
        }
        
        return CompletionStatus.ONGOING;
    }
    
    /**
     * 检测是否达到处理阈值（即使未完成也可以处理一部分）
     */
    public boolean shouldProcessIncrementally(List<String> messages) {
        // 超过50条消息时，即使未完成也应该增量处理
        return messages != null && messages.size() >= 50;
    }
    
    private boolean hasCompletionMarker(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        return COMPLETION_MARKERS.stream().anyMatch(m -> lower.contains(m.toLowerCase()));
    }
    
    private boolean hasInterruptionMarker(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        return INTERRUPTION_MARKERS.stream().anyMatch(m -> lower.contains(m.toLowerCase()));
    }
    
    private boolean hasAgentSuccessMarker(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        return AGENT_SUCCESS_MARKERS.stream().anyMatch(m -> lower.contains(m.toLowerCase()));
    }
    
    /**
     * 完成状态枚举
     */
    public enum CompletionStatus {
        EMPTY(false, "会话为空"),
        TOO_SHORT(false, "消息太少"),
        ONGOING(false, "进行中"),
        COMPLETED(true, "已完成"),
        INTERRUPTED(true, "被中断"),
        TIMEOUT(true, "超时");
        
        private final boolean canProcess;
        private final String description;
        
        CompletionStatus(boolean canProcess, String description) {
            this.canProcess = canProcess;
            this.description = description;
        }
        
        public boolean canProcess() { return canProcess; }
        public String getDescription() { return description; }
    }
}
