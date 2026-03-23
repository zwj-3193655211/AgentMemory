-- AgentMemory 上下文压缩功能迁移脚本
-- 会话摘要存储 - 用于长会话的滑动窗口和摘要压缩
-- 执行时间：2026-03-22

-- ===== 创建会话摘要表 =====

-- 存储会话压缩后的摘要信息
CREATE TABLE IF NOT EXISTS session_summaries (
    id SERIAL PRIMARY KEY,
    session_id VARCHAR(100) REFERENCES sessions(id) ON DELETE CASCADE,
    summary TEXT NOT NULL,                       -- 摘要内容
    compression_type VARCHAR(20) NOT NULL,      -- SLIDING_WINDOW / SUMMARIZE / HYBRID
    original_message_count INTEGER DEFAULT 0,    -- 压缩前消息数
    compressed_message_count INTEGER DEFAULT 0,   -- 压缩后保留的消息数
    window_size INTEGER DEFAULT 50,              -- 滑动窗口大小
    first_message_timestamp TIMESTAMP,           -- 会话开始时间
    last_message_timestamp TIMESTAMP,            -- 最后消息时间
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 1,                   -- 摘要版本，每次压缩递增
    deleted BOOLEAN DEFAULT false
);

-- 唯一约束：每个会话每个版本只能有一个摘要
CREATE UNIQUE INDEX IF NOT EXISTS idx_session_summaries_session_version 
    ON session_summaries(session_id, version) 
    WHERE deleted = false;

-- 会话索引
CREATE INDEX IF NOT EXISTS idx_session_summaries_session 
    ON session_summaries(session_id) 
    WHERE deleted = false;

-- 压缩类型索引
CREATE INDEX IF NOT EXISTS idx_session_summaries_type 
    ON session_summaries(compression_type) 
    WHERE deleted = false;

-- ===== 创建压缩配置表 =====

-- 存储压缩策略配置
CREATE TABLE IF NOT EXISTS compression_config (
    id SERIAL PRIMARY KEY,
    config_key VARCHAR(50) PRIMARY KEY,
    enabled BOOLEAN DEFAULT true,
    window_size INTEGER DEFAULT 50,               -- 滑动窗口：保留最近N条
    summary_threshold INTEGER DEFAULT 100,       -- 触发阈值：超过此消息数开始压缩
    auto_compress BOOLEAN DEFAULT true,          -- 是否自动压缩
    schedule_cron VARCHAR(50) DEFAULT '0 2 * * *', -- 定时任务 cron
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 插入默认配置
INSERT INTO compression_config (config_key, enabled, window_size, summary_threshold, auto_compress, schedule_cron) 
VALUES ('session_compression', true, 50, 100, true, '0 2 * * *')
ON CONFLICT (config_key) DO NOTHING;

-- ===== 创建 LLM Provider 配置表 =====

-- 存储 LLM Provider 配置
CREATE TABLE IF NOT EXISTS llm_providers (
    id SERIAL PRIMARY KEY,
    provider_name VARCHAR(50) NOT NULL,          -- PROVIDER 名称: local / openai / deepseek / ollama
    display_name VARCHAR(100),                   -- 显示名称
    base_url TEXT,                              -- API 基础地址
    api_key VARCHAR(500),                       -- API Key (加密存储)
    model VARCHAR(100),                         -- 模型名称
    enabled BOOLEAN DEFAULT true,
    is_default BOOLEAN DEFAULT false,            -- 是否为默认 Provider
    config JSONB DEFAULT '{}'::jsonb,           -- 额外配置
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(provider_name)
);

-- 插入默认本地 Provider (Qwen3)
INSERT INTO llm_providers (provider_name, display_name, model, enabled, is_default) 
VALUES ('local', '本地 Qwen3', 'qwen3:0.6b', true, false)
ON CONFLICT (provider_name) DO NOTHING;

-- ===== 创建压缩历史记录表 =====

-- 记录压缩操作的历史
CREATE TABLE IF NOT EXISTS compression_history (
    id SERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    operation VARCHAR(20) NOT NULL,             -- COMPRESS / RESTORE / SUMMARIZE
    compression_type VARCHAR(20),                 -- SLIDING_WINDOW / SUMMARIZE / HYBRID
    message_count_before INTEGER,               -- 压缩前消息数
    message_count_after INTEGER,                -- 压缩后消息数
    summary TEXT,                               -- 生成的摘要
    llm_provider VARCHAR(50),                   -- 使用的 LLM Provider
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 压缩历史索引
CREATE INDEX IF NOT EXISTS idx_compression_history_session 
    ON compression_history(session_id);
CREATE INDEX IF NOT EXISTS idx_compression_history_date 
    ON compression_history(created_at);

-- ===== 更新 sessions 表添加压缩相关字段 =====

ALTER TABLE sessions
ADD COLUMN IF NOT EXISTS is_compressed BOOLEAN DEFAULT false,
ADD COLUMN IF NOT EXISTS compression_type VARCHAR(20),
ADD COLUMN IF NOT EXISTS compressed_at TIMESTAMP;

-- ===== 注释 =====
COMMENT ON TABLE session_summaries IS '会话摘要表，存储长会话压缩后的摘要信息';
COMMENT ON COLUMN session_summaries.compression_type IS '压缩类型：SLIDING_WINDOW(滑动窗口), SUMMARIZE(摘要), HYBRID(混合)';
COMMENT ON COLUMN session_summaries.window_size IS '滑动窗口大小，只保留最近N条消息完整';
COMMENT ON COLUMN session_summaries.version IS '摘要版本，每次压缩更新时递增';

COMMENT ON TABLE llm_providers IS 'LLM Provider 配置表，支持本地模型和外部 API';
COMMENT ON COLUMN llm_providers.provider_name IS 'Provider 标识：local(本地), openai, deepseek, ollama 等';
