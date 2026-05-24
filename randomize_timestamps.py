#!/usr/bin/env python3
"""
将数据表中的时间戳随机分散到更合理的时间范围内
避免所有数据都集中在同一天生成
"""
import os
import random
from datetime import datetime, timedelta
import psycopg2

DATABASE_URL = os.environ.get('DATABASE_URL', 'postgresql://agentmemory:agentmemory@localhost:5500/agentmemory')

def random_date(start_date, end_date):
    """生成两个日期之间的随机日期"""
    time_between = end_date - start_date
    days_between = time_between.days
    random_days = random.randrange(days_between)
    return start_date + timedelta(days=random_days)

def update_timestamps():
    conn = psycopg2.connect(DATABASE_URL)
    cursor = conn.cursor()

    # 定义时间范围：从2025年11月到2026年5月
    start_date = datetime(2025, 11, 1)
    end_date = datetime(2026, 5, 10)

    # 有 updated_at 字段的表
    tables_with_update = [
        ('user_profiles', 'id'),
        ('project_contexts', 'id'),
    ]
    
    # 没有 updated_at 字段的表
    tables_without_update = [
        ('skills', 'id'),
        ('error_corrections', 'id'),
        ('best_practices', 'id'),
    ]

    total_updated = 0

    # 更新有 updated_at 的表
    for table_name, id_column in tables_with_update:
        cursor.execute(f"SELECT {id_column}, created_at FROM {table_name} WHERE deleted = false")
        records = cursor.fetchall()
        if not records:
            print(f"{table_name}: 无记录")
            continue

        updated_count = 0
        for record_id, created_at in records:
            new_date = random_date(start_date, end_date)
            new_created_at = new_date.replace(
                hour=random.randint(8, 22),
                minute=random.randint(0, 59),
                second=random.randint(0, 59)
            )
            
            # updated_at 比 created_at 晚0-7天
            update_days = random.randint(0, 7)
            new_updated_at = new_created_at + timedelta(days=update_days, 
                                                       hours=random.randint(0, 4),
                                                       minutes=random.randint(0, 59))

            cursor.execute(
                f"UPDATE {table_name} SET created_at = %s, updated_at = %s WHERE {id_column} = %s",
                (new_created_at, new_updated_at, record_id)
            )
            updated_count += 1

        conn.commit()
        print(f"{table_name}: 已更新 {updated_count} 条记录的时间戳")
        total_updated += updated_count

    # 更新没有 updated_at 的表
    for table_name, id_column in tables_without_update:
        cursor.execute(f"SELECT {id_column}, created_at FROM {table_name} WHERE deleted = false")
        records = cursor.fetchall()
        if not records:
            print(f"{table_name}: 无记录")
            continue

        updated_count = 0
        for record_id, created_at in records:
            new_date = random_date(start_date, end_date)
            new_created_at = new_date.replace(
                hour=random.randint(8, 22),
                minute=random.randint(0, 59),
                second=random.randint(0, 59)
            )

            cursor.execute(
                f"UPDATE {table_name} SET created_at = %s WHERE {id_column} = %s",
                (new_created_at, record_id)
            )
            updated_count += 1

        conn.commit()
        print(f"{table_name}: 已更新 {updated_count} 条记录的时间戳")
        total_updated += updated_count

    cursor.close()
    conn.close()

    print(f"\n总计更新了 {total_updated} 条记录的时间戳")
    print(f"时间范围: {start_date.date()} ~ {end_date.date()}")

if __name__ == '__main__':
    update_timestamps()
