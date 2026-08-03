-- ============================================
-- 错误纠正库前缀清理脚本
-- 清理 title/problem/solution 字段中的残留前缀
-- ============================================

-- 先查看有多少条记录带有前缀
SELECT
  COUNT(*) FILTER (WHERE title LIKE '纠正：%' OR title LIKE '纠正:%') AS title_with_prefix,
  COUNT(*) FILTER (WHERE problem LIKE 'AI认为：%' OR problem LIKE 'AI认为:%' OR problem LIKE 'AI的回答%') AS problem_with_prefix,
  COUNT(*) FILTER (WHERE solution LIKE '正确答案：%' OR solution LIKE '正确答案:%' OR solution LIKE '正确答案是%') AS solution_with_prefix,
  COUNT(*) AS total
FROM error_corrections
WHERE deleted = false;

-- ============================================
-- 1. 清理 title 字段中的 "纠正：" 前缀
-- ============================================
UPDATE error_corrections
SET title = TRIM(SUBSTRING(title FROM 4))
WHERE (title LIKE '纠正：%' OR title LIKE '纠正:%')
  AND deleted = false;

-- ============================================
-- 2. 清理 problem 字段中的 "AI认为：" / "AI的回答是：" 前缀
-- ============================================
UPDATE error_corrections
SET problem = TRIM(SUBSTRING(problem FROM 6))
WHERE (problem LIKE 'AI认为：%' OR problem LIKE 'AI认为:%')
  AND deleted = false;

UPDATE error_corrections
SET problem = TRIM(SUBSTRING(problem FROM 8))
WHERE (problem LIKE 'AI的回答是：%' OR problem LIKE 'AI的回答是:%')
  AND deleted = false;

-- ============================================
-- 3. 清理 solution 字段中的 "正确答案：" / "正确答案是：" 前缀
-- ============================================
UPDATE error_corrections
SET solution = TRIM(SUBSTRING(solution FROM 6))
WHERE (solution LIKE '正确答案：%' OR solution LIKE '正确答案:%')
  AND deleted = false;

UPDATE error_corrections
SET solution = TRIM(SUBSTRING(solution FROM 7))
WHERE (solution LIKE '正确答案是：%' OR solution LIKE '正确答案是:%')
  AND deleted = false;

-- ============================================
-- 4. 清理其他可能的冗余前缀（兜底）
-- ============================================
-- solution 中的 "而是" 开头
UPDATE error_corrections
SET solution = TRIM(SUBSTRING(solution FROM 3))
WHERE solution LIKE '而是%'
  AND deleted = false;

-- solution 中的 "应该用" 开头
UPDATE error_corrections
SET solution = TRIM(SUBSTRING(solution FROM 4))
WHERE solution LIKE '应该用%'
  AND deleted = false;

-- title 中的 "纠正约束：" 前缀
UPDATE error_corrections
SET title = TRIM(SUBSTRING(title FROM 6))
WHERE (title LIKE '纠正约束：%' OR title LIKE '纠正约束:%')
  AND deleted = false;

-- ============================================
-- 验证：查看清理后的结果
-- ============================================
SELECT
  COUNT(*) FILTER (WHERE title LIKE '纠正：%' OR title LIKE '纠正:%') AS title_remaining,
  COUNT(*) FILTER (WHERE problem LIKE 'AI认为：%' OR problem LIKE 'AI认为:%') AS problem_remaining,
  COUNT(*) FILTER (WHERE solution LIKE '正确答案：%' OR solution LIKE '正确答案:%') AS solution_remaining,
  COUNT(*) AS total
FROM error_corrections
WHERE deleted = false;
