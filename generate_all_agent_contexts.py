#!/usr/bin/env python3
"""从多种 Agent 历史记录生成项目上下文数据"""
import os
import json
import re
from pathlib import Path
from datetime import datetime
import psycopg2

DB_CONFIG = {
    "host": "localhost",
    "port": 5500,
    "database": "agentmemory",
    "user": "agentmemory",
    "password": "agentmemory"
}

HOME = Path.home()

TECH_PATTERNS = [
    r'Java[,\s]*(?:Spring Boot|MyBatis|Servlet|JSP)?',
    r'Python[,\s]*(?:Flask|Django|FastAPI|PyTorch|TensorFlow)?',
    r'JavaScript[,\s]*(?:Vue|React|Node\.js|Express)?',
    r'TypeScript',
    r'PostgreSQL|MySQL|MongoDB|Redis|SQLite',
    r'Docker|Kubernetes',
    r'Vue[23]?',
    r'React',
    r'Spring (?:Boot|Cloud)',
    r'ECharts',
    r'Vite|Webpack',
    r'Element [Uu]i',
    r'HTML|CSS',
    r'Git|GitHub',
    r'REST|API|GraphQL',
    r'Maven|Gradle|npm|pip',
    r'PyTorch|TensorFlow|sklearn',
    r'CLIP|OpenCV| PIL',
]

def extract_tech_stack(content):
    techs = set()
    for pattern in TECH_PATTERNS:
        matches = re.findall(pattern, content, re.IGNORECASE)
        for match in matches:
            tech = match.strip().replace('\n', ', ')
            if tech:
                techs.add(tech)
    return list(techs)[:10]

def extract_summary(content):
    lines = content.strip().split('\n')
    summaries = []
    for line in lines[:20]:
        line = line.strip()
        if line and not line.startswith('#') and len(line) > 10:
            summaries.append(line)
        if len(summaries) >= 3:
            break
    return ' '.join(summaries)[:500]

def extract_key_decisions(content):
    decisions = []
    patterns = [
        r'(?:关键点|决策|决定|采用|使用)[：:]\s*([^\n]+)',
        r'(?:原因|因为|因此)[：:]\s*([^\n]+)',
    ]
    for pattern in patterns:
        matches = re.findall(pattern, content, re.IGNORECASE)
        for match in matches[:2]:
            decision = match.strip()
            if decision and len(decision) > 5:
                decisions.append(decision[:200])
    return list(set(decisions))[:5]

def get_project_name_from_path(path_str):
    """从路径提取项目名称"""
    path = Path(path_str)
    parts = path.parts
    for i, part in enumerate(parts):
        if part in ['Desktop', 'Documents', 'projects', 'code', 'workspace']:
            if i + 1 < len(parts):
                return parts[i + 1][:50]
    return parts[-1][:50] if parts else 'Unknown'

def process_claude_projects():
    """处理 Claude Code 项目"""
    projects = []
    claude_projects = HOME / ".claude" / "projects"
    if claude_projects.exists():
        for project in claude_projects.iterdir():
            if project.is_dir():
                memory_dir = project / "memory"
                if memory_dir.exists():
                    mem_files = list(memory_dir.glob("*.md"))
                    for mf in mem_files:
                        if mf.name != "MEMORY.md":
                            projects.append(("claude", mf, project.name))
    return projects

def process_workbuddy_projects():
    """处理 WorkBuddy 项目"""
    projects = []
    wb_path = HOME / "WorkBuddy"
    if wb_path.exists():
        for project_dir in wb_path.iterdir():
            if project_dir.is_dir():
                memory_dir = project_dir / ".workbuddy" / "memory"
                if memory_dir.exists():
                    for mf in memory_dir.glob("*.md"):
                        if mf.name != "MEMORY.md":
                            projects.append(("workbuddy", mf, project_dir.name))
    return projects

def process_iflow_projects():
    """处理 iFlow 项目"""
    projects = []
    iflow_path = HOME / ".iflow" / "projects"
    if iflow_path.exists():
        for project in iflow_path.iterdir():
            if project.is_dir():
                mem_files = list(project.glob("**/*.md"))
                for mf in mem_files[:5]:
                    projects.append(("iflow", mf, project.name))
    return projects

def process_qwen_projects():
    """处理 Qwen 项目"""
    projects = []
    qwen_path = HOME / ".qwen" / "projects"
    if qwen_path.exists():
        for project in qwen_path.iterdir():
            if project.is_dir():
                mem_files = list(project.glob("**/*.md"))
                for mf in mem_files[:5]:
                    projects.append(("qwen", mf, project.name))
    return projects

def save_project_context(conn, project_name, tech_stack, summary, key_decisions, folder_name, agent_type, md_file_path):
    cursor = conn.cursor()
    
    key_decisions_json = json.dumps(key_decisions, ensure_ascii=False)
    structure_json = json.dumps({
        "summary": summary,
        "next_steps": []
    }, ensure_ascii=False)
    
    sql = """
    INSERT INTO project_contexts 
    (id, title, project_name, project_path, tech_stack, key_decisions, structure, updated_at, deleted)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, false)
    ON CONFLICT (id) DO UPDATE SET
        title = EXCLUDED.title,
        tech_stack = EXCLUDED.tech_stack,
        key_decisions = EXCLUDED.key_decisions,
        structure = EXCLUDED.structure,
        updated_at = EXCLUDED.updated_at
    """
    
    import hashlib
    md5_hash = hashlib.md5(md_file_path.encode()).hexdigest()[:8]
    record_id = f"{agent_type[:3]}-{md5_hash}"
    cursor.execute(sql, (
        record_id,
        f"{project_name} 工作记录",
        project_name,
        folder_name,
        tech_stack,
        key_decisions_json,
        structure_json,
        datetime.now()
    ))
    return record_id

def main():
    print("🔍 扫描各 Agent 历史记录...")
    
    all_projects = []
    all_projects.extend(process_claude_projects())
    print(f"  Claude: {len(process_claude_projects())} 个记忆文件")
    all_projects.extend(process_workbuddy_projects())
    print(f"  WorkBuddy: {len(process_workbuddy_projects())} 个记忆文件")
    all_projects.extend(process_iflow_projects())
    print(f"  iFlow: {len(process_iflow_projects())} 个记忆文件")
    all_projects.extend(process_qwen_projects())
    print(f"  Qwen: {len(process_qwen_projects())} 个记忆文件")
    
    print(f"📁 共找到 {len(all_projects)} 个记忆文件")
    
    if not all_projects:
        print("❌ 未找到记忆文件")
        return
    
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        print("✅ 数据库连接成功")
    except Exception as e:
        print(f"❌ 数据库连接失败: {e}")
        return
    
    created_count = 0
    
    for agent_type, md_file, folder_name in all_projects:
        try:
            content = md_file.read_text(encoding='utf-8')
            if not content or len(content) < 50:
                continue
            
            project_name = get_project_name_from_path(str(md_file))
            tech_stack = extract_tech_stack(content)
            summary = extract_summary(content)
            key_decisions = extract_key_decisions(content)
            
            if summary and len(summary) > 20:
                save_project_context(conn, project_name, tech_stack, summary, key_decisions, str(md_file.parent.parent), agent_type, str(md_file))
                created_count += 1
                print(f"  ✅ [{agent_type}] {project_name[:30]}...")
        
        except Exception as e:
            print(f"  ⚠️ 处理失败 {md_file.name}: {e}")
    
    conn.commit()
    conn.close()
    
    print(f"\n🎉 完成！共创建/更新 {created_count} 条项目上下文记录")

if __name__ == "__main__":
    main()
