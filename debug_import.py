"""调试导入脚本"""
import json
from pathlib import Path
import psycopg2

# 测试 Qwen 解析
qwen_file = Path(r'C:\Users\31936\.qwen\projects\c--users-31936\chats\07c3746e-da51-4368-a324-e551f776895b.jsonl')
print('=== 测试 Qwen 解析 ===')
print(f'文件存在: {qwen_file.exists()}')

messages = []
session_id = None
with open(qwen_file, 'r', encoding='utf-8') as f:
    for i, line in enumerate(f):
        if i >= 5:
            break
        data = json.loads(line)
        if session_id is None:
            session_id = data.get('sessionId')
        msg_type = data.get('type')
        parts = data.get('message', {}).get('parts', [])
        print(f'类型: {msg_type}, parts数量: {len(parts)}')
        
        text_parts = []
        for part in parts:
            if isinstance(part, dict) and 'text' in part:
                if not part.get('thought'):
                    text_parts.append(part['text'][:50])
        
        if text_parts:
            print(f'  内容: {text_parts[0]}...')
            messages.append({'role': msg_type, 'content': '\n'.join(text_parts)})

print(f'\nsession_id: {session_id}')
print(f'解析到 {len(messages)} 条有效消息')

# 检查数据库
conn = psycopg2.connect(
    host='localhost', port=5500, database='agentmemory',
    user='agentmemory', password='agentmemory123'
)
cur = conn.cursor()
cur.execute('SELECT id FROM sessions WHERE id = %s', (session_id,))
exists = cur.fetchone()
print(f'数据库中已存在: {exists is not None}')
conn.close()
