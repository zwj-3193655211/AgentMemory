#!/usr/bin/env python3
"""检查数据库中的 embedding 数据"""
import psycopg2

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  检查各表的 embedding 数据")
print("="*80)
print()

tables = ['skills', 'error_corrections', 'best_practices', 'project_contexts', 'user_profiles']

for table in tables:
    cur.execute(f"SELECT COUNT(*) FROM {table}")
    total = cur.fetchone()[0]
    
    cur.execute(f"SELECT COUNT(*) FROM {table} WHERE embedding IS NOT NULL")
    with_embedding = cur.fetchone()[0]
    
    print(f"{table:25} | 总数: {total:5} | 有embedding: {with_embedding:5} | 无embedding: {total - with_embedding:5}")

cur.close()
conn.close()
