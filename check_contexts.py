#!/usr/bin/env python3
import psycopg2

DB_CONFIG = {
    "host": "localhost",
    "port": 5500,
    "database": "agentmemory",
    "user": "agentmemory",
    "password": "agentmemory"
}

conn = psycopg2.connect(**DB_CONFIG)
cursor = conn.cursor()

cursor.execute("""
    SELECT id, title, project_name,
           LEFT(structure::text, 100) as summary
    FROM project_contexts
    ORDER BY created_at DESC
""")
rows = cursor.fetchall()

print(f"共 {len(rows)} 条项目上下文:\n")
for row in rows:
    print(f"ID: {row[0]}")
    print(f"  标题: {row[1]}")
    print(f"  项目: {row[2]}")
    print(f"  摘要: {row[3][:80]}...")
    print()

conn.close()
