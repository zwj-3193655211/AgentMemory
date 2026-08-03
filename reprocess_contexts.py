#!/usr/bin/env python3
"""
批量重提取脚本 - 用新 prompt 重新提取 project_contexts

流程：
1. 连接 PostgreSQL，读取所有 session 及其 messages
2. 对每个 session 的对话内容调用 /extract 接口
3. 如果提取结果是 PROJECT_CONTEXT 类型，upsert 到 project_contexts 表
4. 按 session_id 匹配（通过 title 或 project_path 关联）

使用方式：
  python reprocess_contexts.py                # 默认：仅预览，不写入
  python reprocess_contexts.py --write       # 真正写入数据库
  python reprocess_contexts.py --session X  # 仅处理指定 session
"""

import os
import sys
import json
import time
import psycopg2
import requests
from psycopg2.extras import DictCursor

# ========== 配置 ==========
DB_CONFIG = {
    'host': 'localhost',
    'port': 5500,
    'database': 'agentmemory',
    'user': 'agentmemory',
    'password': os.environ.get('DATABASE_PASSWORD', ''),
}

EXTRACT_URL = 'http://127.0.0.1:8100/extract'
# 如果 embed_server 没启动，可以直接调用 LLM API
OLLAMA_URL = 'http://127.0.0.1:11434/api/chat'
OLLAMA_MODEL = 'qwen3.5:2b'
# 调用 embed_server 的超时时间（秒）
EXTRACT_TIMEOUT = 120

DRY_RUN = '--write' not in sys.argv
SESSION_FILTER = None
for i, arg in enumerate(sys.argv[1:]):
    if arg == '--session' and i+2 <= len(sys.argv)-1:
        SESSION_FILTER = sys.argv[i+2]

print(f"[配置] DRY_RUN={DRY_RUN}, SESSION_FILTER={SESSION_FILTER}")


def get_db_conn():
    return psycopg2.connect(**DB_CONFIG, cursor_factory=DictCursor)


def fetch_sessions_and_messages(conn):
    """读取所有 session 及其 messages"""
    with conn.cursor() as cur:
        if SESSION_FILTER:
            cur.execute("""
                SELECT s.id, s.agent_type, s.project_path, s.started_at
                FROM sessions s
                WHERE s.id = %s AND (s.deleted = false OR s.deleted IS NULL)
                ORDER BY s.started_at DESC
            """, (SESSION_FILTER,))
        else:
            cur.execute("""
                SELECT s.id, s.agent_type, s.project_path, s.started_at
                FROM sessions s
                WHERE (s.deleted = false OR s.deleted IS NULL)
                ORDER BY s.started_at DESC
                LIMIT 500
            """)
        sessions = cur.fetchall()

    results = []
    for sess in sessions:
        sid = sess['id']
        with conn.cursor() as cur:
            cur.execute("""
                SELECT role, content, timestamp
                FROM messages
                WHERE session_id = %s AND (deleted = false OR deleted IS NULL)
                  AND content IS NOT NULL AND content != ''
                ORDER BY timestamp ASC
                LIMIT 200
            """, (sid,))
            msgs = cur.fetchall()

        if not msgs:
            continue

        # 拼接对话内容（最近 50 条消息，限制总长度）
        parts = []
        for m in msgs[-50:]:
            role = m['role']
            content = m['content'] or ''
            if len(content) > 500:
                content = content[:500] + '...(truncated)'
            parts.append(f"[{role}]\n{content}")
        full_content = '\n\n'.join(parts)

        results.append({
            'session_id': sid,
            'agent_type': sess['agent_type'],
            'project_path': sess['project_path'],
            'started_at': sess['started_at'],
            'message_count': len(msgs),
            'content': full_content,
        })

    return results


def call_extract(content: str) -> dict:
    """调用 embed_server /extract 接口"""
    try:
        resp = requests.post(EXTRACT_URL, json={'content': content}, timeout=EXTRACT_TIMEOUT)
        if resp.status_code == 200:
            result = resp.json()
            if result.get('type') and result['type'] != 'SKIP':
                return result
            print(f"  [debug] extract 返回 SKIP: {result.get('reason', '')}")
    except Exception as e:
        print(f"  [warn] embed_server 调用失败 ({e})")

    # 回退：用 embed_server 的 /extract_with_context 或直接调 Ollama
    print(f"  [info] 回退到直接 Ollama 调用")
    return call_ollama_directly(content)


def call_ollama_directly(content: str) -> dict:
    """直接用 Ollama + 新 prompt 提取"""
    prompt = f"""你是记忆提取专家。分析对话内容，提取结构化记忆。

【五大记忆库定义】

1. **PROJECT_CONTEXT（项目上下文）**
   - 核心：项目的实际工作进展、技术决策、架构变化
   - 必须有：工作摘要（具体做了什么，而非仅仅是技术栈列表）
   - 触发词：项目、技术栈、框架、重构、升级、部署、架构、配置

【对话内容】
{content[:3000]}

【输出要求】
严格按JSON格式返回：
{{"type": "类型", "title": "简短标题(≤30字)", "tags": ["标签"], "extracted": {{...}}}}

PROJECT_CONTEXT extracted: {{"project_name": "项目名", "tech_stack": ["技术栈"], "summary": "一段话概括本次做了什么（具体工作内容，非技术栈罗列）", "key_decisions": "做出的关键决策或技术选型", "next_steps": "接下来的待办或下一步计划"}}

如果对话内容不构成项目上下文，返回: {{"type": "SKIP", "reason": "原因"}}
"""

    try:
        resp = requests.post(
            OLLAMA_URL,
            json={
                'model': OLLAMA_MODEL,
                'messages': [{'role': 'user', 'content': prompt}],
                'stream': False,
                'options': {'temperature': 0.3, 'num_predict': 512}
            },
            timeout=120
        )
        if resp.status_code == 200:
            raw = resp.json()['message']['content']
            # 提取 JSON
            import re
            json_match = re.search(r'\{[\s\S]*\}', raw)
            if json_match:
                return json.loads(json_match.group())
    except Exception as e:
        print(f"  [error] Ollama 调用失败: {e}")

    return {'type': 'SKIP', 'reason': '提取失败'}


def find_existing_context(conn, session_id: str, project_path: str):
    """查找是否已存在对应的 project_context 记录"""
    with conn.cursor() as cur:
        # 先按 project_path 匹配
        if project_path:
            cur.execute("""
                SELECT id FROM project_contexts
                WHERE project_path = %s AND (deleted = false OR deleted IS NULL)
                LIMIT 1
            """, (project_path,))
            row = cur.fetchone()
            if row:
                return row['id']

        # 再按 session_id 在 title 中匹配（title 包含 session_id 或前8位）
        sid_short = session_id.replace('-', '')[:8]
        cur.execute("""
            SELECT id FROM project_contexts
            WHERE title LIKE %s AND (deleted = false OR deleted IS NULL)
            LIMIT 1
        """, (f'%{sid_short}%',))
        row = cur.fetchone()
        if row:
            return row['id']

    return None


def upsert_project_context(conn, session_info: dict, extracted: dict, existing_id: str = None):
    """写入或更新 project_contexts 记录"""
    ex = extracted.get('extracted', {})
    title = extracted.get('title', f"项目上下文 {session_info['session_id'][:8]}")
    project_name = ex.get('project_name', '')
    if not project_name and session_info['project_path']:
        # 从路径提取项目名
        parts = [p for p in session_info['project_path'].replace('\\', '/').split('/') if p]
        project_name = parts[-1] if parts else '未命名项目'

    tech_stack = ex.get('tech_stack', [])
    if isinstance(tech_stack, str):
        tech_stack = [s.strip() for s in tech_stack.split(',') if s.strip()]

    summary = ex.get('summary', '')
    key_decisions = ex.get('key_decisions', '')
    next_steps = ex.get('next_steps', '')

    # 构造 structure JSON
    import json as json_lib
    structure = json_lib.dumps({
        'summary': summary,
        'key_decisions': key_decisions,
        'next_steps': next_steps,
        'source_session': session_info['session_id'],
    }, ensure_ascii=False)

    tags = extracted.get('tags', [])
    tech_stack_arr = json_lib.dumps(tech_stack, ensure_ascii=False)
    tags_arr = json_lib.dumps(tags, ensure_ascii=False)

    with conn.cursor() as cur:
        if existing_id:
            # UPDATE
            cur.execute("""
                UPDATE project_contexts SET
                    title = %s,
                    project_name = %s,
                    tech_stack = %s::jsonb,
                    key_decisions = %s,
                    structure = %s::jsonb,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = %s
            """, (title, project_name, tech_stack_arr, key_decisions, structure, existing_id))
            return existing_id
        else:
            # INSERT
            cur.execute("""
                INSERT INTO project_contexts
                    (id, title, project_name, project_path, tech_stack, key_decisions, structure, updated_at, deleted)
                VALUES
                    (gen_random_uuid()::text, %s, %s, %s, %s::jsonb, %s, %s::jsonb, CURRENT_TIMESTAMP, false)
                RETURNING id
            """, (title, project_name, session_info['project_path'], tech_stack_arr, key_decisions, structure))
            row = cur.fetchone()
            return row['id'] if row else None


def main():
    print("=" * 60)
    print("批量重提取 project_contexts")
    print("=" * 60)

    if DRY_RUN:
        print("\n[预览模式] 传入 --write 参数才会真正写入数据库\n")

    conn = get_db_conn()
    try:
        sessions = fetch_sessions_and_messages(conn)
        print(f"读取到 {len(sessions)} 个 session（含消息）\n")

        stats = {'processed': 0, 'project_context': 0, 'skip': 0, 'error': 0, 'written': 0}

        for i, sess in enumerate(sessions):
            sid = sess['session_id']
            print(f"[{i+1}/{len(sessions)}] Session: {sid[:20]}... ({sess['message_count']} msgs)")

            result = call_extract(sess['content'])
            stats['processed'] += 1

            if result.get('type') == 'PROJECT_CONTEXT':
                stats['project_context'] += 1
                ex = result.get('extracted', {})
                print(f"  ✓ PROJECT_CONTEXT: {result.get('title', '')}")
                print(f"    summary: {(ex.get('summary', '') or '')[:80]}")
                print(f"    decisions: {(ex.get('key_decisions', '') or '')[:80]}")

                if not DRY_RUN:
                    existing_id = find_existing_context(conn, sid, sess['project_path'])
                    new_id = upsert_project_context(conn, sess, result, existing_id)
                    conn.commit()
                    stats['written'] += 1
                    print(f"    {'更新' if existing_id else '新增'}记录: {new_id[:20]}...")
            else:
                reason = result.get('reason', '')
                print(f"  - SKIP: {reason}")
                stats['skip'] += 1

            # 避免请求过快
            time.sleep(0.5)

        print("\n" + "=" * 60)
        print("完成！统计：")
        print(f"  处理 session 数: {stats['processed']}")
        print(f"  识别为 PROJECT_CONTEXT: {stats['project_context']}")
        print(f"  跳过: {stats['skip']}")
        print(f"  写入/更新记录: {stats['written']}")
        print(f"  错误: {stats['error']}")
        if DRY_RUN:
            print("\n[预览模式] 传入 --write 参数才会真正写入数据库")
        print("=" * 60)

    finally:
        conn.close()


if __name__ == '__main__':
    main()
