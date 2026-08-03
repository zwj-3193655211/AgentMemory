#!/usr/bin/env python3
"""直接检查数据库中的项目名称字段"""
import psycopg2

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  检查最新添加的7条真实项目上下文")
print("="*80)
print()

# 查看最新的7条记录
cur.execute("""
    SELECT id, title, project_name, tech_stack
    FROM project_contexts
    ORDER BY created_at DESC
    LIMIT 7
""")
rows = cur.fetchall()

for i, row in enumerate(rows, 1):
    print(f"【{i}】")
    print(f"  ID: {row[0]}")
    print(f"  标题: {row[1]}")
    print(f"  项目名称: {row[2]}")
    print(f"  技术栈: {row[3]}")
    print()

# 查看所有不同的project_name
print("="*80)
print("  所有不同的项目名称")
print("="*80)
print()
cur.execute("""
    SELECT DISTINCT project_name, COUNT(*)
    FROM project_contexts
    GROUP BY project_name
""")
rows = cur.fetchall()
for row in rows:
    print(f"  {row[0]}: {row[1]}条")

cur.close()
conn.close()
