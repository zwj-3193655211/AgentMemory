#!/usr/bin/env python3
"""快速查看当前数据库状态"""
import psycopg2

try:
    conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
    cur = conn.cursor()

    print("="*80)
    print("  数据库状态")
    print("="*80)

    tables = ['error_corrections', 'best_practices', 'project_contexts', 'user_profiles', 'skills']
    for table in tables:
        cur.execute(f"SELECT COUNT(*) FROM {table}")
        count = cur.fetchone()[0]
        print(f"  {table:20s}: {count} 条")

    print()
    print("="*80)
    print("  示例数据检查")
    print("="*80)

    print("\n【error_corrections 示例】")
    cur.execute("SELECT title, problem, solution, tags FROM error_corrections LIMIT 1")
    row = cur.fetchone()
    if row:
        print(f"  title: {row[0]}")
        print(f"  problem: {row[1][:50] if row[1] else '空'}...")
        print(f"  solution: {row[2][:50] if row[2] else '空'}...")
        print(f"  tags: {row[3]}")

    print("\n【skills 示例】")
    cur.execute("SELECT title, description, difficulty, steps, tags FROM skills LIMIT 1")
    row = cur.fetchone()
    if row:
        print(f"  title: {row[0]}")
        print(f"  description: {row[1][:50] if row[1] else '空'}...")
        print(f"  difficulty: {row[2]}")
        print(f"  steps: {row[3]}")
        print(f"  tags: {row[4]}")

    cur.close()
    conn.close()

except Exception as e:
    print(f"数据库连接失败: {e}")
    print("请确保数据库服务正在运行！")
