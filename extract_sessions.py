import json
import os
from collections import defaultdict
import psycopg2

# ========== 来源2: iFlow CLI 会话 ==========
print('=' * 70)
print('=== 来源2: iFlow CLI 会话 ===')
print('=' * 70)

iflow_path = os.path.expanduser('~/.iflow/projects')
all_sessions = []

for project_dir in os.listdir(iflow_path):
    project_path = os.path.join(iflow_path, project_dir)
    if not os.path.isdir(project_path):
        continue
    for file in os.listdir(project_path):
        if file.endswith('.jsonl'):
            file_path = os.path.join(project_path, file)
            try:
                with open(file_path, 'r', encoding='utf-8') as f:
                    lines = f.readlines()
                if len(lines) >= 15:
                    user_msgs = []
                    for line in lines:
                        try:
                            data = json.loads(line)
                            if data.get('type') == 'user':
                                content = data.get('message', {}).get('content', '')
                                if isinstance(content, str):
                                    user_msgs.append(content[:100])
                        except:
                            pass
                    all_sessions.append({
                        'file': f'{project_dir}/{file[:25]}',
                        'total': len(lines),
                        'user_msgs': len(user_msgs),
                        'first': user_msgs[0][:60] if user_msgs else ''
                    })
            except:
                pass

all_sessions.sort(key=lambda x: x['total'], reverse=True)
print(f'找到 {len(all_sessions)} 个会话\n')
for s in all_sessions[:10]:
    print(f"{s['file']}... ({s['total']} msgs, user {s['user_msgs']})")
    print(f"  First: {s['first']}...\n")

# ========== 来源3: 数据库会话 ==========
print('=' * 70)
print('=== 来源3: AgentMemory 数据库 ===')
print('=' * 70)

try:
    conn = psycopg2.connect(
        host='localhost',
        port=5500,
        database='agentmemory',
        user='agentmemory',
        password='agentmemory123'
    )
    cur = conn.cursor()
    
    # 获取会话统计
    cur.execute("""
        SELECT session_id, COUNT(*) as msg_count,
               MIN(created_at) as start_time,
               MAX(created_at) as end_time
        FROM messages
        GROUP BY session_id
        ORDER BY msg_count DESC
        LIMIT 10
    """)
    
    db_sessions = cur.fetchall()
    print(f'数据库中共有 {len(db_sessions)} 个会话\n')
    
    for sid, count, start, end in db_sessions:
        # 获取该会话的前几条消息
        cur.execute("""
            SELECT role, content 
            FROM messages 
            WHERE session_id = %s 
            ORDER BY created_at
            LIMIT 5
        """, (sid,))
        first_msgs = cur.fetchall()
        first_content = first_msgs[0][1][:60] if first_msgs else ''
        
        print(f'{sid[:12]}... ({count} msgs)')
        print(f'  First: {first_content}...')
        print(f'  Time: {start} to {end}')
        print()
    
    conn.close()
except Exception as e:
    print(f'Database error: {e}')