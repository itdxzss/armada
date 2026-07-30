-- 对应 Flyway:
-- - armada-api/src/main/resources/db/migration/V085__historical_group_created_at.sql
-- - armada-api/src/main/resources/db/migration/V086__historical_group_pull_source_account_group.sql

ALTER TABLE group_link_preview
    ADD COLUMN group_created_at BIGINT DEFAULT NULL
    COMMENT 'WhatsApp群创建时间(Unix秒)' AFTER announce_only;

ALTER TABLE historical_group_pull_execution
    ADD COLUMN source_account_group_id BIGINT DEFAULT NULL
    COMMENT '来源历史群账号组ID' AFTER operation_account_id;

UPDATE historical_group_pull_execution execution_row
INNER JOIN account operation_account
  ON operation_account.tenant_id = execution_row.tenant_id
 AND operation_account.id = execution_row.operation_account_id
SET execution_row.source_account_group_id = operation_account.account_group_id
WHERE execution_row.source_account_group_id IS NULL
  AND operation_account.account_group_id IS NOT NULL;

ALTER TABLE historical_group_pull_execution
    ADD KEY idx_historical_pull_source_group (
        tenant_id, source_account_group_id, group_jid, created_at
    );
