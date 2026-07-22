-- 仅供回退旧应用前恢复其“未软删即在群”的安全筛选语义。
-- 不删除新增列和历史状态。真实执行前必须确认环境、备份并核对影响行数。

UPDATE account_group_membership
SET deleted_at = COALESCE(deleted_at, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
    updated_at = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
WHERE deleted_at IS NULL
  AND membership_status IN (3, 4, 5);
