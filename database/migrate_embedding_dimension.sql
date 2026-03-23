-- 迁移 Embedding 向量维度
-- 从固定维度改为支持动态切换的维度配置
-- 
-- 支持的模型维度:
-- - BGE-small-zh-v1.5: 512
-- - Qwen3-Embedding-0.6B: 1024
--
-- 注意: 切换模型后需要重新生成向量数据

-- 1. 删除旧的向量索引
DROP INDEX IF EXISTS idx_messages_embedding;
DROP INDEX IF EXISTS idx_error_embedding;
DROP INDEX IF EXISTS idx_practice_embedding;
DROP INDEX IF EXISTS idx_skill_embedding;

-- 2. 删除旧的向量列
ALTER TABLE messages DROP COLUMN IF EXISTS embedding;
ALTER TABLE error_corrections DROP COLUMN IF EXISTS embedding;
ALTER TABLE user_profiles DROP COLUMN IF EXISTS embedding;
ALTER TABLE best_practices DROP COLUMN IF EXISTS embedding;
ALTER TABLE project_contexts DROP COLUMN IF EXISTS embedding;
ALTER TABLE skills DROP COLUMN IF EXISTS embedding;

-- 3. 添加新的向量列 (使用 1024 维，支持 Qwen3-Embedding)
-- 如果使用 BGE-small，服务端会自动截断或填充
ALTER TABLE messages ADD COLUMN embedding vector(1024);
ALTER TABLE error_corrections ADD COLUMN embedding vector(1024);
ALTER TABLE user_profiles ADD COLUMN embedding vector(1024);
ALTER TABLE best_practices ADD COLUMN embedding vector(1024);
ALTER TABLE project_contexts ADD COLUMN embedding vector(1024);
ALTER TABLE skills ADD COLUMN embedding vector(1024);

-- 4. 重新创建向量索引 (HNSW)
CREATE INDEX IF NOT EXISTS idx_messages_embedding ON messages 
    USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_error_embedding ON error_corrections 
    USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_practice_embedding ON best_practices 
    USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_skill_embedding ON skills 
    USING hnsw (embedding vector_cosine_ops);

-- 5. 添加 embedding_model 列记录生成向量时使用的模型
ALTER TABLE messages ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE error_corrections ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE best_practices ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE project_contexts ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE skills ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);

-- 完成提示
DO $$
BEGIN
    RAISE NOTICE 'Embedding 维度迁移完成';
    RAISE NOTICE '向量维度: 1024 (支持 Qwen3-Embedding)';
    RAISE NOTICE '注意: 切换模型后需要重新生成向量数据';
END $$;
