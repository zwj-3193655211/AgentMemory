#!/usr/bin/env python3
"""恢复用户指定的记忆内容"""
import psycopg2
import psycopg2.extras
import json
import uuid
import random
from datetime import datetime, timedelta

conn = psycopg2.connect(
    host='localhost', port=5500, dbname='agentmemory',
    user='agentmemory', password='agentmemory'
)
cur = conn.cursor()

# 时间范围：2025年11月到2026年5月
start_date = datetime(2025, 11, 1)
end_date = datetime(2026, 5, 10)

def random_dt():
    days = random.randint(0, (end_date - start_date).days)
    return start_date + timedelta(days=days, hours=random.randint(8, 22),
                                 minutes=random.randint(0, 59))

def to_pg_array(arr):
    """将Python列表转换为PostgreSQL数组格式"""
    escaped = [str(x).replace('"', '\\"') for x in arr]
    return '{' + ','.join(f'"{x}"' for x in escaped) + '}'

# ===== 用户画像（8条）=====
user_profiles = [
    {"title": "网络配置", "category": "network",
     "items": [{"key": "network", "value": "优先使用国内镜像源、失败时查找解决方法、必要时使用VPN"}],
     "confidence": 0.9},
    {"title": "开发工具", "category": "tool",
     "items": [{"key": "ide", "value": "IDE: Vscode"}, {"key": "terminal", "value": "终端: Bash/Powershell"}],
     "confidence": 0.9},
    {"title": "AI模型偏好", "category": "ai",
     "items": [{"key": "model", "value": "GLM、Claude"}],
     "confidence": 0.85},
    {"title": "工作流程", "category": "workflow",
     "items": [{"key": "process", "value": "迭代开发、调试优先、可视化"}],
     "confidence": 0.85},
    {"title": "项目类型", "category": "project",
     "items": [{"key": "type", "value": "前端项目、视频项目、AI项目、算法项目、后端项目"}],
     "confidence": 0.8},
    {"title": "问题处理", "category": "problem",
     "items": [{"key": "approach", "value": "主动修复问题、分析错误原因、持续优化"}],
     "confidence": 0.9},
    {"title": "技术概览", "category": "tech",
     "items": [{"key": "overview", "value": "对话记录1235条、数据来源Claude Code+WorkBuddy"}],
     "confidence": 0.95},
    {"title": "删除操作习惯", "category": "habit",
     "items": [{"key": "delete", "value": "删除前备份、删除前确认、优先放入回收站、确认后再清空"}],
     "confidence": 0.9},
]

# ===== 技能沉淀（8条）=====
skills = [
    {"title": "Vue 3 + Element Plus 网页开发", "skill_type": "library-api",
     "description": "使用 Vue 3 + Element Plus 进行网页开发，组件化设计，响应式布局",
     "steps": ["项目初始化", "组件开发", "状态管理", "样式优化"],
     "tags": ["Vue", "Element Plus"]},
    {"title": "Remotion视频编辑与TTS旁白集成", "skill_type": "library-api",
     "description": "使用 Remotion 进行视频编辑，集成 TTS 旁白生成",
     "steps": ["项目创建", "视频编辑", "TTS集成", "渲染输出"],
     "tags": ["Remotion", "TTS"]},
    {"title": "Claude Code与多Agent协作", "skill_type": "automation",
     "description": "使用 Claude Code 与多个 Agent 进行协作，提高开发效率",
     "steps": ["环境配置", "任务分配", "结果整合", "质量检查"],
     "tags": ["Claude", "Multi-Agent"]},
    {"title": "算法可视化与视频制作", "skill_type": "data-analysis",
     "description": "将算法过程可视化并制作成教学视频",
     "steps": ["算法实现", "可视化渲染", "视频剪辑", "旁白录制"],
     "tags": ["Algorithm", "Visualization"]},
    {"title": "视频音频同步与编辑", "skill_type": "library-api",
     "description": "视频和音频的同步剪辑处理",
     "steps": ["素材导入", "音视频对齐", "剪辑处理", "导出渲染"],
     "tags": ["Video", "Audio"]},
    {"title": "常见开发问题调试技巧", "skill_type": "troubleshooting",
     "description": "常见开发问题的调试方法和解决技巧",
     "steps": ["问题定位", "日志分析", "断点调试", "方案验证"],
     "tags": ["Debug", "Troubleshooting"]},
    {"title": "Docker容器化与部署", "skill_type": "infra-ops",
     "description": "使用 Docker 进行应用容器化和部署",
     "steps": ["Dockerfile编写", "镜像构建", "容器编排", "部署验证"],
     "tags": ["Docker", "Deployment"]},
    {"title": "npm国内镜像配置与依赖管理", "skill_type": "scaffold",
     "description": "配置 npm 国内镜像源，解决依赖安装问题",
     "steps": ["镜像配置", "依赖安装", "版本管理", "缓存清理"],
     "tags": ["npm", "Mirror"]},
]

# 插入用户画像
print("恢复用户画像...")
for data in user_profiles:
    dt = random_dt()
    cur.execute("""
        INSERT INTO user_profiles (id, title, category, items, confidence, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
    """, (str(uuid.uuid4()), data["title"], data["category"],
          json.dumps(data["items"], ensure_ascii=False),
          data["confidence"], dt, dt + timedelta(days=random.randint(0, 7))))
    print(f"  ✅ {data['title']}")

# 插入技能 - steps是JSONB, tags是ARRAY
print("\n恢复技能沉淀...")
for data in skills:
    dt = random_dt()
    steps_json = json.dumps(data["steps"], ensure_ascii=False)
    tags_str = to_pg_array(data["tags"])
    cur.execute("""
        INSERT INTO skills (id, title, skill_type, description, steps, tags, created_at)
        VALUES (%s, %s, %s, %s, %s::jsonb, %s::text[], %s)
    """, (str(uuid.uuid4()), data["title"], data["skill_type"], data["description"],
          steps_json, tags_str, dt))
    print(f"  ✅ {data['title']}")

conn.commit()

# 统计
print("\n" + "=" * 50)
print("恢复完成！")
for table in ['user_profiles', 'skills']:
    cur.execute(f"SELECT COUNT(*) FROM {table}")
    count = cur.fetchone()[0]
    print(f"{table}: {count} 条")

cur.close()
conn.close()
print("\n时间戳已随机分布在 2025年11月 ~ 2026年5月")
