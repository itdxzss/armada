-- Armada test account reset.
--
-- Scope:
--   - Deletes all account rows for one tenant, including credentials, state,
--     import history, diagnostics, account group membership, baselines, and
--     pending/sent protocol outbox rows for account commands.
--   - Releases bound IP proxies back to IDLE.
--   - By default also deletes join/marketing task data that references the
--     old account ids, because those ids become invalid after re-import.
--
-- This is a test-environment hard cleanup script. It is not a Flyway migration.
-- Default mode is preview only. Set @execute_delete = 1 and keep the exact
-- confirm phrase before running the destructive part.

SET @expected_schema := 'armada';
SET @tenant_code := 'demo';
SET @execute_delete := 0;
SET @confirm_phrase := 'CHANGE_ME';

-- Recommended for a full test account re-import. Set to 0 only if you
-- intentionally want to keep stale task history that may reference old ids.
SET @clear_account_task_data := 1;

SET @tenant_id := (
    SELECT id
    FROM tenant
    WHERE tenant_code = @tenant_code
      AND status = 1
    LIMIT 1
);

SET @can_delete := (
    @execute_delete = 1
    AND @confirm_phrase = 'TYPE_DELETE_ARMADA_TEST_ACCOUNTS'
    AND DATABASE() = @expected_schema
    AND @tenant_id IS NOT NULL
);

SELECT
    DATABASE() AS current_schema,
    @expected_schema AS expected_schema,
    @tenant_code AS tenant_code,
    @tenant_id AS tenant_id,
    @execute_delete AS execute_delete,
    @clear_account_task_data AS clear_account_task_data,
    @can_delete AS can_delete;

SELECT CASE
    WHEN @tenant_id IS NULL THEN 'BLOCKED: tenant_code not found or disabled'
    WHEN DATABASE() <> @expected_schema THEN 'BLOCKED: current schema does not match @expected_schema'
    WHEN @execute_delete <> 1 THEN 'PREVIEW_ONLY: set @execute_delete = 1 to delete'
    WHEN @confirm_phrase <> 'TYPE_DELETE_ARMADA_TEST_ACCOUNTS' THEN 'BLOCKED: confirm phrase mismatch'
    WHEN @can_delete = 1 THEN 'DELETE_ENABLED'
    ELSE 'BLOCKED: unknown guard failure'
END AS cleanup_guard_status;

SELECT
    t.tenant_code,
    a.tenant_id,
    COUNT(*) AS active_and_deleted_account_rows
FROM account a
LEFT JOIN tenant t ON t.id = a.tenant_id
GROUP BY t.tenant_code, a.tenant_id
ORDER BY a.tenant_id;

DROP TEMPORARY TABLE IF EXISTS tmp_armada_cleanup_account_id;
CREATE TEMPORARY TABLE tmp_armada_cleanup_account_id (
    account_id BIGINT NOT NULL PRIMARY KEY,
    protocol_account_id VARCHAR(128) NULL,
    KEY idx_protocol_account_id (protocol_account_id)
) ENGINE = MEMORY;

INSERT INTO tmp_armada_cleanup_account_id (account_id, protocol_account_id)
SELECT id, protocol_account_id
FROM account
WHERE tenant_id = @tenant_id;

DROP TEMPORARY TABLE IF EXISTS tmp_armada_cleanup_marketing_task_id;
CREATE TEMPORARY TABLE tmp_armada_cleanup_marketing_task_id (
    id BIGINT NOT NULL PRIMARY KEY
) ENGINE = MEMORY;

INSERT INTO tmp_armada_cleanup_marketing_task_id (id)
SELECT id
FROM marketing_task
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1;

DROP TEMPORARY TABLE IF EXISTS tmp_armada_cleanup_marketing_attempt_id;
CREATE TEMPORARY TABLE tmp_armada_cleanup_marketing_attempt_id (
    id BIGINT NOT NULL PRIMARY KEY
) ENGINE = MEMORY;

INSERT INTO tmp_armada_cleanup_marketing_attempt_id (id)
SELECT a.id
FROM marketing_task_send_attempt a
JOIN tmp_armada_cleanup_marketing_task_id mt ON mt.id = a.marketing_task_id
WHERE a.tenant_id = @tenant_id;

SELECT 'account' AS table_name, COUNT(*) AS rows_to_delete
FROM account
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_state', COUNT(*)
FROM account_state
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_credential', COUNT(*)
FROM account_credential
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_import_batch', COUNT(*)
FROM account_import_batch
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_import_detail', COUNT(*)
FROM account_import_detail
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_online_attempt_log', COUNT(*)
FROM account_online_attempt_log
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_group_baseline', COUNT(*)
FROM account_group_baseline
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_group_membership', COUNT(*)
FROM account_group_membership
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'bound_ip_proxy', COUNT(*)
FROM ip_proxy
WHERE (tenant_id = @tenant_id OR tenant_id IS NULL)
  AND bound_account_id IN (SELECT account_id FROM tmp_armada_cleanup_account_id)
UNION ALL
SELECT 'protocol_command_outbox_ACCOUNT', COUNT(*)
FROM protocol_command_outbox
WHERE tenant_id = @tenant_id
  AND aggregate_type = 'ACCOUNT'
UNION ALL
SELECT 'join_task', COUNT(*)
FROM join_task
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
UNION ALL
SELECT 'join_task_result', COUNT(*)
FROM join_task_result
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
UNION ALL
SELECT 'marketing_task', COUNT(*)
FROM marketing_task
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
UNION ALL
SELECT 'marketing_task_target', COUNT(*)
FROM marketing_task_target
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
UNION ALL
SELECT 'marketing_task_send_attempt', COUNT(*)
FROM marketing_task_send_attempt
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
ORDER BY table_name;

START TRANSACTION;

SET @now_ms := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

UPDATE ip_proxy
SET status = 1,
    bound_account_id = NULL,
    bound_at = NULL,
    updated_at = @now_ms
WHERE (tenant_id = @tenant_id OR tenant_id IS NULL)
  AND bound_account_id IN (SELECT account_id FROM tmp_armada_cleanup_account_id)
  AND deleted_at IS NULL
  AND status = 2
  AND @can_delete = 1;
SET @released_ip_proxy := ROW_COUNT();

DELETE po
FROM protocol_command_outbox po
JOIN tmp_armada_cleanup_marketing_attempt_id ma
  ON po.aggregate_type = 'MARKETING_SEND_ATTEMPT'
 AND po.aggregate_id = ma.id
WHERE po.tenant_id = @tenant_id
  AND @clear_account_task_data = 1
  AND @can_delete = 1;
SET @deleted_marketing_outbox := ROW_COUNT();

DELETE FROM protocol_command_outbox
WHERE tenant_id = @tenant_id
  AND aggregate_type = 'ACCOUNT'
  AND @can_delete = 1;
SET @deleted_account_outbox := ROW_COUNT();

DELETE a
FROM marketing_task_send_attempt a
JOIN tmp_armada_cleanup_marketing_task_id mt ON mt.id = a.marketing_task_id
WHERE a.tenant_id = @tenant_id
  AND @clear_account_task_data = 1
  AND @can_delete = 1;
SET @deleted_marketing_attempt := ROW_COUNT();

DELETE t
FROM marketing_task_target t
JOIN tmp_armada_cleanup_marketing_task_id mt ON mt.id = t.marketing_task_id
WHERE t.tenant_id = @tenant_id
  AND @clear_account_task_data = 1
  AND @can_delete = 1;
SET @deleted_marketing_target := ROW_COUNT();

DELETE FROM marketing_task
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
  AND @can_delete = 1;
SET @deleted_marketing_task := ROW_COUNT();

DELETE FROM join_task_result
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
  AND @can_delete = 1;
SET @deleted_join_task_result := ROW_COUNT();

DELETE FROM join_task
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
  AND @can_delete = 1;
SET @deleted_join_task := ROW_COUNT();

DELETE FROM account_online_attempt_log
WHERE tenant_id = @tenant_id
  AND @can_delete = 1;
SET @deleted_attempt_log := ROW_COUNT();

DELETE FROM account_group_membership
WHERE tenant_id = @tenant_id
  AND @can_delete = 1;
SET @deleted_group_membership := ROW_COUNT();

DELETE FROM account_group_baseline
WHERE tenant_id = @tenant_id
  AND @can_delete = 1;
SET @deleted_group_baseline := ROW_COUNT();

DELETE FROM account_import_detail
WHERE tenant_id = @tenant_id
  AND @can_delete = 1;
SET @deleted_import_detail := ROW_COUNT();

DELETE FROM account_import_batch
WHERE tenant_id = @tenant_id
  AND @can_delete = 1;
SET @deleted_import_batch := ROW_COUNT();

DELETE FROM account_credential
WHERE tenant_id = @tenant_id
  AND @can_delete = 1;
SET @deleted_credential := ROW_COUNT();

DELETE FROM account_state
WHERE tenant_id = @tenant_id
  AND @can_delete = 1;
SET @deleted_state := ROW_COUNT();

DELETE FROM account
WHERE tenant_id = @tenant_id
  AND @can_delete = 1;
SET @deleted_account := ROW_COUNT();

SELECT
    @can_delete AS can_delete,
    @released_ip_proxy AS released_ip_proxy,
    @deleted_account_outbox AS deleted_account_outbox,
    @deleted_marketing_outbox AS deleted_marketing_outbox,
    @deleted_marketing_attempt AS deleted_marketing_attempt,
    @deleted_marketing_target AS deleted_marketing_target,
    @deleted_marketing_task AS deleted_marketing_task,
    @deleted_join_task_result AS deleted_join_task_result,
    @deleted_join_task AS deleted_join_task,
    @deleted_attempt_log AS deleted_attempt_log,
    @deleted_group_membership AS deleted_group_membership,
    @deleted_group_baseline AS deleted_group_baseline,
    @deleted_import_detail AS deleted_import_detail,
    @deleted_import_batch AS deleted_import_batch,
    @deleted_credential AS deleted_credential,
    @deleted_state AS deleted_state,
    @deleted_account AS deleted_account;

COMMIT;

SELECT 'account' AS table_name, COUNT(*) AS remaining_rows
FROM account
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_state', COUNT(*)
FROM account_state
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_credential', COUNT(*)
FROM account_credential
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_import_batch', COUNT(*)
FROM account_import_batch
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_import_detail', COUNT(*)
FROM account_import_detail
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_online_attempt_log', COUNT(*)
FROM account_online_attempt_log
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_group_baseline', COUNT(*)
FROM account_group_baseline
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'account_group_membership', COUNT(*)
FROM account_group_membership
WHERE tenant_id = @tenant_id
UNION ALL
SELECT 'bound_ip_proxy', COUNT(*)
FROM ip_proxy
WHERE (tenant_id = @tenant_id OR tenant_id IS NULL)
  AND bound_account_id IN (SELECT account_id FROM tmp_armada_cleanup_account_id)
UNION ALL
SELECT 'protocol_command_outbox_ACCOUNT', COUNT(*)
FROM protocol_command_outbox
WHERE tenant_id = @tenant_id
  AND aggregate_type = 'ACCOUNT'
UNION ALL
SELECT 'join_task', COUNT(*)
FROM join_task
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
UNION ALL
SELECT 'join_task_result', COUNT(*)
FROM join_task_result
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
UNION ALL
SELECT 'marketing_task', COUNT(*)
FROM marketing_task
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
UNION ALL
SELECT 'marketing_task_target', COUNT(*)
FROM marketing_task_target
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
UNION ALL
SELECT 'marketing_task_send_attempt', COUNT(*)
FROM marketing_task_send_attempt
WHERE tenant_id = @tenant_id
  AND @clear_account_task_data = 1
ORDER BY table_name;
