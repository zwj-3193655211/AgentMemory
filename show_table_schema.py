#!/usr/bin/env python3
"""查看数据库表的实际结构"""
import psycopg2

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  各表实际字段结构")
print("="*80)
print()

tables = ['error_corrections', 'best_practices', 'project_contexts', 'user_profiles', 'skills']
for table in tables:
    print(f"【{table}】")
    cur.execute(f"""
        SELECT column_name, data_type, is_nullable
        FROM information_schema.columns
        WHERE table_name = '{table}'
        ORDER BY ordinal_position
    """)
    columns = cur.fetchall()
    for col in columns:
        print(f"  {col[0]:20s} {col[1]:20s} nullable={col[2]}")
    print()

cur.close()
conn.close()
