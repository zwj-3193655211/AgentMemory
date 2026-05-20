package com.agentmemory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆结构化提取器
 * 从对话内容中提取结构化字段
 */
public class MemoryExtractor {
    
    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);
    
    /**
     * 提取结果
     */
    public static class ExtractedMemory {
        public String title;
        public String problem;
        public String cause;
        public String solution;
        public String scenario;
        public String practice;
        public String description;
        public List<String> tags;
        public List<String> steps;
        public Map<String, Object> extra;
        
        // 可恢复压缩相关字段
        public String originalContent;    // 原始完整内容
        public String summary;            // 摘要
        public String compressionLevel;   // 压缩级别：FULL/PARTIAL/COMPRESSED
        
        public ExtractedMemory() {
            this.tags = new ArrayList<>();
            this.steps = new ArrayList<>();
            this.extra = new HashMap<>();
            this.compressionLevel = "FULL"; // 默认不压缩
        }
    }
    
    // 问题-解决方案模式 - 更严格匹配（保留给其他类型）
    private static final Pattern PROBLEM_SOLUTION_PATTERN = Pattern.compile(
        "(?<problem>.{10,100}?)(是因为|原因|由于).{0,20}?(?<solution>.{10,200}?)(解决|修复|改好|好了)",
        Pattern.DOTALL
    );

    // === 错误纠正提取模式（用户纠正AI的错误）===

    // "不是X，(而)是Y" 模式：提取AI的错误X和正确答案Y
    private static final Pattern NOT_X_IS_Y_PATTERN = Pattern.compile(
        "(?:不是|并非)(?<wrong>.{2,80}?)[，,。；;\\s]*(?:而是|是|应该是|应该是|要用|其实是)(?<correct>.{2,200})",
        Pattern.DOTALL
    );

    // "不对/错了...应该X" 模式
    private static final Pattern WRONG_SHOULD_PATTERN = Pattern.compile(
        "(?:不对|错了|搞错了|搞反了|方向错了).{0,40}?(?:应该是|应该是|应该用|要用|要改成|改为)(?<correct>.{2,200})",
        Pattern.DOTALL
    );

    // "不要X，(要/应该)Y" 模式
    private static final Pattern DONT_X_DO_Y_PATTERN = Pattern.compile(
        "(?:不要|别|不能用|不能这样|不要这样|不要用).{2,40}?(?:要|应该|改成|改用|用)(?<correct>.{2,200})",
        Pattern.DOTALL
    );

    // "我说的不是X，我说的/其实是Y" 模式
    private static final Pattern I_MEANT_PATTERN = Pattern.compile(
        "(?:我说的不是|我的意思不是|我指的不是|不是这个意思)(?<wrong>.{2,60}?)[，,。；;\\s]*(?:我说的|我的意思是|我指的是|其实是|而是)(?<correct>.{2,200})",
        Pattern.DOTALL
    );

    // "注意/记住不要X" 约束纠正模式
    private static final Pattern CONSTRAINT_PATTERN = Pattern.compile(
        "(?:注意|记住|切记|千万).{0,10}(?:不要|别|不能|不可以)(?<wrong>.{2,150})",
        Pattern.DOTALL
    );

    // "X不行/不对/错误，应该Y" 模式
    private static final Pattern X_BAD_Y_GOOD_PATTERN = Pattern.compile(
        "(?<wrong>.{5,80}?(?:不行|不对|不对的|错误|有问题)).{0,20}?(?:应该|要用|改成|改为|改用)(?<correct>.{2,200})",
        Pattern.DOTALL
    );
    
    // 偏好模式
    private static final Pattern PREFERENCE_PATTERN = Pattern.compile(
        "我(喜欢|习惯|偏好)(?<action>.{0,10}?)(?<target>[^，。！？]{2,20})",
        Pattern.DOTALL
    );
    
    // 步骤模式 - 更全面的匹配
    private static final Pattern STEP_PATTERN = Pattern.compile(
        "(第[一二三四五六七八九十\\d]+[步，、]|[先|然后|再|接着|其次|最后])(?<step>[^。！？\\n]{5,80})",
        Pattern.DOTALL
    );
    
    // 数字步骤模式
    private static final Pattern NUMBERED_STEP_PATTERN = Pattern.compile(
        "(\\d+)[.、:：](?<step>[^\\n]{5,80})",
        Pattern.DOTALL
    );
    
    /**
     * 提取错误纠正记忆（用户纠正AI的错误）
     * 提取AI的错误(wrong)和用户给出的正确内容(correct)
     * 如果没有提取到有效的纠正内容，返回null
     */
    public ExtractedMemory extractErrorCorrection(String content, List<String> tags) {
        ExtractedMemory memory = new ExtractedMemory();
        memory.tags = tags;

        Matcher matcher;

        // 模式1: "不是X，(而)是Y" — 最常见的纠正句式
        matcher = NOT_X_IS_Y_PATTERN.matcher(content);
        if (matcher.find()) {
            memory.problem = "AI认为：" + matcher.group("wrong").trim();
            memory.solution = "正确答案：" + matcher.group("correct").trim();
            memory.title = generateCorrectionTitle(matcher.group("wrong").trim(), matcher.group("correct").trim());
            return memory;
        }

        // 模式2: "不对/错了...应该X" — 先否定再给出正确答案
        matcher = WRONG_SHOULD_PATTERN.matcher(content);
        if (matcher.find()) {
            // 提取"不对"之前的内容作为AI的错误
            int wrongIdx = content.indexOf("不对");
            if (wrongIdx == -1) wrongIdx = content.indexOf("错了");
            if (wrongIdx == -1) wrongIdx = content.indexOf("搞错了");
            if (wrongIdx == -1) wrongIdx = content.indexOf("搞反了");
            if (wrongIdx == -1) wrongIdx = content.indexOf("方向错了");
            String wrongPart = wrongIdx > 0 ? content.substring(0, Math.min(wrongIdx, 80)).trim() : "";
            memory.problem = wrongPart.isEmpty() ? "AI回答有误" : "AI认为：" + wrongPart;
            memory.solution = "正确答案：" + matcher.group("correct").trim();
            memory.title = generateCorrectionTitle(wrongPart, matcher.group("correct").trim());
            return memory;
        }

        // 模式3: "不要X，要/应该Y" — 否定指令+正确指令
        matcher = DONT_X_DO_Y_PATTERN.matcher(content);
        if (matcher.find()) {
            // 找到"不要/别"的位置和后面"要/应该"的位置
            int dontIdx = -1;
            for (String kw : new String[]{"不要", "别", "不能用", "不能这样", "不要这样", "不要用"}) {
                dontIdx = content.indexOf(kw);
                if (dontIdx >= 0) break;
            }
            if (dontIdx >= 0) {
                int doIdx = -1;
                for (String kw : new String[]{"要", "应该", "改成", "改用"}) {
                    int idx = content.indexOf(kw, dontIdx + 2);
                    if (idx > dontIdx) { doIdx = idx; break; }
                }
                if (doIdx > dontIdx) {
                    memory.problem = "AI错误做法：" + content.substring(dontIdx, doIdx).trim();
                    int correctEnd = Math.min(doIdx + 200, content.length());
                    memory.solution = "正确做法：" + content.substring(doIdx, correctEnd).trim();
                }
            }
            if (memory.solution == null || memory.solution.isEmpty()) {
                memory.solution = "正确做法：" + matcher.group("correct").trim();
            }
            memory.title = generateCorrectionTitle(memory.problem, memory.solution);
            return memory;
        }

        // 模式4: "我说的不是X，我说的/其实是Y"
        matcher = I_MEANT_PATTERN.matcher(content);
        if (matcher.find()) {
            memory.problem = "AI误解为：" + matcher.group("wrong").trim();
            memory.solution = "用户实际意思：" + matcher.group("correct").trim();
            memory.title = generateCorrectionTitle(matcher.group("wrong").trim(), matcher.group("correct").trim());
            return memory;
        }

        // 模式5: "X不行/不对...应该Y"
        matcher = X_BAD_Y_GOOD_PATTERN.matcher(content);
        if (matcher.find()) {
            memory.problem = "AI的错误方案：" + matcher.group("wrong").trim();
            memory.solution = "正确方案：" + matcher.group("correct").trim();
            memory.title = generateCorrectionTitle(matcher.group("wrong").trim(), matcher.group("correct").trim());
            return memory;
        }

        // 模式6: "注意/记住不要X" — 约束纠正
        matcher = CONSTRAINT_PATTERN.matcher(content);
        if (matcher.find()) {
            memory.problem = "AI的约束违规：" + matcher.group("wrong").trim();
            memory.solution = "约束要求：" + content.substring(0, Math.min(content.indexOf("不要") > -1 ?
                content.indexOf("不要") + 100 : 80, content.length())).trim();
            memory.title = generateCorrectionTitle(matcher.group("wrong").trim(), null);
            return memory;
        }

        // 兜底：如果内容包含纠正关键词但没匹配到具体模式，保存原始内容
        // 检查是否含有高置信度纠正词
        if (containsStrongCorrectionMarker(content)) {
            memory.problem = "用户纠正";
            memory.solution = content.trim();
            memory.title = generateCorrectionTitle(null, content.trim());
            return memory;
        }

        log.debug("未提取到有效的错误纠正内容，跳过保存: {}",
            content.substring(0, Math.min(50, content.length())));
        return null;
    }

    /**
     * 检查是否包含强纠正标记（兜底用）
     */
    private boolean containsStrongCorrectionMarker(String content) {
        String[] strongMarkers = {
            "搞反了", "搞错了", "方向错了", "你理解错了", "你搞错了",
            "你理解反了", "不是这样的", "不是这个意思", "完全不对",
            "理解错了", "弄反了", "搞混了"
        };
        String lower = content.toLowerCase();
        for (String marker : strongMarkers) {
            if (lower.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成错误纠正标题
     */
    private String generateCorrectionTitle(String wrongPart, String correctPart) {
        if (wrongPart != null && !wrongPart.isEmpty() && correctPart != null && !correctPart.isEmpty()) {
            String shortWrong = wrongPart.length() > 20 ? wrongPart.substring(0, 20) + "..." : wrongPart;
            return "纠正：" + shortWrong;
        } else if (correctPart != null && !correctPart.isEmpty()) {
            String shortCorrect = correctPart.length() > 25 ? correctPart.substring(0, 25) + "..." : correctPart;
            return "纠正：" + shortCorrect;
        } else if (wrongPart != null && !wrongPart.isEmpty()) {
            String shortWrong = wrongPart.length() > 25 ? wrongPart.substring(0, 25) + "..." : wrongPart;
            return "纠正：" + shortWrong;
        }
        return "错误纠正";
    }
    
    /**
     * 提取用户画像记忆
     */
    public ExtractedMemory extractUserProfile(String content, List<String> tags) {
        ExtractedMemory memory = new ExtractedMemory();
        memory.tags = tags;
        
        // 匹配偏好模式
        Matcher matcher = PREFERENCE_PATTERN.matcher(content);
        if (matcher.find()) {
            String action = matcher.group("action").trim();
            String target = matcher.group("target").trim();
            memory.title = "用户偏好：" + action + target;
            memory.description = content.trim();
        } else {
            memory.title = "用户偏好";
            memory.description = content.trim();
        }
        
        return memory;
    }
    
    /**
     * 提取最佳实践记忆
     */
    public ExtractedMemory extractBestPractice(String content, List<String> tags) {
        ExtractedMemory memory = new ExtractedMemory();
        memory.tags = tags;
        
        // 尝试提取场景和实践
        int shouldIndex = content.indexOf("应该");
        int recommendIndex = content.indexOf("推荐");
        int suggestIndex = content.indexOf("建议");
        
        int splitPoint = Math.max(Math.max(shouldIndex, recommendIndex), suggestIndex);
        
        if (splitPoint > 0 && splitPoint < content.length() - 10) {
            memory.scenario = content.substring(0, splitPoint).trim();
            memory.practice = content.substring(splitPoint).trim();
        } else {
            memory.scenario = "通用场景";
            memory.practice = content.trim();
        }
        
        memory.title = generateTitle(memory.scenario, "最佳实践");
        
        return memory;
    }
    
    /**
     * 提取技能记忆
     * 如果没有提取到有效步骤，返回null
     */
    public ExtractedMemory extractSkill(String content, List<String> tags) {
        ExtractedMemory memory = new ExtractedMemory();
        memory.tags = tags;
        
        // 提取步骤 - 尝试多种模式
        Matcher matcher = STEP_PATTERN.matcher(content);
        while (matcher.find()) {
            String step = matcher.group("step").trim();
            if (!step.isEmpty() && step.length() > 3) {
                memory.steps.add(step);
            }
        }
        
        // 尝试数字步骤
        if (memory.steps.isEmpty()) {
            Matcher numMatcher = NUMBERED_STEP_PATTERN.matcher(content);
            while (numMatcher.find()) {
                String step = numMatcher.group("step").trim();
                if (!step.isEmpty() && step.length() > 3) {
                    memory.steps.add(step);
                }
            }
        }
        
        // 只有提取到至少2个有效步骤才保存
        if (memory.steps.size() < 2) {
            // 检查是否包含"就三步"、"分几步"等表示有步骤的内容
            if (content.contains("步") || content.contains("流程") || content.contains("步骤")) {
                // 按句号分割，尝试提取
                String[] sentences = content.split("[。！？\\n]");
                for (String s : sentences) {
                    s = s.trim();
                    // 过滤掉太短的或不含操作词的句子
                    if (s.length() > 5 && (s.contains("写") || s.contains("运行") || 
                        s.contains("执行") || s.contains("安装") || s.contains("配置") ||
                        s.contains("创建") || s.contains("构建") || s.contains("部署"))) {
                        memory.steps.add(s);
                    }
                }
            }
        }
        
        // 最终检查：至少要有2个步骤
        if (memory.steps.size() < 2) {
            log.debug("未提取到有效步骤，跳过保存: {}", content.substring(0, Math.min(50, content.length())));
            return null;
        }
        
        memory.title = generateTitle(content, "技能");
        memory.description = content.trim();
        
        return memory;
    }
    
    /**
     * 提取项目上下文记忆
     */
    public ExtractedMemory extractProjectContext(String content, List<String> tags) {
        ExtractedMemory memory = new ExtractedMemory();
        memory.tags = tags;
        
        // 提取技术栈
        List<String> techStack = extractTechStack(content);
        memory.extra.put("techStack", techStack);
        
        // 提取路径
        List<String> paths = extractPaths(content);
        memory.extra.put("paths", paths);
        
        // 只有提取到有价值信息才保存
        if (techStack.isEmpty() && paths.isEmpty()) {
            log.debug("未提取到项目技术栈或路径，跳过保存: {}", content.substring(0, Math.min(50, content.length())));
            return null;
        }
        
        // 生成更有意义的标题
        if (!techStack.isEmpty()) {
            memory.title = "技术栈: " + String.join(" + ", techStack.subList(0, Math.min(3, techStack.size())));
        } else if (!paths.isEmpty()) {
            memory.title = "项目路径: " + paths.get(0);
        } else {
            memory.title = "项目上下文";
        }
        
        memory.description = content.trim();
        
        return memory;
    }
    
    /**
     * 生成标题
     */
    private String generateTitle(String content, String prefix) {
        if (content == null || content.isEmpty()) {
            return prefix;
        }
        
        // 截取前30个字符
        int len = Math.min(30, content.length());
        String title = content.substring(0, len).trim();
        
        // 移除标点
        title = title.replaceAll("[，。！？、：；]", "");
        
        if (title.length() < content.length()) {
            title += "...";
        }
        
        return prefix + "：" + title;
    }
    
    /**
     * 提取技术栈
     */
    private List<String> extractTechStack(String content) {
        List<String> techStack = new ArrayList<>();
        String[] techs = {"java", "python", "node", "nodejs", "vue", "react", "angular",
                         "postgresql", "mysql", "mongodb", "redis", "sqlite",
                         "docker", "kubernetes", "k8s",
                         "spring", "springboot", "flask", "django", "fastapi",
                         "typescript", "javascript", "golang", "go", "rust",
                         "element", "antd", "tailwind",
                         "maven", "gradle", "npm", "yarn", "pip", "conda",
                         "opencv", "tensorflow", "pytorch", "torch",
                         "latex", "markdown"};
        
        String lower = content.toLowerCase();
        for (String tech : techs) {
            if (lower.contains(tech.toLowerCase())) {
                techStack.add(tech);
            }
        }
        
        return techStack;
    }
    
    /**
     * 提取路径
     */
    private List<String> extractPaths(String content) {
        List<String> paths = new ArrayList<>();
        
        // 匹配 Windows 路径
        Pattern winPattern = Pattern.compile("[A-Z]:\\\\[^\\s]+");
        Matcher winMatcher = winPattern.matcher(content);
        while (winMatcher.find()) {
            paths.add(winMatcher.group());
        }
        
        // 匹配 Unix 路径
        Pattern unixPattern = Pattern.compile("/[a-zA-Z0-9_/.-]+");
        Matcher unixMatcher = unixPattern.matcher(content);
        while (unixMatcher.find()) {
            String path = unixMatcher.group();
            if (path.length() > 3) {  // 过滤太短的路径
                paths.add(path);
            }
        }
        
        return paths;
    }
}