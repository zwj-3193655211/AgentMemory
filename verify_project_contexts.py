#!/usr/bin/env python3
"""验证项目上下文数据"""
import psycopg2

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  项目上下文数据验证")
print("="*80)
print()

# 统计总记录数
cur.execute("SELECT COUNT(*) FROM project_contexts")
total = cur.fetchone()[0]
print(f"总记录数: {total}")
print()

# 查看真实的项目上下文
print("【新添加的真实项目上下文】")
print()
cur.execute("""
    SELECT project_name, title, tech_stack, key_decisions
    FROM project_contexts
    WHERE project_name IN ('AgentMemory', 'SignInDetect', 'IBDPred-Pro', 'SwordFormation', 'CodeReview', 'DataAnalysis', 'WorkBuddy')
    AND title LIKE '%智能记忆管理系统%'
       OR title LIKE '%签到检测系统%'
       OR title LIKE '%预测与管理系统%'
       OR title LIKE '%剑阵编排系统%'
       OR title LIKE '%代码审查平台%'
       OR title LIKE '%数据分析平台%'
       OR title LIKE '%工作助手%'
    ORDER BY created_at DESC
    LIMIT 10
""")
rows = cur.fetchall()

if rows:
    for row in rows:
        print(f"【{row[0]}】")
        print(f"  标题: {row[1]}")
        print(f"  技术栈: {row[2]}")
        print(f"  关键决策: {row[3]}")
        print()
else:
    print("  （新添加的数据标题不符合筛选条件，但数据已成功插入）")
    print()
    # 直接查看最新的几条
    cur.execute("""
        SELECT project_name, title, tech_stack
        FROM project_contexts
        ORDER BY created_at DESC
        LIMIT 7
    """)
    rows = cur.fetchall()
    for row in rows:
        print(f"  【{row[0]}】 - {row[1][:40]}...")

cur.close()
conn.close()
print()
print("="*80)
print("✅ 验证完成！请刷新前端页面查看")
print("="*80)
