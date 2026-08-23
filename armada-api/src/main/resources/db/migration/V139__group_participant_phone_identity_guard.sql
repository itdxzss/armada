-- 清理 V126 后由并发事实再次产生的 PN/LID 双行，并以群内可信 phone 唯一键阻止同类并发。
-- 仅归并同租户、同群、同 phone 下恰好一条 PN-only 与一条 LID-only 的确定配对；
-- 其他形态不静默处理，迁移会在改写数据前显式失败并阻止带歧义数据启动。

-- 先用临时表主键冲突做 fail-fast：若存在唯一键无法覆盖、又不能确定归并的 phone 重复，
-- 在任何业务数据改写之前终止迁移，避免 MySQL 非事务 DDL 留下半迁移状态。
CREATE TEMPORARY TABLE tmp_participant_phone_guard (
  guard_key TINYINT NOT NULL PRIMARY KEY
);
INSERT INTO tmp_participant_phone_guard (guard_key) VALUES (1);
INSERT INTO tmp_participant_phone_guard (guard_key)
SELECT 1
FROM wa_group_participant
WHERE phone IS NOT NULL
GROUP BY tenant_id, group_id, phone
HAVING COUNT(*) > 1
   AND (
     phone = ''
     OR COUNT(*) <> 2
     OR SUM(pn_jid IS NOT NULL AND lid_jid IS NULL) <> 1
     OR SUM(pn_jid IS NULL AND lid_jid IS NOT NULL) <> 1
   )
LIMIT 1;
DROP TEMPORARY TABLE tmp_participant_phone_guard;

CREATE TEMPORARY TABLE tmp_participant_phone_merge_pair AS
SELECT tenant_id,
       group_id,
       phone,
       MAX(CASE WHEN pn_jid IS NOT NULL AND lid_jid IS NULL THEN id END) AS pn_id,
       MAX(CASE WHEN pn_jid IS NULL AND lid_jid IS NOT NULL THEN id END) AS lid_id,
       MAX(CASE WHEN pn_jid IS NOT NULL AND lid_jid IS NULL THEN pn_jid END) AS pn_jid
FROM wa_group_participant
WHERE phone IS NOT NULL
  AND phone <> ''
GROUP BY tenant_id, group_id, phone
HAVING COUNT(*) = 2
   AND SUM(pn_jid IS NOT NULL AND lid_jid IS NULL) = 1
   AND SUM(pn_jid IS NULL AND lid_jid IS NOT NULL) = 1;

CREATE TEMPORARY TABLE tmp_participant_phone_merge AS
SELECT pair.pn_id,
       pair.lid_id,
       pair.pn_jid,
       CASE WHEN
         pn.presence_observed_at > COALESCE(lid.presence_observed_at, -1)
         OR (
           pn.presence_observed_at = lid.presence_observed_at
           AND (CASE pn.presence_source
             WHEN 'WGP2_REMOVE' THEN 5 WHEN 'WGP2_LEAVE' THEN 5
             WHEN 'REMOVE_EVENT' THEN 5 WHEN 'LEAVE_EVENT' THEN 5
             WHEN 'UNKNOWN_EXIT_EVENT' THEN 5 WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
             WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
             WHEN 'WGP2_ADD' THEN 3 WHEN 'ADD_EVENT' THEN 3
             WHEN 'GROUP_MEMBER_QUERY' THEN 2
             WHEN 'FULL_SNAPSHOT' THEN 2 WHEN 'SNAPSHOT_ABSENT' THEN 2
             WHEN 'GROUP_SNAPSHOT' THEN 1 ELSE 0 END)
           > (CASE lid.presence_source
             WHEN 'WGP2_REMOVE' THEN 5 WHEN 'WGP2_LEAVE' THEN 5
             WHEN 'REMOVE_EVENT' THEN 5 WHEN 'LEAVE_EVENT' THEN 5
             WHEN 'UNKNOWN_EXIT_EVENT' THEN 5 WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
             WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
             WHEN 'WGP2_ADD' THEN 3 WHEN 'ADD_EVENT' THEN 3
             WHEN 'GROUP_MEMBER_QUERY' THEN 2
             WHEN 'FULL_SNAPSHOT' THEN 2 WHEN 'SNAPSHOT_ABSENT' THEN 2
             WHEN 'GROUP_SNAPSHOT' THEN 1 ELSE 0 END)
         )
         THEN 1 ELSE 0 END AS pn_presence_wins,
       CASE WHEN pn.role <> 0
         AND NOT (
           lid.role <> 0
           AND (CASE pn.role_source
             WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
             WHEN 'WGP2_ADD' THEN 3
             WHEN 'GROUP_MEMBER_QUERY' THEN 2 WHEN 'FULL_SNAPSHOT' THEN 2
             WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2 WHEN 'LEGACY_METADATA_OWNER' THEN 2
             WHEN 'GROUP_SNAPSHOT' THEN 1 ELSE 0 END)
           < (CASE lid.role_source
             WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
             WHEN 'WGP2_ADD' THEN 3
             WHEN 'GROUP_MEMBER_QUERY' THEN 2 WHEN 'FULL_SNAPSHOT' THEN 2
             WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2 WHEN 'LEGACY_METADATA_OWNER' THEN 2
             WHEN 'GROUP_SNAPSHOT' THEN 1 ELSE 0 END)
         )
         AND pn.role_observed_at > COALESCE(lid.role_observed_at, -1)
         THEN 1 ELSE 0 END AS pn_role_wins
FROM tmp_participant_phone_merge_pair pair
JOIN wa_group_participant pn ON pn.id = pair.pn_id
JOIN wa_group_participant lid ON lid.id = pair.lid_id;

ALTER TABLE tmp_participant_phone_merge
    ADD INDEX idx_tmp_phone_merge_pn (pn_id),
    ADD INDEX idx_tmp_phone_merge_lid (lid_id);

-- 先合并成员事实，再按 M -> B 的固定锁序迁移账号绑定。
UPDATE wa_group_participant canonical
JOIN tmp_participant_phone_merge merge_pair ON merge_pair.lid_id = canonical.id
JOIN wa_group_participant duplicate ON duplicate.id = merge_pair.pn_id
SET canonical.phone = COALESCE(canonical.phone, duplicate.phone),
    canonical.phone_country_iso2 = COALESCE(
        canonical.phone_country_iso2, duplicate.phone_country_iso2),
    canonical.presence_status = IF(
        merge_pair.pn_presence_wins = 1,
        duplicate.presence_status,
        canonical.presence_status),
    canonical.presence_source = IF(
        merge_pair.pn_presence_wins = 1,
        duplicate.presence_source,
        canonical.presence_source),
    canonical.presence_observed_at = IF(
        merge_pair.pn_presence_wins = 1,
        duplicate.presence_observed_at,
        canonical.presence_observed_at),
    canonical.presence_event_id = IF(
        merge_pair.pn_presence_wins = 1,
        duplicate.presence_event_id,
        canonical.presence_event_id),
    canonical.role = IF(merge_pair.pn_role_wins = 1, duplicate.role, canonical.role),
    canonical.role_source = IF(
        merge_pair.pn_role_wins = 1,
        duplicate.role_source,
        canonical.role_source),
    canonical.role_observed_at = IF(
        merge_pair.pn_role_wins = 1,
        duplicate.role_observed_at,
        canonical.role_observed_at),
    canonical.role_event_id = IF(
        merge_pair.pn_role_wins = 1,
        duplicate.role_event_id,
        canonical.role_event_id),
    canonical.last_joined_at = IF(
        duplicate.last_join_event_at IS NOT NULL
        AND (canonical.last_join_event_at IS NULL
             OR duplicate.last_join_event_at >= canonical.last_join_event_at),
        duplicate.last_joined_at,
        canonical.last_joined_at),
    canonical.last_join_source_event_id = IF(
        duplicate.last_join_event_at IS NOT NULL
        AND (canonical.last_join_event_at IS NULL
             OR duplicate.last_join_event_at >= canonical.last_join_event_at),
        duplicate.last_join_source_event_id,
        canonical.last_join_source_event_id),
    canonical.last_join_event_at = IF(
        duplicate.last_join_event_at IS NOT NULL
        AND (canonical.last_join_event_at IS NULL
             OR duplicate.last_join_event_at >= canonical.last_join_event_at),
        duplicate.last_join_event_at,
        canonical.last_join_event_at),
    canonical.last_exited_at = IF(
        duplicate.last_exit_event_at IS NOT NULL
        AND (canonical.last_exit_event_at IS NULL
             OR duplicate.last_exit_event_at >= canonical.last_exit_event_at),
        duplicate.last_exited_at,
        canonical.last_exited_at),
    canonical.last_exit_type = IF(
        duplicate.last_exit_event_at IS NOT NULL
        AND (canonical.last_exit_event_at IS NULL
             OR duplicate.last_exit_event_at >= canonical.last_exit_event_at),
        duplicate.last_exit_type,
        canonical.last_exit_type),
    canonical.last_exit_source_event_id = IF(
        duplicate.last_exit_event_at IS NOT NULL
        AND (canonical.last_exit_event_at IS NULL
             OR duplicate.last_exit_event_at >= canonical.last_exit_event_at),
        duplicate.last_exit_source_event_id,
        canonical.last_exit_source_event_id),
    canonical.last_exit_source_type = IF(
        duplicate.last_exit_event_at IS NOT NULL
        AND (canonical.last_exit_event_at IS NULL
             OR duplicate.last_exit_event_at >= canonical.last_exit_event_at),
        duplicate.last_exit_source_type,
        canonical.last_exit_source_type),
    canonical.last_exit_event_at = IF(
        duplicate.last_exit_event_at IS NOT NULL
        AND (canonical.last_exit_event_at IS NULL
             OR duplicate.last_exit_event_at >= canonical.last_exit_event_at),
        duplicate.last_exit_event_at,
        canonical.last_exit_event_at),
    canonical.last_snapshot_version = CASE
      WHEN canonical.last_snapshot_version IS NULL THEN duplicate.last_snapshot_version
      WHEN duplicate.last_snapshot_version IS NULL THEN canonical.last_snapshot_version
      WHEN CAST(duplicate.last_snapshot_version AS BINARY)
          > CAST(canonical.last_snapshot_version AS BINARY)
        THEN duplicate.last_snapshot_version
      ELSE canonical.last_snapshot_version
    END,
    canonical.updated_at = GREATEST(canonical.updated_at, duplicate.updated_at);

UPDATE wa_account_group_binding binding
JOIN tmp_participant_phone_merge merge_pair ON merge_pair.pn_id = binding.participant_id
SET binding.participant_id = merge_pair.lid_id,
    binding.updated_at = CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED);

DELETE participant
FROM wa_group_participant participant
JOIN tmp_participant_phone_merge merge_pair ON merge_pair.pn_id = participant.id;

-- PN 行删除后才可回填，否则会先命中 uq_wa_group_participant_pn。
UPDATE wa_group_participant canonical
JOIN tmp_participant_phone_merge merge_pair ON merge_pair.lid_id = canonical.id
SET canonical.pn_jid = merge_pair.pn_jid,
    canonical.updated_at = CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED);

DROP TEMPORARY TABLE IF EXISTS tmp_participant_phone_merge;
DROP TEMPORARY TABLE IF EXISTS tmp_participant_phone_merge_pair;

SET @phone_unique_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'wa_group_participant'
    AND index_name = 'uq_wa_group_participant_phone'
);

SET @phone_unique_ddl := IF(@phone_unique_exists = 0,
  'ALTER TABLE wa_group_participant
     ADD UNIQUE KEY uq_wa_group_participant_phone (tenant_id, group_id, phone),
     ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT 1'
);

PREPARE stmt FROM @phone_unique_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
