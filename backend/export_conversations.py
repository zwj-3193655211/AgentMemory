import sqlite3
import os

dbPath = os.path.expanduser("~/.agentmemory/data/agentmemory.db")
outputPath = os.path.expanduser("~/.agentmemory/export/conversations.txt")

conn = sqlite3.connect(dbPath)
c = conn.cursor()

# 获取最近20个会话
c.execute('SELECT id FROM sessions ORDER BY updated_at DESC LIMIT 20')
sessions = [r[0] for r in c.fetchall()]

os.makedirs(os.path.dirname(outputPath), exist_ok=True)

with open(outputPath, 'w', encoding='utf-8') as f:
    f.write('AgentMemory 对话导出\n')
    f.write('='*60 + '\n\n')
    
    total_msgs = 0
    for sid in sessions:
        c.execute('SELECT role, content FROM messages WHERE session_id = ? ORDER BY timestamp', (sid,))
        messages = c.fetchall()
        
        if messages:
            f.write(f'--- 会话: {sid[:8]}... ({len(messages)}条消息) ---\n\n')
            for role, content in messages:
                if content and len(content.strip()) > 0:
                    role_name = '用户' if role == 'user' else 'AI' if role == 'assistant' else role
                    f.write(f'[{role_name}]: {content[:500]}\n\n')
                    total_msgs += 1
            f.write('\n')

print(f'导出完成: {outputPath}')
print(f'共导出 {total_msgs} 条消息')
conn.close()
