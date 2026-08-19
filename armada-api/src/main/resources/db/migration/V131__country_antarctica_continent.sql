-- V131：补齐国家主数据的第七大洲口径。
-- V098 曾把南极相关 ISO2 保留为空；当前群列表按国家所属洲展示，统一改为 ANTARCTICA。

UPDATE country
SET continent_code = 'ANTARCTICA'
WHERE deleted_at IS NULL
  AND iso2 IN ('AQ', 'BV', 'HM', 'TF')
  AND continent_code IS NULL;
