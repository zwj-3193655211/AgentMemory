-- 从 messages 表提取错误纠正，批量插入 error_corrections
-- 仅处理有明确纠正结构的消息，排除控制台噪声

BEGIN;

-- 1. "不是X，是Y" 模式（包含"而是"的更精确）
INSERT INTO error_corrections (id, title, problem, solution, agent_type, session_id, created_at, updated_at, deleted, visit_count)
SELECT
  gen_random_uuid(),
  LEFT(problem_part, 50),
  problem_part,
  solution_part,
  s.agent_type,
  m.session_id,
  m.created_at,
  m.created_at,
  false,
  0
FROM messages m
LEFT JOIN sessions s ON m.session_id = s.id
CROSS JOIN LATERAL (
  SELECT
    -- 问题部分："不是" 到 "而是/是" 之前
    TRIM(
      SUBSTRING(m.content FROM
        (CASE
          WHEN m.content ~ '不是.*而是' THEN POSITION('不是' IN m.content)
          WHEN m.content ~ '不是.*是' AND m.content !~ '不是是' THEN POSITION('不是' IN m.content)
        END)
        FOR (
          CASE
            WHEN m.content ~ '不是.*而是' THEN POSITION('而是' IN m.content) - POSITION('不是' IN m.content)
            WHEN m.content ~ '不是.*是[^不]' THEN POSITION(SUBSTRING(m.content FROM POSITION('不是' IN m.content) + 2 FOR 1) IN m.content) - 1
          END
        )
      )
    ) AS problem_part,
    -- 解决方案部分："而是/是" 之后到句尾
    TRIM(
      SUBSTRING(m.content FROM
        (CASE
          WHEN m.content ~ '不是.*而是' THEN POSITION('而是' IN m.content) + 2
          WHEN m.content ~ '不是.*是[^不]' THEN POSITION(SUBSTRING(m.content FROM POSITION('不是' IN m.content) + 2 FOR 1) IN m.content) + POSITION('不是' IN m.content) + 1
        END)
      )
    ) AS solution_part
) parts
WHERE m.role = 'user'
  AND m.deleted = false
  AND m.content ~ '不是.*而是'
  AND LENGTH(m.content) BETWEEN 5 AND 800
  AND m.content !~ '(不是内部或外部命令|Conversation info|```json|```markdown|```bash|保留所有权利)'
  AND parts.problem_part IS NOT NULL
  AND LENGTH(parts.problem_part) >= 3
  AND LENGTH(parts.solution_part) >= 3
  AND parts.problem_part != parts.solution_part
ON CONFLICT DO NOTHING;

-- 2. "不要X，要Y" 或 "不要X，应该Y" 模式
INSERT INTO error_corrections (id, title, problem, solution, agent_type, session_id, created_at, updated_at, deleted, visit_count)
SELECT
  gen_random_uuid(),
  LEFT(problem_part, 50),
  problem_part,
  solution_part,
  s.agent_type,
  m.session_id,
  m.created_at,
  m.created_at,
  false,
  0
FROM messages m
LEFT JOIN sessions s ON m.session_id = s.id
CROSS JOIN LATERAL (
  SELECT
    TRIM(SUBSTRING(m.content FROM 1 FOR want_pos - 1)) AS problem_part,
    TRIM(SUBSTRING(m.content FROM want_pos + COALESCE((SELECT MAX(LENGTH(kw)) FROM unnest(ARRAY['应该用','应该','要用','要','改用','改成','改为']) kw WHERE SUBSTRING(m.content FROM want_pos FOR LENGTH(kw)) = kw), 1))) AS solution_part
  FROM (
    SELECT
      MAX(CASE WHEN SUBSTRING(m.content FROM pos FOR 2) = '要' AND SUBSTRING(m.content FROM pos FOR 3) NOT IN ('要是','要改') THEN pos
               WHEN SUBSTRING(m.content FROM pos FOR 2) = '应该' THEN pos
               WHEN SUBSTRING(m.content FROM pos FOR 3) = '应该用' THEN pos
               WHEN SUBSTRING(m.content FROM pos FOR 2) = '改用' THEN pos
               WHEN SUBSTRING(m.content FROM pos FOR 2) = '改成' THEN pos END) AS want_pos
    FROM generate_series(1, LENGTH(m.content)) AS pos
    WHERE pos > 2 AND (
      SUBSTRING(m.content FROM GREATEST(1, pos-1) FOR 2) = '不要'
      OR SUBSTRING(m.content FROM GREATEST(1, pos-1) FOR 1) = '别'
      OR SUBSTRING(m.content FROM GREATEST(1, pos-1) FOR 2) = '不能'
    )
  ) pre
  WHERE want_pos IS NOT NULL
) parts
WHERE m.role = 'user'
  AND m.deleted = false
  AND m.content ~ '(不要|别|不能)'
  AND m.content ~ '(要|应该|改用|改成)'
  AND LENGTH(m.content) BETWEEN 5 AND 800
  AND m.content !~ '(不是内部或外部命令|Conversation info|```json|保留所有权利)'
  AND parts.problem_part IS NOT NULL
  AND LENGTH(parts.problem_part) >= 2
  AND LENGTH(parts.solution_part) >= 2
ON CONFLICT DO NOTHING;

-- 3. "不对" 或 "错了" 直接纠错模式
INSERT INTO error_corrections (id, title, problem, solution, agent_type, session_id, created_at, updated_at, deleted, visit_count)
SELECT
  gen_random_uuid(),
  LEFT(problem_part, 50),
  problem_part,
  solution_part,
  s.agent_type,
  m.session_id,
  m.created_at,
  m.created_at,
  false,
  0
FROM messages m
LEFT JOIN sessions s ON m.session_id = s.id
CROSS JOIN LATERAL (
  SELECT
    TRIM(SUBSTRING(m.content FROM 1 FOR fix_pos - 1)) AS problem_part,
    TRIM(SUBSTRING(m.content FROM fix_pos + COALESCE((SELECT MAX(LENGTH(kw)) FROM unnest(ARRAY['应该用','应该','要用','要','改用','改成','改为','改回去']) kw WHERE SUBSTRING(m.content FROM fix_pos FOR LENGTH(kw)) = kw), 1))) AS solution_part
  FROM (
    SELECT
      MAX(CASE WHEN SUBSTRING(m.content FROM pos FOR 2) = '不对' THEN pos
               WHEN SUBSTRING(m.content FROM pos FOR 2) = '错了' THEN pos
               WHEN SUBSTRING(m.content FROM pos FOR 3) = '搞错了' THEN pos END) AS fix_pos
    FROM generate_series(1, LENGTH(m.content)) AS pos
  ) pre
  WHERE fix_pos IS NOT NULL
) parts
WHERE m.role = 'user'
  AND m.deleted = false
  AND m.content ~ '(不对|错了|搞错了)'
  AND LENGTH(m.content) BETWEEN 5 AND 800
  AND m.content !~ '(不是内部或外部命令|Conversation info|```json|保留所有权利)'
  AND parts.problem_part IS NOT NULL
  AND LENGTH(parts.problem_part) >= 2
  AND LENGTH(parts.solution_part) >= 2
  AND parts.problem_part != parts.solution_part
ON CONFLICT DO NOTHING;

COMMIT;

-- 验证结果
SELECT COUNT(*) as total_error_corrections FROM error_corrections WHERE deleted = false;
