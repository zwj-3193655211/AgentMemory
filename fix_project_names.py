#!/usr/bin/env python3
"""修复新添加记录的project_name字段"""
import psycopg2

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  修复 project_name 为 NULL 的记录")
print("="*80)
print()

# 查找 project_name 为 NULL 的记录
cur.execute("""
    SELECT id, title FROM project_contexts
    WHERE project_name IS NULL
    ORDER BY created_at DESC
""")
rows = cur.fetchall()

print(f"找到 {len(rows)} 条 project_name 为 NULL 的记录")
print()

# 定义标题到项目名的映射
title_to_project = {
    "晨读晨练签到检测系统": "SignInDetect",
    "SwordFormation - 剑阵编排系统": "SwordFormation",
    "IBDP课程预测与管理系统": "IBDPred-Pro",
    "DataAnalysis - 数据分析平台": "DataAnalysis",
    "CodeReview - AI代码审查平台": "CodeReview",
    "WorkBuddy - 智能工作助手": "WorkBuddy",
    "AgentMemory - 智能记忆管理系统": "AgentMemory"
}

fixed = 0
for row in rows:
    record_id, title = row
    project_name = None
    
    # 根据标题匹配项目名
    for key, value in title_to_project.items():
        if key in title:
            project_name = value
            break
    
    if project_name:
        cur.execute("""
            UPDATE project_contexts
            SET project_name = %s
            WHERE id = %s
        """, (project_name, record_id))
        fixed += 1
        print(f"  ✓ {title[:40]}... → {project_name}")
    else:
        print(f"  ✗ {title[:40]}... → 未找到匹配项目名")

conn.commit()

print()
print(f"成功修复 {fixed} 条记录")

# 验证
print()
print("="*80)
print("  验证修复结果")
print("="*80)
print()

cur.execute("""
    SELECT DISTINCT project_name, COUNT(*)
    FROM project_contexts
    GROUP BY project_name
    ORDER BY COUNT(*) DESC
""")
rows = cur.fetchall()
for row in rows:
    print(f"  {row[0]}: {row[1]}条")

cur.close()
conn.close()
print()
print("="*80)
print("✅ 修复完成！请刷新前端页面")
print("="*80)
