-- AgentMemory 性能优化脚本
-- 创建时间: 2026-03-23
-- 说明: 使用触发器自动更新会话消息计数，消除手动更新查询

-- ============================================
-- 1. 创建自动更新消息计数的触发器
-- ============================================

-- 删除旧的触发器（如果存在）
DROP TRIGGER IF EXISTS update_session_message_count ON messages;

-- 创建触发器：每次插入消息时自动更新会话的消息计数
CREATE TRIGGER update_session_message_count
AFTER INSERT ON messages
FOR EACH ROW
BEGIN
    -- 如果是新消息，增加计数
    INSERT INTO sessions (id, message_count, updated_at)
    VALUES (NEW.session_id, 1, CURRENT_TIMESTAMP)
    ON CONFLICT (id) DO UPDATE SET
        message_count = sessions.message_count + 1,
        updated_at = CURRENT_TIMESTAMP;
END;

-- ============================================
-- 2. 创建优化消息计数的函数（用于批量导入）
-- ============================================

-- 删除旧的函数（如果存在）
DROP FUNCTION IF EXISTS update_session_counts_batch();

-- 创建批量更新函数
CREATE OR REPLACE FUNCTION update_session_counts_batch()
RETURNS void AS $$
BEGIN
    -- 批量更新所有会话的消息计数
    UPDATE sessions s
    SET message_count = (
        SELECT COUNT(*)
        FROM messages m
        WHERE m.session_id = s.id
    ),
    updated_at = CURRENT_TIMESTAMP
    WHERE s.id IN (
        SELECT DISTINCT session_id
        FROM messages
        WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '1 day'
    );
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- 3. 验证触发器
-- ============================================

-- 查看触发器信息
SELECT
    trigger_name,
    event_manipulation,
    event_object_table,
    action_statement
FROM information_schema.triggers
WHERE event_object_table = 'messages';

-- ============================================
-- 4. 性能测试
-- ============================================

-- 测试插入消息（应该自动更新计数）
INSERT INTO messages (id, session_id, role, content, timestamp)
VALUES ('test-msg-001', 'test-session-001', 'user', 'Test message', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 检查会话计数（应该自动增加）
SELECT id, message_count, updated_at
FROM sessions
WHERE id = 'test-session-001';

-- 清理测试数据
DELETE FROM messages WHERE id = 'test-msg-001';
DELETE FROM sessions WHERE id = 'test-session-001';

-- ============================================
-- 5. 创建索引以优化触发器性能
-- ============================================

-- 为 sessions 表添加索引（如果不存在）
CREATE INDEX IF NOT EXISTS idx_sessions_updated_at
ON sessions(updated_at DESC);

-- 为 messages 表添加复合索引（如果不存在）
CREATE INDEX IF NOT EXISTS idx_messages_session_created
ON messages(session_id, created_at DESC);

-- ============================================
-- 6. 优化说明
-- ============================================

-- 使用触发器的好处：
-- 1. 自动化：无需手动更新计数
-- 2. 原子性：计数更新与消息插入在同一事务中
-- 3. 性能：减少应用层的查询次数
--
-- 优化后的 saveMessage 流程：
-- 1. 开始事务
-- 2. UPSERT 会话（1次查询）
-- 3. 插入消息（1次查询）→ 触发器自动更新计数
-- 4. 提交事务
--
-- 总共：2次查询（相比之前的3次）
-- 性能提升：约 33%

COMMIT;
