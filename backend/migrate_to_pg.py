"""
SQLite 数据迁移到 PostgreSQL
"""
import sqlite3
import psycopg2
from datetime import datetime

# SQLite 连接
sqlite_conn = sqlite3.connect(r'C:\Users\31936\.agentmemory\data\agentmemory.db')
sqlite_c = sqlite_conn.cursor()

# PostgreSQL 连接
pg_conn = psycopg2.connect(
    host='localhost',
    port=5500,
    database='agentmemory',
    user='agentmemory',
    password='agentmemory123'
)
pg_c = pg_conn.cursor()

def parse_timestamp(ts):
    """解析各种格式的时间戳"""
    if not ts:
        return None, None
    
    try:
        # 毫秒时间戳
        if isinstance(ts, int) or (isinstance(ts, str) and ts.isdigit()):
            ts_int = int(ts)
            if ts_int > 1e12:  # 毫秒
                dt = datetime.fromtimestamp(ts_int / 1000)
            else:  # 秒
                dt = datetime.fromtimestamp(ts_int)
            return dt, dt.strftime('%Y-%m-%d')
        
        # ISO 格式字符串
        if isinstance(ts, str):
            # 处理 ISO 格式
            if 'T' in ts:
                dt = datetime.fromisoformat(ts.replace('Z', '+00:00').replace('+00:00', ''))
                return dt, dt.strftime('%Y-%m-%d')
    except:
        pass
    
    return None, None

print("开始迁移数据...")

# 迁移 sessions
sqlite_c.execute("SELECT id, agent_id, project_path, workspace_path, started_at, ended_at, message_count, created_at, updated_at FROM sessions")
sessions = sqlite_c.fetchall()
print(f"Sessions: {len(sessions)} 条")

for s in sessions:
    session_id, agent_id, project_path, workspace_path, started_at, ended_at, message_count, created_at, updated_at = s
    
    # 解析时间
    started_dt, started_date = parse_timestamp(started_at)
    ended_dt, _ = parse_timestamp(ended_at)
    created_dt, created_date = parse_timestamp(created_at)
    updated_dt, _ = parse_timestamp(updated_at)
    
    date_key = started_date or created_date
    
    # 从 session_id 推断 agent_type
    agent_type = 'iflow'  # 默认
    if 'claude' in session_id.lower():
        agent_type = 'claude'
    
    pg_c.execute("""
        INSERT INTO sessions (id, agent_id, agent_type, project_path, workspace_path, date_key, started_at, ended_at, message_count, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (id) DO UPDATE SET message_count = EXCLUDED.message_count, updated_at = EXCLUDED.updated_at
    """, (session_id, agent_id, agent_type, project_path, workspace_path, date_key, started_dt, ended_dt, message_count, created_dt, updated_dt))

pg_conn.commit()
print("Sessions 迁移完成")

# 迁移 messages（分批处理）
sqlite_c.execute("SELECT COUNT(*) FROM messages")
total_messages = sqlite_c.fetchone()[0]
print(f"Messages: {total_messages} 条")

batch_size = 1000
offset = 0
success = 0
errors = 0

while offset < total_messages:
    sqlite_c.execute(f"SELECT id, session_id, parent_id, role, content, raw_json, timestamp, created_at FROM messages ORDER BY rowid LIMIT {batch_size} OFFSET {offset}")
    messages = sqlite_c.fetchall()
    
    for m in messages:
        msg_id, session_id, parent_id, role, content, raw_json, timestamp, created_at = m
        
        # 解析时间
        ts_dt, date_key = parse_timestamp(timestamp)
        created_dt, _ = parse_timestamp(created_at)
        
        try:
            pg_c.execute("""
                INSERT INTO messages (id, session_id, parent_id, role, content, raw_json, timestamp, date_key, created_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (id) DO NOTHING
            """, (msg_id, session_id, parent_id, role, content, raw_json, ts_dt, date_key, created_dt))
            success += 1
        except Exception as e:
            errors += 1
            if errors < 10:
                print(f"  跳过错误消息 {msg_id}: {str(e)[:50]}")
    
    pg_conn.commit()
    offset += batch_size
    print(f"  已处理 {min(offset, total_messages)}/{total_messages} 条 (成功: {success}, 错误: {errors})")

print(f"\nMessages 迁移完成: 成功 {success}, 错误 {errors}")

# 验证
pg_c.execute("SELECT COUNT(*) FROM sessions")
print(f"\nPostgreSQL sessions: {pg_c.fetchone()[0]} 条")
pg_c.execute("SELECT COUNT(*) FROM messages")
print(f"PostgreSQL messages: {pg_c.fetchone()[0]} 条")

# 检查按日期分布
pg_c.execute("SELECT date_key, COUNT(*) FROM messages WHERE date_key IS NOT NULL GROUP BY date_key ORDER BY date_key DESC LIMIT 10")
print("\n按日期分布:")
for row in pg_c.fetchall():
    print(f"  {row[0]}: {row[1]} 条")

# 检查按 Agent 分布
pg_c.execute("SELECT agent_type, COUNT(*) FROM sessions GROUP BY agent_type")
print("\n按 Agent 分布:")
for row in pg_c.fetchall():
    print(f"  {row[0]}: {row[1]} 个会话")

sqlite_conn.close()
pg_conn.close()
print("\n迁移完成!")