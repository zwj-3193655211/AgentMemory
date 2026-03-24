-- 添加 parser_type 字段，支持自定义 Agent 解析器类型
-- 2026-03-24

ALTER TABLE agents ADD COLUMN IF NOT EXISTS parser_type VARCHAR(50) DEFAULT 'openclaw';

-- 更新现有 Agent 的 parser_type
UPDATE agents SET parser_type = 'openclaw' WHERE parser_type IS NULL;
UPDATE agents SET parser_type = 'iflow' WHERE name = 'iflow';
UPDATE agents SET parser_type = 'claude' WHERE name = 'claude';
UPDATE agents SET parser_type = 'qwen' WHERE name IN ('qwen', 'qoder');
