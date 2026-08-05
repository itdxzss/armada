-- 正式迁移文件：
-- armada-api/src/main/resources/db/migration/V096__group_list_history_metadata.sql

-- 迁移前：确认版本号未占用。
SELECT version, script, success
FROM flyway_schema_history
WHERE version = '096' OR version = '96';

-- 迁移前：确认目标表存在；以下语句只读。
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('group_link', 'group_link_preview', 'country');

-- 迁移后：确认新增列。
SELECT table_name, column_name, column_type, is_nullable, column_default, column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND (
      (table_name = 'group_link' AND column_name IN ('is_historical', 'is_post_control'))
      OR (table_name = 'country' AND column_name = 'continent_code')
      OR (table_name = 'group_link_preview' AND column_name IN (
          'wa_description', 'admin_only_edit_info', 'member_add_mode', 'join_approval_mode',
          'ephemeral_duration_seconds', 'creator_country_iso2', 'creator_continent_code',
          'metadata_observed_at'
      ))
  )
ORDER BY table_name, ordinal_position;

-- 迁移后：确认新增表和索引。
SELECT table_name, index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN (
      'group_link', 'country', 'whatsapp_group_member_snapshot', 'group_metadata_sync_task'
  )
  AND index_name IN (
      'idx_group_link_historical', 'idx_group_link_post_control', 'idx_country_continent_sort',
      'uq_whatsapp_group_member', 'idx_whatsapp_group_admin',
      'uq_group_metadata_sync_task', 'idx_group_metadata_due', 'idx_group_metadata_running'
  )
ORDER BY table_name, index_name, seq_in_index;

-- 迁移后：除四个特殊地区外，检查仍未归入六大洲的有效国家/地区。
SELECT iso2, name_zh, continent_code
FROM country
WHERE deleted_at IS NULL
  AND iso2 NOT IN ('AQ', 'BV', 'HM', 'TF')
  AND continent_code IS NULL
ORDER BY iso2;
