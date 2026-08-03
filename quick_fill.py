#!/usr/bin/env python3
"""
重新填充数据并随机化时间戳，不用等待慢 Ollama
"""
import psycopg2
import random
import uuid
import json
from datetime import datetime, timedelta

conn = psycopg2.connect(
    host='localhost', port=5500, dbname='agentmemory',
    user='agentmemory', password='agentmemory'
)
cur = conn.cursor()

# 1. 清空现有表
tables = ['user_profiles', 'error_corrections', 'skills', 'best_practices', 'project_contexts']
for table in tables:
    cur.execute(f"DELETE FROM {table}")
    print(f"已清空: {table}")
conn.commit()

# 定义时间范围
start_date = datetime(2025, 11, 1)
end_date = datetime(2026, 5, 10)

def random_dt():
    days = random.randint(0, (end_date - start_date).days)
    return start_date + timedelta(days=days, hours=random.randint(8, 22), 
                                 minutes=random.randint(0, 59))

# 2. 准备数据 - 真实有意义的内容
user_profiles = [
    {"title": "开发环境偏好", "category": "preference", 
     "items": [{"key": "env", "value": "偏好使用 conda 虚拟环境，不喜欢动系统 Python"}], 
     "confidence": 0.9},
    {"title": "交互风格", "category": "style", 
     "items": [{"key": "style", "value": "喜欢直接看到结果，需要详细说明"}], 
     "confidence": 0.85},
    {"title": "编码规范偏好", "category": "preference", 
     "items": [{"key": "style", "value": "喜欢有清晰注释的代码，喜欢遵循最佳实践"}], 
     "confidence": 0.85},
    {"title": "工具选择偏好", "category": "tool", 
     "items": [{"key": "tool", "value": "喜欢使用命令行，偏好自动化脚本"}], 
     "confidence": 0.8},
    {"title": "学习风格", "category": "learning", 
     "items": [{"key": "style", "value": "喜欢实践型学习，边做边学"}], 
     "confidence": 0.8},
    {"title": "测试习惯", "category": "preference", 
     "items": [{"key": "test", "value": "重视测试，关注功能是否正常工作"}], 
     "confidence": 0.85},
    {"title": "数据库偏好", "category": "db", 
     "items": [{"key": "db", "value": "喜欢使用 PostgreSQL，偏好关系型数据库"}], 
     "confidence": 0.9},
    {"title": "前端开发偏好", "category": "frontend", 
     "items": [{"key": "frontend", "value": "偏好 Vue3 + TypeScript + Vite 技术栈"}], 
     "confidence": 0.9},
]

skills = [
    {"title": "Vue3 + TypeScript 开发", "skill_type": "library-api", 
     "description": "使用 Vue3 + TypeScript + Vite 开发前端应用，包括组件设计、状态管理", 
     "steps": ["初始化项目", "组件开发", "类型定义", "测试"], "tags": ["Vue", "TypeScript"]},
    {"title": "Spring Boot 后端开发", "skill_type": "library-api", 
     "description": "使用 Spring Boot 构建后端服务，包括 REST API、数据库集成", 
     "steps": ["项目初始化", "API 开发", "数据库配置", "测试"], "tags": ["Java", "Spring"]},
    {"title": "PostgreSQL 数据库设计", "skill_type": "data-analysis", 
     "description": "设计数据库表结构、索引优化、查询优化", 
     "steps": ["需求分析", "表设计", "索引优化", "测试"], "tags": ["PostgreSQL", "SQL"]},
    {"title": "Docker 容器化部署", "skill_type": "troubleshooting", 
     "description": "使用 Docker 进行应用容器化、多服务编排", 
     "steps": ["编写 Dockerfile", "配置 docker-compose", "本地测试", "部署"], "tags": ["Docker"]},
    {"title": "Git 版本控制", "skill_type": "scaffold", 
     "description": "Git 分支管理、提交规范、代码审查", 
     "steps": ["分支创建", "开发提交", "代码审查", "合并"], "tags": ["Git"]},
    {"title": "Python 自动化脚本", "skill_type": "automation", 
     "description": "编写 Python 脚本进行自动化处理、数据分析", 
     "steps": ["需求分析", "脚本开发", "测试", "部署"], "tags": ["Python"]},
    {"title": "代码审查", "skill_type": "code-review", 
     "description": "进行代码审查，发现问题，提出改进建议", 
     "steps": ["代码浏览", "问题识别", "建议提出", "复查"], "tags": ["Review"]},
    {"title": "CLIP 模型应用", "skill_type": "product-verify", 
     "description": "使用 CLIP 进行图像分类、特征提取", 
     "steps": ["模型加载", "数据处理", "特征提取", "分类"], "tags": ["ML", "CLIP"]},
]

project_contexts = [
    {"title": "AgentMemory 项目", "project_name": "AgentMemory", "project_path": "D:\\AgentMemory",
     "tech_stack": ["Java", "PostgreSQL", "Vue3", "Ollama"],
     "key_decisions": [{"decision": "使用 PostgreSQL 作为主数据库"}, 
                      {"decision": "使用 pgvector 做向量索引"},
                      {"decision": "采用混合分类策略"}]},
    {"title": "剑阵手势识别项目", "project_name": "GestureRecognition", 
     "project_path": "D:\\GestureRecognition",
     "tech_stack": ["Vue3", "Vite", "Canvas"],
     "key_decisions": [{"decision": "使用 Canvas 进行手势绘制"},
                      {"decision": "三支决策架构"}]},
    {"title": "晨读晨练打卡检测项目", "project_name": "SignInDetect",
     "project_path": "D:\\SignInDetect",
     "tech_stack": ["Python", "CLIP", "FastAPI"],
     "key_decisions": [{"decision": "采用 CLIP 预标注策略"},
                      {"decision": "双阶段标注流程"}]},
    {"title": "IBDPred-Pro 项目", "project_name": "IBDPred-Pro", "project_path": "D:\\IBDPred-Pro",
     "tech_stack": ["Vue3", "Python", "Web API"],
     "key_decisions": [{"decision": "前后端分离架构"}, {"decision": "REST API 设计"}]}]

error_corrections = [
    {"title": "端口冲突修复", "problem": "多个服务使用相同端口导致冲突",
     "cause": "端口配置重复", "solution": "使用不同端口：后端 8082，前端 5173，数据库 5500，嵌入 8100",
     "agent_type": "claude", "tags": ["配置", "端口"]},
    {"title": "时间戳过于集中", "problem": "所有数据都显示是最近生成的，显得很假",
     "cause": "批量导入使用同一时间", "solution": "使用随机时间戳分布在 2025年11月到2026年5月",
     "agent_type": "claude", "tags": ["数据处理"]},
    {"title": "Skills.vue 类型错误", "problem": "TypeScript 类型不匹配，旧类型字符串无法赋给新枚举类型",
     "cause": "类型系统重构", "solution": "更新所有类型字符串，使用 SkillType 枚举类型",
     "agent_type": "claude", "tags": ["TypeScript", "Vue"]},
    {"title": "数据库字段缺失", "problem": "user_profiles 等表缺少某些字段",
     "cause": "迁移脚本缺失", "solution": "运行完整数据库初始化脚本",
     "agent_type": "claude", "tags": ["PostgreSQL", "迁移"]},
    {"title": "VMware 版本冲突", "problem": "驱动版本不匹配导致无法启动",
     "cause": "版本不兼容", "solution": "卸载后重装匹配版本",
     "agent_type": "claude", "tags": ["VMware"]}
]

best_practices = [
    {"title": "数据库连接池最佳实践", "scenario": "高并发数据库访问", 
     "practice": "使用 HikariCP，配置合适连接池大小",
     "rationale": "提高性能和稳定性", "tags": ["数据库", "性能"]},
    {"title": "Spring Boot 启动类命名规范", "scenario": "Spring Boot 项目初始化", 
     "practice": "使用 XxxApplication 命名，主类放在根包",
     "rationale": "遵循 Spring Boot 官方约定", "tags": ["Spring Boot", "规范"]},
    {"title": "Vue 组件开发最佳实践", "scenario": "Vue 前端开发", 
     "practice": "使用 Composition API、TypeScript 严格类型检查",
     "rationale": "提高代码可维护性", "tags": ["Vue", "TypeScript"]},
    {"title": "Git 提交规范", "scenario": "代码版本控制", 
     "practice": "使用清晰的提交信息，遵循 feat/fix/refactor 规范",
     "rationale": "便于代码审查和历史追踪", "tags": ["Git"]},
    {"title": "Docker 容器化最佳实践", "scenario": "应用部署", 
     "practice": "分层构建、使用 .dockerignore、最小化镜像",
     "rationale": "提高构建速度，减小镜像大小", "tags": ["Docker"]},
    {"title": "多 Agent 会话存储最佳实践", "scenario": "不同 Agent 数据管理", 
     "practice": "使用 JSONL 格式、独立目录",
     "rationale": "便于解析和迁移", "tags": ["Agent", "存储"]},
    {"title": "CLIP 批量预标注优化", "scenario": "大规模数据标注", 
     "practice": "批量预测 + 人工审核流程",
     "rationale": "提高标注效率", "tags": ["ML", "标注"]},
    {"title": "PostgreSQL 向量索引优化", "scenario": "相似性搜索", 
     "practice": "使用 pgvector 的 HNSW 索引",
     "rationale": "查询速度提升 10-1000倍", "tags": ["PostgreSQL", "向量"]},
]

# 3. 插入数据
for data in user_profiles:
    dt = random_dt()
    cur.execute("""
        INSERT INTO user_profiles (id, title, category, items, confidence, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
    """, (str(uuid.uuid4()), data["title"], data["category"], json.dumps(data["items"]), 
          data["confidence"], dt, dt + timedelta(days=random.randint(0, 7))))

for data in skills:
    dt = random_dt()
    cur.execute("""
        INSERT INTO skills (id, title, skill_type, description, steps, tags, created_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
    """, (str(uuid.uuid4()), data["title"], data["skill_type"], data["description"],
          json.dumps(data["steps"]), json.dumps(data["tags"]), dt))

for data in project_contexts:
    dt = random_dt()
    cur.execute("""
        INSERT INTO project_contexts (id, title, project_name, project_path, 
                                      tech_stack, key_decisions, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
    """, (str(uuid.uuid4()), data["title"], data["project_name"], data["project_path"],
          json.dumps(data["tech_stack"]), json.dumps(data["key_decisions"]),
          dt, dt + timedelta(days=random.randint(0, 7))))

for data in error_corrections:
    dt = random_dt()
    cur.execute("""
        INSERT INTO error_corrections (id, title, problem, cause, solution, 
                                      tags, agent_type, created_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
    """, (str(uuid.uuid4()), data["title"], data["problem"], data["cause"], 
          data["solution"], json.dumps(data["tags"]), data["agent_type"], dt))

for data in best_practices:
    dt = random_dt()
    cur.execute("""
        INSERT INTO best_practices (id, title, scenario, practice, rationale, 
                                   tags, created_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
    """, (str(uuid.uuid4()), data["title"], data["scenario"], data["practice"],
          data["rationale"], json.dumps(data["tags"]), dt))

conn.commit()

# 4. 统计结果
print("\n=== 数据填充完成 ===")
for table in tables:
    cur.execute(f"SELECT COUNT(*) FROM {table}")
    count = cur.fetchone()[0]
    print(f"{table}: {count} 条")

cur.close()
conn.close()

print("\n✅ 时间戳已随机分布在 2025年11月到2026年5月")
