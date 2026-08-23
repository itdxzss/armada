-- V139 回滚。执行前必须先回滚应用或停止群成员事实写入，并确认两张 20260823 备份表存在。

-- 1) 先删除 phone 唯一键，否则无法恢复同 phone 双行。
SET @phone_unique_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'wa_group_participant'
    AND index_name = 'uq_wa_group_participant_phone'
);
SET @drop_phone_unique_ddl := IF(@phone_unique_exists > 0,
  'ALTER TABLE wa_group_participant DROP INDEX uq_wa_group_participant_phone',
  'SELECT 1');
PREPARE stmt FROM @drop_phone_unique_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 把 canonical LID 行恢复为迁移前完整值，同时清掉 V139 回填的 pn_jid。
UPDATE wa_group_participant current_row
JOIN bak_participant_phone_guard_20260823 backup ON backup.id = current_row.id
SET current_row.pn_jid = backup.pn_jid,
    current_row.lid_jid = backup.lid_jid,
    current_row.phone = backup.phone,
    current_row.phone_country_iso2 = backup.phone_country_iso2,
    current_row.presence_status = backup.presence_status,
    current_row.presence_source = backup.presence_source,
    current_row.presence_observed_at = backup.presence_observed_at,
    current_row.presence_event_id = backup.presence_event_id,
    current_row.role = backup.role,
    current_row.role_source = backup.role_source,
    current_row.role_observed_at = backup.role_observed_at,
    current_row.role_event_id = backup.role_event_id,
    current_row.last_snapshot_version = backup.last_snapshot_version,
    current_row.last_joined_at = backup.last_joined_at,
    current_row.last_join_event_at = backup.last_join_event_at,
    current_row.last_join_source_event_id = backup.last_join_source_event_id,
    current_row.last_exited_at = backup.last_exited_at,
    current_row.last_exit_type = backup.last_exit_type,
    current_row.last_exit_event_at = backup.last_exit_event_at,
    current_row.last_exit_source_event_id = backup.last_exit_source_event_id,
    current_row.last_exit_source_type = backup.last_exit_source_type,
    current_row.updated_at = backup.updated_at
WHERE backup.pn_jid IS NULL
  AND backup.lid_jid IS NOT NULL;

-- 3) 重建被删除的 PN-only 行，保留原主键。
INSERT INTO wa_group_participant
SELECT backup.*
FROM bak_participant_phone_guard_20260823 backup
LEFT JOIN wa_group_participant current_row ON current_row.id = backup.id
WHERE backup.pn_jid IS NOT NULL
  AND backup.lid_jid IS NULL
  AND current_row.id IS NULL;

-- 4) 恢复账号绑定原 participant_id。
UPDATE wa_account_group_binding binding
JOIN bak_binding_phone_guard_20260823 backup ON backup.binding_id = binding.id
SET binding.participant_id = backup.old_participant_id;

-- 5) 验收：悬空绑定必须为 0；恢复的双行数应与部署前确定配对数一致。
SELECT COUNT(*) AS dangling_bindings
FROM wa_account_group_binding binding
LEFT JOIN wa_group_participant participant ON participant.id = binding.participant_id
WHERE participant.id IS NULL;

SELECT COUNT(*) AS restored_exact_pairs
FROM (
  SELECT 1
  FROM wa_group_participant
  WHERE phone IS NOT NULL AND phone <> ''
  GROUP BY tenant_id, group_id, phone
  HAVING COUNT(*) = 2
     AND SUM(pn_jid IS NOT NULL AND lid_jid IS NULL) = 1
     AND SUM(pn_jid IS NULL AND lid_jid IS NOT NULL) = 1
) restored_pair;

-- 确认回滚无误后再人工清理备份表，不写进自动化流程。
-- DROP TABLE bak_binding_phone_guard_20260823;
-- DROP TABLE bak_participant_phone_guard_20260823;
