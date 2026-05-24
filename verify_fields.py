import psycopg2
from psycopg2.extras import RealDictCursor

conn = psycopg2.connect(
    host="localhost",
    port=5500,
    database="agentmemory",
    user="agentmemory",
    password="agentmemory"
)

print("="*80)
print("  验证各记忆表字段完整性")
print("="*80)
print()

tables = [
    ("error_corrections", ["problem", "solution", "cause", "example", "tags", "title"]),
    ("best_practices", ["title", "practice", "scenario", "rationale", "tags"]),
    ("project_contexts", ["title", "project_name", "project_path", "tech_stack", "key_decisions", "structure", "summary"]),
    ("user_profiles", ["title", "category", "items", "updated_at"]),
    ("skills", ["title", "description", "difficulty", "steps", "tags"])
]

for table, fields in tables:
    print("="*80)
    print(f"  {table}")
    print("="*80)
    
    with conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(f"SELECT * FROM {table} LIMIT 2")
        rows = cur.fetchall()
        
        for row in rows:
            print(f"\n【{row['title']}】")
            for field in fields:
                value = row.get(field)
                if value is not None and value != "" and value != []:
                    val_str = str(value)
                    if len(val_str) > 100:
                        val_str = val_str[:97] + "..."
                    print(f"  {field}: {val_str}")
                else:
                    print(f"  {field}: (空) ⚠️")
    
    with conn.cursor() as cur:
        cur.execute(f"SELECT COUNT(*) FROM {table}")
        total = cur.fetchone()[0]
        
        # 检查是否有空值
        null_checks = []
        for field in fields:
            if field in ["tags", "tech_stack", "steps", "key_decisions", "structure"]:
                cur.execute(f"SELECT COUNT(*) FROM {table} WHERE {field} IS NULL OR {field} = '{{}}' OR {field} = '[]'")
            else:
                cur.execute(f"SELECT COUNT(*) FROM {table} WHERE {field} IS NULL OR {field} = ''")
            null_count = cur.fetchone()[0]
            if null_count > 0:
                null_checks.append(f"{field} 有 {null_count} 个空值")
    
    print(f"\n📊 总数: {total} 条")
    if null_checks:
        print("⚠️  空值警告:")
        for nc in null_checks:
            print(f"   - {nc}")
    else:
        print("✅ 所有字段均已填充")
    print()

conn.close()
print("="*80)
