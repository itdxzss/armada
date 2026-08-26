-- V140 回滚操作手册。Flyway 迁移只前滚；本文件不删除 canonical 列或永久审计表。
-- 执行前必须明确目标环境、备份范围，并停止所有新版本实例继续写入分类。

-- 1. 先确认 canonical 分类头部自洽；结果必须为 0。
SELECT COUNT(*) AS invalid_canonical_headers
FROM wa_group
WHERE NOT (
  (group_classification = 0
    AND group_classified_at IS NULL
    AND group_classification_source IS NULL)
  OR (group_classification IN (1, 2)
    AND group_classified_at IS NOT NULL
    AND group_classification_source IS NOT NULL)
);

-- 2. 若必须回退到仍读取 group_link 双布尔的旧应用，先把 canonical 唯一事实投影回兼容列。
--    两列由同一个 CASE 派生，任何一行都不会双 true。
UPDATE group_link handle
INNER JOIN wa_group current_group
  ON current_group.tenant_id = handle.tenant_id
 AND (
   current_group.id = handle.group_id
   OR (handle.group_id IS NULL
     AND handle.link_url = CONCAT('wa://group/', current_group.group_jid))
 )
SET handle.is_historical = CASE
      WHEN current_group.group_classification = 1 THEN 1 ELSE 0 END,
    handle.is_post_control = CASE
      WHEN current_group.group_classification = 2 THEN 1 ELSE 0 END,
    handle.updated_at = CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED)
WHERE handle.deleted_at IS NULL;

-- 3. 投影后必须为 0；否则禁止部署旧应用。
SELECT COUNT(*) AS legacy_dual_true_rows
FROM group_link
WHERE deleted_at IS NULL
  AND is_historical = 1
  AND is_post_control = 1;

-- 4. 部署旧应用后保留 wa_group 的 canonical 列、索引和
--    wa_group_classification_migration_audit，便于重新前滚与审计。
--    如确需物理删除，另建经过评审的新 Flyway 迁移，禁止手工 DROP 或删除 Flyway 历史。
