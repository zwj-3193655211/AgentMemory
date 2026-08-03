import psycopg2
conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()

tables = ['error_corrections', 'best_practices', 'project_contexts']
for table in tables:
    print(f"\n=== {table} ===")
    cur.execute(f"SELECT column_name FROM information_schema.columns WHERE table_name = '{table}' ORDER BY ordinal_position")
    cols = [c[0] for c in cur.fetchall()]
    print(cols)
    cur.execute(f"SELECT * FROM {table} LIMIT 1")
    print("Sample:", cur.fetchone())

conn.close()
