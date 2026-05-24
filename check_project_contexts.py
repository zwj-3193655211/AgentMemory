#!/usr/bin/env python3
"""检查当前项目上下文数据"""
import psycopg2

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  当前项目上下文数据（前10条）")
print("="*80)
print()

cur.execute("""
    SELECT id, title, project_name, tech_stack, project_path
    FROM project_contexts
    LIMIT 10
""")
rows = cur.fetchall()

for i, row in enumerate(rows, 1):
    print(f"【{i}】ID: {row[0]}")
    print(f"    标题: {row[1][:60]}...")
    print(f"    项目名称: {row[2]}")
    print(f"    技术栈: {row[3]}")
    print(f"    项目路径: {row[4]}")
    print()

cur.execute("SELECT COUNT(*) FROM project_contexts")
count = cur.fetchone()[0]
print(f"总记录数: {count}")

cur.close()
conn.close()
