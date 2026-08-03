#!/usr/bin/env python3
"""从 Claude Code 的 .jsonl 会话文件生成项目上下文"""
import os
import json
from pathlib import Path
from datetime import datetime
import psycopg2
import hashlib

DB_CONFIG = {
    "host": "localhost",
    "port": 5500,
    "database": "agentmemory",
    "user": "agentmemory",
    "password": "agentmemory"
}

CLAUDE_PROJECTS = Path.home() / ".claude" / "projects"

def extract_info_from_jsonl(jsonl_path):
    """从 Claude 会话 JSONL 文件提取信息"""
    try:
        messages = []
        with open(jsonl_path, 'r', encoding='utf-8') as f:
            for line in f:
                if line.strip():
                    try:
                        msg = json.loads(line)
                        if isinstance(msg, dict) and 'content' in msg:
                            messages.append(msg)
                    except:
                        pass

        if not messages:
            return None

        full_content = ""
        for msg in messages:
            content = msg.get('content', '')
            if isinstance(content, list):
                for c in content:
                    if isinstance(c, dict) and c.get('type') == 'text':
                        full_content += c.get('text', '') + '\n'
            elif isinstance(content, str):
                full_content += content + '\n'

        if len(full_content) < 100:
            return None

        tech_stack = extract_tech_stack(full_content)
        summary = extract_summary(full_content)
        key_decisions = extract_key_decisions(full_content)

        return {
            'tech_stack': tech_stack,
            'summary': summary,
            'key_decisions': key_decisions,
            'content_preview': full_content[:500]
        }
    except Exception as e:
        print(f"    Error reading {jsonl_path}: {e}")
        return None

def extract_tech_stack(content):
    techs = set()
    patterns = [
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
        r'CLIP|OpenCV|PIL',
        r'pgvector|Vector',
    ]
    for pattern in patterns:
        import re
        matches = re.findall(pattern, content, re.IGNORECASE)
        for match in matches:
            tech = match.strip().replace('\n', ', ')
            if tech:
                techs.add(tech)
    return list(techs)[:10]

def extract_summary(content):
    lines = content.strip().split('\n')
    summaries = []
    for line in lines[:30]:
        line = line.strip()
        if line and not line.startswith('#') and not line.startswith('```') and len(line) > 15:
            summaries.append(line)
        if len(summaries) >= 3:
            break
    return ' '.join(summaries)[:500]

def extract_key_decisions(content):
    decisions = []
    import re
    patterns = [
        r'(?:关键点|决策|决定|采用|使用)[：:]\s*([^\n]+)',
        r'(?:原因|因为|因此)[：:]\s*([^\n]+)',
        r'使用\s+(\w+(?:\s+\w+)?)\s+(?:实现|构建|开发)',
    ]
    for pattern in patterns:
        matches = re.findall(pattern, content, re.IGNORECASE)
        for match in matches[:2]:
            decision = match.strip() if isinstance(match, str) else str(match)
            if decision and len(decision) > 5:
                decisions.append(decision[:200])
    return list(set(decisions))[:5]

def save_project_context(conn, project_name, tech_stack, summary, key_decisions, session_path, source):
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

    md5_hash = hashlib.md5(session_path.encode()).hexdigest()[:8]
    record_id = f"cla-{md5_hash}"

    cursor.execute(sql, (
        record_id,
        f"{project_name} Claude会话",
        project_name,
        session_path,
        tech_stack,
        key_decisions_json,
        structure_json,
        datetime.now()
    ))
    return record_id

def main():
    print("🔍 扫描 Claude Code AgentMemory 项目会话...")
    
    agentmemory_project = CLAUDE_PROJECTS / "C--Users-31936-Desktop-AgentMemory"
    if not agentmemory_project.exists():
        print("❌ 未找到 Claude AgentMemory 项目")
        return
    
    jsonl_files = list(agentmemory_project.glob("*.jsonl"))
    print(f"📁 找到 {len(jsonl_files)} 个会话文件")
    
    if not jsonl_files:
        return
    
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        print("✅ 数据库连接成功")
    except Exception as e:
        print(f"❌ 数据库连接失败: {e}")
        return
    
    created_count = 0
    
    for jsonl_file in jsonl_files:
        print(f"  处理: {jsonl_file.name}...")
        info = extract_info_from_jsonl(jsonl_file)
        if info and info['summary']:
            record_id = save_project_context(
                conn, "AgentMemory", info['tech_stack'],
                info['summary'], info['key_decisions'],
                str(jsonl_file), "claude"
            )
            created_count += 1
            print(f"    ✅ {info['summary'][:60]}...")
        else:
            print(f"    ⚠️ 无法提取有效信息")
    
    conn.commit()
    conn.close()
    
    print(f"\n🎉 完成！为 AgentMemory 创建了 {created_count} 条项目上下文记录")

if __name__ == "__main__":
    main()
