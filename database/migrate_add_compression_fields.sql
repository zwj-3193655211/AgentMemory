-- 添加 compression_config 表缺少的字段
-- 执行时间：2026-03-22

-- 添加 compression_type 字段
ALTER TABLE compression_config 
ADD COLUMN IF NOT EXISTS compression_type VARCHAR(20) DEFAULT 'SLIDING_WINDOW';

-- 添加 llm_provider 字段（存储选中的 LLM Provider 名称，__builtin__ 表示使用内置模型）
ALTER TABLE compression_config 
ADD COLUMN IF NOT EXISTS llm_provider VARCHAR(100) DEFAULT '__builtin__';

-- 添加注释
COMMENT ON COLUMN compression_config.compression_type IS '压缩类型：SLIDING_WINDOW(滑动窗口), SUMMARIZE(LLM摘要), HYBRID(混合)';
COMMENT ON COLUMN compression_config.llm_provider IS '使用的 LLM Provider 名称，__builtin__ 表示使用内置 embedding_service';
