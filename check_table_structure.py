#!/usr/bin/env python3
"""检查项目上下文表结构并修复显示问题"""
import psycopg2

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

# 查看表结构
cur.execute("""
    SELECT column_name, data_type 
    FROM information_schema.columns 
    WHERE table_name = 'project_contexts'
    ORDER BY ordinal_position
""")
print("项目上下文表结构:")
for row in cur.fetchall():
    print(f"  {row[0]}: {row[1]}")
print()

# 查看用户画像表结构
cur.execute("""
    SELECT column_name, data_type 
    FROM information_schema.columns 
    WHERE table_name = 'user_profiles'
    ORDER BY ordinal_position
""")
print("用户画像表结构:")
for row in cur.fetchall():
    print(f"  {row[0]}: {row[1]}")

cur.close()
conn.close()
