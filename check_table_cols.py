#!/usr/bin/env python3
import psycopg2
conn = psycopg2.connect(
    host='localhost', port=5500, dbname='agentmemory',
    user='agentmemory', password='agentmemory'
)
cur = conn.cursor()
tables = ['user_profiles', 'error_corrections', 'skills', 'best_practices', 'project_contexts']
for table in tables:
    cur.execute(f"SELECT column_name FROM information_schema.columns WHERE table_name = '{table}' ORDER BY ordinal_position")
    cols = [c[0] for c in cur.fetchall()]
    print(f"{table}: {cols}")
conn.close()
