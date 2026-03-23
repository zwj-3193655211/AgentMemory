import sqlite3
import json

conn = sqlite3.connect(r'C:\Users\31936\.agentmemory\data\agentmemory.db')
c = conn.cursor()

# 查找有内容的用户/AI消息
c.execute("""
    SELECT id, role, content, raw_json
    FROM messages 
    WHERE content IS NOT NULL AND content != ''
    ORDER BY rowid DESC LIMIT 5
""")

print("有内容的消息:")
for row in c.fetchall():
    print(f'\nid: {row[0]}')
    print(f'role: {row[1]}')
    print(f'content: {row[2][:200] if row[2] else "None"}...')

# 检查当前会话
c.execute("""
    SELECT COUNT(*) FROM messages 
    WHERE session_id = 'session-2d6abd80-f60f-4a57-8d34-948f66b5410f'
    AND content IS NOT NULL AND content != ''
""")
print(f'\n当前会话有内容的消息数: {c.fetchone()[0]}')

conn.close()