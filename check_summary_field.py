#!/usr/bin/env python3
"""检查项目上下文表结构和数据"""
import psycopg2

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  检查项目上下文表结构")
print("="*80)
print()

# 查看表结构
cur.execute("""
    SELECT column_name, data_type
    FROM information_schema.columns
    WHERE table_name = 'project_contexts'
    ORDER BY ordinal_position
""")
columns = cur.fetchall()

print("所有字段：")
for col in columns:
    print(f"  - {col[0]}: {col[1]}")
print()

# 检查是否有 summary 或类似的字段
print("="*80)
print("  检查工作摘要相关字段")
print("="*80)
print()

# 查找可能的工作摘要字段
summary_fields = [col[0] for col in columns if 'summary' in col[0].lower() or 'abstract' in col[0].lower() or 'desc' in col[0].lower()]
print(f"可能的摘要字段: {summary_fields if summary_fields else '无'}")

# 查看一条完整记录的所有字段值
print()
print("="*80)
print("  查看一条完整记录")
print("="*80)
print()

cur.execute("""
    SELECT *
    FROM project_contexts
    LIMIT 1
""")
row = cur.fetchone()

print("第一条记录的字段值：")
for i, col in enumerate(columns):
    value = row[i]
    if value is None:
        value_str = "NULL"
    elif isinstance(value, (list, dict)):
        value_str = str(value)[:50] + "..."
    else:
        value_str = str(value)[:50] + "..."
    print(f"  {col[0]}: {value_str}")

cur.close()
conn.close()
