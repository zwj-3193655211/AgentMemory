-- 为 compression_history 添加软删除字段
ALTER TABLE compression_history ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE;
ALTER TABLE compression_history ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;

-- 创建索引加速查询
CREATE INDEX IF NOT EXISTS idx_compression_history_deleted ON compression_history(deleted);
