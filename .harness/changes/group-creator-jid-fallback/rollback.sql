-- 仅供回滚方案审阅，尚未执行。执行前必须确认环境并备份命中行。
-- 回退到不识别来源的旧代码前，需要先清理仍是推导来源的兼容创建者投影，
-- 防止旧代码将推导值当作真实群主；已升级为来源2的记录不动。
UPDATE group_link_preview
SET owner_phone = NULL, creator_country_iso2 = NULL, creator_continent_code = NULL,
    last_preview_at = NULL, metadata_observed_at = NULL, creator_phone_source = 0
WHERE creator_phone_source = 1;
-- 保留新增列对旧代码兼容，无需 DROP，也不得篡改 Flyway 历史。
