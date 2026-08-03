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

# 更新网络配置用户画像
items_json = '''[{"key": "优先使用国内镜像源", "value": "已设置"}, {"key": "失败时查找解决方法", "value": "已设置"}, {"key": "必要时使用VPN", "value": "已设置"}]'''
cursor.execute("UPDATE user_profiles SET items = %s WHERE title = '网络配置'", (items_json,))

conn.commit()
conn.close()

print("✅ 网络配置用户画像已更新")
