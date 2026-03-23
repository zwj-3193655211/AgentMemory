package com.agentmemory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 记忆分类器
 * 判断消息属于哪类记忆库
 */
public class MemoryClassifier {
    
    private static final Logger log = LoggerFactory.getLogger(MemoryClassifier.class);
    
    // 记忆类型枚举
    public enum MemoryType {
        ERROR_CORRECTION("错误纠正", "error_corrections"),
        USER_PROFILE("用户画像", "user_profiles"),
        BEST_PRACTICE("实践经验", "best_practices"),
        PROJECT_CONTEXT("项目上下文", "project_contexts"),
        SKILL("技能沉淀", "skills"),
        UNKNOWN("未知", null);
        
        private final String displayName;
        private final String tableName;
        
        MemoryType(String displayName, String tableName) {
            this.displayName = displayName;
            this.tableName = tableName;
        }
        
        public String getDisplayName() { return displayName; }
        public String getTableName() { return tableName; }
    }
    
    // 触发关键词配置
    private static final Map<MemoryType, List<String>> TRIGGER_KEYWORDS = Map.of(
        MemoryType.ERROR_CORRECTION, List.of(
            "失败", "报错", "错误", "不行", "问题", "bug", "异常", "崩溃",
            "缺少", "找不到", "无法", "不能"
        ),
        MemoryType.USER_PROFILE, List.of(
            "我喜欢", "我习惯", "我用", "不用", "偏好", "喜欢用",
            "我的环境", "我的系统", "我装的是", "我用的版本"
        ),
        MemoryType.BEST_PRACTICE, List.of(
            "最佳实践", "推荐", "建议", "应该", "最好", "通常",
            "经验是", "坑", "注意", "记得", "别忘了"
        ),
        MemoryType.PROJECT_CONTEXT, List.of(
            "项目", "工程", "这个项目", "我们的项目", "项目结构",
            "技术栈", "用的是", "框架", "数据库", "端口"
        ),
        MemoryType.SKILL, List.of(
            "步骤", "流程", "先", "再", "然后", "最后",
            "如何", "怎么", "方法", "方式", "教程"
        )
    );
    
    // 反关键词（出现这些词时不归类到对应类型）
    private static final Map<MemoryType, List<String>> ANTI_KEYWORDS = Map.of(
        MemoryType.ERROR_CORRECTION, List.of("成功", "完成", "好了", "没问题", "可以了"),
        MemoryType.USER_PROFILE, List.of("不需要", "不用管"),
        MemoryType.BEST_PRACTICE, List.of("错误", "失败", "问题")
    );
    
    // 正则模式
    private static final Map<MemoryType, List<Pattern>> PATTERNS = Map.of(
        MemoryType.ERROR_CORRECTION, List.of(
            Pattern.compile("(失败|报错|错误|异常).{0,20}(解决|修复|改|方案)"),
            Pattern.compile("(缺少|找不到|没有).{0,10}(dll|文件|模块|依赖)"),
            Pattern.compile("打包.{0,20}(失败|错误|问题)")
        ),
        MemoryType.USER_PROFILE, List.of(
            Pattern.compile("我(喜欢|习惯|偏好).{0,20}(用|使|不)"),
            Pattern.compile("(用|不用).{0,10}(npm|yarn|pip|conda)")
        ),
        MemoryType.BEST_PRACTICE, List.of(
            Pattern.compile("(建议|推荐).{0,30}(用|使|做)"),
            Pattern.compile("(注意|记得).{0,20}(要|别|不)")
        )
    );
    
    // "已解决"标记词 - 出现这些词表示问题已被解决，是值得保存的错误纠正
    private static final List<String> RESOLVED_MARKERS = List.of(
        "后来发现", "原来是", "原因找到了", "问题是", "解决办法是", "解决方法是",
        "这样就行", "就好了", "改好了", "修好了", "搞定了", "弄好了",
        "是因为", "原来是因为", "问题出在", "根本原因是", "所以要用", "得用"
    );
    
    // "求助"标记词 - 出现这些词表示这是提问求助，不是已解决的经验
    private static final List<String> HELP_REQUEST_MARKERS = List.of(
        "请帮我", "帮我", "求助", "请问", "怎么解决", "如何解决",
        "怎么办", "为什么会", "请修复", "请检查", "帮我看看", "能不能"
    );
    
    // "技能/步骤"标记词
    private static final List<String> SKILL_MARKERS = List.of(
        "步骤", "流程", "就几步", "分几步", "其实就", "总共", "第一步", "首先"
    );
    
    // "最佳实践/经验"标记词
    private static final List<String> PRACTICE_MARKERS = List.of(
        "有个坑", "踩坑", "注意", "记得", "别忘了", "经验是", "建议", "推荐"
    );
    
    /**
     * 分类消息
     */
    public MemoryType classify(String content) {
        if (content == null || content.trim().isEmpty()) {
            return MemoryType.UNKNOWN;
        }
        
        String lowerContent = content.toLowerCase();
        
        // 特殊检查：如果是求助请求，不应保存为错误纠正
        if (isHelpRequest(content)) {
            // 检查是否是用户偏好
            if (hasPreferenceMarkers(content)) {
                return MemoryType.USER_PROFILE;
            }
            // 检查是否是项目上下文
            if (hasProjectContextMarkers(content)) {
                return MemoryType.PROJECT_CONTEXT;
            }
            // 其他求助请求不保存
            return MemoryType.UNKNOWN;
        }
        
        // 检查是否是技能/步骤描述
        if (hasSkillMarkers(content)) {
            return MemoryType.SKILL;
        }
        
        // 检查是否是最佳实践/经验
        if (hasPracticeMarkers(content)) {
            return MemoryType.BEST_PRACTICE;
        }
        
        // 检查是否是用户偏好
        if (hasPreferenceMarkers(content)) {
            return MemoryType.USER_PROFILE;
        }
        
        // 检查是否是项目上下文
        if (hasProjectContextMarkers(content)) {
            return MemoryType.PROJECT_CONTEXT;
        }
        
        Map<MemoryType, Integer> scores = new EnumMap<>(MemoryType.class);
        
        // 1. 关键词匹配打分
        for (Map.Entry<MemoryType, List<String>> entry : TRIGGER_KEYWORDS.entrySet()) {
            MemoryType type = entry.getKey();
            int score = 0;
            
            for (String keyword : entry.getValue()) {
                if (lowerContent.contains(keyword.toLowerCase())) {
                    score += 1;
                }
            }
            
            // 检查反关键词
            List<String> antiKeywords = ANTI_KEYWORDS.getOrDefault(type, List.of());
            for (String anti : antiKeywords) {
                if (lowerContent.contains(anti.toLowerCase())) {
                    score -= 2;
                }
            }
            
            scores.put(type, score);
        }
        
        // 2. 正则模式匹配加成
        for (Map.Entry<MemoryType, List<Pattern>> entry : PATTERNS.entrySet()) {
            MemoryType type = entry.getKey();
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(content).find()) {
                    scores.merge(type, 3, Integer::sum);
                }
            }
        }
        
        // 3. 特殊检查：ERROR_CORRECTION 必须有"已解决"标记
        if (scores.getOrDefault(MemoryType.ERROR_CORRECTION, 0) > 0) {
            if (!hasResolvedMarker(content)) {
                scores.put(MemoryType.ERROR_CORRECTION, 0);
            }
        }
        
        // 4. 找出得分最高的类型
        return scores.entrySet().stream()
            .filter(e -> e.getValue() > 0)
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(MemoryType.UNKNOWN);
    }
    
    /**
     * 检查是否是求助请求
     */
    private boolean isHelpRequest(String content) {
        String lower = content.toLowerCase();
        for (String marker : HELP_REQUEST_MARKERS) {
            if (lower.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否有已解决标记
     */
    private boolean hasResolvedMarker(String content) {
        String lower = content.toLowerCase();
        for (String marker : RESOLVED_MARKERS) {
            if (lower.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否有技能/步骤标记
     */
    private boolean hasSkillMarkers(String content) {
        String lower = content.toLowerCase();
        for (String marker : SKILL_MARKERS) {
            if (lower.contains(marker.toLowerCase())) {
                // 确保包含操作相关内容
                if (lower.contains("步") || lower.contains("先") || lower.contains("然后") || lower.contains("最后")) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 检查是否有最佳实践/经验标记
     */
    private boolean hasPracticeMarkers(String content) {
        String lower = content.toLowerCase();
        for (String marker : PRACTICE_MARKERS) {
            if (lower.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否有用户偏好标记
     */
    private boolean hasPreferenceMarkers(String content) {
        String lower = content.toLowerCase();
        return lower.contains("我喜欢") || lower.contains("我习惯") || 
               lower.contains("我偏好") || lower.contains("我不用") ||
               lower.contains("我更喜欢") || lower.contains("我通常") ||
               lower.contains("不用") && (lower.contains("npm") || lower.contains("yarn"));
    }
    
    /**
     * 检查是否有项目上下文标记
     */
    private boolean hasProjectContextMarkers(String content) {
        String lower = content.toLowerCase();
        return lower.contains("项目") && (lower.contains("技术栈") || lower.contains("框架") ||
               lower.contains("数据库") || lower.contains("用的") || lower.contains("语言"));
    }
    
    /**
     * 提取记忆标题
     */
    public String extractTitle(String content, MemoryType type) {
        if (content == null || content.length() < 10) {
            return content;
        }
        
        // 截取前50个字符作为标题，遇到句号/问号则截断
        int maxLen = Math.min(50, content.length());
        int cutPos = maxLen;
        
        for (int i = 0; i < maxLen && i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '。' || c == '？' || c == '！' || c == '\n') {
                cutPos = i;
                break;
            }
        }
        
        String title = content.substring(0, cutPos).trim();
        if (cutPos < content.length()) {
            title += "...";
        }
        
        return title;
    }
    
    /**
     * 提取标签
     */
    public List<String> extractTags(String content) {
        Set<String> tags = new LinkedHashSet<>();
        
        // 技术关键词
        String[] techKeywords = {
            "python", "java", "javascript", "typescript", "node", "npm", "yarn",
            "docker", "kubernetes", "git", "maven", "gradle",
            "postgresql", "mysql", "mongodb", "redis", "sqlite",
            "spring", "vue", "react", "angular", "flask", "django",
            "pyinstaller", "pip", "conda", "wsl", "linux", "windows",
            "api", "rest", "graphql", "json", "yaml", "xml",
            "打包", "部署", "测试", "调试", "编译"
        };
        
        String lowerContent = content.toLowerCase();
        for (String keyword : techKeywords) {
            if (lowerContent.contains(keyword.toLowerCase())) {
                tags.add(keyword.toLowerCase());
            }
        }
        
        return new ArrayList<>(tags);
    }
    
    /**
     * 判断是否值得保存为记忆
     */
    public boolean isWorthRemembering(String content, MemoryType type) {
        if (type == MemoryType.UNKNOWN) {
            return false;
        }
        
        // 太短的内容不保存
        if (content == null || content.length() < 20) {
            return false;
        }
        
        // 纯工具调用不保存
        if (content.startsWith("调用") || content.contains("执行命令")) {
            return false;
        }
        
        // 纯提问不保存（以问号结尾且没有解决方案）
        if (content.endsWith("？") || content.endsWith("?")) {
            // 除非包含已解决标记
            if (!hasResolvedMarker(content)) {
                return false;
            }
        }
        
        return true;
    }
}