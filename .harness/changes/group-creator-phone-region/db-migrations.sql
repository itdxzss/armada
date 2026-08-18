-- 正式 Flyway 迁移：
-- armada-api/src/main/resources/db/migration/V128__group_creator_phone_region.sql

-- 部署后核对号段覆盖数量。
SELECT country_iso2, COUNT(*) AS prefix_count
FROM country_phone_region_prefix_mapping
GROUP BY country_iso2
ORDER BY country_iso2;

-- 核对已回填且明确标注为手机号号段归属区的历史快照数量。
SELECT creator_country_iso2, creator_phone_region_code, COUNT(*) AS group_count
FROM group_link_preview
WHERE creator_phone_region_code IS NOT NULL
GROUP BY creator_country_iso2, creator_phone_region_code
ORDER BY creator_country_iso2, creator_phone_region_code;
