-- AgentMemory 完整优化脚本
-- 执行时间: 约 1 分钟
-- 说明: 一键执行所有优化

\echo '========================================'
\echo 'AgentMemory 性能优化'
\echo '========================================'
\echo '执行时间: 2026-03-23'
\echo ''

-- 显示当前数据库信息
\echo '数据库信息:'
SELECT current_database() as database_name,
       current_user as user,
       version() as postgresql_version;

\echo ''
\echo '========================================'
\echo '1. 添加向量索引...'
\echo '========================================'

-- 删除旧的索引（如果存在）
DO $$
BEGIN
    DROP INDEX IF EXISTS idx_error_corrections_embedding_ivfflat;
    DROP INDEX IF EXISTS idx_error_corrections_embedding_hnsw;
    DROP INDEX IF EXISTS idx_best_practices_embedding_ivfflat;
    DROP INDEX IF EXISTS idx_best_practices_embedding_hnsw;
    DROP INDEX IF EXISTS idx_skills_embedding_ivfflat;
    DROP INDEX IF EXISTS idx_skills_embedding_hnsw;
END $$;

-- 创建向量索引
CREATE INDEX CONCURRENTLY idx_error_corrections_embedding_hnsw
ON error_corrections
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

CREATE INDEX CONCURRENTLY idx_best_practices_embedding_hnsw
ON best_practices
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

CREATE INDEX CONCURRENTLY idx_skills_embedding_hnsw
ON skills
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

\echo '✅ 向量索引创建完成'
\echo ''

\echo '========================================'
\echo '2. 添加触发器...'
\echo '========================================'

-- 删除旧的触发器（如果存在）
DROP TRIGGER IF EXISTS update_session_message_count ON messages;

-- 创建自动更新消息计数的触发器
CREATE TRIGGER update_session_message_count
AFTER INSERT ON messages
FOR EACH ROW
BEGIN
    INSERT INTO sessions (id, message_count, updated_at)
    VALUES (NEW.session_id, 1, CURRENT_TIMESTAMP)
    ON CONFLICT (id) DO UPDATE SET
        message_count = sessions.message_count + 1,
        updated_at = CURRENT_TIMESTAMP;
END;

\echo '✅ 触发器创建完成'
\echo ''

\echo '========================================'
\echo '3. 验证优化结果'
\echo '========================================'

\echo '向量索引:'
SELECT indexname, tablename
FROM pg_indexes
WHERE indexname LIKE '%embedding%'
ORDER BY tablename;

\echo ''
\echo '触发器:'
SELECT trigger_name, event_object_table
FROM information_schema.triggers
WHERE event_object_table = 'messages';

\echo ''
\echo '========================================'
\echo '优化完成！'
\echo '========================================'

-- 显示优化统计
WITH vector_stats AS (
    SELECT COUNT(*) as total_indexes
    FROM pg_indexes
    WHERE indexname LIKE '%embedding%'
),
trigger_stats AS (
    SELECT COUNT(*) as total_triggers
    FROM information_schema.triggers
    WHERE event_object_table = 'messages'
)
SELECT
    total_indexes as vector_indexes_created,
    total_triggers as triggers_created,
    '优化已应用，请重启应用以生效' as next_step
FROM vector_stats, trigger_stats;

\echo ''
\echo '下一步操作:'
\echo '1. 重启应用: ./stop.sh && ./start.sh'
\echo '2. 验证功能: curl http://localhost:8080/api/stats'
\echo '3. 查看详细指南: cat OPTIMIZATION_GUIDE.md'
