"""
导入 WorkBuddy 的历史对话到 AgentMemory 数据库
WorkBuddy 使用 JSONL 格式存储对话数据，位于 ~/.workbuddy/projects/
"""
import json
import logging
import os
import re
import sys
import psycopg2
from datetime import datetime, timezone
from pathlib import Path

logger = logging.getLogger(__name__)

WORKBUDDY_PROJECTS_DIR = os.path.expanduser('~/.workbuddy/projects')

# PostgreSQL 数据库连接配置
def get_pg_conn():
    return psycopg2.connect(
        host=os.environ.get('DATABASE_HOST', 'localhost'),
        port=int(os.environ.get('DATABASE_PORT', 5500)),
        database=os.environ.get('DATABASE_NAME', 'agentmemory'),
        user=os.environ.get('DATABASE_USER', 'agentmemory'),
        password=os.environ.get('DATABASE_PASSWORD') or sys.exit('ERROR: DATABASE_PASSWORD environment variable not set')
    )

def ms_to_datetime(ms):
    """将毫秒时间戳转换为 datetime"""
    if ms:
        try:
            return datetime.fromtimestamp(ms / 1000.0)
        except (ValueError, OSError):
            pass
    return None

def ensure_agent(cur, agent_name='workbuddy'):
    """确保 agent 存在，返回 id"""
    cur.execute("SELECT id FROM agents WHERE name = %s AND type = 'workbuddy'", (agent_name,))
    row = cur.fetchone()
    if row:
        return row[0]
    cur.execute(
        "INSERT INTO agents (name, display_name, created_at, type, parser_type) VALUES (%s, %s, NOW(), 'workbuddy', 'workbuddy') RETURNING id",
        (agent_name, 'WorkBuddy (Claw)')
    )
    return cur.fetchone()[0]

def extract_user_query(text):
    """从用户消息中提取 <user_query> 部分，去掉 system-reminder 等系统内容"""
    if not text:
        return ''
    # 尝试提取 <user_query> 标签内容
    m = re.search(r'<user_query>(.*?)</user_query>', text, re.DOTALL)
    if m:
        return m.group(1).strip()
    # 如果没有 user_query 标签，尝试去掉 system-reminder 块
    cleaned = re.sub(r'<system-reminder[^>]*>.*?</system-reminder>', '', text, flags=re.DOTALL)
    cleaned = re.sub(r'<user_info>.*?</user_info>', '', cleaned, flags=re.DOTALL)
    cleaned = re.sub(r'<project_context>.*?</project_context>', '', cleaned, flags=re.DOTALL)
    cleaned = re.sub(r'<additional_data>.*?</additional_data>', '', cleaned, flags=re.DOTALL)
    cleaned = re.sub(r'<memory_and_skills_reminder>.*?</memory_and_skills_reminder>', '', cleaned, flags=re.DOTALL)
    cleaned = cleaned.strip()
    return cleaned if cleaned else text.strip()

def extract_assistant_text(content):
    """从 assistant 消息的 content 数组中提取文本"""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        texts = []
        for block in content:
            if isinstance(block, dict):
                btype = block.get('type', '')
                if btype in ('output_text', 'text'):
                    t = block.get('text', '')
                    if t.strip():
                        texts.append(t.strip())
        return '\n'.join(texts)
    return ''

def extract_user_text(content):
    """从 user 消息的 content 数组中提取文本并清理"""
    if isinstance(content, str):
        return extract_user_query(content)
    if isinstance(content, list):
        texts = []
        for block in content:
            if isinstance(block, dict):
                btype = block.get('type', '')
                if btype in ('input_text', 'text'):
                    t = block.get('text', '')
                    cleaned = extract_user_query(t)
                    if cleaned:
                        texts.append(cleaned)
        return '\n'.join(texts)
    return ''

def parse_jsonl_file(filepath):
    """解析一个 WorkBuddy JSONL 对话文件，返回 (title, messages)"""
    messages = []
    first_user_text = ''
    timestamps = []

    with open(filepath, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue

            msg_type = obj.get('type', '')
            role = obj.get('role', '')
            msg_id = obj.get('id', '')
            ts_ms = obj.get('timestamp', 0)

            # 只处理 message 类型的 user 和 assistant
            if msg_type != 'message':
                continue

            content = obj.get('content', '')

            if role == 'user':
                text = extract_user_text(content)
                if text:
                    if not first_user_text:
                        first_user_text = text[:100]
                    ts = ms_to_datetime(ts_ms)
                    if ts:
                        timestamps.append(ts)
                    messages.append({
                        'role': 'user',
                        'content': text,
                        'timestamp': ts
                    })
            elif role == 'assistant':
                text = extract_assistant_text(content)
                if text:
                    ts = ms_to_datetime(ts_ms)
                    if ts:
                        timestamps.append(ts)
                    messages.append({
                        'role': 'assistant',
                        'content': text,
                        'timestamp': ts
                    })

    # 用第一条用户消息的前50个字符作为标题
    title = first_user_text[:50] if first_user_text else 'Untitled Session'
    # 清理标题中的换行
    title = title.replace('\n', ' ').strip()
    if len(title) > 50:
        title = title[:47] + '...'

    return title, messages, timestamps

def import_session(pg_conn, session_id, title, messages, timestamps, project_dir, agent_name='workbuddy'):
    """导入单个会话到 PostgreSQL"""
    cur = pg_conn.cursor()

    # 检查是否已存在
    cur.execute("SELECT id FROM sessions WHERE id = %s", (session_id,))
    if cur.fetchone():
        return False  # 已存在，跳过

    agent_id = ensure_agent(cur, agent_name)

    started_at = min(timestamps) if timestamps else datetime.now()
    ended_at = max(timestamps) if timestamps else started_at

    # 从 project_dir 推导工作区路径
    # 格式: c-Users-31936-WorkBuddy-2026-05-19-task-4
    workspace = project_dir
    m = re.match(r'[Cc]-Users-31936-WorkBuddy-(.+)', project_dir)
    if m:
        workspace = m.group(1)

    # 插入会话
    cur.execute("""
        INSERT INTO sessions (id, agent_id, agent_type, project_path, workspace_path, date_key, started_at, ended_at, message_count, title, created_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW())
    """, (
        session_id,
        agent_id,
        'workbuddy',
        project_dir,
        workspace,
        started_at.date(),
        started_at,
        ended_at,
        len(messages),
        title
    ))

    # 插入消息
    for i, msg in enumerate(messages):
        msg_db_id = f"{session_id}-msg-{i}"
        cur.execute("""
            INSERT INTO messages (id, session_id, role, content, timestamp, date_key, created_at)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (id) DO NOTHING
        """, (
            msg_db_id,
            session_id,
            msg['role'],
            msg['content'],
            msg.get('timestamp'),
            msg['timestamp'].date() if msg.get('timestamp') else None,
            msg.get('timestamp') or datetime.now()
        ))

    pg_conn.commit()
    return True

def scan_and_import():
    """扫描并导入 WorkBuddy 历史"""
    if not os.path.exists(WORKBUDDY_PROJECTS_DIR):
        print(f"WorkBuddy 项目目录不存在: {WORKBUDDY_PROJECTS_DIR}")
        return 0

    pg_conn = get_pg_conn()

    print("=== 导入 WorkBuddy 对话历史 ===")

    total_sessions = 0
    total_messages = 0
    imported_sessions = 0
    imported_messages = 0

    # 遍历所有项目目录
    for project_dir in sorted(os.listdir(WORKBUDDY_PROJECTS_DIR)):
        project_path = os.path.join(WORKBUDDY_PROJECTS_DIR, project_dir)
        if not os.path.isdir(project_path):
            continue

        # 遍历该目录下的 JSONL 文件（排除 subagents）
        for filename in os.listdir(project_path):
            if not filename.endswith('.jsonl'):
                continue
            filepath = os.path.join(project_path, filename)
            # 跳过子代理文件
            if 'subagents' in filepath:
                continue

            session_id = filename.replace('.jsonl', '')  # 用 UUID 作为 session_id

            try:
                title, messages, timestamps = parse_jsonl_file(filepath)
                total_sessions += 1
                total_messages += len(messages)

                if not messages:
                    continue

                if import_session(pg_conn, session_id, title, messages, timestamps, project_dir):
                    imported_sessions += 1
                    imported_messages += len(messages)
                    print(f"  [{imported_sessions}] {title} ({len(messages)} 条消息) [{project_dir}]")

            except Exception as e:
                logger.error(f"导入 WorkBuddy 会话失败 '{session_id}': {e}", exc_info=True)

    pg_conn.close()

    print(f"\n=== 导入完成 ===")
    print(f"共发现 {total_sessions} 个会话, {total_messages} 条消息")
    print(f"成功导入 {imported_sessions} 个会话, {imported_messages} 条消息")
    return imported_sessions

if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO, format='%(levelname)s: %(message)s')
    scan_and_import()
