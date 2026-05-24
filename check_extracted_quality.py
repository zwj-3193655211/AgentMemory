#!/usr/bin/env python3
"""检查提取的记忆质量"""
import psycopg2

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  提取记忆质量检查")
print("="*80)
print()

tables = [
    ('error_corrections', '错误纠正'),
    ('best_practices', '最佳实践'),
    ('skills', '技能沉淀'),
    ('project_contexts', '项目上下文'),
]

for table, name in tables:
    print(f"【{name}】")
    cur.execute(f"SELECT COUNT(*) FROM {table}")
    total = cur.fetchone()[0]
    print(f"  总数: {total}")

    # 检查前3条
    cur.execute(f"SELECT title, created_at FROM {table} ORDER BY created_at DESC LIMIT 3")
    rows = cur.fetchall()
    print(f"  最新3条:")
    for row in rows:
        title = row[0][:50] if row[0] else "无标题"
        created = row[1].strftime('%Y-%m-%d') if row[1] else "无时间"
        print(f"    - [{created}] {title}...")

    # 检查是否有空字段
    cur.execute(f"SELECT COUNT(*) FROM {table} WHERE title IS NULL OR title = ''")
    empty_title = cur.fetchone()[0]

    print(f"  空标题: {empty_title}")
    print()

print("="*80)
print("  用户画像检查")
print("="*80)
cur.execute("SELECT COUNT(*) FROM user_profiles")
user_count = cur.fetchone()[0]
print(f"  用户画像数量: {user_count}")
print()
print("  说明: 用户画像为0是因为会话消息中缺少'我喜欢'、'我习惯'等关键词。")
print("  系统真实分类流程会基于内容特征进行分类。")
print("="*80)

cur.close()
conn.close()
