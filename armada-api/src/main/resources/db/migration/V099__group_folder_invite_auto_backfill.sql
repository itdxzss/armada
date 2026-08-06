SET @group_invite_backfill_now :=
    CAST(ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) AS UNSIGNED);

INSERT INTO group_metadata_sync_task (
    tenant_id,
    group_link_id,
    status,
    trigger_source,
    attempt_count,
    next_run_at,
    lease_until,
    execution_account_id,
    rerun_requested,
    last_started_at,
    last_success_at,
    last_error_code,
    last_error_message,
    created_at,
    updated_at
)
SELECT link.tenant_id,
       link.id,
       1,
       7,
       0,
       @group_invite_backfill_now,
       NULL,
       NULL,
       0,
       NULL,
       NULL,
       NULL,
       NULL,
       @group_invite_backfill_now,
       @group_invite_backfill_now
FROM group_link link
LEFT JOIN group_link_preview preview
  ON preview.tenant_id = link.tenant_id
 AND preview.group_link_id = link.id
WHERE link.deleted_at IS NULL
  AND link.folder_id IS NOT NULL
  AND link.link_url LIKE 'wa://group/%'
  AND NULLIF(TRIM(preview.invite_code), '') IS NULL
ON DUPLICATE KEY UPDATE
    trigger_source = 7,
    attempt_count = CASE WHEN status = 2 THEN attempt_count ELSE 0 END,
    next_run_at = CASE WHEN status = 2 THEN next_run_at ELSE VALUES(next_run_at) END,
    lease_until = CASE WHEN status = 2 THEN lease_until ELSE NULL END,
    execution_account_id = CASE WHEN status = 2 THEN execution_account_id ELSE NULL END,
    rerun_requested = CASE WHEN status = 2 THEN TRUE ELSE FALSE END,
    last_error_code = CASE WHEN status = 2 THEN last_error_code ELSE NULL END,
    last_error_message = CASE WHEN status = 2 THEN last_error_message ELSE NULL END,
    status = CASE WHEN status = 2 THEN status ELSE 1 END,
    updated_at = VALUES(updated_at);
