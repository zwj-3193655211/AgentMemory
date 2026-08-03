-- 清理 error_corrections 表中已有的冗余前缀
UPDATE error_corrections SET
  title = regexp_replace(title, '^(纠正[：:]|纠正约束[：:])\s*', ''),
  problem = regexp_replace(problem, '^(AI认为[：:]|AI的错误方案[：:]|AI错误做法[：:]|AI的约束违规[：:]|AI误解为[：:]|用户纠正)\s*', ''),
  solution = regexp_replace(solution, '^(正确答案[：:]|正确做法[：:]|正确方案[：:]|约束要求[：:]|用户实际意思[：:])\s*', '')
WHERE problem LIKE 'AI%' OR solution LIKE '正确%' OR title LIKE '纠正%';

-- 清理 best_practices 表中类似前缀（如果有的话）
UPDATE best_practices SET
  title = regexp_replace(title, '^(纠正[：:])\s*', '')
WHERE title LIKE '纠正%';
