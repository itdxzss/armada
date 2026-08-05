-- 正式迁移文件：
-- armada-api/src/main/resources/db/migration/V098_1__normalize_legacy_wgp2_removed.sql
-- 本文件仅归档，执行以 Flyway 文件为准，禁止手工修改共享库。

UPDATE whatsapp_group_departed_member
SET exit_type = 'UNKNOWN'
WHERE source_type = 'WGP2_NOTIFICATION'
  AND exit_type = 'REMOVED';
