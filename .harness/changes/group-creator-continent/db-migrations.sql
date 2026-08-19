-- 正式迁移脚本：
-- armada-api/src/main/resources/db/migration/V131__country_antarctica_continent.sql

SELECT iso2, continent_code
FROM country
WHERE deleted_at IS NULL
  AND iso2 IN ('AQ', 'BV', 'HM', 'TF')
ORDER BY iso2;

UPDATE country
SET continent_code = 'ANTARCTICA'
WHERE deleted_at IS NULL
  AND iso2 IN ('AQ', 'BV', 'HM', 'TF')
  AND continent_code IS NULL;

SELECT iso2, continent_code
FROM country
WHERE deleted_at IS NULL
  AND iso2 IN ('AQ', 'BV', 'HM', 'TF')
ORDER BY iso2;
