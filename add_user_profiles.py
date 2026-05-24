#!/usr/bin/env python3
"""补充用户画像数据 - 适合答辩展示"""
import psycopg2
import json
import uuid
import random
from datetime import datetime, timedelta

print("="*80)
print("  补充用户画像数据")
print("="*80)
print()

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

# 生成随机时间戳（分布在过去6个月内）
def random_dt():
    days_ago = random.randint(30, 180)
    return datetime.now() - timedelta(days=days_ago, hours=random.randint(8, 22))

# 用户画像数据
user_profiles = [
    {
        "title": "网络配置偏好",
        "category": "环境",
        "items": {"items": ["优先使用国内镜像源", "失败时查找解决方法", "必要时使用VPN", "网络下载策略完善"]}
    },
    {
        "title": "开发工具偏好",
        "category": "工具",
        "items": {"items": ["IDE: VS Code", "终端: Bash/PowerShell", "版本控制: Git", "容器化: Docker"]}
    },
    {
        "title": "AI模型偏好",
        "category": "AI",
        "items": {"items": ["优先使用GLM", "其次Claude", "本地Ollama: qwen3.5:2b", "注重模型响应质量"]}
    },
    {
        "title": "工作流程偏好",
        "category": "工作",
        "items": {"items": ["迭代开发", "调试优先", "可视化", "注重代码质量"]}
    },
    {
        "title": "项目类型偏好",
        "category": "项目",
        "items": {"items": ["前端项目", "视频项目", "AI项目", "算法项目", "后端项目"]}
    },
    {
        "title": "问题处理习惯",
        "category": "习惯",
        "items": {"items": ["主动修复问题", "分析错误原因", "持续优化", "记录解决方案"]}
    },
    {
        "title": "技术概览",
        "category": "技能",
        "items": {"items": ["对话历史记录丰富", "数据来源: Claude Code + WorkBuddy", "多技术栈经验"]}
    },
    {
        "title": "删除操作安全习惯",
        "category": "安全",
        "items": {"items": ["删除前备份", "删除前确认", "优先放入回收站", "确认后再清空"]}
    },
    {
        "title": "代码规范习惯",
        "category": "编码",
        "items": {"items": ["遵循最佳实践", "TypeScript严格类型检查", "Git提交信息规范"]}
    },
    {
        "title": "学习探索习惯",
        "category": "学习",
        "items": {"items": ["探索新技术", "尝试新工具和框架", "记录学习心得"]}
    },
]

print("插入用户画像数据...")
for data in user_profiles:
    dt = random_dt()
    cur.execute("""
        INSERT INTO user_profiles
        (id, title, category, items, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s)
    """, (
        str(uuid.uuid4()),
        data["title"],
        data["category"],
        json.dumps(data["items"]),
        dt,
        dt
    ))
    print(f"  ✓ {data['title']}")

conn.commit()

# 显示结果
print()
print("="*80)
print("  用户画像补充完成")
print("="*80)
cur.execute("SELECT COUNT(*) FROM user_profiles")
count = cur.fetchone()[0]
print(f"  当前用户画像总数: {count}")
print()
print("各记忆表最终统计:")
tables = ['error_corrections', 'best_practices', 'skills', 'project_contexts', 'user_profiles']
for table in tables:
    cur.execute(f"SELECT COUNT(*) FROM {table}")
    cnt = cur.fetchone()[0]
    print(f"  {table:20s}: {cnt:3d} 条")

cur.close()
conn.close()
print()
print("✅ 用户画像已补充，数据时间戳已随机分布")
print("="*80)
