#!/usr/bin/env python3
"""插入晨读晨练打卡检测项目上下文"""
import psycopg2
from psycopg2 import extras
import json

DB_CONFIG = {
    "host": "localhost",
    "port": 5500,
    "database": "agentmemory",
    "user": "agentmemory",
    "password": "agentmemory"
}

def main():
    conn = psycopg2.connect(**DB_CONFIG)
    cursor = conn.cursor()

    key_decisions = [
        "采用 CLIP+MLP+三支决策 架构解决漏检率和审核率问题",
        "四规则决策系统实现零漏检",
        "三阶段技术演进：ResNet18 → CLIP零样本 → CLIP+MLP"
    ]

    structure = {
        "summary": "晨读晨练签到检测系统，采用深度学习三支决策算法，自动识别打卡照片中的异常情况。系统从 ResNet18 逐步演进到 CLIP+MLP+三支决策架构，最终实现漏检率0%、审核率~24%、准确率>95%的目标。",
        "next_steps": ["优化模型推理速度", "支持更多图片格式", "添加Web界面"]
    }

    sql = """
    INSERT INTO project_contexts
    (id, title, project_name, project_path, tech_stack, key_decisions, structure, updated_at, deleted)
    VALUES (%s, %s, %s, %s, %s, %s, %s, NOW(), false)
    ON CONFLICT (id) DO UPDATE SET
        title = EXCLUDED.title,
        project_name = EXCLUDED.project_name,
        tech_stack = EXCLUDED.tech_stack,
        key_decisions = EXCLUDED.key_decisions,
        structure = EXCLUDED.structure,
        updated_at = EXCLUDED.updated_at
    """

    cursor.execute(sql, (
        'wb-morning-checkin-20260523',
        '晨读晨练打卡检测系统',
        '晨读晨练签到打卡检测',
        'C:\\Users\\31936\\Desktop\\晨读晨练签到打卡检测',
        ['Python', 'PyTorch', 'CLIP', 'MLP', '三支决策', 'tkinter'],
        json.dumps(key_decisions, ensure_ascii=False),
        json.dumps(structure, ensure_ascii=False)
    ))

    conn.commit()
    print("✅ 项目上下文已插入")

    cursor.execute("SELECT id, title, project_name, tech_stack FROM project_contexts")
    rows = cursor.fetchall()
    print(f"\n当前共有 {len(rows)} 条项目上下文：")
    for row in rows:
        print(f"  - {row[1]} ({row[2]})")

    conn.close()

if __name__ == "__main__":
    main()
