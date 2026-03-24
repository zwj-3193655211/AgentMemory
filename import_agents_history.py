"""
导入 Qwen CLI 和 Claude CLI 的历史对话到 AgentMemory 数据库
"""
import json
import os
import psycopg2
from datetime import datetime
from pathlib import Path

# 数据库连接 - 使用环境变量
def get_db_conn():
    return psycopg2.connect(
        host=os.environ.get('DATABASE_HOST', 'localhost'),
        port=int(os.environ.get('DATABASE_PORT', 5500)),
        database=os.environ.get('DATABASE_NAME', 'agentmemory'),
        user=os.environ.get('DATABASE_USER', 'agentmemory'),
        password=os.environ.get('DATABASE_PASSWORD', 'agentmemory123')
    )

def ensure_agent(cur, agent_type):
    """确保 agent 存在，返回 id"""
    cur.execute("SELECT id FROM agents WHERE name = %s", (agent_type,))
    row = cur.fetchone()
    if row:
        return row[0]
    cur.execute(
        "INSERT INTO agents (name, display_name, created_at) VALUES (%s, %s, NOW()) RETURNING id",
        (agent_type, agent_type.upper())
    )
    return cur.fetchone()[0]

def parse_qwen_session(file_path):
    """解析 Qwen CLI 的 JSONL 会话文件"""
    messages = []
    session_id = None
    cwd = None
    
    with open(file_path, 'r', encoding='utf-8') as f:
        for line in f:
            try:
                data = json.loads(line)
            except:
                continue
            
            if session_id is None:
                session_id = data.get('sessionId')
            if cwd is None:
                cwd = data.get('cwd', '')
            
            msg_type = data.get('type')
            if msg_type not in ('user', 'assistant'):
                continue
            
            # 提取消息内容
            parts = data.get('message', {}).get('parts', [])
            text_parts = []
            for part in parts:
                if isinstance(part, dict) and 'text' in part:
                    # 跳过 thought 内容
                    if not part.get('thought'):
                        text_parts.append(part['text'])
                elif isinstance(part, dict) and 'functionCall' in part:
                    text_parts.append(f"[工具调用: {part['functionCall'].get('name', 'unknown')}]")
            
            content = '\n'.join(text_parts) if text_parts else ''
            
            if content:
                messages.append({
                    'role': 'user' if msg_type == 'user' else 'assistant',
                    'content': content,
                    'timestamp': data.get('timestamp', '')
                })
    
    return {
        'session_id': session_id,
        'cwd': cwd,
        'messages': messages
    }

def parse_claude_session(file_path):
    """解析 Claude CLI 的 JSONL 会话文件"""
    messages = []
    session_id = None
    cwd = None
    
    with open(file_path, 'r', encoding='utf-8') as f:
        for line in f:
            try:
                data = json.loads(line)
            except:
                continue
            
            if session_id is None:
                session_id = data.get('sessionId')
            if cwd is None:
                cwd = data.get('cwd', '')
            
            msg_type = data.get('type')
            if msg_type not in ('user', 'assistant'):
                continue
            
            # 提取消息内容
            msg = data.get('message', {})
            content_parts = []
            
            if msg_type == 'user':
                content = msg.get('content', '')
                if isinstance(content, str) and content:
                    content_parts.append(content)
            else:
                content_list = msg.get('content', [])
                if isinstance(content_list, list):
                    for item in content_list:
                        if isinstance(item, dict):
                            if item.get('type') == 'text':
                                content_parts.append(item.get('text', ''))
                            elif item.get('type') == 'tool_use':
                                content_parts.append(f"[工具调用: {item.get('name', 'unknown')}]")
                        elif isinstance(item, str):
                            content_parts.append(item)
            
            content = '\n'.join(content_parts) if content_parts else ''
            
            if content:
                messages.append({
                    'role': 'user' if msg_type == 'user' else 'assistant',
                    'content': content,
                    'timestamp': data.get('timestamp', '')
                })
    
    return {
        'session_id': session_id,
        'cwd': cwd,
        'messages': messages
    }

def import_session(conn, session_data, agent_type):
    """导入单个会话到数据库"""
    cur = conn.cursor()
    
    session_id = session_data['session_id']
    if not session_id:
        return False
    
    # 检查是否已存在
    cur.execute("SELECT id FROM sessions WHERE id = %s", (session_id,))
    if cur.fetchone():
        return False  # 已存在，跳过
    
    agent_id = ensure_agent(cur, agent_type)
    
    # 解析时间
    first_ts = session_data['messages'][0].get('timestamp') if session_data['messages'] else None
    if first_ts:
        try:
            started_at = datetime.fromisoformat(first_ts.replace('Z', '+00:00'))
        except:
            started_at = datetime.now()
    else:
        started_at = datetime.now()
    
    # 插入会话
    cur.execute("""
        INSERT INTO sessions (id, agent_id, agent_type, project_path, date_key, started_at, message_count, created_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, NOW())
    """, (
        session_id,
        agent_id,
        agent_type,
        session_data['cwd'],
        started_at.date(),
        started_at,
        len(session_data['messages'])
    ))
    
    # 插入消息
    for i, msg in enumerate(session_data['messages']):
        msg_id = f"{session_id}-{i}"
        role = msg['role']
        content = msg['content']
        ts = msg.get('timestamp')
        
        try:
            created_at = datetime.fromisoformat(ts.replace('Z', '+00:00')) if ts else datetime.now()
        except:
            created_at = datetime.now()
        
        cur.execute("""
            INSERT INTO messages (id, session_id, role, content, created_at)
            VALUES (%s, %s, %s, %s, %s)
            ON CONFLICT (id) DO NOTHING
        """, (msg_id, session_id, role, content, created_at))
    
    conn.commit()
    return True

def scan_and_import():
    """扫描并导入所有历史"""
    conn = get_db_conn()
    
    # 使用环境变量配置路径
    qwen_path_str = os.environ.get('QWEN_PROJECTS_PATH', '~/.qwen/projects')
    claude_path_str = os.environ.get('CLAUDE_PROJECTS_PATH', '~/.claude/projects')
    
    qwen_path = Path(os.path.expanduser(qwen_path_str))
    claude_path = Path(os.path.expanduser(claude_path_str))
    
    # Qwen CLI
    qwen_count = 0
    if qwen_path.exists():
        print("=== 导入 Qwen CLI 历史 ===")
        for chats_dir in qwen_path.rglob('chats'):
            for jsonl_file in chats_dir.glob('*.jsonl'):
                try:
                    session_data = parse_qwen_session(str(jsonl_file))
                    if session_data['messages']:
                        if import_session(conn, session_data, 'qwen'):
                            qwen_count += 1
                            print(f"  导入: {jsonl_file.name} ({len(session_data['messages'])} 条消息)")
                except Exception as e:
                    print(f"  错误: {jsonl_file.name} - {e}")
    
    # Claude CLI
    claude_count = 0
    if claude_path.exists():
        print("\n=== 导入 Claude CLI 历史 ===")
        for jsonl_file in claude_path.glob('**/*.jsonl'):
            # 排除 memory 目录
            if 'memory' in str(jsonl_file):
                continue
            try:
                session_data = parse_claude_session(str(jsonl_file))
                if session_data['messages']:
                    if import_session(conn, session_data, 'claude'):
                        claude_count += 1
                        print(f"  导入: {jsonl_file.name} ({len(session_data['messages'])} 条消息)")
            except Exception as e:
                print(f"  错误: {jsonl_file.name} - {e}")
    
    conn.close()
    
    print(f"\n=== 导入完成 ===")
    print(f"Qwen CLI: {qwen_count} 个会话")
    print(f"Claude CLI: {claude_count} 个会话")

if __name__ == '__main__':
    scan_and_import()