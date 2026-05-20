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

    // 句子结束标点
    private static final String SENTENCE_END = "[。！？；\\n]";
    
    // 截取到句子结束的辅助方法
    private String extractToSentenceEnd(String text, int start, int maxLen) {
        if (start >= text.length()) return "";
        if (start < 0) start = 0;

        // 先尝试找到最近的句子结束符
        int endIdx = text.indexOf("。", start);
        int qIdx = text.indexOf("？", start);
        int exIdx = text.indexOf("！", start);
        int semiIdx = text.indexOf("；", start);
        int nlIdx = text.indexOf("\n", start);

        // 找最近的结束符
        int nearest = start + maxLen;
        if (endIdx >= start && endIdx < nearest) nearest = endIdx;      // 不包含句号本身
        if (qIdx >= start && qIdx < nearest) nearest = qIdx;
        if (exIdx >= start && exIdx < nearest) nearest = exIdx;
        if (semiIdx >= start && semiIdx < nearest) nearest = semiIdx;
        if (nlIdx >= start && nlIdx < nearest) nearest = nlIdx;

        String result = text.substring(start, Math.min(nearest, text.length())).trim();
        // 如果没有找到结束符但超过最大长度，截断
        if (result.length() > maxLen && nearest >= start + maxLen) {
            result = result.substring(0, maxLen);
        }
        return result;
    }

    /**
     * 检查提取结果是否像垃圾内容
     */
    private boolean looksLikeGarbage(String text) {
        if (text == null || text.length() < 3) return true;
        // 如果包含大量 markdown/代码符号，可能是垃圾
        int specialCount = 0;
        for (char c : text.toCharArray()) {
            if (c == '|' || c == '*' || c == '`' || c == '#' || c == '>' || c == '<'
                || c == '[' || c == ']' || c == '{' || c == '}') {
                specialCount++;
            }
        }
        // 特殊字符占比过高则认为是垃圾
        return specialCount > text.length() * 0.25;
    }

    // "不是X，(而)是Y" 模式：提取AI的错误X和正确答案Y
    private static final Pattern NOT_X_IS_Y_PATTERN = Pattern.compile(
        "(?:不是|并非)[^，。！？；]{2,60}?[，,]?(?:而是|是|应该用|要用)[^。！？]{2,150}?",
        Pattern.DOTALL
    );

    // "不对/错了...应该X" 模式
    private static final Pattern WRONG_SHOULD_PATTERN = Pattern.compile(
        "(?:不对|错了|搞错了|搞反了|方向错了)[^，。！？；]{0,40}[，,]?(?:应该是|应该用|要用|要改成|改为)[^。！？]{2,150}?",
        Pattern.DOTALL
    );

    // "不要X，(要/应该)Y" 模式
    private static final Pattern DONT_X_DO_Y_PATTERN = Pattern.compile(
        "(?:不要|别|不能用|不能这样|不要这样|不要用)[^，。！？；]{2,60}[，,]?(?:要|应该|改成|改用)[^。！？]{2,150}?",
        Pattern.DOTALL
    );

    // "我说的不是X，我说的/其实是Y" 模式
    private static final Pattern I_MEANT_PATTERN = Pattern.compile(
        "(?:我说的不是|我的意思不是|我指的不是|不是这个意思)[^，,。；]{2,60}[，,]?(?:我说的|我的意思是|我指的是|其实是|而是)[^。！？]{2,150}?",
        Pattern.DOTALL
    );

    // "注意/记住不要X" 约束纠正模式
    private static final Pattern CONSTRAINT_PATTERN = Pattern.compile(
        "(?:注意|记住|切记|千万).{0,10}(?:不要|别|不能|不可以)[^。！？；]{2,100}?",
        Pattern.DOTALL
    );

    // "X不行/不对/错误，应该Y" 模式
    private static final Pattern X_BAD_Y_GOOD_PATTERN = Pattern.compile(
        "(?:不行|不对|不对的|错误|有问题)[^，,。；]{2,60}[，,]?(?:应该|要用|改成|改为|改用)[^。！？]{2,150}?",
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
     * 严格标准：必须同时提取到 problem 和 solution，且语义完整
     */
    public ExtractedMemory extractErrorCorrection(String content, List<String> tags) {
        ExtractedMemory memory = new ExtractedMemory();
        memory.tags = tags;

        // 先做全局过滤：如果整段内容像对话碎片/代码/无意义的文本，直接跳过
        if (isDialogFragment(content)) {
            log.debug("内容疑似对话碎片，跳过: {}", content.substring(0, Math.min(40, content.length())));
            return null;
        }

        CorrectionResult result;

        // 模式1: "不是X，(而)是Y" — 最常见的纠正句式
        Matcher matcher = NOT_X_IS_Y_PATTERN.matcher(content);
        if (matcher.find()) {
            result = extractProblemAndSolution(matcher.group());
            if (result != null && result.isValid()) {
                memory.problem = result.problem;
                memory.solution = result.solution;
                memory.title = generateCorrectionTitle(result.problem, result.solution);
                return memory;
            }
        }

        // 模式2: "不对/错了...应该X" — 先否定再给出正确答案
        matcher = WRONG_SHOULD_PATTERN.matcher(content);
        if (matcher.find()) {
            result = extractProblemAndSolution(matcher.group());
            if (result != null && result.isValid()) {
                memory.problem = result.problem;
                memory.solution = result.solution;
                memory.title = generateCorrectionTitle(result.problem, result.solution);
                return memory;
            }
        }

        // 模式3: "不要X，要/应该Y" — 否定指令+正确指令
        matcher = DONT_X_DO_Y_PATTERN.matcher(content);
        if (matcher.find()) {
            result = extractProblemAndSolution(matcher.group());
            if (result != null && result.isValid()) {
                memory.problem = result.problem;
                memory.solution = result.solution;
                memory.title = generateCorrectionTitle(result.problem, result.solution);
                return memory;
            }
        }

        // 模式4: "我说的不是X，我说的/其实是Y"
        matcher = I_MEANT_PATTERN.matcher(content);
        if (matcher.find()) {
            result = extractProblemAndSolution(matcher.group());
            if (result != null && result.isValid()) {
                memory.problem = result.problem;
                memory.solution = result.solution;
                memory.title = generateCorrectionTitle(result.problem, result.solution);
                return memory;
            }
        }

        // 模式5: "X不行/不对...应该Y"
        matcher = X_BAD_Y_GOOD_PATTERN.matcher(content);
        if (matcher.find()) {
            result = extractProblemAndSolution(matcher.group());
            if (result != null && result.isValid()) {
                memory.problem = result.problem;
                memory.solution = result.solution;
                memory.title = generateCorrectionTitle(result.problem, result.solution);
                return memory;
            }
        }

        // 模式6: "注意/记住不要X" — 约束纠正
        matcher = CONSTRAINT_PATTERN.matcher(content);
        if (matcher.find()) {
            result = extractProblemAndSolution(matcher.group());
            if (result != null && result.isValid()) {
                memory.problem = result.problem;
                memory.solution = result.solution;
                memory.title = generateCorrectionTitle(result.problem, result.solution);
                return memory;
            }
        }

        // 不再兜底 — 宁可少存也不要存垃圾
        log.debug("未提取到有效的错误纠正内容，跳过保存: {}",
            content.substring(0, Math.min(50, content.length())));
        return null;
    }

    /**
     * 纠正提取结果
     */
    private static class CorrectionResult {
        String problem;   // AI 的错误/用户的否定
        String solution;  // 正确做法

        boolean isValid() {
            if (problem == null || solution == null) return false;
            problem = problem.trim();
            solution = solution.trim();
            // problem 和 solution 都必须有一定长度且不相同
            if (problem.length() < 3 || solution.length() < 5) return false;
            if (problem.equals(solution)) return false;
            // solution 不能只是对话碎片
            if (isDialogFragment(solution)) return false;
            return true;
        }
    }

    /**
     * 从匹配的内容中同时提取"问题"和"正确做法"
     */
    private CorrectionResult extractProblemAndSolution(String text) {
        CorrectionResult result = new CorrectionResult();

        // 1. "不是X，而是Y" — 明确的问题+解决方案结构
        int notIdx = text.indexOf("不是");
        int butIdx = text.indexOf("而是");
        if (notIdx >= 0 && butIdx > notIdx) {
            result.problem = text.substring(notIdx, butIdx).trim();
            result.solution = extractToSentenceEnd(text, butIdx + 2, 150).trim();
            return result;
        }

        // 2. "不是X，是Y" — 没有"而是"，用单独的"是"
        if (notIdx >= 0) {
            int isIdx = text.lastIndexOf("是");
            if (isIdx > notIdx + 2) {
                result.problem = text.substring(notIdx, isIdx).trim();
                result.solution = extractToSentenceEnd(text, isIdx + 1, 150).trim();
                return result;
            }
        }

        // 3. "不对/错了...应该/要用..."
        int wrongEnd = findKeywordEnd(text, new String[]{"不对", "错了", "搞错了", "搞反了"});
        if (wrongEnd >= 0) {
            int shouldIdx = findLastKeyword(text, new String[]{"应该用", "要用", "应该", "要改成", "改为", "改成", "改用"});
            if (shouldIdx > wrongEnd) {
                result.problem = text.substring(0, shouldIdx).trim();
                result.solution = extractToSentenceEnd(text, shouldIdx, 150).trim();
                return result;
            }
        }

        // 4. "不要X，要/应该Y"
        int dontIdx = findKeywordEnd(text, new String[]{"不要", "别", "不能", "不可以"});
        if (dontIdx >= 0) {
            int wantIdx = findLastKeyword(text, new String[]{"要", "应该", "改成", "改用"});
            if (wantIdx > dontIdx) {
                result.problem = text.substring(0, wantIdx).trim();
                result.solution = extractToSentenceEnd(text, wantIdx, 150).trim();
                return result;
            }
        }

        // 5. "我说的不是X，我的意思是Y"
        int meantIdx = text.lastIndexOf("我的意思是");
        if (meantIdx < 0) meantIdx = text.lastIndexOf("我指的是");
        if (meantIdx < 0) meantIdx = text.lastIndexOf("其实是");
        if (meantIdx >= 0) {
            result.problem = text.substring(0, meantIdx).trim();
            result.solution = extractToSentenceEnd(text, meantIdx, 150).trim();
            return result;
        }

        // 6. "X不行/不对...应该Y"
        int badIdx = findKeywordEnd(text, new String[]{"不行", "不对", "错误", "有问题"});
        if (badIdx >= 0) {
            int fixIdx = findLastKeyword(text, new String[]{"应该用", "要用", "应该", "改成", "改为"});
            if (fixIdx > badIdx) {
                result.problem = text.substring(0, fixIdx).trim();
                result.solution = extractToSentenceEnd(text, fixIdx, 150).trim();
                return result;
            }
        }

        // 7. 约束模式: "注意/记住不要X" — problem 和 solution 相同（都是约束本身）
        int constraintIdx = findKeywordEnd(text, new String[]{"注意", "记住", "切记", "千万"});
        if (constraintIdx >= 0) {
            result.problem = text.trim();
            result.solution = text.trim();
            return result;
        }

        return null;
    }

    private int findKeywordEnd(String text, String[] keywords) {
        for (String kw : keywords) {
            int idx = text.indexOf(kw);
            if (idx >= 0) return idx + kw.length();
        }
        return -1;
    }

    private int findLastKeyword(String text, String[] keywords) {
        int last = -1;
        for (String kw : keywords) {
            int idx = text.lastIndexOf(kw);
            if (idx > last) last = idx;
        }
        return last;
    }

    /**
     * 判断文本是否像对话碎片（不是完整的纠正内容）
     */
    private static boolean isDialogFragment(String text) {
        if (text == null || text.length() < 5) return true;

        // 1. 包含大量 markdown/代码符号 → 是对话碎片
        int specialCount = 0;
        for (char c : text.toCharArray()) {
            if (c == '|' || c == '*' || c == '`' || c == '#' || c == '>' || c == '<'
                || c == '[' || c == ']' || c == '{' || c == '}') {
                specialCount++;
            }
        }
        if (specialCount > text.length() * 0.15) return true;

        // 2. 包含目录树符号
        if (text.contains("├") || text.contains("└") || text.contains("│")) return true;

        // 3. 包含 Windows 命令行错误提示
        if (text.contains("不是内部或外部命令") || text.contains("可运行的程序")) return true;

        // 4. 包含 YAML/JSON 片段特征
        if (text.contains("\":\"")) return true;

        // 5. 以问句开头（不是纠正，是讨论）
        if (text.trim().startsWith("?") || text.trim().startsWith("？")
            || text.trim().startsWith("怎么") || text.trim().startsWith("为什么")) return true;

        // 6. 内容是纯路径或代码
        if (text.matches("^[A-Z]:\\\\.*\\.(md|txt|java|py|js).*$")) return true;

        return false;
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
     * 基于 problem 生成，让用户一眼看出这是关于什么的纠正
     */
    private String generateCorrectionTitle(String problem, String solution) {
        String source = (problem != null && !problem.isEmpty()) ? problem : solution;
        if (source == null || source.isEmpty()) return "错误纠正";

        // 移除常见前缀词
        String clean = source.replaceAll("^(不是|并非|不要|别|不能|不可以|注意|记住|切记|不对|错了|搞错了|搞反了|而是|其实|应该用|要用|应该|要改成|改为|改成|改用|我说的|我的意思是|我指的是)\\s*", "");

        // 移除标点
        clean = clean.replaceAll("[，。！？、：；\"'\\[\\]\\(\\)]", "");

        // 截取前 30 字
        if (clean.length() > 30) {
            clean = clean.substring(0, 30) + "...";
        }

        return clean.isEmpty() ? "错误纠正" : clean;
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