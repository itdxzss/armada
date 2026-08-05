-- 本文件仅提供人工恢复指引，不得在未确认目标环境和影响范围时直接执行。
-- 推荐顺序：先停用群详情同步调度与事件消费，再回滚应用代码；新增列和表可以暂时保留。

-- 只读检查：快照和任务数据量。任何结果非零都意味着删表会丢失数据。
SELECT COUNT(*) AS member_snapshot_rows FROM whatsapp_group_member_snapshot;
SELECT status, COUNT(*) AS task_rows
FROM group_metadata_sync_task
GROUP BY status
ORDER BY status;

-- 只读检查：已经固化的历史事实数量。分类字段不提供批量改回 0 的回滚 SQL。
SELECT
    SUM(is_historical = 1) AS historical_groups,
    SUM(is_post_control = 1) AS post_control_groups,
    SUM(is_historical = 1 AND is_post_control = 1) AS overlapping_groups
FROM group_link;

-- 只有在停 job、备份数据并获得明确人工确认后，才可按依赖逆序执行以下 DDL：
-- DROP TABLE group_metadata_sync_task;
-- DROP TABLE whatsapp_group_member_snapshot;
-- ALTER TABLE group_link_preview
--   DROP COLUMN metadata_observed_at,
--   DROP COLUMN creator_continent_code,
--   DROP COLUMN creator_country_iso2,
--   DROP COLUMN ephemeral_duration_seconds,
--   DROP COLUMN join_approval_mode,
--   DROP COLUMN member_add_mode,
--   DROP COLUMN admin_only_edit_info,
--   DROP COLUMN wa_description;
-- ALTER TABLE country DROP INDEX idx_country_continent_sort, DROP COLUMN continent_code;
-- ALTER TABLE group_link
--   DROP INDEX idx_group_link_post_control,
--   DROP INDEX idx_group_link_historical,
--   DROP COLUMN is_post_control,
--   DROP COLUMN is_historical;
