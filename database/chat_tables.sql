-- 聊天功能表
-- 对话会话和消息持久化

-- 聊天会话表
CREATE TABLE IF NOT EXISTS chat_sessions (
    id VARCHAR(64) PRIMARY KEY,
    agent_type VARCHAR(50) NOT NULL,       -- agent 类型: ollama/claude/qwen/crush/deepseek/openai 等
    agent_name VARCHAR(100) NOT NULL,      -- agent 显示名称
    title VARCHAR(255),                     -- 会话标题（取首条消息摘要）
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN DEFAULT FALSE
);

-- 聊天消息表
CREATE TABLE IF NOT EXISTS chat_messages (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES chat_sessions(id),
    role VARCHAR(20) NOT NULL,             -- user / assistant / system
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON chat_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_deleted ON chat_sessions(deleted);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated ON chat_sessions(updated_at DESC);
