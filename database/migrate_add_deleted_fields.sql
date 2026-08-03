-- 添加 deleted 字段到缺失的表
ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT false;
ALTER TABLE project_contexts ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT false;
ALTER TABLE skills ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT false;

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_user_profiles_deleted ON user_profiles(deleted);
CREATE INDEX IF NOT EXISTS idx_project_contexts_deleted ON project_contexts(deleted);
CREATE INDEX IF NOT EXISTS idx_skills_deleted ON skills(deleted);
