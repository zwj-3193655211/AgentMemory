"""
批量对历史消息进行记忆分类提取（一次性脚本）
重点提取"用户纠正AI"的错误纠正记忆
"""
import psycopg2
import psycopg2.extras
import re
import os
import uuid
import time
from datetime import datetime

# 连接数据库
conn = psycopg2.connect(
    host="localhost", port=5500, dbname="agentmemory",
    user="agentmemory", password=os.environ.get("DATABASE_PASSWORD", "agentmemory")
)
conn.autocommit = False
cur = conn.cursor()

# ===== 分类关键词（与 MemoryClassifier.java 一致）=====
CORRECTION_MARKERS = [
    "不对", "错了", "不是", "不应该", "搞反了", "搞错了", "方向错了",
    "不是这样", "不是这个", "你理解错了", "你搞错了", "你没理解",
    "不要这样", "不应该这样", "别这样", "不能用",
    "我说的不是", "不是这个意思", "我的意思是", "我指的是",
    "不要用", "注意不要", "记住不要", "弄错了", "理解错了", "弄混了"
]

STRONG_CORRECTION_MARKERS = [
    "搞反了", "搞错了", "方向错了", "你理解错了", "你搞错了",
    "你理解反了", "不是这样的", "不是这个意思", "完全不对",
    "理解错了", "弄反了", "搞混了"
]

# 纠正模式正则
PATTERNS = [
    # "不是X，是Y"
    re.compile(r'(?:不是|并非)(.{2,80}?)[，,。；;\s]*(?:而是|是|应该是|要用|其实是)(.{2,200})', re.DOTALL),
    # "不对/错了...应该"
    re.compile(r'(?:不对|错了|搞错了|搞反了|方向错了).{0,40}?(?:应该是|应该用|要用|要改成|改为)(.{2,200})', re.DOTALL),
    # "不要X，要/应该Y"
    re.compile(r'(?:不要|别|不能用|不能这样|不要这样|不要用).{2,40}?(?:要|应该|改成|改用|用)(.{2,200})', re.DOTALL),
    # "我说的不是X，其实是Y"
    re.compile(r'(?:我说的不是|我的意思不是|我指的不是|不是这个意思)(.{2,60}?)[，,。；;\s]*(?:我说的|我的意思是|我指的是|其实是|而是)(.{2,200})', re.DOTALL),
    # "X不行/不对...应该Y"
    re.compile(r'(.{5,80}?(?:不行|不对|不对的|错误|有问题)).{0,20}?(?:应该|要用|改成|改为|改用)(.{2,200})', re.DOTALL),
    # "注意/记住不要X"
    re.compile(r'(?:注意|记住|切记|千万).{0,10}(?:不要|别|不能|不可以)(.{2,150})', re.DOTALL),
]

HELP_REQUEST_MARKERS = [
    "请帮我", "帮我", "求助", "请问", "怎么解决", "如何解决",
    "怎么办", "为什么会", "请修复", "请检查", "帮我看看", "能不能"
]


def is_correction(content):
    """判断内容是否是用户纠正AI"""
    lower = content.lower()

    # 如果是纯求助，不太可能是纠正
    help_count = sum(1 for m in HELP_REQUEST_MARKERS if m in lower)
    correction_count = sum(1 for m in CORRECTION_MARKERS if m in lower)

    # 必须有纠正标记，且纠正标记多于求助标记
    return correction_count > 0 and correction_count >= help_count


def extract_correction(content):
    """提取错误纠正内容"""
    for i, pattern in enumerate(PATTERNS):
        m = pattern.search(content)
        if m:
            groups = m.groups()
            if len(groups) >= 2:
                wrong = groups[0].strip()
                correct = groups[1].strip()
                if len(wrong) < 2 or len(correct) < 2:
                    continue
                # 生成标题
                short_wrong = wrong[:25] + "..." if len(wrong) > 25 else wrong
                return {
                    "title": f"纠正：{short_wrong}",
                    "problem": f"AI认为：{wrong}",
                    "solution": f"正确答案：{correct}",
                }
            elif len(groups) == 1:
                text = groups[0].strip()
                if len(text) < 2:
                    continue
                return {
                    "title": f"纠正约束：{text[:30]}",
                    "problem": f"AI的约束违规：{text}",
                    "solution": f"约束要求：{content[:200]}",
                }

    # 兜底：强纠正标记
    for marker in STRONG_CORRECTION_MARKERS:
        if marker in content.lower():
            return {
                "title": f"纠正：{content[:30]}...",
                "problem": "用户纠正",
                "solution": content[:500],
            }

    return None


def main():
    print("=" * 60)
    print("  批量历史消息记忆提取")
    print("=" * 60)

    # 1. 读取所有用户消息
    print("\n[1/4] 读取用户消息...")
    cur.execute("""
        SELECT m.id, m.content, m.session_id, s.agent_type
        FROM messages m
        JOIN sessions s ON m.session_id = s.id
        WHERE m.role = 'user'
          AND m.deleted = false
          AND m.content IS NOT NULL
          AND LENGTH(m.content) > 10
        ORDER BY m.created_at ASC
    """)
    user_messages = cur.fetchall()
    print(f"  共 {len(user_messages)} 条用户消息")

    # 2. 读取已有记忆标题，避免重复
    print("\n[2/4] 读取已有记忆...")
    cur.execute("SELECT title FROM error_corrections WHERE deleted = false")
    existing_titles = set(row[0] for row in cur.fetchall())
    cur.execute("SELECT COUNT(*) FROM error_corrections WHERE deleted = false")
    existing_count = cur.fetchone()[0]
    print(f"  已有 {existing_count} 条错误纠正记忆")

    # 3. 分类和提取
    print("\n[3/4] 分类提取中...")
    new_memories = []
    skip_existing = 0
    skip_no_match = 0
    skip_too_short = 0

    for msg_id, content, session_id, agent_type in user_messages:
        if len(content) < 15:
            skip_too_short += 1
            continue

        if not is_correction(content):
            continue

        extracted = extract_correction(content)
        if extracted is None:
            skip_no_match += 1
            continue

        # 标题去重
        if extracted["title"] in existing_titles:
            skip_existing += 1
            continue
        existing_titles.add(extracted["title"])

        new_memories.append({
            "id": str(uuid.uuid4()),
            "title": extracted["title"],
            "problem": extracted["problem"],
            "solution": extracted["solution"],
            "cause": "",
            "example": "",
            "tags": [],
            "agent_type": agent_type,
            "session_id": session_id,
        })

    print(f"  匹配到 {len(new_memories)} 条新的错误纠正")
    print(f"  跳过：已有={skip_existing}, 不匹配={skip_no_match}, 太短={skip_too_short}")

    # 4. 插入数据库
    if new_memories:
        print(f"\n[4/4] 插入 {len(new_memories)} 条记忆...")
        for mem in new_memories:
            cur.execute("""
                INSERT INTO error_corrections (id, title, problem, solution, cause, example, tags, agent_type, session_id)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (
                mem["id"], mem["title"], mem["problem"], mem["solution"],
                mem["cause"], mem["example"], mem["tags"],
                mem["agent_type"], mem["session_id"]
            ))

        conn.commit()
        print(f"  ✅ 成功插入 {len(new_memories)} 条错误纠正记忆")
    else:
        print("\n[4/4] 没有新的记忆需要插入")

    # 最终统计
    cur.execute("SELECT COUNT(*) FROM error_corrections WHERE deleted = false")
    final_count = cur.fetchone()[0]
    print(f"\n{'=' * 60}")
    print(f"  最终错误纠正记忆数量: {final_count} (之前: {existing_count})")
    print(f"{'=' * 60}")

    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
