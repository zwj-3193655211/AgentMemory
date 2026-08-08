-- =====================================================
-- 记忆系统重构迁移脚本 (migrate_memory_reorg.sql)
-- 1. user_profiles 加来源字段（agent 记忆文件导入）
-- 2. 新建 experiences 表（合并 error_corrections + best_practices）
-- 3. skills 加状态字段（LLM 候选 + 人工确认）
-- 4. 删除 project_contexts
-- 5. agents 表加记忆源字段（8 agent 全接入）
-- =====================================================

-- 1. user_profiles 加来源字段
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS source_agent VARCHAR(50);
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS source_path TEXT;
-- 来源去重索引（同源同内容不重复导入）
CREATE INDEX IF NOT EXISTS idx_profiles_source ON user_profiles(source_agent, source_path);

-- 2. experiences 新表（合并 error_corrections + best_practices）
CREATE TABLE IF NOT EXISTS experiences (
    id VARCHAR(100) PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    type VARCHAR(20) NOT NULL,          -- 'best_practice' | 'error_correction'
    scenario TEXT NOT NULL,             -- 原 problem/scenario
    practice TEXT NOT NULL,             -- 原 solution/practice
    rationale TEXT,                     -- 原 cause/rationale
    example TEXT,
    tags TEXT[],
    source_session VARCHAR(100),
    embedding vector(1024),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_experiences_type ON experiences(type);
CREATE INDEX IF NOT EXISTS idx_experiences_tags ON experiences USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_experiences_embedding ON experiences USING hnsw (embedding vector_cosine_ops);

-- 3. skills 加状态字段（LLM 候选 pending/approved/rejected）
ALTER TABLE skills ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'approved';
ALTER TABLE skills ADD COLUMN IF NOT EXISTS extracted_by VARCHAR(20) DEFAULT 'manual';
CREATE INDEX IF NOT EXISTS idx_skills_status ON skills(status);

-- 4. 迁移旧数据到 experiences
INSERT INTO experiences (id, title, type, scenario, practice, rationale, example, tags, source_session, embedding, created_at)
SELECT 'ec_' || id, title, 'error_correction', problem, solution, cause, example, tags, session_id, embedding, created_at
FROM error_corrections
WHERE deleted = false OR deleted IS NULL
ON CONFLICT (id) DO NOTHING;

INSERT INTO experiences (id, title, type, scenario, practice, rationale, tags, source_session, embedding, created_at)
SELECT 'bp_' || id, title, 'best_practice', scenario, practice, rationale, tags, source_session, embedding, created_at
FROM best_practices
ON CONFLICT (id) DO NOTHING;

-- 5. 删除旧表（project_contexts 直接删除；error_corrections/best_practices 数据已迁移后删除）
DROP TABLE IF EXISTS project_contexts;
DROP TABLE IF EXISTS error_corrections;
DROP TABLE IF EXISTS best_practices;

-- 6. agents 表加记忆源字段（8 agent 全接入）
ALTER TABLE agents ADD COLUMN IF NOT EXISTS memory_sources JSONB;   -- [{"path":"...","format":"markdown"|"sqlite"|"jsonl","table":"..."}]
ALTER TABLE agents ADD COLUMN IF NOT EXISTS session_db_path TEXT;   -- SQLite 会话库路径（hermes/mavis/marvis）

-- 7. 注册新 agent（hermes/mavis/marvis/minimax），已有 agent 不重复
INSERT INTO agents (name, display_name, parser_type, enabled) VALUES
    ('hermes',  'Hermes',       'sqlite', true),
    ('mavis',   'Mavis',        'sqlite', true),
    ('marvis',  'Marvis',       'sqlite', true),
    ('minimax', 'MiniMax Code', 'jsonl',  true)
ON CONFLICT (name) DO NOTHING;
