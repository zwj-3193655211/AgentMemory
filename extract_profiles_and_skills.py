#!/usr/bin/env python3
import json
import re
import psycopg2
from collections import defaultdict
from datetime import datetime
from pathlib import Path

DB_CONFIG = {
    "host": "localhost",
    "port": 5500,
    "database": "agentmemory",
    "user": "agentmemory",
    "password": "agentmemory"
}

WORKBUDDY_PATH = Path.home() / ".workbuddy" / "projects"
CLAUDE_HISTORY = Path.home() / ".claude" / "history.jsonl"

def parse_claude_history():
    """解析 Claude Code 历史记录"""
    user_queries = []

    if not CLAUDE_HISTORY.exists():
        print(f"Claude history not found: {CLAUDE_HISTORY}")
        return user_queries

    with open(CLAUDE_HISTORY, "r", encoding="utf-8") as f:
        for line in f:
            try:
                entry = json.loads(line.strip())
                display = entry.get("display", "")
                if display and len(display) > 5:
                    user_queries.append({"text": display, "source": "claude"})
            except json.JSONDecodeError:
                continue

    print(f"Claude: 读取 {len(user_queries)} 条用户查询")
    return user_queries

def parse_workbuddy_history():
    """解析 WorkBuddy JSONL 历史记录"""
    user_queries = []

    if not WORKBUDDY_PATH.exists():
        print(f"WorkBuddy path not found: {WORKBUDDY_PATH}")
        return user_queries

    for project_dir in WORKBUDDY_PATH.iterdir():
        if not project_dir.is_dir():
            continue

        jsonl_files = list(project_dir.glob("*.jsonl"))
        for jsonl_file in jsonl_files:
            try:
                with open(jsonl_file, "r", encoding="utf-8") as f:
                    for line in f:
                        try:
                            entry = json.loads(line.strip())
                            if entry.get("type") == "message" and entry.get("role") == "user":
                                content = entry.get("content", [])
                                if isinstance(content, list):
                                    for item in content:
                                        if item.get("type") == "input_text":
                                            text = item.get("text", "")
                                            if "<user_query>" in text:
                                                match = re.search(r"<user_query>(.*?)</user_query>", text, re.DOTALL)
                                                if match:
                                                    query = match.group(1).strip()
                                                    if len(query) > 3:
                                                        user_queries.append({"text": query, "source": "workbuddy"})
                        except json.JSONDecodeError:
                            continue
            except Exception as e:
                continue

    print(f"WorkBuddy: 读取 {len(user_queries)} 条用户查询")
    return user_queries

def extract_user_preferences(all_queries):
    """从对话历史中提取用户偏好（细粒度）"""
    preferences = []

    # 网络/下载偏好
    network_keywords = ["镜像", "国内", "下载", "vpn", "代理", "npm", "pip", "install"]
    network_rules = []
    for q in all_queries:
        text = q["text"]
        if any(kw in text for kw in network_keywords):
            if "镜像" in text or "国内" in text:
                network_rules.append("优先使用国内镜像源")
            if "vpn" in text or "代理" in text:
                network_rules.append("必要时使用VPN")
            if "npm" in text and ("registry" in text or "源" in text):
                network_rules.append("配置npm国内镜像")
            if "pip" in text and ("镜像" in text or "源" in text):
                network_rules.append("配置pip国内镜像")

    if network_rules:
        preferences.append({
            "category": "网络配置",
            "items": [{"key": rule, "value": "已设置"} for rule in set(network_rules)]
        })

    # 开发工具偏好
    tool_keywords = {
        "IDE": ["vscode", "vs code", "visual studio", "idea", "pycharm"],
        "编辑器": ["vim", "neovim", "emacs", "sublime"],
        "终端": ["bash", "powershell", "zsh", "terminal"],
        "浏览器": ["chrome", "edge", "firefox"]
    }
    tools_used = []
    for q in all_queries:
        text = q["text"].lower()
        for tool_type, keywords in tool_keywords.items():
            for kw in keywords:
                if kw in text:
                    tools_used.append(f"{tool_type}: {kw.capitalize()}")
    
    if tools_used:
        preferences.append({
            "category": "开发工具",
            "items": [{"key": t, "value": "常用"} for t in set(tools_used)]
        })

    # AI模型偏好
    model_keywords = {"GLM": ["glm", "智谱"], "Claude": ["claude"], "GPT": ["gpt", "openai"], "Qwen": ["qwen", "通义"]}
    models_used = []
    for q in all_queries:
        text = q["text"].lower()
        for model_name, keywords in model_keywords.items():
            for kw in keywords:
                if kw in text:
                    models_used.append(model_name)
    
    if models_used:
        preferences.append({
            "category": "AI模型偏好",
            "items": [{"key": m, "value": "常用"} for m in set(models_used)]
        })

    # 工作流程偏好
    workflow_patterns = [
        ("迭代开发", ["迭代", "逐步", "分步", "先做", "再做"]),
        ("调试优先", ["调试", "修复", "错误", "bug"]),
        ("可视化", ["可视化", "界面", "页面", "展示"]),
        ("文档优先", ["文档", "README", "说明"]),
        ("测试驱动", ["测试", "test", "单元测试"])
    ]
    workflows = []
    for pattern_name, keywords in workflow_patterns:
        for q in all_queries:
            text = q["text"].lower()
            if any(kw in text for kw in keywords):
                workflows.append(pattern_name)
                break
    
    if workflows:
        preferences.append({
            "category": "工作流程",
            "items": [{"key": w, "value": "偏好"} for w in set(workflows)]
        })

    # 项目类型偏好
    project_types = [
        ("前端项目", ["网页", "前端", "vue", "react", "html", "css"]),
        ("视频项目", ["remotion", "视频", "动画"]),
        ("AI项目", ["agent", "llm", "向量", "embedding"]),
        ("算法项目", ["算法", "数据结构", "图论"]),
        ("后端项目", ["java", "spring", "api", "后端"])
    ]
    projects = []
    for project_name, keywords in project_types:
        for q in all_queries:
            text = q["text"].lower()
            if any(kw in text for kw in keywords):
                projects.append(project_name)
                break
    
    if projects:
        preferences.append({
            "category": "项目类型",
            "items": [{"key": p, "value": "常做"} for p in set(projects)]
        })

    # 错误处理偏好
    error_handling = []
    for q in all_queries:
        text = q["text"]
        if "修复" in text or "解决" in text:
            error_handling.append("主动修复问题")
        if "分析" in text and ("错误" in text or "失败" in text):
            error_handling.append("分析错误原因")
        if "优化" in text:
            error_handling.append("持续优化")
    
    if error_handling:
        preferences.append({
            "category": "问题处理",
            "items": [{"key": e, "value": "习惯"} for e in set(error_handling)]
        })

    return preferences

def extract_skills_from_history(all_queries):
    """从历史记录中提取技能"""
    skills_patterns = {
        "video_editing": {
            "keywords": ["remotion", "视频", "音频", "tts", "旁白"],
            "skill_type": "视频处理",
            "title": "Remotion视频编辑与TTS旁白集成",
            "description": "使用Remotion创建视频项目，集成TTS服务生成旁白音频，支持时间轴编辑和片段调整。",
            "steps": ["分析视频需求", "设计场景结构", "编写组件代码", "生成音频", "调试优化"]
        },
        "web_development": {
            "keywords": ["网页", "前端", "vue", "html", "css", "javascript"],
            "skill_type": "前端开发",
            "title": "Vue 3 + Element Plus 网页开发",
            "description": "使用Vue 3和Element Plus组件库开发响应式网页应用，支持动态交互效果。",
            "steps": ["需求分析", "组件设计", "代码实现", "测试调试"]
        },
        "algorithm": {
            "keywords": ["算法", "最短路", "图论", "动态规划"],
            "skill_type": "算法",
            "title": "算法可视化与视频制作",
            "description": "制作算法讲解视频，支持步骤分解、图形化展示和自动生成旁白。",
            "steps": ["算法理解", "步骤分解", "可视化设计", "视频制作"]
        },
        "debugging": {
            "keywords": ["修复", "错误", "bug", "调试", "崩溃"],
            "skill_type": "调试",
            "title": "常见开发问题调试技巧",
            "description": "解决开发过程中遇到的各类问题，包括API错误、环境配置、依赖冲突等。",
            "steps": ["问题定位", "原因分析", "方案设计", "修复验证"]
        },
        "ai_tools": {
            "keywords": ["claude", "agent", "skill", "模型", "glm", "gpt"],
            "skill_type": "AI工具",
            "title": "Claude Code与多Agent协作",
            "description": "使用Claude Code的Agent模式和多Agent协作（Agent Teams）完成复杂任务。",
            "steps": ["任务分析", "Agent选择", "协作配置", "执行监控"]
        },
        "docker_devops": {
            "keywords": ["docker", "容器", "部署", "nginx"],
            "skill_type": "DevOps",
            "title": "Docker容器化与部署",
            "description": "使用Docker构建和部署应用，配置Nginx反向代理，实现CI/CD自动化流程。",
            "steps": ["镜像构建", "容器配置", "部署测试", "监控维护"]
        },
        "npm_config": {
            "keywords": ["npm", "install", "registry", "镜像"],
            "skill_type": "工具配置",
            "title": "npm国内镜像配置与依赖管理",
            "description": "配置npm使用国内镜像源，解决网络问题，管理项目依赖。",
            "steps": ["配置镜像源", "安装依赖", "版本管理", "问题排查"]
        },
        "video_audio": {
            "keywords": ["音频", "旁白", "时长", "编辑"],
            "skill_type": "多媒体",
            "title": "视频音频同步与编辑",
            "description": "处理视频和音频同步问题，调整时长，添加旁白和音效。",
            "steps": ["音频生成", "时长调整", "音视频同步", "质量优化"]
        }
    }

    extracted_skills = []
    found_skills = set()

    for q in all_queries:
        text = q["text"].lower()
        for skill_id, skill_info in skills_patterns.items():
            for kw in skill_info["keywords"]:
                if kw.lower() in text and skill_id not in found_skills:
                    found_skills.add(skill_id)
                    extracted_skills.append({
                        "id": f"skill-{skill_id}-{datetime.now().strftime('%Y%m%d')}",
                        "title": skill_info["title"],
                        "skill_type": skill_info["skill_type"],
                        "description": skill_info["description"],
                        "steps": json.dumps(skill_info["steps"], ensure_ascii=False),
                        "tags": skill_info["keywords"][:3]
                    })
                    break

    return extracted_skills

def save_to_database(profiles, skills):
    """保存到数据库"""
    conn = psycopg2.connect(**DB_CONFIG)
    cursor = conn.cursor()

    # 先清空旧数据
    cursor.execute("DELETE FROM user_profiles WHERE id LIKE 'profile-real%'")
    cursor.execute("DELETE FROM skills WHERE id LIKE 'skill-%-20260524'")

    for p in profiles:
        cursor.execute("""
            INSERT INTO user_profiles (id, title, category, items, created_at, deleted)
            VALUES (%s, %s, %s, %s, NOW(), false)
        """, (p["id"], p["title"], p["category"], p["items"]))
        print(f"添加用户画像: {p['title']}")

    for s in skills:
        cursor.execute("""
            INSERT INTO skills (id, title, skill_type, description, steps, tags, created_at, deleted)
            VALUES (%s, %s, %s, %s, %s, %s, NOW(), false)
        """, (s["id"], s["title"], s["skill_type"], s["description"], s["steps"], s["tags"]))
        print(f"添加技能: {s['title']}")

    conn.commit()
    conn.close()

def main():
    print("=== 从真实对话历史提取用户画像和技能 ===\n")

    all_queries = []

    print("1. 解析 Claude Code 历史记录...")
    all_queries.extend(parse_claude_history())

    print("\n2. 解析 WorkBuddy 历史记录...")
    all_queries.extend(parse_workbuddy_history())

    print(f"\n共读取 {len(all_queries)} 条用户查询")

    print("\n3. 提取用户偏好...")
    preferences = extract_user_preferences(all_queries)

    # 生成用户画像数据
    profiles = []
    for i, pref in enumerate(preferences):
        profile_id = f"profile-real-{i+1}"
        profiles.append({
            "id": profile_id,
            "title": pref["category"],
            "category": "preference",
            "items": json.dumps(pref["items"], ensure_ascii=False)
        })

    # 添加技术概览画像
    profiles.append({
        "id": "profile-real-summary",
        "title": "技术概览",
        "category": "techstack",
        "items": json.dumps([
            {"key": "对话记录", "value": f"{len(all_queries)} 条"},
            {"key": "数据来源", "value": "Claude Code + WorkBuddy"},
            {"key": "提取时间", "value": datetime.now().strftime("%Y-%m-%d %H:%M")}
        ], ensure_ascii=False)
    })

    print(f"\n提取到 {len(profiles)} 条用户画像")

    print("\n4. 提取技能...")
    extracted_skills = extract_skills_from_history(all_queries)

    print(f"\n提取到 {len(extracted_skills)} 个技能")

    print("\n5. 保存到数据库...")
    save_to_database(profiles, extracted_skills)

    print("\n✅ 完成！")
    print(f"   - {len(profiles)} 条用户画像（包含网络配置、开发工具、AI模型偏好等）")
    print(f"   - {len(extracted_skills)} 条技能沉淀")

if __name__ == "__main__":
    main()
