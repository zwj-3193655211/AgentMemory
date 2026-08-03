#!/usr/bin/env python3
import psycopg2
import json

DB_CONFIG = {
    "host": "localhost",
    "port": 5500,
    "database": "agentmemory",
    "user": "agentmemory",
    "password": "agentmemory"
}

conn = psycopg2.connect(**DB_CONFIG)
cursor = conn.cursor()

# 删除重复的低质量记录
duplicates_to_delete = [
    'wb-.workbuddy-20260524000007',  # 重复
    'cla-67d999b2',  # 低质量提取
]

for dup_id in duplicates_to_delete:
    cursor.execute("DELETE FROM project_contexts WHERE id = %s", (dup_id,))
    print(f"删除: {dup_id}")

# 更新 project_name 为文件名的记录，改为更有意义的项目名
updates = {
    'wor-8cbf881d': ('AgentMemory', 'saveSessionSummary TODO 修复'),
    'wor-b577d9f2': ('AgentMemory', '端口占用问题修复'),
    'wor-773e7271': ('晨读晨练签到打卡检测', '晨读检测Bug修复'),
    'wor-3d20655c': ('AgentMemory', 'DATABASE_PASSWORD 修复'),
    'wor-2637fb3f': ('AgentMemory', '错误纠正模块反馈'),
    'wor-81c6de31': ('AgentMemory', 'Ollama模型丢失问题'),
    'wor-0301e104': ('Java作业辅助', 'Spring项目选题建议'),
    'wor-6e59971b': ('大创项目', '知识图谱学业规划助手'),
    'wor-d5beffbb': ('晨读晨练签到打卡检测', '打卡数据统计'),
    'wor-97da7319': ('晨读晨练签到打卡检测', '第二课堂评分统计'),
    'wor-e6d691e7': ('AI动态推送', '每日AI动态整理'),
    'wor-701d51ff': ('AI动态推送', '每周AI动态推送'),
    'wor-87e85829': ('AI动态推送', '每周AI动态推送'),
    'wor-9f23028e': ('WorkBuddy初始化', 'Bootstrap流程 + 蓝桥杯练习'),
    'wor-85ba225b': ('AgentMemory', 'ECharts仪表盘修复'),
    'cla-9f22cc7a': ('AgentMemory', '多Agent适配优化'),
    'cla-9b826e2f': ('face-retrieval-system', '人脸检索系统'),
    'cla-ad113b6a': ('face-retrieval-system', '环境配置'),
}

for rec_id, (proj_name, title) in updates.items():
    cursor.execute("""
        UPDATE project_contexts
        SET project_name = %s, title = %s
        WHERE id = %s
    """, (proj_name, title, rec_id))
    print(f"更新: {rec_id} -> {proj_name}")

conn.commit()

# 显示最终结果
cursor.execute("""
    SELECT id, title, project_name
    FROM project_contexts
    ORDER BY project_name, title
""")
rows = cursor.fetchall()
print(f"\n✅ 最终结果 ({len(rows)} 条):")
current_proj = None
for row in rows:
    if row[2] != current_proj:
        current_proj = row[2]
        print(f"\n【{current_proj}】")
    print(f"  - {row[1]}")

conn.close()
