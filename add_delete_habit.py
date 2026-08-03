import psycopg2

DB_CONFIG = {
    "host": "localhost",
    "port": 5500,
    "database": "agentmemory",
    "user": "agentmemory",
    "password": "agentmemory"
}

conn = psycopg2.connect(**DB_CONFIG)
cursor = conn.cursor()

# 添加删除操作偏好
items_json = '''[{"key": "删除前备份", "value": "已设置"}, {"key": "删除前确认", "value": "已设置"}, {"key": "优先放入回收站", "value": "已设置"}, {"key": "确认后再清空", "value": "已设置"}]'''

cursor.execute("""
    INSERT INTO user_profiles (id, title, category, items, created_at, deleted)
    VALUES ('profile-real-delete-habit', '删除操作习惯', 'workhabit', %s, NOW(), false)
""", (items_json,))

conn.commit()
conn.close()

print("✅ 删除操作习惯已添加")
