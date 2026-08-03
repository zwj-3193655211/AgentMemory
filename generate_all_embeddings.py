#!/usr/bin/env python3
"""为所有记忆库记录生成 embedding 向量"""
import psycopg2
import requests
import json
import time

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

EMBED_URL = "http://localhost:8100/embed"

tables = [
    ('skills', 'title'),
    ('error_corrections', 'title'),
    ('best_practices', 'title'),
    ('project_contexts', 'title'),
    ('user_profiles', 'title')
]

def generate_embedding(text):
    """调用 embedding 服务生成向量"""
    try:
        response = requests.post(EMBED_URL, json={"texts": [str(text)[:500]]}, timeout=30)
        if response.status_code == 200:
            data = response.json()
            embeddings = data.get('embeddings', [])
            if embeddings and len(embeddings) > 0:
                return embeddings[0]
    except Exception as e:
        print(f"  生成 embedding 失败: {e}")
    return None

def vector_to_pg_array(vector):
    """将向量转换为 PostgreSQL 格式"""
    return '[' + ','.join(str(x) for x in vector) + ']'

print("="*80)
print("  开始生成 embedding 向量")
print("="*80)
print()
print(f"Embedding 服务: {EMBED_URL}")
print()

total_records = 0
success_count = 0
fail_count = 0

for table, text_field in tables:
    print(f"【处理表: {table}】")

    cur.execute(f"SELECT id, {text_field} FROM {table} WHERE embedding IS NULL")
    rows = cur.fetchall()

    if not rows:
        print(f"  无需处理（所有记录已有 embedding）")
        continue

    print(f"  需要处理: {len(rows)} 条记录")

    table_success = 0
    table_fail = 0

    for i, (record_id, text) in enumerate(rows, 1):
        if not text or len(str(text).strip()) < 5:
            continue

        embedding = generate_embedding(str(text))

        if embedding:
            pg_vector = vector_to_pg_array(embedding)
            cur.execute(f"UPDATE {table} SET embedding = %s WHERE id = %s", (pg_vector, record_id))
            conn.commit()
            table_success += 1
        else:
            table_fail += 1

        if i % 10 == 0:
            print(f"  进度: {i}/{len(rows)}")

        time.sleep(0.1)

    print(f"  完成: 成功 {table_success}, 失败 {table_fail}")
    total_records += len(rows)
    success_count += table_success
    fail_count += table_fail
    print()

print("="*80)
print("  处理完成")
print("="*80)
print(f"总计: 处理 {total_records} 条, 成功 {success_count}, 失败 {fail_count}")
print()

print("验证 embedding 生成结果:")
for table, _ in tables:
    cur.execute(f"SELECT COUNT(*) FROM {table} WHERE embedding IS NOT NULL")
    count = cur.fetchone()[0]
    cur.execute(f"SELECT COUNT(*) FROM {table}")
    total = cur.fetchone()[0]
    print(f"  {table}: {count}/{total} 条有 embedding")

cur.close()
conn.close()
print()
print("="*80)
print("✅ Embedding 向量生成完成！")
print("="*80)
