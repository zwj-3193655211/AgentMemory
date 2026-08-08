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
        ERROR_CORRECTION("错误纠正", "experiences"),
        USER_PROFILE("用户画像", "user_profiles"),
        BEST_PRACTICE("实践经验", "experiences"),
        PROJECT_CONTEXT("项目上下文", "sessions"),
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
            "不对", "错了", "不是", "不应该", "搞反了", "搞错了", "别这样",
            "不能用", "不要用", "不是这样", "不对的", "方向错了",
            "你理解错了", "你搞错了", "你没理解", "不对吧", "怎么会",
            "我说的不是", "不是这个意思", "不要这样", "不应该这样",
            "重新看", "注意不要", "记住不要", "别忘了"
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
        MemoryType.ERROR_CORRECTION, List.of(),
        MemoryType.USER_PROFILE, List.of("不需要", "不用管"),
        MemoryType.BEST_PRACTICE, List.of("错误", "失败", "问题")
    );
    
    // 正则模式
    private static final Map<MemoryType, List<Pattern>> PATTERNS = Map.of(
        MemoryType.ERROR_CORRECTION, List.of(
            // "不是X，是Y" 模式
            Pattern.compile("不是.{2,30}?[，,。;；][\\s]*(是|应该是|要用|要改|应该是|应该是)"),
            // "不对/错了...应该" 模式
            Pattern.compile("(不对|错了|搞错了|搞反了).{2,40}?(应该是|应该是|应该用|要改成|要用|改为)"),
            // "不要X，要Y" 模式
            Pattern.compile("(不要|别|不能用|不能这样).{2,30}?(要|应该|改成|改用)"),
            // "我说的不是X，(我说的)是Y" 模式
            Pattern.compile("(我说的是?|我的意思是|我指的是).{2,50}?(不是|而是).{0,20}"),
            // "注意不要/记住不要" 约束纠正模式
            Pattern.compile("(注意|记住|切记|千万).{0,10}(不要|别|不能|不可以)")
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
    
    // "纠正"标记词 - 出现这些词表示用户在纠正AI的错误
    private static final List<String> CORRECTION_MARKERS = List.of(
        "不对", "错了", "不是", "不应该", "搞反了", "搞错了", "方向错了",
        "不是这样", "不是这个", "你理解错了", "你搞错了", "你没理解",
        "不要这样", "不应该这样", "别这样", "不能用",
        "我说的不是", "不是这个意思", "我的意思是", "我指的是",
        "不要用", "不要这样", "注意不要", "记住不要"
    );

    // "纠正+正确答案"标记词 - 既有否定又有正确指引
    private static final List<String> CORRECTION_WITH_ANSWER_MARKERS = List.of(
        "应该是", "应该是", "应该用", "要改成", "要用", "改为", "改成",
        "而是", "应该是这个", "正确的是", "其实", "实际上"
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
     * 错误纠正 = 用户纠正AI的错误，优先级最高
     */
    public MemoryType classify(String content) {
        if (content == null || content.trim().isEmpty()) {
            return MemoryType.UNKNOWN;
        }

        String lowerContent = content.toLowerCase();

        // 最高优先级：检查是否是用户纠正AI的错误
        // 纠正检测在求助检测之前，因为"帮我改成X"虽然含"帮我"但本质是纠正
        if (hasCorrectionMarker(content)) {
            return MemoryType.ERROR_CORRECTION;
        }

        // 特殊检查：如果是求助请求，不应保存
        if (isHelpRequest(content)) {
            // 检查是否是用户偏好
            if (hasPreferenceMarkers(content)) {
                return MemoryType.USER_PROFILE;
            }
            // 项目上下文并入实践经验
            if (hasProjectContextMarkers(content)) {
                return MemoryType.BEST_PRACTICE;
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

        // 项目上下文并入实践经验
        if (hasProjectContextMarkers(content)) {
            return MemoryType.BEST_PRACTICE;
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

        // 3. 找出得分最高的类型
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
     * 检查是否有纠正标记（用户在纠正AI）
     */
    private boolean hasCorrectionMarker(String content) {
        String lower = content.toLowerCase();
        for (String marker : CORRECTION_MARKERS) {
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
               (lower.contains("不用") && (lower.contains("npm") || lower.contains("yarn")));
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
        
        // 纯提问不保存（以问号结尾且没有纠正标记）
        if (content.endsWith("？") || content.endsWith("?")) {
            // 除非包含纠正标记
            if (!hasCorrectionMarker(content)) {
                return false;
            }
        }
        
        return true;
    }
}