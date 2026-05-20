-- 查找明确的用户纠正消息（排除控制台噪声）
SELECT id, LEFT(content, 200) as snippet, session_id, created_at
FROM messages
WHERE role = 'user'
  AND deleted = false
  AND content ~ '(不是|不对|错了|纠正|重新|理解错|你要改成|不要这样|我是说|我是指)'
  AND content !~ '(不是内部或外部命令|Conversation info|```json|```markdown|```bash|保留所有权利)'
  AND LENGTH(content) BETWEEN 5 AND 500
ORDER BY created_at DESC
LIMIT 20;
