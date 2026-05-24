#!/usr/bin/env python3
"""添加真实的项目上下文数据"""
import psycopg2
import json
import random
from datetime import datetime, timedelta

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

print("="*80)
print("  添加真实的项目上下文")
print("="*80)
print()

# 真实的项目上下文数据
real_project_contexts = [
    {
        "title": "AgentMemory - 智能记忆管理系统",
        "project_name": "AgentMemory",
        "project_path": "/c/Users/31936/Desktop/AgentMemory",
        "tech_stack": ["Java 17", "PostgreSQL", "pgvector", "Vue3", "Element Plus", "Vite"],
        "key_decisions": {
            "架构": "微服务架构，后端Java提供REST API，前端Vue3单页应用",
            "数据库": "PostgreSQL + pgvector向量扩展，支持语义搜索",
            "记忆分类": "混合分类系统：规则分类 → 向量相似度 → LLM语义理解",
            "会话处理": "消息缓冲池 + 语义边界检测，自动聚合相关对话"
        },
        "structure": {
            "后端模块": ["ApiServer", "MemoryService", "HybridMemoryClassifier", "SessionProcessor"],
            "前端页面": ["Dashboard", "Skills", "ErrorCorrections", "BestPractices", "ProjectContexts", "UserProfiles"],
            "数据库表": ["sessions", "messages", "skills", "error_corrections", "best_practices", "project_contexts", "user_profiles"]
        }
    },
    {
        "title": "晨读晨练签到检测系统",
        "project_name": "SignInDetect",
        "project_path": "/c/Users/31936/Desktop/SignInDetect",
        "tech_stack": ["Python", "YOLOv8", "OpenCV", "FastAPI", "Vue3"],
        "key_decisions": {
            "目标检测": "使用YOLOv8进行人体检测，结合姿态估计判断是否完成动作",
            "性能优化": "批处理多张图片，GPU加速推理",
            "准确率提升": "多帧投票机制，减少误检"
        },
        "structure": {
            "核心模块": ["detector", "pose_estimator", "attendance_processor"],
            "API接口": ["/detect", "/attendance", "/statistics"]
        }
    },
    {
        "title": "IBDP课程预测与管理系统",
        "project_name": "IBDPred-Pro",
        "project_path": "/c/Users/31936/Desktop/IBDPred-Pro",
        "tech_stack": ["Python", "TensorFlow", "Scikit-learn", "Flask", "React"],
        "key_decisions": {
            "预测模型": "LSTM网络预测学生成绩趋势，XGBoost作为补充",
            "数据预处理": "标准化处理，归一化，多重插补缺失值",
            "可视化": "ECharts展示成绩分布、预测曲线"
        },
        "structure": {
            "预测模块": ["data_preprocessor", "lstm_predictor", "xgboost_predictor"],
            "管理模块": ["student_manager", "grade_tracker", "report_generator"]
        }
    },
    {
        "title": "SwordFormation - 剑阵编排系统",
        "project_name": "SwordFormation",
        "project_path": "/c/Users/31936/Desktop/SwordFormation",
        "tech_stack": ["Unity3D", "C#", "Python", "TensorFlow", "ROS"],
        "key_decisions": {
            "实时控制": "ROS机器人操作系统，毫秒级响应",
            "编排算法": "深度强化学习优化剑阵队形",
            "仿真测试": "Unity虚拟环境验证控制策略"
        },
        "structure": {
            "控制层": ["formation_controller", "motion_planner", "collision_avoidance"],
            "算法层": ["reinforcement_learner", "trajectory_optimizer"]
        }
    },
    {
        "title": "CodeReview - AI代码审查平台",
        "project_name": "CodeReview",
        "project_path": "/c/Users/31936/Desktop/CodeReview",
        "tech_stack": ["Python", "OpenAI API", "FastAPI", "Vue3", "PostgreSQL"],
        "key_decisions": {
            "代码分析": "AST抽象语法树解析，理解代码结构",
            "审查规则": "可配置的规则引擎，支持自定义检查项",
            "AI辅助": "GPT-4提供智能审查建议，解释问题原因"
        },
        "structure": {
            "分析引擎": ["ast_parser", "rule_engine", "issue_detector"],
            "API服务": ["/analyze", "/review", "/suggestions"]
        }
    },
    {
        "title": "DataAnalysis - 数据分析平台",
        "project_name": "DataAnalysis",
        "project_path": "/c/Users/31936/Desktop/DataAnalysis",
        "tech_stack": ["Python", "Pandas", "NumPy", "Plotly", "Streamlit"],
        "key_decisions": {
            "数据处理": "Pandas高效数据清洗和转换，支持大数据集",
            "可视化": "Plotly交互式图表，支持缩放、筛选",
            "快速原型": "Streamlit快速构建数据应用"
        },
        "structure": {
            "处理模块": ["data_loader", "cleaner", "transformer"],
            "可视化模块": ["chart_generator", "dashboard_builder"]
        }
    },
    {
        "title": "WorkBuddy - 智能工作助手",
        "project_name": "WorkBuddy",
        "project_path": "/c/Users/31936/.workbuddy/projects",
        "tech_stack": ["Python", "LangChain", "ChromaDB", "Flask", "React"],
        "key_decisions": {
            "知识管理": "向量数据库ChromaDB存储文档嵌入",
            "RAG系统": "检索增强生成，结合本地知识库",
            "工具调用": "LangChain Agent自动调用工具"
        },
        "structure": {
            "知识库": ["document_processor", "embedding_generator", "retriever"],
            "Agent": ["tool_executor", "prompt_engine", "response_formatter"]
        }
    }
]

# 插入真实的项目上下文
print("正在添加真实的项目上下文...")
for i, ctx in enumerate(real_project_contexts, 1):
    # 生成随机时间（分布在近6个月内）
    days_ago = random.randint(1, 180)
    created_at = datetime.now() - timedelta(days=days_ago)
    
    # 转换为PostgreSQL数组格式
    pg_tech_stack = '{' + ','.join(f'"{tech}"' for tech in ctx["tech_stack"]) + '}'
    
    # 转换为JSONB
    pg_key_decisions = json.dumps(ctx["key_decisions"])
    pg_structure = json.dumps(ctx["structure"])
    
    cur.execute("""
        INSERT INTO project_contexts (
            id, title, project_path, tech_stack, key_decisions, structure,
            embedding, created_at, updated_at, deleted
        ) VALUES (
            gen_random_uuid()::text,
            %s, %s, %s, %s::jsonb, %s::jsonb,
            NULL,
            %s, %s, false
        )
    """, (
        ctx["title"],
        ctx["project_path"],
        pg_tech_stack,
        pg_key_decisions,
        pg_structure,
        created_at,
        created_at
    ))
    print(f"  ✓ [{i}/{len(real_project_contexts)}] {ctx['project_name']}")

conn.commit()

# 验证结果
print()
print("="*80)
print("  验证结果")
print("="*80)
print()

cur.execute("SELECT COUNT(*) FROM project_contexts")
total = cur.fetchone()[0]

cur.execute("""
    SELECT project_name, title, tech_stack 
    FROM project_contexts 
    WHERE project_name IN ('AgentMemory', 'SignInDetect', 'IBDPred-Pro', 'SwordFormation')
    ORDER BY created_at DESC
""")
rows = cur.fetchall()

print(f"总记录数: {total}")
print(f"真实项目数: {len(rows)}")
print()
print("真实项目上下文：")
for row in rows:
    print(f"  【{row[0]}】")
    print(f"    标题: {row[1]}")
    print(f"    技术栈: {row[2]}")
    print()

cur.close()
conn.close()
print("="*80)
print("✅ 真实项目上下文添加完成！")
print("="*80)
