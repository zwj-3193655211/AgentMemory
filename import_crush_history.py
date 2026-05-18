"""
导入 Crush CLI 的历史对话到 AgentMemory 数据库
Crush 使用 SQLite 数据库存储对话数据
"""
import json
import os
import sqlite3
import psycopg2
from datetime import datetime
from pathlib import Path

# Crush 数据库路径
CRUSH_DB_PATH = os.path.expanduser('~/.crush/crush.db')

# PostgreSQL 数据库连接配置
def get_pg_conn():
    return psycopg2.connect(
        host=os.environ.get('DATABASE_HOST', 'localhost'),
        port=int(os.environ.get('DATABASE_PORT', 5500)),
        database=os.environ.get('DATABASE_NAME', 'agentmemory'),
        user=os.environ.get('DATABASE_USER', 'agentmemory'),
        password=os.environ.get('DATABASE_PASSWORD', 'agentmemory123')
    )

def get_crush_conn():
    """连接到 Crush 的 SQLite 数据库"""
    return sqlite3.connect(CRUSH_DB_PATH)

def unix_to_datetime(ts):
    """将 Unix 时间戳转换为 datetime"""
    if ts:
        return datetime.fromtimestamp(ts)
    return datetime.now()

def ensure_agent(cur, agent_type):
    """确保 agent 存在，返回 id"""
    cur.execute("SELECT id FROM agents WHERE name = %s", (agent_type,))
    row = cur.fetchone()
    if row:
        return row[0]
    cur.execute(
        "INSERT INTO agents (name, display_name, created_at) VALUES (%s, %s, NOW()) RETURNING id",
        (agent_type, 'Crush CLI')
    )
    return cur.fetchone()[0]

def parse_crush_message(parts_str):
    """解析 Crush 消息的 parts 字段"""
    try:
        parts = json.loads(parts_str) if parts_str else []
        text_parts = []
        for part in parts:
            if isinstance(part, dict):
                part_type = part.get('type')
                if part_type == 'text':
                    text_parts.append(part.get('data', {}).get('text', ''))
                elif part_type == 'finish':
                    pass  # skip finish marker
        return '\n'.join(text_parts)
    except:
        return parts_str if parts_str else ''

def get_crush_sessions(conn):
    """从 Crush 数据库获取所有会话和消息"""
    cur = conn.cursor()
    
    sessions = []
    
    # 查询所有会话
    cur.execute("""
        SELECT id, parent_session_id, title, message_count, 
               prompt_tokens, completion_tokens, cost, 
               updated_at, created_at, summary_message_id, todos
        FROM sessions 
        ORDER BY created_at DESC
    """)
    
    for row in cur.fetchall():
        session_id = row[0]
        
        # 查询该会话的所有消息
        cur.execute("""
            SELECT id, role, parts, model, created_at, finished_at, provider
            FROM messages 
            WHERE session_id = ? 
            ORDER BY created_at ASC
        """, (session_id,))
        
        messages = []
        for msg_row in cur.fetchall():
            msg_id, role, parts, model, created_at, finished_at, provider = msg_row
            content = parse_crush_message(parts)
            if content.strip():  # 只添加有内容的消息
                messages.append({
                    'id': msg_id,
                    'role': role,
                    'content': content,
                    'timestamp': unix_to_datetime(created_at).isoformat() if created_at else None,
                    'model': model,
                    'provider': provider
                })
        
        if messages:  # 只返回有消息的会话
            sessions.append({
                'session_id': session_id,
                'title': row[2] or 'Untitled Session',
                'message_count': row[3] or len(messages),
                'cwd': '',
                'started_at': unix_to_datetime(row[8]).isoformat() if row[8] else None,
                'updated_at': unix_to_datetime(row[7]).isoformat() if row[7] else None,
                'messages': messages
            })
    
    return sessions

def import_crush_session(pg_conn, session_data, agent_type='crush'):
    """导入单个 Crush 会话到 PostgreSQL"""
    cur = pg_conn.cursor()
    
    session_id = session_data.get('session_id')
    if not session_id:
        return False
    
    # 检查是否已存在
    cur.execute("SELECT id FROM sessions WHERE id = %s", (session_id,))
    if cur.fetchone():
        return False  # 已存在，跳过
    
    agent_id = ensure_agent(cur, agent_type)
    
    # 解析时间
    started_at = session_data.get('started_at')
    if started_at:
        try:
            started_at = datetime.fromisoformat(started_at)
        except:
            started_at = datetime.now()
    else:
        started_at = datetime.now()
    
    # 插入会话
    cur.execute("""
        INSERT INTO sessions (id, agent_id, agent_type, project_path, date_key, started_at, message_count, title, created_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, NOW())
    """, (
        session_id,
        agent_id,
        agent_type,
        session_data.get('cwd', ''),
        started_at.date(),
        started_at,
        len(session_data.get('messages', [])),
        session_data.get('title', 'Untitled Session')
    ))
    
    # 插入消息
    for i, msg in enumerate(session_data.get('messages', [])):
        msg_id = f"{session_id}-{i}"
        role = msg['role']
        content = msg['content']
        ts = msg.get('timestamp')
        
        try:
            created_at = datetime.fromisoformat(ts) if ts else datetime.now()
        except:
            created_at = datetime.now()
        
        cur.execute("""
            INSERT INTO messages (id, session_id, role, content, created_at)
            VALUES (%s, %s, %s, %s, %s)
            ON CONFLICT (id) DO NOTHING
        """, (msg_id, session_id, role, content, created_at))
    
    pg_conn.commit()
    return True

def scan_and_import():
    """扫描并导入 Crush 历史"""
    if not os.path.exists(CRUSH_DB_PATH):
        print(f"Crush 数据库不存在: {CRUSH_DB_PATH}")
        return 0
    
    crush_conn = get_crush_conn()
    pg_conn = get_pg_conn()
    
    print("=== 导入 Crush CLI 历史 ===")
    
    sessions = get_crush_sessions(crush_conn)
    print(f"找到 {len(sessions)} 个会话")
    
    count = 0
    for session_data in sessions:
        try:
            if import_crush_session(pg_conn, session_data, 'crush'):
                count += 1
                title = session_data.get('title', 'Untitled')[:30]
                msg_count = len(session_data.get('messages', []))
                print(f"  导入: {title}... ({msg_count} 条消息)")
        except Exception as e:
            print(f"  错误: {e}")
    
    crush_conn.close()
    pg_conn.close()
    
    print(f"\n=== 导入完成 ===")
    print(f"Crush CLI: {count} 个会话")
    return count

if __name__ == '__main__':
    scan_and_import()
