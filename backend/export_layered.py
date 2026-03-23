"""
分层导出对话记录
按 日期/Agent/会话 三层结构组织
"""
import psycopg2
import os
from datetime import datetime

# PostgreSQL 连接
conn = psycopg2.connect(
    host='localhost',
    port=5500,
    database='agentmemory',
    user='agentmemory',
    password='agentmemory123'
)

c = conn.cursor()

# 导出根目录
export_root = os.path.expanduser("~/.agentmemory/export")

print("开始分层导出...")

# 获取所有有消息的日期
c.execute("""
    SELECT DISTINCT date_key FROM messages 
    WHERE date_key IS NOT NULL 
    ORDER BY date_key DESC
""")
dates = [row[0] for row in c.fetchall()]

total_sessions = 0
total_messages = 0

for date_key in dates:
    date_str = date_key.strftime('%Y-%m-%d') if hasattr(date_key, 'strftime') else str(date_key)
    date_dir = os.path.join(export_root, date_str)
    os.makedirs(date_dir, exist_ok=True)
    
    print(f"\n处理日期: {date_str}")
    
    # 获取该日期的所有 Agent
    c.execute("""
        SELECT DISTINCT s.agent_type 
        FROM sessions s
        JOIN messages m ON s.id = m.session_id
        WHERE m.date_key = %s
    """, (date_key,))
    agents = [row[0] for row in c.fetchall()]
    
    for agent_type in agents:
        agent_dir = os.path.join(date_dir, agent_type)
        os.makedirs(agent_dir, exist_ok=True)
        
        # 获取该日期该 Agent 的所有会话
        c.execute("""
            SELECT s.id, s.project_path, COUNT(m.id) as msg_count
            FROM sessions s
            JOIN messages m ON s.id = m.session_id
            WHERE m.date_key = %s AND s.agent_type = %s
            GROUP BY s.id, s.project_path
            ORDER BY msg_count DESC
        """, (date_key, agent_type))
        sessions = c.fetchall()
        
        for session_id, project_path, msg_count in sessions:
            # 会话文件名
            session_short = session_id[:8]
            project_name = os.path.basename(project_path) if project_path else "unknown"
            safe_name = "".join(c if c.isalnum() or c in '-_' else '_' for c in project_name)[:30]
            filename = f"{session_short}_{safe_name}.txt"
            filepath = os.path.join(agent_dir, filename)
            
            # 获取会话消息
            c.execute("""
                SELECT role, content, timestamp 
                FROM messages 
                WHERE session_id = %s 
                ORDER BY timestamp
            """, (session_id,))
            messages = c.fetchall()
            
            if not messages:
                continue
            
            # 写入文件
            with open(filepath, 'w', encoding='utf-8') as f:
                f.write("=" * 70 + "\n")
                f.write(f"AgentMemory 会话记录\n")
                f.write("=" * 70 + "\n")
                f.write(f"Agent: {agent_type}\n")
                f.write(f"会话ID: {session_id}\n")
                f.write(f"日期: {date_str}\n")
                f.write(f"工作目录: {project_path or '未知'}\n")
                f.write(f"消息数: {len(messages)}\n")
                f.write("=" * 70 + "\n\n")
                
                for role, content, ts in messages:
                    if not content or not content.strip():
                        continue
                    
                    # 时间格式
                    time_str = ""
                    if ts:
                        if hasattr(ts, 'strftime'):
                            time_str = ts.strftime('%H:%M:%S')
                        else:
                            time_str = str(ts)[:8]
                    
                    role_name = "用户" if role == "user" else "AI" if role == "assistant" else role
                    f.write(f"[{role_name} {time_str}]\n")
                    f.write(f"{content[:2000]}\n")
                    if len(content) > 2000:
                        f.write(f"... (截断，共 {len(content)} 字符)\n")
                    f.write("\n" + "-" * 70 + "\n\n")
            
            total_sessions += 1
            total_messages += len(messages)
        
        print(f"  {agent_type}: {len(sessions)} 个会话")

# 创建索引文件
index_path = os.path.join(export_root, "index.txt")
with open(index_path, 'w', encoding='utf-8') as f:
    f.write("AgentMemory 对话索引\n")
    f.write("=" * 50 + "\n\n")
    f.write(f"生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
    f.write(f"总会话数: {total_sessions}\n")
    f.write(f"总消息数: {total_messages}\n\n")
    
    f.write("目录结构:\n")
    f.write("  日期/\n")
    f.write("    Agent类型/\n")
    f.write("      会话文件.txt\n\n")
    
    c.execute("""
        SELECT m.date_key, s.agent_type, COUNT(DISTINCT m.session_id), COUNT(*)
        FROM messages m
        JOIN sessions s ON m.session_id = s.id
        WHERE m.date_key IS NOT NULL
        GROUP BY m.date_key, s.agent_type
        ORDER BY m.date_key DESC, s.agent_type
    """)
    f.write("统计:\n")
    for date_key, agent_type, sess_count, msg_count in c.fetchall():
        date_str = date_key.strftime('%Y-%m-%d') if hasattr(date_key, 'strftime') else str(date_key)
        f.write(f"  {date_str} / {agent_type}: {sess_count} 会话, {msg_count} 消息\n")

conn.close()

print(f"\n导出完成!")
print(f"导出目录: {export_root}")
print(f"总会话: {total_sessions}")
print(f"总消息: {total_messages}")
print(f"索引文件: {index_path}")
