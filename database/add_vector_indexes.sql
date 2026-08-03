-- AgentMemory 向量索引优化脚本
-- 创建时间: 2026-03-23
-- 说明: 为向量字段添加 HNSW 索引，大幅提升查询性能

-- 注意: 执行前请确保已安装 pgvector 扩展
-- SELECT extversion FROM pg_extension WHERE extname = 'vector';

-- ============================================
-- 1. 错误纠正表索引
-- ============================================

-- 检查并删除旧索引（如果存在）
DO $$
BEGIN
    DROP INDEX IF EXISTS idx_error_corrections_embedding_ivfflat;
    DROP INDEX IF EXISTS idx_error_corrections_embedding_hnsw;
END $$;

-- 创建 HNSW 索引（推荐，查询最快）
CREATE INDEX CONCURRENTLY idx_error_corrections_embedding_hnsw
ON error_corrections
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

COMMENT ON INDEX idx_error_corrections_embedding_hnsw IS
'HNSW 向量索引，用于语义相似度搜索';

-- ============================================
-- 2. 最佳实践表索引
-- ============================================

-- 检查并删除旧索引
DO $$
BEGIN
    DROP INDEX IF EXISTS idx_best_practices_embedding_ivfflat;
    DROP INDEX IF EXISTS idx_best_practices_embedding_hnsw;
END $$;

-- 创建 HNSW 索引
CREATE INDEX CONCURRENTLY idx_best_practices_embedding_hnsw
ON best_practices
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

COMMENT ON INDEX idx_best_practices_embedding_hnsw IS
'HNSW 向量索引，用于语义相似度搜索';

-- ============================================
-- 3. 技能表索引
-- ============================================

-- 检查并删除旧索引
DO $$
BEGIN
    DROP INDEX IF EXISTS idx_skills_embedding_ivfflat;
    DROP INDEX IF EXISTS idx_skills_embedding_hnsw;
END $$;

-- 创建 HNSW 索引
CREATE INDEX CONCURRENTLY idx_skills_embedding_hnsw
ON skills
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

COMMENT ON INDEX idx_skills_embedding_hnsw IS
'HNSW 向量索引，用于语义相似度搜索';

-- ============================================
-- 4. 项目上下文表索引（如果有 embedding 字段）
-- ============================================

-- 检查表是否有 embedding 字段
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'project_contexts'
        AND column_name = 'embedding'
    ) THEN
        DROP INDEX IF EXISTS idx_project_contexts_embedding_hnsw;

        CREATE INDEX CONCURRENTLY idx_project_contexts_embedding_hnsw
        ON project_contexts
        USING hnsw (embedding vector_cosine_ops)
        WITH (m = 16, ef_construction = 64);

        COMMENT ON INDEX idx_project_contexts_embedding_hnsw IS
        'HNSW 向量索引，用于语义相似度搜索';
    END IF;
END $$;

-- ============================================
-- 5. 验证索引创建
-- ============================================

-- 查看所有向量相关索引
SELECT
    schemaname,
    tablename,
    indexname,
    indexdef
FROM pg_indexes
WHERE indexname LIKE '%embedding%'
ORDER BY tablename, indexname;

-- 查看索引大小
SELECT
    tablename,
    indexname,
    pg_size_pretty(pg_relation_size(indexrelid)) as index_size
FROM pg_indexes
WHERE indexname LIKE '%embedding%'
ORDER BY tablename, indexname;

-- ============================================
-- 6. 性能测试建议
-- ============================================

-- 测试查询性能（执行前先记下时间）
EXPLAIN ANALYZE
SELECT title, 1 - (embedding <=> '[0.1,0.2,0.3]'::vector) as similarity
FROM error_corrections
WHERE embedding IS NOT NULL AND (deleted = false OR deleted IS NULL)
ORDER BY similarity DESC
LIMIT 10;

-- 预期结果:
-- 创建索引前: 100-1000ms（取决于数据量）
-- 创建索引后: 1-10ms

-- ============================================
-- 索引参数说明
-- ============================================

-- HNSW 索引参数:
-- m = 16: 每个节点的最大连接数（推荐 8-32）
-- ef_construction = 64: 构建索引时的搜索范围（推荐 32-128）
--
-- 选择建议:
-- - m 越大，索引越大，但搜索越准确
-- - ef_construction 越大，索引质量越好，但构建越慢
--
-- 查询时可以调整:
-- SET hnsw.ef_search = 100;  -- 提高查询精度（默认 40）
--   值越大，查询越准确，但越慢

COMMIT;
