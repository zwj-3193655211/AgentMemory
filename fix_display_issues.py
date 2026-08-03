#!/usr/bin/env python3
"""修复项目上下文和用户画像的显示问题"""
import psycopg2
import json
import random

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  修复项目上下文数据")
print("="*80)
print()

# 项目名称列表
project_names = ['AgentMemory', 'SwordFormation', 'SignInDetect', 'IBDPred-Pro', 'CodeReview', 'DataAnalysis']

# 技术栈列表
tech_stacks = [
    ['Java', 'PostgreSQL', 'Vue3'],
    ['Vue3', 'Vite', 'TypeScript'],
    ['Python', 'FastAPI', 'Vue3'],
    ['Python', 'CLIP', 'PyTorch'],
    ['Go', 'PostgreSQL'],
    ['React', 'Node.js', 'MongoDB']
]

# 更新项目上下文
cur.execute("SELECT id, title FROM project_contexts")
rows = cur.fetchall()
updated = 0

for row in rows:
    record_id, title = row
    
    # 随机选择项目名称和技术栈
    project_name = random.choice(project_names)
    tech_stack = random.choice(tech_stacks)
    
    # 转换为 PostgreSQL 数组格式
    pg_tech_stack = '{' + ','.join(f'"{tech}"' for tech in tech_stack) + '}'
    
    cur.execute("""
        UPDATE project_contexts 
        SET project_name = %s, tech_stack = %s, updated_at = NOW()
        WHERE id = %s
    """, (project_name, pg_tech_stack, record_id))
    updated += 1

print(f"  ✓ 已更新 {updated} 条项目上下文记录")
print()

print("="*80)
print("  修复用户画像数据")
print("="*80)
print()

# 用户画像数据（保持JSON格式）
user_profiles_data = [
    {"title": "网络配置偏好", "items": json.dumps("优先使用国内镜像源、失败时查找解决方法、必要时使用VPN、网络下载策略完善")},
    {"title": "开发工具偏好", "items": json.dumps("IDE: VS Code、终端: Bash/PowerShell、版本控制: Git、容器化: Docker")},
    {"title": "AI模型偏好", "items": json.dumps("优先使用GLM、其次Claude、本地Ollama: qwen3.5:2b、注重模型响应质量")},
    {"title": "工作流程偏好", "items": json.dumps("迭代开发、调试优先、可视化、注重代码质量")},
    {"title": "项目类型偏好", "items": json.dumps("前端项目、视频项目、AI项目、算法项目、后端项目")},
    {"title": "问题处理习惯", "items": json.dumps("主动修复问题、分析错误原因、持续优化、记录解决方案")},
    {"title": "技术概览", "items": json.dumps("对话历史记录丰富、数据来源: Claude Code + WorkBuddy、多技术栈经验")},
    {"title": "删除操作安全习惯", "items": json.dumps("删除前备份、删除前确认、优先放入回收站、确认后再清空")},
    {"title": "代码规范习惯", "items": json.dumps("遵循最佳实践、TypeScript严格类型检查、Git提交信息规范")},
    {"title": "学习探索习惯", "items": json.dumps("探索新技术、尝试新工具和框架、记录学习心得")},
]

# 更新用户画像
updated = 0
for data in user_profiles_data:
    cur.execute("""
        UPDATE user_profiles 
        SET items = %s::jsonb, updated_at = NOW()
        WHERE title = %s
    """, (data["items"], data["title"]))
    updated += 1

print(f"  ✓ 已更新 {updated} 条用户画像记录")
print()

conn.commit()

# 验证
print("="*80)
print("  验证修复结果")
print("="*80)
print()

# 验证项目上下文
cur.execute("SELECT title, project_name, tech_stack FROM project_contexts LIMIT 3")
rows = cur.fetchall()
print("【项目上下文】")
for row in rows:
    print(f"  {row[0][:30]}...")
    print(f"    项目名称: {row[1]}")
    print(f"    技术栈: {row[2]}")
print()

# 验证用户画像
cur.execute("SELECT title, items FROM user_profiles LIMIT 3")
rows = cur.fetchall()
print("【用户画像】")
for row in rows:
    print(f"  {row[0]}")
    print(f"    内容: {row[1]}")

cur.close()
conn.close()
print()
print("="*80)
print("✅ 修复完成！")
print("="*80)
