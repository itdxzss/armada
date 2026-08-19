-- 仅回滚 V131 的主数据赋值；不会恢复已经删除的手机号州推断运行时逻辑。
-- 精确回滚前提：执行 V131 前 AQ/BV/HM/TF 的 continent_code 均为 NULL（V098 基线）。
SELECT iso2, continent_code
FROM country
WHERE deleted_at IS NULL
  AND iso2 IN ('AQ', 'BV', 'HM', 'TF')
ORDER BY iso2;

UPDATE country
SET continent_code = NULL
WHERE deleted_at IS NULL
  AND iso2 IN ('AQ', 'BV', 'HM', 'TF')
  AND continent_code = 'ANTARCTICA';

SELECT iso2, continent_code
FROM country
WHERE deleted_at IS NULL
  AND iso2 IN ('AQ', 'BV', 'HM', 'TF')
ORDER BY iso2;
