#!/usr/bin/env python3
"""验证标签修复结果"""
import psycopg2

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  标签修复验证")
print("="*80)
print()

tables = [
    ('skills', '技能沉淀'),
    ('best_practices', '最佳实践'),
    ('error_corrections', '错误纠正'),
]

for table, name in tables:
    print(f"【{name}】")
    cur.execute(f"SELECT title, tags FROM {table} LIMIT 3")
    rows = cur.fetchall()
    for row in rows:
        title = row[0][:30] if row[0] else "无标题"
        tags = row[1] if row[1] else "无标签"
        print(f"  - {title}...")
        print(f"    标签: {tags}")
    print()

cur.close()
conn.close()
print("="*80)
print("✅ 标签数据验证完成！")
print("="*80)
