import psycopg2
conn = psycopg2.connect(host='localhost', port=5500, dbname='agentmemory', user='agentmemory', password='agentmemory')
cur = conn.cursor()
cur.execute('SELECT * FROM llm_providers')
rows = cur.fetchall()
for row in rows:
    print(row)
conn.close()
