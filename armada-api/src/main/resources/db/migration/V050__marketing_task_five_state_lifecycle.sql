-- 普通群组营销任务收敛为五态；目标和发送尝试仍保留各自的成功/失败结果状态。
UPDATE marketing_task
SET status = 7,
    next_round_at = NULL,
    finished_at = COALESCE(finished_at, updated_at)
WHERE status IN (3, 4, 6);

-- 终态不允许继续占用账号，迁移时清理旧状态遗留的 current-only 占用行。
DELETE FROM marketing_account_occupancy
WHERE marketing_task_id IN (
    SELECT id
    FROM marketing_task
    WHERE status = 7
       OR deleted_at IS NOT NULL
);

-- V049 上线前已存在的未启动/执行中/已暂停任务可能尚无占用行。每个账号按最早任务确定 owner，
-- 已存在的有效占用由唯一键保留；冲突任务后续由运营关闭或自然完成，不允许再生成新的冲突锁。
INSERT IGNORE INTO marketing_account_occupancy
    (account_id, marketing_task_id, occupied_at, created_at, updated_at, tenant_id)
SELECT ranked.account_id,
       ranked.marketing_task_id,
       ranked.occupied_at,
       ranked.occupied_at,
       ranked.occupied_at,
       ranked.tenant_id
FROM (
    SELECT target.account_id,
           task.id AS marketing_task_id,
           COALESCE(task.created_at, task.updated_at, 0) AS occupied_at,
           task.tenant_id,
           ROW_NUMBER() OVER (
               PARTITION BY task.tenant_id, target.account_id
               ORDER BY COALESCE(task.created_at, task.updated_at, 0) ASC, task.id ASC
           ) AS owner_rank
    FROM marketing_task task
    JOIN marketing_task_target target ON target.marketing_task_id = task.id
    WHERE task.deleted_at IS NULL
      AND task.status IN (1, 2, 5)
) ranked
WHERE ranked.owner_rank = 1;

ALTER TABLE marketing_task
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1
    COMMENT '任务状态:1=未启动 2=执行中 5=已暂停 7=已完成 8=已关闭';
