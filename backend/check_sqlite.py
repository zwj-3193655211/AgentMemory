import sqlite3
conn = sqlite3.connect(r'C:\Users\31936\.agentmemory\data\agentmemory.db')
c = conn.cursor()
c.execute("SELECT COUNT(*) FROM messages WHERE session_id LIKE '%2d6abd80%'")
print(f'SQLite 当前会话消息数: {c.fetchone()[0]}')
c.execute("SELECT role, substr(content,1,60), timestamp FROM messages WHERE session_id LIKE '%2d6abd80%' ORDER BY rowid DESC LIMIT 3")
print('\n最新消息:')
for r in c.fetchall():
    ts = r[2][:19] if r[2] else "无时间"
    print(f'  [{r[0]}] {r[1]} ({ts})')
conn.close()
