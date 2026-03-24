import sqlite3
import os

# 使用环境变量或默认用户目录
sqlite_path = os.environ.get('SQLITE_PATH', 
    os.path.expanduser('~/.agentmemory/data/agentmemory.db'))

conn = sqlite3.connect(sqlite_path)
c = conn.cursor()
c.execute("SELECT COUNT(*) FROM messages WHERE session_id LIKE '%2d6abd80%'")
print(f'SQLite 当前会话消息数: {c.fetchone()[0]}')
c.execute("SELECT role, substr(content,1,60), timestamp FROM messages WHERE session_id LIKE '%2d6abd80%' ORDER BY rowid DESC LIMIT 3")
print('\n最新消息:')
for r in c.fetchall():
    ts = r[2][:19] if r[2] else "无时间"
    print(f'  [{r[0]}] {r[1]} ({ts})')
conn.close()