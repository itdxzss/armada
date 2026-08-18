-- 回滚前应先停止写入新字段的应用版本。
ALTER TABLE group_link_preview
    DROP COLUMN creator_phone_region_name,
    DROP COLUMN creator_phone_region_code;

DROP TABLE country_phone_region_prefix_mapping;
