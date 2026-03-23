-- AgentMemory 数据库初始化脚本
-- PostgreSQL + pgvector

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- Agent 信息表
CREATE TABLE IF NOT EXISTS agents (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    display_name VARCHAR(200),
    log_base_path TEXT,
    cli_path TEXT,
    version VARCHAR(50),
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 会话表
CREATE TABLE IF NOT EXISTS sessions (
    id VARCHAR(100) PRIMARY KEY,
    agent_id INTEGER REFERENCES agents(id),
    agent_type VARCHAR(50),
    project_path TEXT,
    workspace_path TEXT,
    date_key DATE,
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    message_count INTEGER DEFAULT 0,
    title VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP + INTERVAL '14 days',
    deleted BOOLEAN DEFAULT false
);

-- 消息表
CREATE TABLE IF NOT EXISTS messages (
    id VARCHAR(100) PRIMARY KEY,
    session_id VARCHAR(100) REFERENCES sessions(id),
    parent_id VARCHAR(100),
    role VARCHAR(20) NOT NULL,
    content TEXT,
    raw_json JSONB,
    timestamp TIMESTAMP,
    date_key DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP + INTERVAL '14 days',
    deleted BOOLEAN DEFAULT false
);

-- 向量列（用于语义搜索）
ALTER TABLE messages ADD COLUMN IF NOT EXISTS embedding vector(1536);

-- ===== 五大记忆库表 =====

-- 1. 错误纠正库
CREATE TABLE IF NOT EXISTS error_corrections (
    id VARCHAR(100) PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    problem TEXT NOT NULL,
    cause TEXT,
    solution TEXT NOT NULL,
    example TEXT,
    tags TEXT[],
    agent_type VARCHAR(50),
    session_id VARCHAR(100),
    embedding vector(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP + INTERVAL '30 days',
    deleted BOOLEAN DEFAULT false,
    visit_count INTEGER DEFAULT 0
);

-- 2. 用户画像库
CREATE TABLE IF NOT EXISTS user_profiles (
    id VARCHAR(100) PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    category VARCHAR(100),
    items JSONB DEFAULT '[]'::jsonb,
    confidence FLOAT DEFAULT 1.0,
    embedding vector(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. 实践经验库
CREATE TABLE IF NOT EXISTS best_practices (
    id VARCHAR(100) PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    scenario TEXT NOT NULL,
    practice TEXT NOT NULL,
    rationale TEXT,
    tags TEXT[],
    source_session VARCHAR(100),
    embedding vector(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP + INTERVAL '30 days',
    deleted BOOLEAN DEFAULT false,
    visit_count INTEGER DEFAULT 0
);

-- 4. 项目上下文库
CREATE TABLE IF NOT EXISTS project_contexts (
    id VARCHAR(100) PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    project_path TEXT NOT NULL,
    tech_stack TEXT[],
    key_decisions JSONB DEFAULT '[]'::jsonb,
    structure JSONB DEFAULT '{}'::jsonb,
    embedding vector(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 5. 技能沉淀库
CREATE TABLE IF NOT EXISTS skills (
    id VARCHAR(100) PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    skill_type VARCHAR(100),
    description TEXT,
    steps JSONB DEFAULT '[]'::jsonb,
    tags TEXT[],
    embedding vector(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    visit_count INTEGER DEFAULT 0
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);
CREATE INDEX IF NOT EXISTS idx_messages_date ON messages(date_key);
CREATE INDEX IF NOT EXISTS idx_messages_role ON messages(role);
CREATE INDEX IF NOT EXISTS idx_messages_expires ON messages(expires_at);
CREATE INDEX IF NOT EXISTS idx_sessions_agent ON sessions(agent_type);
CREATE INDEX IF NOT EXISTS idx_sessions_date ON sessions(date_key);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);

-- 记忆库索引
CREATE INDEX IF NOT EXISTS idx_error_tags ON error_corrections USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_error_session ON error_corrections(session_id);
CREATE INDEX IF NOT EXISTS idx_profile_category ON user_profiles(category);
CREATE INDEX IF NOT EXISTS idx_practice_tags ON best_practices USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_context_path ON project_contexts(project_path);
CREATE INDEX IF NOT EXISTS idx_skill_type ON skills(skill_type);
CREATE INDEX IF NOT EXISTS idx_skill_tags ON skills USING GIN(tags);

-- 向量索引（使用 HNSW 算法，性能更好）
-- 如果数据量小于 1000 条，可以暂时不创建索引
-- HNSW 索引适合高召回率场景
CREATE INDEX IF NOT EXISTS idx_messages_embedding ON messages 
    USING hnsw (embedding vector_cosine_ops);

-- 记忆库向量索引
CREATE INDEX IF NOT EXISTS idx_error_embedding ON error_corrections 
    USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_practice_embedding ON best_practices 
    USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_skill_embedding ON skills 
    USING hnsw (embedding vector_cosine_ops);

-- 插入默认 Agent
INSERT INTO agents (name, display_name, enabled) VALUES
    ('iflow', 'iFlow CLI', true),
    ('claude', 'Claude Code', true),
    ('qwen', 'Qwen CLI', true),
    ('qoder', 'Qoder CLI', true),
    ('openclaw', 'OpenClaw', true),
    ('nanobot', 'Nanobot', true)
ON CONFLICT (name) DO NOTHING;

-- 更新时间触发器
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_agents_updated_at
    BEFORE UPDATE ON agents
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_sessions_updated_at
    BEFORE UPDATE ON sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_profiles_updated_at
    BEFORE UPDATE ON user_profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_contexts_updated_at
    BEFORE UPDATE ON project_contexts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();