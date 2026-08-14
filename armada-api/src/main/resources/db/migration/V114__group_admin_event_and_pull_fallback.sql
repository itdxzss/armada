-- 群管理员角色事件与定点成员查询上线后，一次性唤醒因本地管理员事实缺失而等待的普通拉群执行。
-- 仅处理仍在执行中的任务和精确原因码；不回填账号群关系，不创建周期 metadata 查询。

UPDATE pull_task_group_execution execution_row
JOIN pull_task task_row
  ON task_row.tenant_id = execution_row.tenant_id
 AND task_row.id = execution_row.task_id
SET execution_row.execution_status = 2,
    execution_row.wait_resource_type = NULL,
    execution_row.reason_code = NULL,
    execution_row.reason_message = NULL,
    execution_row.next_run_at = 0,
    execution_row.lock_owner = NULL,
    execution_row.lock_expires_at = NULL,
    execution_row.version = execution_row.version + 1,
    execution_row.updated_at = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE execution_row.execution_status = 3
  AND execution_row.stage = 3
  AND execution_row.wait_resource_type = 1
  AND execution_row.reason_code = 'MANAGER_ADMIN_ACTOR_UNAVAILABLE'
  AND execution_row.manual_paused = 0
  AND task_row.task_type = 'STANDARD'
  AND task_row.mode = 'NORMAL_LINK'
  AND task_row.status = 'EXECUTING'
  AND task_row.deleted_at IS NULL;
