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

def cleanup_garbage():
    """清理低质量数据"""
    conn = psycopg2.connect(**DB_CONFIG)
    cursor = conn.cursor()

    # 删除 user_profiles 中的垃圾数据（包含系统日志、npm、错误信息的）
    cursor.execute("""
        DELETE FROM user_profiles 
        WHERE deleted = false
        AND (title = '用户偏好' OR title LIKE '%npm%' OR title LIKE '%node%' OR
             items::text LIKE '%CONNECTION%' OR items::text LIKE '%npm%' OR 
             items::text LIKE '%error%' OR items::text LIKE '%node%')
    """)
    deleted_profiles = cursor.rowcount
    print(f"删除用户画像垃圾数据: {deleted_profiles} 条")

    # 删除 skills 中的垃圾数据（包含系统日志、JSON、错误信息的）
    cursor.execute("""
        DELETE FROM skills 
        WHERE deleted = false
        AND (title LIKE '%System:%' OR title LIKE '%技能：%' OR 
             description LIKE '%Exec completed%' OR description LIKE '%json%' OR 
             description LIKE '%message_id%' OR description LIKE '%sender%' OR
             description LIKE '%Channel%' OR description LIKE '%error%' OR
             title LIKE '%npm%' OR title LIKE '%node%')
    """)
    deleted_skills = cursor.rowcount
    print(f"删除技能垃圾数据: {deleted_skills} 条")

    conn.commit()
    conn.close()
    return deleted_profiles + deleted_skills

def add_high_quality_profiles():
    """添加高质量用户画像数据"""
    conn = psycopg2.connect(**DB_CONFIG)
    cursor = conn.cursor()

    profiles = [
        {
            "id": "profile-dev-environment",
            "title": "开发环境偏好",
            "category": "环境配置",
            "items": json.dumps({
                "editor": "VS Code",
                "theme": "Dark+",
                "keyboard": "Vim",
                "fontSize": 14,
                "lineNumbers": "on",
                "extensions": ["Java Development Kit", "Vue Language Features", "Docker"]
            })
        },
        {
            "id": "profile-tech-stack",
            "title": "技术栈偏好",
            "category": "技术偏好",
            "items": json.dumps({
                "backend": ["Java 17", "Spring Boot", "Javalin"],
                "frontend": ["Vue 3", "Element Plus", "Vite"],
                "database": ["PostgreSQL", "pgvector", "SQLite"],
                "tools": ["Docker", "Git", "Maven"]
            })
        },
        {
            "id": "profile-work-habits",
            "title": "工作习惯",
            "category": "工作方式",
            "items": json.dumps({
                "workHours": "9:00-18:00",
                "focusTime": "9:00-12:00",
                "reviewTime": "15:00-17:00",
                "weeklyReview": "Friday",
                "preferredMethodology": "Agile"
            })
        },
        {
            "id": "profile-learning",
            "title": "学习偏好",
            "category": "学习方式",
            "items": json.dumps({
                "learningStyle": "Project-Based",
                "resources": ["YouTube", "Documentation", "GitHub"],
                "interests": ["LLM", "Vector Database", "AI Agents", "System Design"]
            })
        },
        {
            "id": "profile-collaboration",
            "title": "协作风格",
            "category": "团队协作",
            "items": json.dumps({
                "workflow": "Git Flow",
                "communication": "Async first",
                "meetingFrequency": "Weekly",
                "qualityGate": "80% coverage",
                "codeReview": "Pair Programming"
            })
        }
    ]

    for p in profiles:
        cursor.execute("""
            INSERT INTO user_profiles (id, title, category, items, created_at, deleted)
            VALUES (%s, %s, %s, %s, NOW(), false)
            ON CONFLICT (id) DO UPDATE SET
                items = EXCLUDED.items,
                updated_at = NOW()
        """, (p["id"], p["title"], p["category"], p["items"]))
        print(f"添加用户画像: {p['title']}")

    conn.commit()
    conn.close()

def add_high_quality_skills():
    """添加高质量技能沉淀数据"""
    conn = psycopg2.connect(**DB_CONFIG)
    cursor = conn.cursor()

    skills = [
        {
            "id": "skill-java-debug",
            "title": "Java远程调试技巧",
            "skill_type": "debugging",
            "description": "掌握Java应用的远程调试方法，包括使用JDWP协议连接远程JVM，设置断点和条件断点，查看线程堆栈和内存快照。",
            "steps": json.dumps([
                "在启动参数中添加: -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005",
                "在IDE中配置Remote Debug连接",
                "设置断点并启动调试会话",
                "使用JDK工具(jstack, jmap)辅助分析"
            ]),
            "tags": ["Java", "Debugging", "JVM"]
        },
        {
            "id": "skill-docker-compose",
            "title": "Docker Compose编排实践",
            "skill_type": "devops",
            "description": "熟练使用Docker Compose管理多容器应用，包括网络配置、数据卷挂载、环境变量管理和健康检查配置。",
            "steps": json.dumps([
                "编写docker-compose.yml定义服务",
                "配置网络模式和端口映射",
                "设置健康检查和重启策略",
                "使用.env文件管理环境变量"
            ]),
            "tags": ["Docker", "DevOps", "Container"]
        },
        {
            "id": "skill-postgresql-optimization",
            "title": "PostgreSQL查询优化",
            "skill_type": "database",
            "description": "掌握PostgreSQL性能调优技巧，包括索引优化、查询计划分析、连接池配置和查询重写。",
            "steps": json.dumps([
                "使用EXPLAIN ANALYZE分析查询计划",
                "识别全表扫描并添加合适索引",
                "优化JOIN顺序和子查询",
                "配置合适的work_mem和shared_buffers"
            ]),
            "tags": ["PostgreSQL", "Optimization", "Database"]
        },
        {
            "id": "skill-vue-composables",
            "title": "Vue 3 Composables模式",
            "skill_type": "frontend",
            "description": "深入理解Vue 3组合式API，能够创建可复用的composables，实现逻辑复用和状态管理。",
            "steps": json.dumps([
                "识别组件间可复用的逻辑",
                "创建useXxx形式的composable函数",
                "使用ref/reactive管理状态",
                "通过return暴露必要的方法和状态"
            ]),
            "tags": ["Vue 3", "Composables", "Frontend"]
        },
        {
            "id": "skill-git-rebase",
            "title": "Git Rebase工作流",
            "skill_type": "version-control",
            "description": "熟练使用git rebase进行分支整理，包括交互式rebase、解决冲突和保持提交历史清晰。",
            "steps": json.dumps([
                "git fetch origin 获取最新代码",
                "git rebase origin/main 变基到主分支",
                "解决冲突后git add并git rebase --continue",
                "使用git rebase -i进行提交历史整理"
            ]),
            "tags": ["Git", "Version Control", "Workflow"]
        },
        {
            "id": "skill-api-design",
            "title": "RESTful API设计原则",
            "skill_type": "architecture",
            "description": "掌握RESTful API设计最佳实践，包括资源命名、状态码使用、错误处理和版本管理。",
            "steps": json.dumps([
                "使用名词表示资源，避免动词",
                "合理使用HTTP方法(GET/POST/PUT/DELETE)",
                "统一错误响应格式",
                "通过URL路径或请求头进行版本控制"
            ]),
            "tags": ["API", "REST", "Design"]
        },
        {
            "id": "skill-unit-testing",
            "title": "单元测试最佳实践",
            "skill_type": "testing",
            "description": "掌握单元测试的编写技巧，包括测试隔离、断言使用、测试覆盖率和测试命名规范。",
            "steps": json.dumps([
                "确保每个测试只验证一个行为",
                "使用Mock隔离外部依赖",
                "遵循AAA模式(Arrange-Act-Assert)",
                "保持测试代码简洁可读"
            ]),
            "tags": ["Testing", "Unit Test", "Quality"]
        },
        {
            "id": "skill-vector-search",
            "title": "向量数据库检索优化",
            "skill_type": "ai",
            "description": "掌握pgvector向量检索技术，包括HNSW索引创建、距离度量选择和查询优化。",
            "steps": json.dumps([
                "使用CREATE INDEX创建HNSW索引",
                "选择合适的距离度量(cosine/euclidean)",
                "优化top_k参数平衡精度和速度",
                "结合文本搜索实现混合检索"
            ]),
            "tags": ["Vector Database", "pgvector", "AI"]
        }
    ]

    for s in skills:
        cursor.execute("""
            INSERT INTO skills (id, title, skill_type, description, steps, tags, created_at, deleted)
            VALUES (%s, %s, %s, %s, %s, %s, NOW(), false)
            ON CONFLICT (id) DO UPDATE SET
                description = EXCLUDED.description,
                steps = EXCLUDED.steps
        """, (s["id"], s["title"], s["skill_type"], s["description"], s["steps"], s["tags"]))
        print(f"添加技能: {s['title']}")

    conn.commit()
    conn.close()

if __name__ == "__main__":
    print("=== 开始清理和优化数据 ===")
    
    # 清理垃圾数据
    deleted = cleanup_garbage()
    print(f"\n共清理 {deleted} 条垃圾数据")

    # 添加高质量数据
    print("\n=== 添加高质量用户画像 ===")
    add_high_quality_profiles()

    print("\n=== 添加高质量技能沉淀 ===")
    add_high_quality_skills()

    print("\n✅ 优化完成！")
