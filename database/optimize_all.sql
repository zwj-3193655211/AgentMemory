-- AgentMemory 完整优化脚本
-- 执行时间: 约 1 分钟
-- 说明: 一键执行所有优化
-- PostgreSQL 版本

-- 显示当前数据库信息
DO $$ BEGIN RAISE NOTICE '========================================'; END $$;
DO $$ BEGIN RAISE NOTICE 'AgentMemory 性能优化'; END $$;
DO $$ BEGIN RAISE NOTICE '========================================'; END $$;
DO $$ BEGIN RAISE NOTICE '执行时间: 2026-03-23'; END $$;
DO $$ BEGIN RAISE NOTICE ''; END $$;

-- 显示当前数据库信息
DO $$ BEGIN RAISE NOTICE '数据库信息:'; END $$;
SELECT current_database() as database_name,
       current_user as user,
       version() as postgresql_version;

DO $$ BEGIN RAISE NOTICE ''; END $$;
DO $$ BEGIN RAISE NOTICE '========================================'; END $$;
DO $$ BEGIN RAISE NOTICE '1. 添加向量索引...'; END $$;
DO $$ BEGIN RAISE NOTICE '========================================'; END $$;

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

DO $$ BEGIN RAISE NOTICE '✅ 向量索引创建完成'; END $$;
DO $$ BEGIN RAISE NOTICE ''; END $$;

DO $$ BEGIN RAISE NOTICE '========================================'; END $$;
DO $$ BEGIN RAISE NOTICE '2. 添加触发器...'; END $$;
DO $$ BEGIN RAISE NOTICE '========================================'; END $$;

-- 删除旧的触发器（如果存在）
DROP TRIGGER IF EXISTS update_session_message_count ON messages;
DROP FUNCTION IF EXISTS update_session_message_count_func() CASCADE;

-- 创建触发器函数
CREATE OR REPLACE FUNCTION update_session_message_count_func()
RETURNS TRIGGER AS $$
BEGIN
    -- 如果是新消息，增加计数
    INSERT INTO sessions (id, message_count, updated_at)
    VALUES (NEW.session_id, 1, CURRENT_TIMESTAMP)
    ON CONFLICT (id) DO UPDATE SET
        message_count = sessions.message_count + 1,
        updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 创建触发器：每次插入消息时自动更新会话的消息计数
CREATE OR REPLACE TRIGGER update_session_message_count
AFTER INSERT ON messages
FOR EACH ROW
EXECUTE FUNCTION update_session_message_count_func();

DO $$ BEGIN RAISE NOTICE '✅ 触发器创建完成'; END $$;
DO $$ BEGIN RAISE NOTICE ''; END $$;

DO $$ BEGIN RAISE NOTICE '========================================'; END $$;
DO $$ BEGIN RAISE NOTICE '3. 验证优化结果'; END $$;
DO $$ BEGIN RAISE NOTICE '========================================'; END $$;

DO $$ BEGIN RAISE NOTICE '向量索引:'; END $$;
SELECT indexname, tablename
FROM pg_indexes
WHERE indexname LIKE '%embedding%'
ORDER BY tablename;

DO $$ BEGIN RAISE NOTICE ''; END $$;
DO $$ BEGIN RAISE NOTICE '触发器:'; END $$;
SELECT trigger_name, event_object_table
FROM information_schema.triggers
WHERE event_object_table = 'messages';

DO $$ BEGIN RAISE NOTICE ''; END $$;
DO $$ BEGIN RAISE NOTICE '========================================'; END $$;
DO $$ BEGIN RAISE NOTICE '优化完成！'; END $$;
DO $$ BEGIN RAISE NOTICE '========================================'; END $$;

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

DO $$ BEGIN RAISE NOTICE ''; END $$;
DO $$ BEGIN RAISE NOTICE '下一步操作:'; END $$;
DO $$ BEGIN RAISE NOTICE '1. 重启应用: ./stop.sh && ./start.sh'; END $$;
DO $$ BEGIN RAISE NOTICE '2. 验证功能: curl http://localhost:8080/api/stats'; END $$;
DO $$ BEGIN RAISE NOTICE '3. 查看详细指南: cat OPTIMIZATION_GUIDE.md'; END $$;
