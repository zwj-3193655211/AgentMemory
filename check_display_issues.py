#!/usr/bin/env python3
"""检查并修复项目上下文和用户画像的显示问题"""
import psycopg2
import json

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  检查项目上下文数据")
print("="*80)
print()

# 检查项目上下文
cur.execute("SELECT title, project_name, tech_stack, summary FROM project_contexts LIMIT 5")
rows = cur.fetchall()
for i, row in enumerate(rows, 1):
    print(f"【项目 {i}】")
    print(f"  title: {row[0][:50]}...")
    print(f"  project_name: {row[1]}")
    print(f"  tech_stack: {row[2]}")
    print(f"  summary: {row[3]}")
    print()

print("="*80)
print("  检查用户画像数据")
print("="*80)
print()

# 检查用户画像
cur.execute("SELECT title, category, items FROM user_profiles LIMIT 5")
rows = cur.fetchall()
for i, row in enumerate(rows, 1):
    print(f"【画像 {i}】")
    print(f"  title: {row[0]}")
    print(f"  category: {row[1]}")
    print(f"  items: {row[2]}")
    print()

cur.close()
conn.close()
