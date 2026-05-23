#!/usr/bin/env python3
import psycopg2
import json
from datetime import datetime

DB_CONFIG = {
    "host": "localhost",
    "port": 5500,
    "database": "agentmemory",
    "user": "agentmemory",
    "password": "agentmemory"
}

conn = psycopg2.connect(**DB_CONFIG)
cursor = conn.cursor()

cursor.execute("""
    INSERT INTO project_contexts
    (id, title, project_name, project_path, tech_stack, key_decisions, structure, updated_at, deleted)
    VALUES
    ('am-main-context',
     'AgentMemory 多Agent记忆管理系统',
     'AgentMemory',
     'C:\\Users\\31936\\Desktop\\AgentMemory',
     %s,
     %s,
     %s,
     NOW(),
     false)
    ON CONFLICT (id) DO UPDATE SET
        title = EXCLUDED.title,
        tech_stack = EXCLUDED.tech_stack,
        key_decisions = EXCLUDED.key_decisions,
        structure = EXCLUDED.structure,
        updated_at = EXCLUDED.updated_at
""", (
    ['Java 17', 'Spring Boot', 'PostgreSQL', 'pgvector', 'Vue 3', 'Element Plus', 'Vite', 'Python Flask', 'CLIP', 'Docker'],
    json.dumps(["采用三级缓存架构提升查询性能", "pgvector HNSW索引实现10-1000倍向量搜索加速", "混合分类系统：规则+向量+LLM三层架构", "支持7种Agent数据源统一接入"], ensure_ascii=False),
    json.dumps({
        "summary": "AgentMemory是一个多Agent记忆管理系统，为AI编程助手提供持久化记忆存储与智能检索能力。系统支持Claude Code、iFlow、Qwen、OpenClaw、Codex CLI、Crush CLI、WorkBuddy等7种Agent的对话记录自动采集与存储，采用CLIP模型实现语义向量搜索。",
        "next_steps": ["优化向量检索速度", "增加更多Agent支持", "添加Web管理界面", "实现会话摘要自动生成"]
    }, ensure_ascii=False)
))

conn.commit()
print("✅ AgentMemory 项目上下文已添加")

cursor.execute("SELECT id, title, project_name FROM project_contexts WHERE id = 'am-main-context'")
row = cursor.fetchone()
print(f"验证: {row}")

conn.close()
