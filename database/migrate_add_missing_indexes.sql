-- 迁移：添加缺失的索引
-- 执行时间: 2026-05-19
-- 说明: 添加外键索引和软删除字段索引，提升查询性能

-- 外键索引
CREATE INDEX IF NOT EXISTS idx_sessions_agent_id ON sessions(agent_id);
CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id);

-- 软删除字段索引
CREATE INDEX IF NOT EXISTS idx_sessions_deleted ON sessions(deleted);
CREATE INDEX IF NOT EXISTS idx_messages_deleted ON messages(deleted);

-- 记忆库表软删除索引
CREATE INDEX IF NOT EXISTS idx_error_deleted ON error_corrections(deleted);
CREATE INDEX IF NOT EXISTS idx_profile_deleted ON user_profiles(deleted);
CREATE INDEX IF NOT EXISTS idx_practice_deleted ON best_practices(deleted);
CREATE INDEX IF NOT EXISTS idx_context_deleted ON project_contexts(deleted);
CREATE INDEX IF NOT EXISTS idx_skill_deleted ON skills(deleted);

-- sessions 时间索引（用于排序查询）
CREATE INDEX IF NOT EXISTS idx_sessions_created_at ON sessions(created_at DESC);
