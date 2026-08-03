-- 增量压缩支持字段
-- 执行时间：2026-08-04

-- session_summaries 表增加增量压缩字段
ALTER TABLE session_summaries
    ADD COLUMN IF NOT EXISTS last_compressed_count INTEGER DEFAULT 0;

COMMENT ON COLUMN session_summaries.last_compressed_count IS '上次压缩时的消息总数，用于增量压缩（只处理新增消息）';
