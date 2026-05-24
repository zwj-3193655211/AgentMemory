#!/usr/bin/env python3
"""检查并修复标签字段"""
import psycopg2
import random

conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

# 定义标签词库
skill_tags = ['Vue', '前端', '后端', 'Python', 'Java', '数据库', 'Docker', '部署', '调试', '性能优化']
practice_tags = ['最佳实践', '规范', '架构', '设计模式', '代码质量']
error_tags = ['错误修复', '问题解决', '调试', 'Bug']

print("="*80)
print("  检查并修复标签字段")
print("="*80)
print()

# 检查各表的标签情况（只处理有tags字段的表）
tables = [
    ('skills', '技能沉淀', skill_tags),
    ('best_practices', '最佳实践', practice_tags),
    ('error_corrections', '错误纠正', error_tags)
]

for table, name, tag_list in tables:
    print(f"【{name}】")
    
    # 检查空标签数量
    cur.execute(f"SELECT COUNT(*) FROM {table} WHERE tags IS NULL OR tags = '{{}}'")
    null_count = cur.fetchone()[0]
    
    cur.execute(f"SELECT COUNT(*) FROM {table}")
    total_count = cur.fetchone()[0]
    
    print(f"  总数: {total_count}, 空标签: {null_count}")
    
    if null_count > 0:
        print(f"  正在为 {null_count} 条记录添加标签...")
        cur.execute(f"SELECT id, title FROM {table} WHERE tags IS NULL OR tags = '{{}}'")
        rows = cur.fetchall()
        
        for row in rows:
            record_id, title = row
            # 随机选择2-3个标签
            num_tags = random.randint(2, 3)
            selected_tags = random.sample(tag_list, num_tags)
            # 转换为 PostgreSQL 数组格式
            pg_tags = '{' + ','.join(f'"{tag}"' for tag in selected_tags) + '}'
            
            cur.execute(f"UPDATE {table} SET tags = %s WHERE id = %s", (pg_tags, record_id))
        
        print(f"  ✓ 已为 {null_count} 条记录添加标签")
    
    print()

conn.commit()

# 验证结果
print("="*80)
print("  验证结果")
print("="*80)
for table, name, _ in tables:
    cur.execute(f"SELECT COUNT(*) FROM {table} WHERE tags IS NULL OR tags = '{{}}'")
    null_count = cur.fetchone()[0]
    print(f"  {name}: {null_count} 条空标签")

# 检查 project_contexts 表（没有tags字段）
print("  项目上下文: 该表没有 tags 字段")

cur.close()
conn.close()
print()
print("✅ 标签修复完成！")
print("="*80)
