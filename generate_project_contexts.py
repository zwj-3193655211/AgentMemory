#!/usr/bin/env python3
"""从 WorkBuddy 历史记忆文件生成项目上下文数据"""
import os
import json
import psycopg2
from psycopg2 import extras
import re
from datetime import datetime
from pathlib import Path

DB_CONFIG = {
    "host": os.environ.get('DATABASE_HOST', 'localhost'),
    "port": int(os.environ.get('DATABASE_PORT', 5500)),
    "database": os.environ.get('DATABASE_NAME', 'agentmemory'),
    "user": os.environ.get('DATABASE_USER', 'agentmemory'),
    "password": os.environ.get('DATABASE_PASSWORD', 'agentmemory')
}

WORKBUDDY_PATH = Path.home() / "WorkBuddy"

TECH_PATTERNS = [
    r'Java[,\s]*(?:Spring Boot|MyBatis|Servlet|JSP)?',
    r'Python[,\s]*(?:Flask|Django|FastAPI)?',
    r'JavaScript[,\s]*(?:Vue|React|Node\.js|Express)?',
    r'TypeScript',
    r'PostgreSQL|MySQL|MongoDB|Redis',
    r'Docker|Kubernetes',
    r'Vue[23]?',
    r'React',
    r'Node\.?js',
    r'Spring (?:Boot|Cloud)',
    r'ECharts',
    r'Vite|Webpack',
    r'Element [Uu]i',
    r'pgvector|vector',
    r'HTML|CSS|SASS',
    r'Git|GitHub',
    r'API|REST|GraphQL',
    r'Maven|Gradle|npm',
]

def extract_tech_stack(content):
    """从内容中提取技术栈"""
    techs = set()
    for pattern in TECH_PATTERNS:
        matches = re.findall(pattern, content, re.IGNORECASE)
        for match in matches:
            tech = match.strip().replace('\n', ', ')
            if tech:
                techs.add(tech)
    return list(techs)[:10]

def extract_project_name(content, folder_name):
    """从内容或文件夹名提取项目名称"""
    project_match = re.search(r'AgentMemory|项目[：:]\s*([^\n]+)', content)
    if project_match:
        return project_match.group(1).strip()
    
    if 'AgentMemory' in folder_name:
        return 'AgentMemory'
    elif 'claw' in folder_name.lower():
        return 'Automation Project'
    elif 'task' in folder_name.lower():
        return f"Task-{folder_name[:10]}"
    
    return folder_name[:50]

def extract_summary(content):
    """提取工作摘要"""
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
    """提取关键决策"""
    decisions = []
    patterns = [
        r'(?:关键点|决策|决定|采用|使用)[：:]\s*([^\n]+)',
        r'(?:原因|因为|因此)[：:]\s*([^\n]+)',
        r'(?:而不是|选择|选用)[：:]\s*([^\n]+)',
    ]
    for pattern in patterns:
        matches = re.findall(pattern, content, re.IGNORECASE)
        for match in matches[:2]:
            decision = match.strip()
            if decision and len(decision) > 5:
                decisions.append(decision[:200])
    return list(set(decisions))[:5]

def extract_next_steps(content):
    """提取下一步计划"""
    steps = []
    patterns = [
        r'(?:下一步|遗留|待办|后续)[：:]\s*([^\n]+)',
        r'(?:TODO|FIXME|待修复)[：:]\s*([^\n]+)',
    ]
    for pattern in patterns:
        matches = re.findall(pattern, content, re.IGNORECASE)
        for match in matches[:2]:
            step = match.strip()
            if step and len(step) > 5:
                steps.append(step[:200])
    return list(set(steps))[:3]

def get_memory_files():
    """获取所有 WorkBuddy 记忆文件"""
    memory_files = []
    for project_dir in WORKBUDDY_PATH.iterdir():
        if project_dir.is_dir():
            memory_dir = project_dir / ".workbuddy" / "memory"
            if memory_dir.exists():
                for md_file in memory_dir.glob("*.md"):
                    if md_file.name != "MEMORY.md":
                        memory_files.append(md_file)
    return memory_files

def save_project_context(conn, project_name, tech_stack, summary, key_decisions, next_steps, folder_name):
    """保存项目上下文到数据库"""
    cursor = conn.cursor()
    
    key_decisions_json = json.dumps(key_decisions, ensure_ascii=False)
    structure_json = json.dumps({
        "summary": summary,
        "next_steps": next_steps
    }, ensure_ascii=False)
    
    sql = """
    INSERT INTO project_contexts 
    (id, title, project_name, project_path, tech_stack, key_decisions, structure, updated_at, deleted)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, false)
    ON CONFLICT (id) DO UPDATE SET
        title = EXCLUDED.title,
        project_name = EXCLUDED.project_name,
        tech_stack = EXCLUDED.tech_stack,
        key_decisions = EXCLUDED.key_decisions,
        structure = EXCLUDED.structure,
        updated_at = EXCLUDED.updated_at
    """
    
    record_id = f"wb-{folder_name[:20]}-{datetime.now().strftime('%Y%m%d%H%M%S')}"
    cursor.execute(sql, (
        record_id,
        f"{project_name} 工作记录",
        project_name,
        str(WORKBUDDY_PATH / folder_name),
        tech_stack,
        key_decisions_json,
        structure_json,
        datetime.now()
    ))
    
    return record_id

def main():
    print("🔍 扫描 WorkBuddy 历史记忆文件...")
    memory_files = get_memory_files()
    print(f"📁 找到 {len(memory_files)} 个记忆文件")
    
    if not memory_files:
        print("❌ 未找到记忆文件")
        return
    
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        print("✅ 数据库连接成功")
    except Exception as e:
        print(f"❌ 数据库连接失败: {e}")
        return
    
    created_count = 0
    
    for md_file in memory_files:
        try:
            content = md_file.read_text(encoding='utf-8')
            if not content:
                continue
            folder_name = md_file.parent.parent.name
            
            project_name = extract_project_name(content, folder_name)
            tech_stack = extract_tech_stack(content)
            summary = extract_summary(content)
            key_decisions = extract_key_decisions(content)
            next_steps = extract_next_steps(content)
            
            if summary and len(summary) > 20:
                record_id = save_project_context(
                    conn, project_name, tech_stack, summary,
                    key_decisions, next_steps, folder_name
                )
                created_count += 1
                print(f"  ✅ {project_name}: {summary[:50]}...")
        
        except Exception as e:
            print(f"  ⚠️ 处理失败 {md_file.name}: {e}")
    
    conn.commit()
    conn.close()
    
    print(f"\n🎉 完成！共创建/更新 {created_count} 条项目上下文记录")

if __name__ == "__main__":
    main()
