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
    
    // 问题-解决方案模式 - 更严格匹配
    private static final Pattern PROBLEM_SOLUTION_PATTERN = Pattern.compile(
        "(?<problem>.{10,100}?)(是因为|原因|由于).{0,20}?(?<solution>.{10,200}?)(解决|修复|改好|好了)",
        Pattern.DOTALL
    );
    
    // "后来发现"模式 - 常见的已解决表达
    private static final Pattern LATER_FOUND_PATTERN = Pattern.compile(
        "(?<problem>.{10,80}?)[，,]?(后来发现|原来|原来是)(?<cause>.{5,50}?)[，,，]?(?<solution>.{5,100}?)(就好了|就行|这样|搞定)",
        Pattern.DOTALL
    );
    
    // 解决方案模式
    private static final Pattern SOLUTION_PATTERN = Pattern.compile(
        "(解决办法|解决方法|解决方案|修复方法)[是为：:](?<solution>.{10,200})",
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
     * 提取错误纠正记忆
     * 如果没有有效的解决方案，返回null表示不应保存
     */
    public ExtractedMemory extractErrorCorrection(String content, List<String> tags) {
        ExtractedMemory memory = new ExtractedMemory();
        memory.tags = tags;
        
        // 尝试匹配"后来发现...就好了"模式（最常见的已解决表达）
        Matcher laterMatcher = LATER_FOUND_PATTERN.matcher(content);
        if (laterMatcher.find()) {
            memory.problem = laterMatcher.group("problem").trim();
            memory.cause = laterMatcher.group("cause").trim();
            memory.solution = laterMatcher.group("solution").trim();
            memory.title = generateTitle(memory.problem, "错误纠正");
            return memory;
        }
        
        // 尝试匹配问题-解决方案模式
        Matcher matcher = PROBLEM_SOLUTION_PATTERN.matcher(content);
        if (matcher.find()) {
            memory.problem = matcher.group("problem").trim();
            memory.solution = matcher.group("solution").trim();
            memory.title = generateTitle(memory.problem, "错误纠正");
            return memory;
        }
        
        // 尝试匹配明确的解决方案模式
        Matcher solutionMatcher = SOLUTION_PATTERN.matcher(content);
        if (solutionMatcher.find()) {
            memory.solution = solutionMatcher.group("solution").trim();
            // 尝试从前面提取问题
            int solutionStart = content.indexOf("解决");
            if (solutionStart > 20) {
                memory.problem = content.substring(0, Math.min(solutionStart, 100)).trim();
            } else {
                memory.problem = content.substring(0, Math.min(80, content.length())).trim();
            }
            memory.title = generateTitle(memory.problem, "错误纠正");
            return memory;
        }
        
        // 尝试提取原因
        int causeIndex = content.indexOf("因为");
        if (causeIndex == -1) causeIndex = content.indexOf("原因是");
        if (causeIndex == -1) causeIndex = content.indexOf("问题出在");
        
        if (causeIndex != -1) {
            // 提取问题（原因之前的部分）
            memory.problem = content.substring(0, Math.min(causeIndex, 100)).trim();
            // 提取原因和解决方案（原因之后的部分）
            int end = Math.min(causeIndex + 150, content.length());
            String causeAndSolution = content.substring(causeIndex, end).trim();
            
            // 检查是否有解决方案标记
            if (causeAndSolution.contains("所以") || causeAndSolution.contains("要用") || 
                causeAndSolution.contains("需要") || causeAndSolution.contains("改为")) {
                memory.cause = causeAndSolution;
                memory.solution = causeAndSolution;
                memory.title = generateTitle(memory.problem, "错误纠正");
                return memory;
            }
        }
        
        // 如果以上都没匹配到有效的解决方案，返回null表示不应保存
        log.debug("未找到有效的错误解决方案，跳过保存: {}", content.substring(0, Math.min(50, content.length())));
        return null;
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