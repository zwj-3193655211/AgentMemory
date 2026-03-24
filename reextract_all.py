#!/usr/bin/env python3
"""从所有会话重新提取记忆 - 使用 Ollama"""
import psycopg2
import requests
import json
import uuid
import os
import time
from datetime import datetime

# Ollama 配置 - 使用环境变量
OLLAMA_BASE = os.environ.get('OLLAMA_BASE', 'http://localhost:11434')
OLLAMA_MODEL = os.environ.get('OLLAMA_MODEL', 'qwen3.5:2b')

# 数据库配置 - 使用环境变量
DB_CONFIG = {
    "host": os.environ.get('DATABASE_HOST', 'localhost'),
    "port": int(os.environ.get('DATABASE_PORT', 5500)),
    "database": os.environ.get('DATABASE_NAME', 'agentmemory'),
    "user": os.environ.get('DATABASE_USER', 'agentmemory'),
    "password": os.environ.get('DATABASE_PASSWORD', 'agentmemory123')
}

EXTRACTION_PROMPT = """你是记忆提取专家。分析以下对话内容，提取有价值的记忆。

【五大记忆类型】

1. ERROR_CORRECTION（错误纠正）- 已发生的具体问题 + 解决方案
2. USER_PROFILE（用户偏好）- 用户的偏好、习惯、约束
3. BEST_PRACTICE（最佳实践）- 特定场景下验证过有效的做法
4. PROJECT_CONTEXT（项目上下文）- 项目技术背景、架构决策
5. SKILL（技能沉淀）- 可复用的能力包

【对话内容】
{content}

【输出要求】
返回JSON数组，每条记忆包含 type, title, content 字段。无有价值内容返回空数组 []。
示例：
[{{"type": "USER_PROFILE", "title": "Python环境偏好", "content": "用户要求使用conda虚拟环境，不要动系统Python"}}]"""

def call_ollama(prompt, max_retries=3):
    """调用 Ollama API"""
    for attempt in range(max_retries):
        try:
            response = requests.post(
                f"{OLLAMA_BASE}/api/generate",
                json={
                    "model": OLLAMA_MODEL,
                    "prompt": prompt,
                    "stream": False,
                    "think": False,  # 关闭思考模式
                    "options": {
                        "temperature": 0.3,
                        "num_predict": 1000
                    }
                },
                timeout=120
            )
            
            if response.status_code == 200:
                return response.json().get("response", "").strip()
        except Exception as e:
            print(f"    API调用失败 (尝试 {attempt+1}/{max_retries}): {e}")
            time.sleep(2)
    return None

def parse_extraction_result(response_text):
    """解析提取结果"""
    if not response_text:
        return []
    
    # 尝试提取JSON数组
    try:
        # 找到数组部分
        start = response_text.find('[')
        end = response_text.rfind(']') + 1
        if start >= 0 and end > start:
            json_str = response_text[start:end]
            return json.loads(json_str)
    except:
        pass
    return []

def main():
    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()
    
    # 1. 清空现有记忆表
    print("=" * 60)
    print("清空现有记忆表...")
    tables = ['user_profiles', 'error_corrections', 'skills', 'best_practices', 'project_contexts']
    for table in tables:
        cur.execute(f"DELETE FROM {table}")
        print(f"  已清空: {table}")
    conn.commit()
    
    # 2. 获取所有会话
    cur.execute("""
        SELECT s.id, s.agent_type, s.created_at
        FROM sessions s
        WHERE s.deleted = false
        ORDER BY s.created_at DESC
    """)
    sessions = cur.fetchall()
    print(f"\n共 {len(sessions)} 个会话待处理\n")
    
    # 统计
    stats = {
        'ERROR_CORRECTION': 0,
        'USER_PROFILE': 0,
        'BEST_PRACTICE': 0,
        'PROJECT_CONTEXT': 0,
        'SKILL': 0,
        'SKIP': 0
    }
    
    # 3. 处理每个会话
    for i, (session_id, agent_type, created_at) in enumerate(sessions, 1):
        print(f"[{i}/{len(sessions)}] 会话 {session_id[:8]}... ({agent_type})")
        
        # 获取该会话的消息
        cur.execute("""
            SELECT role, content FROM messages
            WHERE session_id = %s AND deleted = false
            ORDER BY created_at
        """, (session_id,))
        messages = cur.fetchall()
        
        if not messages:
            print("  无消息，跳过")
            continue
        
        # 组装对话内容
        content_parts = []
        for role, content in messages:
            if content:
                content_parts.append(f"{role}: {content[:500]}")
        
        full_content = "\n".join(content_parts)
        
        # 如果内容太长，截取
        if len(full_content) > 8000:
            full_content = full_content[:8000] + "...(截断)"
        
        # 调用 Ollama 提取
        prompt = EXTRACTION_PROMPT.format(content=full_content)
        response = call_ollama(prompt)
        
        if not response:
            print("  API调用失败，跳过")
            continue
        
        # 解析结果
        memories = parse_extraction_result(response)
        
        if not memories:
            print("  无有价值记忆")
            stats['SKIP'] += 1
            continue
        
        # 存储记忆
        for mem in memories:
            mem_type = mem.get('type', 'SKIP')
            title = mem.get('title', '')[:100]
            content = mem.get('content', '')
            
            if mem_type not in stats:
                mem_type = 'SKIP'
            
            if mem_type == 'SKIP':
                stats['SKIP'] += 1
                continue
            
            mem_id = str(uuid.uuid4())
            now = datetime.now()
            
            try:
                if mem_type == 'ERROR_CORRECTION':
                    cur.execute("""
                        INSERT INTO error_corrections (id, title, problem, solution, created_at, session_id)
                        VALUES (%s, %s, %s, %s, %s, %s)
                    """, (mem_id, title, content, '', now, session_id))
                
                elif mem_type == 'USER_PROFILE':
                    cur.execute("""
                        INSERT INTO user_profiles (id, title, category, items, created_at, session_id)
                        VALUES (%s, %s, %s, %s, %s, %s)
                    """, (mem_id, title, 'preference', 
                          json.dumps([{"key": "preference", "value": content}]), now, session_id))
                
                elif mem_type == 'BEST_PRACTICE':
                    cur.execute("""
                        INSERT INTO best_practices (id, title, scenario, practice, created_at, session_id)
                        VALUES (%s, %s, %s, %s, %s, %s)
                    """, (mem_id, title, '', content, now, session_id))
                
                elif mem_type == 'PROJECT_CONTEXT':
                    cur.execute("""
                        INSERT INTO project_contexts (id, title, key_decisions, created_at, session_id, project_path)
                        VALUES (%s, %s, %s, %s, %s, %s)
                    """, (mem_id, title, json.dumps([{"decision": content}]), now, session_id, ''))
                
                elif mem_type == 'SKILL':
                    cur.execute("""
                        INSERT INTO skills (id, title, description, created_at, session_id)
                        VALUES (%s, %s, %s, %s, %s)
                    """, (mem_id, title, content, now, session_id))
                
                stats[mem_type] += 1
                print(f"  + {mem_type}: {title[:30]}...")
                
            except Exception as e:
                print(f"  存储失败: {e}")
        
        conn.commit()
        
        # 每10个会话输出统计
        if i % 10 == 0:
            print(f"\n--- 当前进度 ---")
            for t, c in stats.items():
                print(f"  {t}: {c}")
            print()
    
    # 最终统计
    print("\n" + "=" * 60)
    print("提取完成! 最终统计:")
    for t, c in stats.items():
        print(f"  {t}: {c}")
    
    # 检查各表数量
    print("\n各表记录数:")
    for table in tables:
        cur.execute(f"SELECT COUNT(*) FROM {table}")
        print(f"  {table}: {cur.fetchone()[0]}")
    
    conn.close()

if __name__ == "__main__":
    main()